# Teste de Carga e Performance (k6) — issue #129

Cenário realista contra a API real do IWrite, autenticado com a sessão de servidor
introduzida na PR #139 (cookie `JSESSIONID` + CSRF de duplo envio via `XSRF-TOKEN`).
Substitui o teste anterior, que só exercitava `/ping`.

> ⚠️ **Rode SEMPRE contra o seu ambiente LOCAL.** O script recusa qualquer
> `BASE_URL` que não resolva para `localhost`, `127.0.0.1`, `::1` ou
> `host.docker.internal` — ver [Segurança de destino](#4-segurança-de-destino).
> **Nunca** aponte para Render, produção ou o servidor acadêmico compartilhado
> (`https://eqNN.dsc.rodrigor.com`): o Postgres é compartilhado com outras equipes.

---

## 1. O que o teste faz

Cada VU representa uma **sessão independente do mesmo autor editando o seu
próprio livro** — não N usuários compartilhando um único livro. `setup()`
cria 1 livro + 1 seção + 1 capítulo + 1 cena **por VU** (ver [§5](#5-dados-sintéticos-e-limpeza));
cada VU autentica com a sua própria sessão (ver [§3](#3-autenticação)) e só
lê/escreve o livro/cena no índice correspondente ao seu `__VU`.

Cada VU roda uma única iteração k6 (`options.scenarios.default`, executor
`per-vu-iterations`) contendo um laço interno manual de turnos — não uma
iteração k6 por turno (`options.stages`/`ramping-vus`, usado antes). Antes
de qualquer tráfego principal, a VU autentica (ver [§3](#3-autenticação)) e
só então espera até seu instante absoluto de ativação, relativo ao início
da curva de carga MEDIDA — `loadStartAt()`, que começa depois de uma janela
de preparação de autenticação, não em `exec.scenario.startTime` diretamente
(ver [§3](#3-autenticação)). A partir daí, cada VU escalona sua própria
rampa de subida/descida (`activationOffsetMs()`/`deactivationOffsetMs()` em
`carga.js`): a k-ésima VU (por índice, 1..`VUS`) entra em
`loadStartAt()+(k/VUS)×WARMUP_DURATION` e sai em
`loadStartAt()+WARMUP_DURATION+STEADY_DURATION+(k/VUS)×RAMPDOWN_DURATION` —
a discretização exata do alvo contínuo `VUs_ativas(t)=VUS×t/WARMUP_DURATION`
(rampa linear 0→VUS), garantindo que a última VU cubra o extremo exato de
cada rampa, sem cauda ociosa (achado do Codex, PR #141 — "Cover the full ramp
interval with VU offsets"; ver o comentário de `activationOffsetMs()` em
`carga.js` para a tabela de verificação). É uma **aproximação discreta
equivalente ao `ramping-vus` só nesses pontos de ativação/desativação** — não
uma cópia bit-a-bit do algoritmo interno do executor; a ordem exata de qual
VU entra/sai primeiro pode diferir. Motivo da iteração única por VU: é a
única forma de o passo 6 abaixo (`refresh_outline_after_save`) rodar em
fire-and-forget genuíno no k6 v2.1.0 — confirmado empiricamente que o k6
sempre drena todas as Promises pendentes de uma iteração, mesmo as nunca
aguardadas, antes de considerá-la concluída, então qualquer Promise
criada dentro da MESMA iteração k6 sempre vira barreira da iteração seguinte
(ver [§9](#9-resultados-obtidos)).

Fluxo por VU, a cada turno do laço interno:

1. `GET /api/books` — tag `operation=list_books`
2. `GET /api/books/{bookId}/outline` — tag `operation=load_outline`
3. `GET /api/scenes/{sceneId}` — tag `operation=load_scene` (o mesmo que o
   `SceneEditor` real faz ao abrir uma cena — `getScene(sceneId)` em
   `web/src/features/scenes/api/scenes-api.ts`)
4. debounce do `AUTO_SAVE` (`AUTO_SAVE_DELAY_MS`, default `1200`ms — mesmo
   valor que `CONTENT_AUTOSAVE_DELAY_MS` em
   `web/src/features/scenes/components/scene-editor.tsx`): a VU aguarda esse
   intervalo depois de montar o conteúdo alterado, antes de enviar o PATCH,
   modelando o tempo que o editor real espera sem nova tecla antes de
   persistir. Não é think time — o think time (passo 7) representa o
   intervalo de atividade do usuário DEPOIS do save concluído
5. `PATCH /api/scenes/{sceneId}/content` — tag `operation=save_scene`
6. **Só se o passo 5 retornou 200:** `GET /api/books/{bookId}/outline` de
   novo — tag `operation=refresh_outline_after_save` (separada de
   `load_outline`, nunca agregada nela). Espelha o `BookWorkspace` real: ele
   mantém a query do outline ativa e `contentMutation.mutateAsync()` chama
   `queryClient.invalidateQueries({ queryKey: queryKeys.outline(bookId) })`
   assim que o save é confirmado, o que refaz este `GET`. Como o frontend só
   invalida nesse caso, o script também só refaz a chamada quando o `PATCH`
   teve sucesso. O editor real dispara essa invalidação com `void
   queryClient.invalidateQueries(...)` (`scene-editor.tsx`) — fire-and-forget
   genuíno, sem bloquear a UI nem o think time seguinte. O script espelha isso
   de verdade: `http.asyncRequest()` é disparado e **nunca aguardado** (nem
   direto, nem via `Promise.all` com o think time) — o status é checado dentro
   do `.then()` da própria Promise, que roda mesmo sem ninguém a aguardar
   diretamente, e `.catch()` evita qualquer rejeição não tratada. O turno
   seguinte já começa assim que o think time do passo 7 termina, mesmo que
   este refresh ainda esteja em voo — ver [§9](#9-resultados-obtidos) para a
   prova empírica de por que uma versão anterior com `Promise.all` (que
   rodava o refresh "em paralelo" com o think time, mas ainda aguardava os
   dois) continuava sendo barreira sempre que o refresh era mais lento que o
   think time.
7. think time curto (0.3–1s, configurável), depois do qual o próximo turno
   já começa — independentemente do refresh do passo 6 ainda estar pendente

Cada requisição das 5 operações acima também carrega uma tag `phase`
(`ramp_up`/`steady`/`ramp_down`), calculada a cada dispatch de requisição
(não mais uma vez por turno) a partir de `loadStartAt()` — o início da curva
medida, depois da janela de preparação de autenticação (ver
[§3](#3-autenticação)), não diretamente de `exec.scenario.startTime` — e das
durações configuradas
(`WARMUP_DURATION`/`STEADY_DURATION`/`RAMPDOWN_DURATION`) — ver
[§7](#7-thresholds) para por que isso importa para os thresholds.

Sem chamadas de IA. Cada VU escreve **somente no próprio livro/cena**, nunca
um recurso compartilhado com outra VU — evita contenção artificial no lock
pessimista de linha do livro (`SceneService.updateContent()` →
`BookAccessService.requireBookEditAccessForUpdate()` →
`BookRepository.findByIdAndTenantIdForUpdate()`): com um único livro
compartilhado, todo `save_scene` de todas as VUs serializaria nesse lock,
medindo contenção do harness em vez da latência real de escritas
concorrentes de usuários diferentes. A `expectedContentRevision` do `PATCH`
vem sempre da leitura do passo 3, **nunca de um cache local** — assim uma
falha ambígua no `PATCH` anterior (ex.: a escrita foi aplicada no servidor
mas a resposta se perdeu) nunca produz uma sequência artificial de conflitos
de revisão: a próxima escrita sempre parte do estado real e atual do
servidor. Um novo `operationId` é gerado a cada `PATCH`.

Os passos 1-3 são **pré-requisitos sequenciais**: um usuário real não abre uma
cena sem antes navegar até o outline, nem chega no outline sem antes listar os
livros. Se `list_books`, `load_outline` ou `load_scene` retornar algo
diferente de 200, o turno encerra ali mesmo (think time e volta ao topo do
laço interno para o próximo turno) — os passos seguintes não rodam. Isso
evita que uma falha de leitura vire tráfego de escrita e distorça a
latência/taxa de erro de `save_scene`.

Cada requisição carrega duas tags: `operation` (dimensão funcional, ex.
`save_scene`) e `name` (rota HTTP normalizada, ex. `PATCH
/api/scenes/{sceneId}/content`) — a tag automática `url` do k6, que carregaria
a URL concreta com o `bookId`/`sceneId` sintéticos embutidos, é desabilitada
via `systemTags`. Nenhuma tag exportada carrega ID, título, conteúdo, usuário
ou tenant — ver [§8](#8-como-ler-o-resultado).

O `contentJson` enviado é o mesmo documento ProseMirror que o editor real
produz e versiona (`web/src/features/scenes/editor/tiptap-editor.tsx`,
`plainTextToDocument`), não apenas `contentText` — a versão anterior deste
script só enviava texto puro, o que subestimava o custo real do caminho de
save (ver [§9](#9-resultados-obtidos)).

Cada VU autentica **uma única vez, sozinha, no topo da sua (única) iteração
k6** — não em `setup()`, e sem retry: uma falha aqui encerra a VU inteira,
sem gerar tráfego de retry artificial (ver [§3](#3-autenticação)). `setup()`
também autentica,
mas só internamente, para provisionar os dados (ver [§5](#5-dados-sintéticos-e-limpeza));
essa sessão de `setup()` nunca é repassada às VUs. Cada VU e `teardown()` têm
o próprio cookie jar isolado do k6 e fazem o próprio handshake CSRF + login,
guardando a sessão só nesse jar — nunca numa variável, em `data`, ou em
qualquer estrutura que possa acabar num summary do k6. Isso reflete o uso
real (uma sessão de servidor dura a visita inteira, uma por autor) e modela
literalmente o cenário do [§1](#1-o-que-o-teste-faz): sessões independentes
do mesmo autor.

**Efeito colateral:** com uma sessão por VU (em vez de uma única sessão de
`setup()` repassada a todas), uma execução com `VUS` VUs agora faz até
`VUS + 2` logins (1 de `setup()` + 1 por VU + 1 de `teardown()`) contra a
mesma conta, e esses logins tendem a se concentrar dentro do
`WARMUP_DURATION` — o que pode estourar o rate limiter de login
(`IWRITE_LOGIN_RATE_LIMIT_MAX_PER_ACCOUNT`/`_ORIGIN`, padrão 8/20 por janela
de 1 minuto — ver `.env.example`) e devolver `429` antes da fase medida
terminar de autenticar. Por isso o comando de subida em [§2](#2-pré-requisitos)
inclui o overlay versionado
[`docker-compose.loadtest.yml`](../docker-compose.loadtest.yml), que eleva
os dois limites só para este ambiente **local e isolado** — os defaults de
produção em `application.yml`/`.env.example` não são alterados, e o overlay
só entra em vigor se alguém passar `-f docker-compose.loadtest.yml`
explicitamente.

`GET /ping` continua no script, mas só como smoke check **inicial** dentro do
`setup()`: se o ambiente não responder, o teste aborta antes de criar qualquer
dado.

---

## 2. Pré-requisitos

Suba o backend localmente com o overlay de demonstração **e** o overlay de
carga (cria os usuários `autor-a@iwrite.local` / `autor-b@iwrite.local` — ver
[`docs/demonstracao-multi-tenant.md`](../docs/demonstracao-multi-tenant.md) —
e eleva o rate limiter de login só nesta stack, ver [§1](#1-o-que-o-teste-faz)):

```bash
# copie .env.example para .env e preencha IWRITE_DEMO_SEED_ENABLED=true e as
# duas senhas (sem valor padrão) antes de subir
docker compose \
  -f docker-compose.yml \
  -f docker-compose.demo.yml \
  -f docker-compose.loadtest.yml \
  up -d --build
```

Equivalente em PowerShell:

```powershell
docker compose `
  -f docker-compose.yml `
  -f docker-compose.demo.yml `
  -f docker-compose.loadtest.yml `
  up -d --build
```

**O overlay `-f docker-compose.loadtest.yml` não é opcional** para rodar com
`VUS` maior que ~8: sem ele, o backend sobe com o rate limiter de login
padrão de produção (8 tentativas/conta por minuto) e a execução recebe `429`
antes de todas as VUs terminarem de autenticar — ver [§1](#1-o-que-o-teste-faz).

O orçamento padrão do overlay é **1000** tentativas/janela (conta e origem),
suficiente para `VUS` até `998` (regra: `IWRITE_LOADTEST_LOGIN_RATE_LIMIT >=
VUS + 2` — 1 login de `setup()` + `VUS` logins das VUs + 1 de `teardown()`).

Para uma execução com `VUS` acima de `998`, sobrescreva com um valor que
respeite a regra `>= VUS + 2` **antes** de subir a stack — e passe o mesmo
`VUS` para o `k6 run` em [§6](#6-executando), senão os dois números divergem:

```bash
# bash — exemplo com VUS=1500
VUS=1500
export IWRITE_LOADTEST_LOGIN_RATE_LIMIT=$((VUS + 2))
```

```powershell
# PowerShell — exemplo com VUS=1500
$loadTestVus = 1500
$env:IWRITE_LOADTEST_LOGIN_RATE_LIMIT = ($loadTestVus + 2).ToString()
```

Depois, ao rodar o k6 ([§6](#6-executando)), passe o mesmo valor:

```bash
k6 run -e VUS="$VUS" ...
```

```powershell
k6 run -e "VUS=$loadTestVus" ...
```

Tenha o k6 disponível (`k6.io/docs` para instalação, ou use a imagem
`grafana/k6` via Docker apontando `BASE_URL` para `host.docker.internal`).

**Rode 10/30 VUs com a máquina na tomada, nunca na bateria.** Numa
remedição desta PR, a mesma execução de 30 VUs estourou o threshold
`list_books` (`p(95)<500ms`) duas vezes seguidas na bateria (595.6ms,
556.7ms) e passou com folga (106.0ms) assim que a máquina foi conectada à
tomada — plano de energia do Windows inalterado, só a fonte. Throttling de
CPU por bateria sozinho já é suficiente para derrubar esse threshold; não há
verificação automática disso no script — confira manualmente antes de medir
(`[System.Windows.Forms.SystemInformation]::PowerStatus.PowerLineStatus` deve
ser `Online`, não `Offline`; equivalente a `Win32_Battery.BatteryStatus=2`
usado em rodadas anteriores).

---

## 3. Autenticação

O script reproduz exatamente o handshake que `web/e2e/auth.setup.ts` usa contra
o backend real — mas cada contexto de execução (setup(), cada VU, teardown())
faz o próprio handshake, no seu próprio cookie jar isolado do k6:

1. `GET /api/auth/csrf` → emite o cookie `XSRF-TOKEN` (o Spring Security emite
   esse cookie em qualquer requisição que passe pelo `CsrfFilter`, então o
   `/ping` inicial já pode tê-lo emitido — o script lê do cookie jar do k6,
   não da resposta de uma chamada específica).
2. `POST /api/auth/login` com `{ "email": ..., "password": ... }` e o header
   `X-XSRF-TOKEN` — devolve o cookie de sessão `JSESSIONID`.
3. O k6 recicla o "jar corrente" (o que `http.cookieJar()` devolve) a cada
   chamada de nível superior — não é o jar persistente "por VU" que a
   documentação do k6 sugere à primeira leitura. Por isso `authenticateVuOnce()`
   (chamada uma única vez, no topo da única iteração k6 daquela VU) guarda a
   referência do próprio jar e a passa explicitamente (`{ jar }`) em toda
   requisição de todos os turnos do laço interno — sem isso, a sessão se
   perderia. `setup()` e `teardown()` usam o mesmo padrão com um `jar` local
   de escopo único. O header CSRF de duplo envio (`X-XSRF-TOKEN`) é lido de
   volta do jar ativo no momento de cada requisição (`authHeaders(jar)`); o
   cookie de sessão em si não precisa de header manual — o k6 já o reenvia
   sozinho a partir do jar passado. `setup()` nunca devolve o jar, o cookie ou
   o token a ninguém — só `[{ bookId, sceneId }, ...]` (ver
   [§5](#5-dados-sintéticos-e-limpeza)).

**Autenticação roda ANTES de qualquer offset de ativação, numa janela de
preparação isolada** (achado do Codex, PR #141, "Reach peak load before
starting the steady phase"). Antes, cada VU esperava seu
`activationOffsetMs()` e só DEPOIS autenticava — a última VU só começava o
handshake em `t=WARMUP_MS`, exatamente quando `currentPhase()` já rotulava
amostras como `steady`; se autenticar fosse lento (o próprio
`http_req_duration{operation:auth_login}` tolera até `8s` de p95), o início
de `steady` era medido com menos que `VUS` VUs realmente produzindo carga.
Agora toda VU autentica **imediatamente** no início da sua iteração k6, e
`login()` usa `HTTP_REQUEST_TIMEOUT` ([§6](#6-executando)) em cada uma das 2
requisições do handshake — o pior caso de uma tentativa de autenticação
(sucesso ou falha terminal) é limitado a `2×HTTP_REQUEST_TIMEOUT_MS`. Um
novo relógio de carga, `loadStartAt() = exec.scenario.startTime +
AUTH_PREPARE_MS` (`AUTH_PREPARE_MS = 2×HTTP_REQUEST_TIMEOUT_MS + margem`),
garante por construção que toda autenticação bem-sucedida termina antes de
`loadStartAt()` — logo antes do instante de ativação de qualquer VU, que é
sempre `loadStartAt()+activationOffsetMs(vuIndex)` (nunca antes). Só depois
de autenticar com sucesso a VU espera até esse instante absoluto — nunca um
delay relativo ao momento em que a autenticação terminou, o que faria uma
autenticação lenta "empurrar" o início do tráfego principal em vez de
simplesmente consumir mais da margem da janela de preparação. Ver "Prova da
janela de preparação de autenticação" em [§9](#9-resultados-obtidos) para a
validação empírica (`VUS=1/2/10`, inclusive com login artificialmente lento).

**Nenhuma falha de login de VU é tolerada — e nenhuma gera retry.** Cada
autenticação bem/mal sucedida incrementa a métrica `vu_auth_success`
(`k6/metrics.Rate`) exatamente uma vez por VU, com threshold `rate==1` sem
tolerância ([§7](#7-thresholds)) — uma única falha reprova o `k6 run` inteiro
ao final, e nenhuma operação principal é despachada por essa VU. Como
`authenticateVuOnce()` só roda uma vez (default() agora só tem uma iteração
k6 por VU — ver [§1](#1-o-que-o-teste-faz)), uma falha aqui é terminal por
construção: não existe "próxima iteração" desta VU para retentar. Antes
(quando `default()` era chamada de novo a cada iteração k6), uma falha
persistente virava uma tempestade de retries — cada iteração subsequente da
mesma VU tentava o handshake de novo após só 0.3-1s de think time,
consumindo o orçamento de rate-limit de login da conta/origem e podendo
impedir até o login de `teardown()` (achado do Codex, PR #141, ver
[§9](#9-resultados-obtidos)). Sem retry, esse orçamento fica preservado para
`teardown()` limpar os livros sintéticos das outras VUs mesmo quando alguma
VU falha ao autenticar.

**Credenciais vêm só de variável de ambiente, nunca de arquivo versionado:**

| Variável | Obrigatória | Padrão |
|---|---|---|
| `LOAD_TEST_PASSWORD` | **sim** | nenhum — o script aborta sem ela |
| `LOAD_TEST_EMAIL` | não | `autor-a@iwrite.local` |

Use a mesma senha do seed demo. O Docker Compose **não** exporta variáveis do
`.env` para o seu shell — `.env` só é lido pelo `docker compose` ao subir os
containers — então não basta apontar para `IWRITE_DEMO_AUTOR_A_PASSWORD`,
essa variável não existe no shell. Digite a senha explicitamente, sem eco:

```bash
# bash
read -rsp "Senha do autor-a, igual à configurada no .env: " LOAD_TEST_PASSWORD
echo
export LOAD_TEST_PASSWORD
```

```powershell
# PowerShell
$securePassword = Read-Host "Senha do autor-a, igual à configurada no .env" -AsSecureString
$env:LOAD_TEST_PASSWORD = [System.Net.NetworkCredential]::new("", $securePassword).Password
```

O script nunca imprime a senha (só o e-mail aparece nos logs, em caso de erro
de login).

---

## 4. Segurança de destino

`BASE_URL` só é aceita sem override se o host resolver para um destes:
`localhost`, `127.0.0.1`, `::1`, `host.docker.internal`. A
resolução do host **não usa regex ingênua** — o parser (`parseSafeHost` em
`carga.js`) só aceita esquema `http:`/`https:`, resolve corretamente literais
IPv6 (`[::1]`) e **rejeita qualquer URL com user-info** (`user@host` ou
`user:senha@host`) mesmo que o host à direita seja local: uma URL como
`http://localhost:8085@host-externo` não é "reinterpretada" para extrair o
host real, ela é recusada inteira, porque tentar adivinhar o host por trás de
credenciais é exatamente a superfície que um parser baseado em regex
simples pode errar.

**`backend` (o hostname do container do Compose) não está na allowlist.**
`parseSafeHost()` só compara a autoridade léxica da URL — nunca resolve nem
verifica para qual endereço `backend` realmente aponta. Rodar o k6 de dentro
da rede do Compose com `-e BASE_URL=http://backend:8085` é um cenário real
(container-a-container), mas numa máquina com um domain search DNS fora
dessa rede, o hostname genérico `backend` pode resolver para um serviço
remoto completamente não relacionado — e a allowlist não teria como
diferenciar os dois casos. Por isso `backend` exige o mesmo override que
qualquer outro host não controlado:

```bash
-e BASE_URL=http://backend:8085 -e ALLOW_UNSAFE_TARGET=eu-autorizo-um-destino-externo
```

Nenhuma mensagem de erro do parser imprime a `BASE_URL` bruta — só uma
descrição genérica do problema (`BASE_URL inválida.`, `BASE_URL rejeitada
porque contém user-info.`, etc.). Uma URL rejeitada por conter user-info pode
literalmente carregar uma senha (`http://usuario:senha@host`); ecoar a URL
inteira em stdout/stderr vazaria exatamente o que a rejeição deveria proteger.

Qualquer host fora da lista, ou URL inválida, faz o script abortar **antes de
criar qualquer dado ou logar**, com uma mensagem explicando como liberar
deliberadamente:

```bash
-e ALLOW_UNSAFE_TARGET=eu-autorizo-um-destino-externo
```

Não use esse override contra Render, produção ou o servidor acadêmico
compartilhado — o nome da variável é feio de propósito.

---

## 5. Dados sintéticos e limpeza

`setup()` cria, sob o marcador `LOADTEST-<runId>-vu<N>` (nunca reaproveita
livro ou cena existente), **um por VU** — não um único livro compartilhado:

- 1 livro (`LOADTEST-<runId>-vu<N>`)
- 1 seção + 1 capítulo (hierarquia mínima)
- 1 cena

`setup()` devolve só uma lista não sensível `[{ bookId, sceneId }, ...]`,
indexada 1:1 com `__VU` — a VU de índice `__VU` usa exclusivamente
`data[__VU - 1]`, nunca um recurso de outra VU. Nada de sessão, cookie ou CSRF
sai de `setup()` (ver [§3](#3-autenticação)).

Como o loop cria um livro completo por VU em série, o tempo de `setup()`
cresce com `VUS` — ver `SETUP_TIMEOUT`/`TEARDOWN_TIMEOUT` em [§6](#6-executando).

**Se seção, capítulo ou cena de alguma VU falhar depois que o respectivo
livro já existe**, o `setup()` remove **todos os livros já criados até
aquele ponto** (não só o do livro corrente) antes de relançar o erro
original e reprovar o teste — necessário porque `teardown()` nunca roda
quando `setup()` lança. O log da tentativa de limpeza lista quais livros
falharam ao ser removidos (só `bookId`/status HTTP), nunca cookies ou o
token CSRF.

**Se a própria resposta do `POST /api/books` se perder** (timeout, erro de
rede) enquanto o servidor ainda está terminando a transação, o ID nunca
chegaria à lista de limpeza e uma única consulta imediata pode rodar ANTES
do commit — `recoverOrphanedBookIds()` cobre esse caso com retentativa
limitada por tempo: antes de relançar o erro, `setup()` consulta os livros
da conta repetidamente (intervalo curto de 0.5s, janela de até 5s — constantes
`ORPHAN_RECOVERY_POLL_INTERVAL_S`/`ORPHAN_RECOVERY_WINDOW_MS` em
`carga.js`), continuando mesmo depois de uma consulta vazia, até a janela
fechar — bound só por tempo (`Date.now() >= deadline`), nunca por resposta
do servidor, então nunca é um loop infinito. Casa pelo marcador EXATO desta
execução (prefixo `LOADTEST-${runId}-vu`, nunca `LOADTEST-` genérico),
acumulando IDs sem duplicar (`Set`) e unindo qualquer órfão encontrado aos
já rastreados antes de tentar remover todos. Nunca toca livros de outra
execução concorrente (outro `runId`).

**Bounded em três níveis, não só pela janela declarada** (achado do Codex,
PR #141, "Bound each orphan-recovery request"): (1) cada `GET` de
recuperação individual usa `timeout: min(ORPHAN_RECOVERY_SINGLE_REQUEST_MAX_MS,
HTTP_REQUEST_TIMEOUT_MS, tempo restante até o deadline da janela)` — sem um
teto individual **menor que a própria janela** (`ORPHAN_RECOVERY_SINGLE_REQUEST_MAX_MS
= 2000ms`, achado de follow-up pós-#141: com os defaults antigos,
`min(HTTP_REQUEST_TIMEOUT_MS=10000, ORPHAN_RECOVERY_WINDOW_MS=5000)` dava
`5000ms` — uma única consulta lenta podia sozinha consumir a janela de `5s`
inteira, sem sobrar chance para nenhum poll seguinte capturar um commit
tardio); (2) o `sleep()` entre polls nunca ultrapassa o deadline; (3) a
própria janela nunca é maior que o orçamento que `setup()` ainda pode gastar
dentro de `SETUP_TIMEOUT` sem comprometer o cleanup dos livros já
conhecidos.

`setup()` recusa iniciar a próxima operação de provisionamento (falha
controlada, preservando o erro original) sempre que o restante não cobrir
request-em-andamento + cleanup sequencial dos livros já conhecidos —
verificado **imediatamente antes de cada `POST`** (livro, seção, capítulo e
cena), não só uma vez por livro (achado de follow-up pós-#141: um guard que
só roda antes do `POST /api/books` não impede que seção/capítulo consumam o
orçamento restante antes de disparar a cena sem sobrar tempo para limpar).
Antes de `POST /api/books` a reserva inclui também a janela de recuperação
de órfãos inteira (`ORPHAN_RECOVERY_WINDOW_MS`) — só essa request pode gerar
um órfão de ID desconhecido; seção/capítulo/cena já sabem o `bookId` (já em
`createdBookIds`) e vão direto para o cleanup, sem precisar de varredura, por
isso não reservam essa janela. O deadline (`setupDeadlineAt`) é capturado na
**primeira linha de `setup()`**, antes até da validação de
`LOAD_TEST_PASSWORD`/`GET /ping`/login — o `setupTimeout` do k6 já conta
desde a entrada real da função, então calcular o deadline depois do handshake
de autenticação faria o orçamento achar que sobra mais tempo do que o k6
realmente ainda concede. Nunca redefinido depois — é a mesma referência
temporal usada em toda checagem de orçamento do `setup()` inteiro. Em vez de
deixar o k6 matar `setup()` de fora por `SETUP_TIMEOUT`, o guard falha
controladamente antes. Uma validação fail-fast rejeita, antes de criar
qualquer dado, um `SETUP_TIMEOUT` matematicamente insuficiente para o `VUS`
pedido. O `GET /ping`, o `DELETE` de `cleanupBooks()` e os 4 `POST`s de
provisionamento também usam timeout explícito — ver "Prova do bound de
recuperação de órfãos" e "Prova do orçamento de cleanup" em
[§9](#9-resultados-obtidos) para a fault injection original da #141, e o
follow-up de orçamento de setup em [§9](#9-resultados-obtidos) para a fault
injection desta rodada.

`teardown()` autentica de novo (sessão própria, não reaproveita a de nenhuma
VU nem a de `setup()`) e apaga **todos** os livros da execução (cascata apaga
seção/capítulo/cena via `CascadeType.ALL` + `orphanRemoval`) — **reprova a
execução** (lança e faz o `k6 run` sair com código diferente de zero) se
qualquer `DELETE` não retornar `204`, listando quais livros não foram
removidos — um teardown que só loga e segue deixaria livros `LOADTEST-` para
trás sem que nenhum threshold acusasse nada, já que essas chamadas acontecem
fora do loop de VUs medido.

Se o teste for interrompido (Ctrl+C, k6 morto, timeout) antes do `teardown()`
rodar, limpe manualmente. `setup()` imprime o `runId` desta execução
(`runId desta execução: <RUN_ID>`) no log do k6 imediatamente após gerá-lo,
ANTES do primeiro `POST /api/books` — copie esse valor do terminal (ou do
`stdout` salvo, se você redirecionou a execução) mesmo que o setup tenha
falhado antes de criar qualquer livro. As mensagens de falha de `setup()`
também repetem o `runId` para facilitar copiar de qualquer ponto do log.

**Nunca use um `LIKE 'LOADTEST-%'` genérico** — isso apaga livros de
qualquer outra execução (histórica ou concorrente) que bata com o prefixo
`LOADTEST-`, mesmo com `runId` diferente, corrompendo os resultados dela.
Escope sempre pelo `runId` impresso, com a mesma consulta de conferência
antes do `DELETE`:

```bash
# lista os livros LOADTEST- residuais de uma sessão autenticada (substitua os
# cookies pelos da sua sessão de teste)
curl -s -b cookies.txt "$BASE_URL/api/books" | grep -o '"title":"LOADTEST-[^"]*"'

# ou direto no banco do ambiente LOCAL (nunca em produção) — substitua
# <RUN_ID> pelo valor impresso pelo setup() desta execução interrompida.
# Primeiro confira o que seria apagado:
docker exec iwrite-db psql -U postgres -d iwrite -c \
  "select id, title from books where title like 'LOADTEST-<RUN_ID>-vu%';"

# só então apague, com o mesmo predicado escopado por runId:
docker exec iwrite-db psql -U postgres -d iwrite -c \
  "delete from books where title like 'LOADTEST-<RUN_ID>-vu%';"
```

Alternativa mais segura (e preferencial sobre o `LIKE` acima, quando você já
tem os IDs em mãos): apague por IDs explícitos, os `id` retornados pela
consulta de conferência acima. `books.id` é `uuid` (`V1__create_books.sql`,
`Book.java`), não inteiro — cole os valores exatos impressos pela consulta,
entre aspas simples e com `::uuid` explícito, nunca literais numéricos como
`11, 12, 13` (o Postgres rejeitaria a comparação `uuid = integer`):

```bash
docker exec iwrite-db psql -U postgres -d iwrite -c \
  "delete from books where id in (
    '11111111-1111-4111-8111-111111111111'::uuid,
    '22222222-2222-4222-8222-222222222222'::uuid
  );"
```

Isso é sempre limpeza manual de emergência contra o Postgres do seu
ambiente LOCAL — nunca rode nada disto contra produção, e nunca use um
padrão genérico (`LOADTEST-%`) que possa apagar títulos de outra execução.
Prefira sempre o predicado escopado por `runId` (`LIKE
'LOADTEST-<RUN_ID>-vu%'`, acima) a apagar por IDs — a versão por `runId` não
depende de copiar/colar UUIDs corretamente.

---

## 6. Executando

Variáveis de carga (todas opcionais, com padrão realista):

| Variável | O que faz | Padrão |
|---|---|---|
| `BASE_URL` | URL do backend | `http://localhost:8085` |
| `VUS` | VUs no pico (também define quantas cenas o `setup()` cria) | `10` |
| `WARMUP_DURATION` | rampa de subida | `30s` |
| `STEADY_DURATION` | carga estável | `2m` |
| `RAMPDOWN_DURATION` | desaquecimento | `30s` |
| `THINK_TIME_MIN_S` / `THINK_TIME_MAX_S` | think time por turno (depois do save concluído) | `0.3` / `1` |
| `AUTO_SAVE_DELAY_MS` | debounce do `AUTO_SAVE` antes do PATCH (== `CONTENT_AUTOSAVE_DELAY_MS` do frontend) | `1200` |
| `HTTP_REQUEST_TIMEOUT` | timeout por requisição das 5 operações do turno (formato de duração do k6) — dimensiona `maxDuration` da VU, ver abaixo | `10s` |
| `RESULT_PATH` | caminho do resumo JSON já sanitizado (opcional) | nenhum — só imprime no terminal |
| `SETUP_TIMEOUT` | timeout de `setup()` (formato de duração do k6, ex. `10m`, `90s`, `0.5m`) — sobe com `VUS` porque `setup()` cria um livro completo por VU em série; rejeitado com mensagem clara, antes de criar qualquer dado, se for matematicamente insuficiente para `VUS` (ver [§5](#5-dados-sintéticos-e-limpeza)) | `10m` |
| `TEARDOWN_TIMEOUT` | timeout de `teardown()`, mesmo formato | `10m` |

**Orçamento de `maxDuration` da VU (achado do Codex, PR #141 — "Let the
final turn drain before maxDuration"):** o laço interno de turnos só
verifica o limite de desativação **antes** de iniciar um turno, nunca o
interrompe no meio — então o último turno de uma VU pode começar a poucos
milissegundos do fim nominal da rampa e ainda assim precisar completar as 3
leituras síncronas, o debounce, o `PATCH`, o think time e a drenagem do
`refresh_outline_after_save` fire-and-forget que ele mesmo disparou.
`maxDuration` (`options.scenarios.default.maxDuration`) soma ao fim nominal
(`WARMUP_DURATION+STEADY_DURATION+RAMPDOWN_DURATION`) uma folga calculada, não
um número fixo arbitrário:

```
4×HTTP_REQUEST_TIMEOUT                 // list_books + load_outline + load_scene + save_scene, síncronos
+ AUTO_SAVE_DELAY_MS
+ THINK_TIME_MAX_S×1000
+ 1×HTTP_REQUEST_TIMEOUT               // drenagem do refresh_outline_after_save pendente
+ 5000ms                               // margem técnica: agendamento de VU, parsing de JSON, GC do k6
```

O refresh não soma um `HTTP_REQUEST_TIMEOUT` por turno pendente: não importa
quantos refreshes de turnos anteriores ainda estejam em voo quando a VU
retorna, todos — inclusive o do último turno — já estão limitados ao mesmo
deadline individual (o instante em que cada um foi disparado +
`HTTP_REQUEST_TIMEOUT`), então o pior caso de drenagem final é UM
`HTTP_REQUEST_TIMEOUT` a partir do último refresh disparado, nunca a soma de
vários. Com os defaults (`HTTP_REQUEST_TIMEOUT=10s`, `AUTO_SAVE_DELAY_MS=1200`,
`THINK_TIME_MAX_S=1`), a folga é `4×10s + 1.2s + 1s + 10s + 5s = 57.2s`.

**Janela de preparação de autenticação (achado do Codex, PR #141 — "Reach
peak load before starting the steady phase"):** antes de `WARMUP_DURATION`
começar a contar, `maxDuration` soma mais `AUTH_PREPARE_MS` — a janela em
que toda VU autentica antes de esperar seu offset de ativação (ver
[§3](#3-autenticação)):

```
AUTH_PREPARE_MS = 2×HTTP_REQUEST_TIMEOUT   // as 2 requisições do handshake (csrf + login)
                 + 2000ms                  // margem técnica: VUS logins ~simultâneos, GC do k6
```

Com o default (`HTTP_REQUEST_TIMEOUT=10s`), `AUTH_PREPARE_MS = 22s`. O
orçamento total de `maxDuration` passa a ser:

```
AUTH_PREPARE_MS + WARMUP_DURATION + STEADY_DURATION + RAMPDOWN_DURATION + <folga acima>
```

nunca reduzindo warmup/steady/rampdown/grace — com os defaults completos,
`22s + 30s + 2m + 30s + 57.2s = 4m19.2s`.

`SETUP_TIMEOUT`/`TEARDOWN_TIMEOUT` são validados antes de rodar (regex de
duração do k6: unidades `ms`/`s`/`m`/`h` encadeadas, cada uma com parte
fracionária opcional — `10m`, `90s`, `1h30m`, `0.5m`, `1.5s` são todos
aceitos; `10x`, `1..5s`, `abc` continuam rejeitados) — um valor malformado
falha imediatamente com uma mensagem clara, em vez de o k6 rejeitar
silenciosamente as `options` ou cair no timeout padrão de 60s.

`THINK_TIME_MIN_S`/`THINK_TIME_MAX_S` também são validados antes de montar
`options` ou disparar qualquer requisição: precisam ser números finitos,
não negativos, com `THINK_TIME_MIN_S <= THINK_TIME_MAX_S` (`0` e
`min == max` são válidos). Um valor como `abc`, `NaN`, `Infinity`, `-1` ou
`min > max` falha imediatamente — sem isso, `Number("abc")` viraria `NaN` e
o think time seguinte seria efetivamente zero, inflando a taxa de
requisições da VU sem aviso.

`AUTO_SAVE_DELAY_MS` (default `1200`, mesmo valor que
`CONTENT_AUTOSAVE_DELAY_MS` em
`web/src/features/scenes/components/scene-editor.tsx`) é validado com a
mesma regra (finito, não negativo) antes de rodar. Modela o debounce real do
editor: a VU aguarda esse intervalo depois de montar o conteúdo alterado, e
só então envia o PATCH de `AUTO_SAVE` — sem isso, o cenário dispararia saves
mais rápido do que qualquer editor real permite, inflando artificialmente a
taxa de escritas e de atualizações de contagem de palavras sob carga. Valores
abaixo de `1200` são um modo de estresse deliberado (rajada de saves mais
rápida que qualquer usuário real geraria através do editor), não o baseline
realista que este cenário pretende medir por padrão.

Validação estática antes de rodar:

```bash
k6 inspect loadtest/carga.js
```

Os três comandos abaixo assumem que `LOAD_TEST_PASSWORD` já foi definida
explicitamente como em [§3](#3-autenticação) — nunca via
`IWRITE_DEMO_AUTOR_A_PASSWORD`, que não existe no shell (só no `.env` lido
pelo Docker Compose).

**Nenhum comando abaixo passa `-e LOAD_TEST_PASSWORD=...`** (achado do Codex,
PR #141, "Keep the load-test password out of the process arguments"): o k6
já expõe toda variável de ambiente do processo em `__ENV` automaticamente —
confirmado empiricamente (`k6 v2.1.0`, script mínimo lendo `__ENV.FOO` com só
`export FOO=...` no shell, sem nenhum `-e`) — então `-e LOAD_TEST_PASSWORD=`
nunca foi necessário para o script enxergar a senha via
`__ENV.LOAD_TEST_PASSWORD` (`carga.js`). O problema de `-e VAR=valor` é que o
valor entra na **linha de comando do processo** k6: numa máquina
multiusuário, qualquer ferramenta que liste processos (`ps`, Gerenciador de
Tarefas, `/proc/<pid>/cmdline`) consegue ler esse argumento — inclusive de
outro usuário sem acesso ao seu shell/histórico. `export`/`$env:` continuam
sendo o único lugar onde a senha existe: no ambiente do processo, nunca em
`argv`. Confirmado depois de cada execução abaixo que a senha nunca aparece
na linha de comando do processo `k6` (inspeção de processo durante a
execução, ver [§10](#10-validado)).

### Smoke curto

```bash
k6 run \
  -e BASE_URL=http://localhost:8085 \
  -e VUS=2 -e WARMUP_DURATION=5s -e STEADY_DURATION=10s -e RAMPDOWN_DURATION=5s \
  loadtest/carga.js
```

```powershell
k6 run `
  -e BASE_URL=http://localhost:8085 `
  -e VUS=2 -e WARMUP_DURATION=5s -e STEADY_DURATION=10s -e RAMPDOWN_DURATION=5s `
  loadtest/carga.js
```

### Baseline — 10 VUs (padrão de estágios: 30s/2m/30s)

```bash
k6 run \
  -e BASE_URL=http://localhost:8085 \
  -e VUS=10 -e RESULT_PATH=loadtest/resultados/resultado-10vus.json \
  loadtest/carga.js
```

```powershell
k6 run `
  -e BASE_URL=http://localhost:8085 `
  -e VUS=10 -e RESULT_PATH=loadtest/resultados/resultado-10vus.json `
  loadtest/carga.js
```

### Carga ampliada — 30 VUs

```bash
k6 run \
  -e BASE_URL=http://localhost:8085 \
  -e VUS=30 -e RESULT_PATH=loadtest/resultados/resultado-30vus.json \
  loadtest/carga.js
```

```powershell
k6 run `
  -e BASE_URL=http://localhost:8085 `
  -e VUS=30 -e RESULT_PATH=loadtest/resultados/resultado-30vus.json `
  loadtest/carga.js
```

**Segredo nenhum sai de `setup()`, então nenhum caminho de summary consegue
vazá-lo — nem o nativo do k6.** `setup()` só devolve
`[{ bookId, sceneId }, ...]` (ver [§5](#5-dados-sintéticos-e-limpeza)); cada
VU e `teardown()` autenticam sozinhos, guardando a sessão só no próprio
cookie jar do k6, nunca em `data`. Isso cobre os **três** caminhos de summary
do k6, não só um:

- `RESULT_PATH` (via `handleSummary()`, que também aplica um allowlist extra
  em `setup_data` como defesa em profundidade);
- `--summary-export=<arquivo>`;
- `K6_SUMMARY_EXPORT=<arquivo>`.

Os dois últimos são nativos do k6 e **ignoram `handleSummary()` por
completo** — o k6 v2 anexa o resumo legado bruto por conta própria depois do
callback do usuário. Antes, `setup()` devolvia `authHeaders` com o
`JSESSIONID`/`XSRF-TOKEN` vivos da execução, então esses dois caminhos
vazavam a sessão mesmo com `handleSummary()` sanitizando `RESULT_PATH`. Como
`setup()` agora nunca devolve nada sensível, os três caminhos são seguros por
construção — testado explicitamente para os três, ver [§10](#10-validado).

O resumo impresso no terminal é minimalista e **gerado localmente** — `carga.js`
não importa nenhuma biblioteca remota (nem o `jslib` oficial do k6 para o
texto colorido padrão). O teste tem que conseguir iniciar sem acesso à
internet; os JSONs em `RESULT_PATH` continuam sendo a evidência principal.

`loadtest/resultado.json` **não é** uma cópia de execução — é o resumo
comparativo (10 vs. 30 VUs) descrito em [§9](#9-resultados-obtidos):
commit, ambiente, RPS, percentis, thresholds, limitações, gargalo e próxima
ação. Os JSONs brutos por execução ficam em `loadtest/resultados/`.

---

## 7. Thresholds

```text
http_req_failed              < 1%
checks                       > 99%
vu_auth_success               == 100%   (zero tolerância — ver §3)
http_req_duration p(95)      < 500ms   (global — todas as fases, todas as requisições)
http_req_duration p(95)      < 500ms   (por operação principal, SÓ fase estável: list_books, load_outline, load_scene, save_scene, refresh_outline_after_save — tag phase:steady)
operation_status_success rate > 99%    (por operação principal, SÓ fase estável — status EXATO esperado, não a classificação HTTP 200-399 padrão do k6, ver abaixo)
http_req_duration p(95)      < 2000ms  (auth/setup/teardown — fora do loop medido)
http_req_duration p(95)      < 8000ms  (auth_login — bcrypt + cold start da JVM, ver §9)
```

Qualquer violação faz o `k6 run` sair com código diferente de zero — apropriado
para gate de CI/CD. Os thresholds de auth/setup/teardown existem sobretudo
para que o k6 reporte as métricas dessas tags separadas das operações
principais no summary (ele só cria uma série por tag quando há threshold
associado), não como um orçamento de performance rígido — são operações que
rodam 1x ou `VUS` vezes por execução, nunca sob a carga em regime.
`vu_auth_success` é a única exceção: `rate==1` sem tolerância, porque uma
única falha de login de VU nunca pode "passar" reduzindo a carga
silenciosamente — ver [§3](#3-autenticação).

**Por que `operation_status_success`, e não `http_req_failed{operation:X}`,
por operação:** `http_req_failed` global dilui uma falha concentrada numa
única operação entre as outras quatro requisições do mesmo turno — 4% de
falha isolada em `save_scene` ainda fica bem abaixo de 1% no total agregado
de 5 operações. Além disso, `http_req_failed` usa a classificação padrão do
k6, que trata **qualquer resposta 200-399 como sucesso HTTP** — mas o
contrato deste cenário exige especificamente `200`. Uma regressão funcional
(ex.: `save_scene` passando a devolver `204` em vez de `200`) não apareceria
em `http_req_failed` nem em `http_req_failed{operation:save_scene}` de jeito
nenhum, porque `204` também é "sucesso" para essa classificação — só
`operation_status_success` (Rate customizada, tag `operation`+`phase`,
alimentada por `checkExactStatus()` com o status EXATO esperado) captura
essa regressão (achado do Codex, PR #141, "Gate exact operation statuses
instead of HTTP failures"). Validado com fault injection (servidor local
regredindo `4%` dos `PATCH` de `200` para `204`, ver
[§9](#9-resultados-obtidos)): `http_req_failed` global e
`http_req_failed{operation:save_scene}` ficaram em `0.00%` (ambos
passariam) enquanto `operation_status_success{operation:save_scene,
phase:steady}` caiu para `96.00%` e reprovou o threshold — o `k6 run` saiu
com código diferente de zero só por causa do gate de status exato.

**Por que `phase:steady` e não a operação sem filtro:** os estágios de
`WARMUP_DURATION`/`RAMPDOWN_DURATION` rodam com menos VUs que o pico
(rampando para cima/para baixo), então misturar essas amostras no mesmo p95
que os 2 minutos de `STEADY_DURATION` dilui o percentil — um threshold podia
"passar" mesmo que a carga em regime, sozinha, já rompesse o teto. A fase é
recalculada a cada dispatch de requisição (`currentPhase()` em `carga.js`,
via `loadStartAt()` — o início da curva medida, depois da janela de
preparação de autenticação, ver [§3](#3-autenticação) — e as durações
configuradas, convertidas para ms por `k6DurationToMs()`) — **não** mais uma
vez só no topo do turno: um turno que atravesse uma borda de fase durante o
debounce de `AUTO_SAVE_DELAY_MS` (1200ms) pode legitimamente ter
`list_books`/`load_outline`/`load_scene` numa fase e `save_scene`/
`refresh_outline_after_save` na fase seguinte — capturar a fase uma única vez
contaminaria os percentis de uma fase com amostras que na verdade pertencem à
outra (achado do Codex, PR #141, com prova empírica em
[§9](#9-resultados-obtidos)). `http_req_duration` **sem** tag continua
agregando as 3 fases — é o número de "execução completa" em
[§9](#9-resultados-obtidos), não o de regime permanente.

---

## 8. Como ler o resultado

- **Tags exportadas:** `operation` (dimensão funcional: `list_books`,
  `save_scene`, `auth_login`, ...), `name` (rota HTTP normalizada, ex. `GET
  /api/scenes/{sceneId}`) e, só nas 5 operações principais, `phase`
  (`ramp_up`/`steady`/`ramp_down`) — nunca a URL concreta. A tag automática
  `url` do k6 está desabilitada via `systemTags`, então nenhum `bookId`,
  `sceneId`, `runId`, título, conteúdo, usuário ou tenant vaza para uma tag,
  mesmo que o resultado seja exportado para um time-series output (`--out
  json=...`) ou o k6 cloud — validado rodando um smoke com `--out json=` e
  conferindo que nenhum UUID aparece em `data.tags` e que `phase` só assume
  os 3 valores esperados (arquivo temporário, nunca versionado).
- **`http_req_duration{operation:...,phase:steady}`** — cada operação
  principal tem sua própria série de percentis (`avg`, `min`, `med`=p50,
  `p(90)`, `p(95)`, `p(99)`, `max`), restrita à fase estável, graças a essas
  tags e a `summaryTrendStats` configurado no script. O k6 só rastreia uma
  combinação de tags como sub-métrica separada no summary quando ela tem
  threshold associado ([§7](#7-thresholds)) — por isso `operation:X` **sem**
  `phase` não aparece mais como série própria: sem essa tag adicional, a
  amostra cairia de volta no `http_req_duration` global agregando as 3 fases.
- **`http_req_duration` (sem tag) e `http_reqs.rate`** — agregam **todas** as
  requisições da execução, **todas as fases**: smoke, auth, setup do
  livro/seção/capítulo/cenas, as 5 operações principais **e** o teardown. Não
  representam a latência do loop principal sozinho nem a fase estável isolada
  — para isso, leia as séries `phase:steady` das 5 operações principais.
  `resultado.json` mantém essa distinção explícita (`execucao_completa` vs.
  `operacoes_principais` [phase:steady] vs. `auth_setup_teardown`).
- **`checks`** — as 5 asserções de status das operações principais
  (`list_books`, `load_outline`, `load_scene`, `save_scene`,
  `refresh_outline_after_save` — só roda, e só é checada, quando `save_scene`
  teve sucesso).
- Para investigar a operação mais lenta (`save_scene`, ver §9), use a
  observabilidade já existente do projeto (OTel + Loki/Tempo/Grafana via
  `docker-compose.observability.yml`) e procure os eventos de negócio
  `scene_content_save` no serviço do backend — ver
  [`docs/otel-correlated-logs.md`](../docs/otel-correlated-logs.md) para o
  formato dos logs estruturados.

---

## 9. Resultados obtidos

Execuções reais, ambiente local isolado (ver limitações abaixo). **Esta é
uma remedição depois de 3 novos P2 do Codex sobre `3331d93`** (código medido
anterior: `dece9f3`):

1. **"Release the iteration when think time ends".** O refetch
   (`refresh_outline_after_save`) já usava `http.asyncRequest()` "em
   paralelo" com o think time via `Promise.all([refreshPromise,
   delay(thinkTime())])`, mas isso continuava sendo barreira da iteração
   seguinte sempre que o refresh fosse mais lento que o think time —
   contrariando o fire-and-forget genuíno do frontend real (`void
   queryClient.invalidateQueries(...)`, `scene-editor.tsx`). Confirmado
   **empiricamente** (não só por leitura de documentação) que o k6 v2.1.0
   drena **todas** as Promises pendentes de uma iteração — mesmo as nunca
   aguardadas — antes de considerá-la concluída, então nenhuma variação de
   Promise dentro da MESMA iteração k6 resolve isso (dois scripts throwaway
   não versionados provaram isso: um com Promise solta, outro com laço manual
   dentro de uma única iteração k6 longa — ver "Prova do fire-and-forget"
   abaixo). Corrigido restruturando `default()` para rodar **uma única
   iteração k6 por VU** (`options.scenarios.default`, executor
   `per-vu-iterations`) contendo um laço interno manual de turnos — dentro
   desse laço, o refresh é disparado e nunca aguardado, então o turno
   seguinte começa após o think time mesmo com o refresh ainda em voo; o
   evento loop só é forçado a drená-lo quando a VU inteira termina.
2. **"Tag each operation using its dispatch-time phase".** `phase` era
   capturada uma vez no topo do turno e reutilizada nas 5 operações — um
   turno que atravessasse a borda `warmup`/`steady`/`rampdown` durante o
   debounce de `AUTO_SAVE_DELAY_MS` (1200ms) podia marcar `save_scene` como
   `phase:steady` já em `ramp_down`, contaminando os percentis que os
   thresholds tratam como exclusivamente de pico. Corrigido chamando
   `currentPhase()` no instante do dispatch de cada uma das 5 requisições —
   consequência direta da mesma restruturação do finding 1 (o laço manual já
   precisava recalcular tudo por turno).
3. **"Stop retrying authentication after a recorded failure".** Uma VU com
   falha de login persistente re-tentava o handshake de duas requisições a
   cada iteração k6, gerando tempestade de retries que podia esgotar o
   rate-limit de login da conta/origem e impedir a autenticação do
   `teardown()`. Corrigido também como consequência da mesma restruturação:
   como `default()` agora só roda 1x por VU, `authenticateVuOnce()` não tem
   "próxima iteração" para retentar — uma falha já é terminal por
   construção, sem necessidade de estado tri-valor.

Os três achados exigiam mudar quando/como cada requisição é disparada dentro
do turno de uma VU, então foram resolvidos pela **mesma mudança estrutural**
— ver [§1](#1-o-que-o-teste-faz). Sob condições normais (backend saudável),
isso **não muda a carga oferecida** de forma perceptível: o refresh de
`dece9f3` já rodava em paralelo com o think time e quase sempre terminava
antes dele; o efeito só aparece quando o outline está degradado — ver
"Comparação com a rodada anterior" abaixo.

### Esta rodada — 4 novos P2 do Codex sobre `e8ffa7b` (código medido anterior: `3bb79ce`)

1. **"Let the final turn drain before maxDuration".** `MAIN_LOOP_GRACE_MS`
   era um valor fixo (`60000`ms) sem relação com o pior caso real de um
   turno — não provava que o último turno (e o `refresh_outline_after_save`
   pendente que ele dispara) sempre terminaria antes de `maxDuration`; sob
   endpoint degradado, o k6 podia matar a iteração e descartar tráfego lento
   exatamente na condição que o teste deveria observar. Corrigido com
   `HTTP_REQUEST_TIMEOUT` explícito por requisição (default `10s`, aplicado
   às 5 operações do turno) e uma folga derivada matematicamente do pior
   caso — ver [§6](#6-executando) para a fórmula completa e a prova de que
   nenhum turno iniciado legalmente pode ser morto por `maxDuration`.
2. **"Point reproducibility commands at a reachable commit".** O README
   instruía `git checkout <measured_code_commit> -- loadtest/carga.js` e
   `git cat-file blob <measured_code_commit>:...`, mas `measured_code_commit`
   é um commit intermediário da branch de desenvolvimento que pode deixar de
   ser alcançável depois do squash-merge (confirmado: `e8ffa7b` tem `c9921c9`
   como único pai, `git merge-base --is-ancestor 3bb79ce e8ffa7b` falha).
   Corrigido ancorando a reprodutibilidade em `measured_script_git_blob` —
   ver "Hierarquia de confiança pós-squash" logo abaixo.
3. **"Cover the full ramp interval with VU offsets".** `(vuIndex-1)/VUS`
   varia só de `0` a `(VUS-1)/VUS` — nunca alcança `1` — então a última VU
   desativava `RAMPDOWN_MS/VUS` antes do fim nominal de cada rampa (ex.:
   `VUS=2`/rampa de `5s` deixava os últimos `2.5s` sem nenhuma VU ativa).
   Corrigido trocando para `vuIndex/VUS`, a discretização exata do alvo
   contínuo `VUs_ativas(t)=VUS×t/WARMUP_MS` — garante que a última VU cubra
   o extremo exato de cada rampa. Ver o comentário de `activationOffsetMs()`
   em `carga.js` para a tabela de verificação (VUS=1/2/10/30) e "Prova da
   cobertura de rampa" abaixo para a validação empírica.
4. **"Report steady-state turn counts from steady samples".** O
   `resultado.json` da rodada anterior reportava `turnos_totais` (789 em 10
   VUs, 2343 em 30 VUs) dentro de `operacoes_principais` — mas esse número
   vinha de `checks` **globais** (`3945/5`, todas as fases: ramp_up + steady
   + ramp_down), não de amostras `phase:steady`, sobrestimando o throughput
   de regime (o raw summary da rodada anterior já mostrava só 630/1866
   amostras `save_scene` com `phase:steady`). Corrigido lendo o `count` da
   própria `Trend` com threshold `http_req_duration{operation:save_scene,
   phase:steady}` (habilitado via `'count'` em `summaryTrendStats`) como
   `turnos_steady`, e movendo a contagem whole-run — agora
   `turnos_execucao_total`, lida de `root_group.checks` — para
   `execucao_completa`. Nenhum `Counter` dedicado foi adicionado: o `count`
   da `Trend` já existente é fonte de verdade suficiente.

Os achados 1 e 3 alteram `loadtest/carga.js` e por isso exigiram nova
medição completa (novo `measured_code_commit`, novo blob, novo SHA-256,
smoke/10 VUs/30 VUs remedidos naquela rodada). Os achados 2 e 4 são só de
documentação/leitura dos dados já coletados.

### Rodada seguinte — 2 novos P2 do Codex sobre `f98275a` (código medido anterior: `fbd98db`)

1. **"Reach peak load before starting the steady phase".** Cada VU esperava
   seu `activationOffsetMs()` (relativo a `exec.scenario.startTime`) e só
   DEPOIS autenticava — a última VU só começava o handshake (`GET
   /api/auth/csrf` + `POST /api/auth/login`) em `t=WARMUP_MS`, exatamente
   quando `currentPhase()` já rotulava amostras como `steady`. Se autenticar
   fosse lento o bastante (o próprio `http_req_duration{operation:
   auth_login}` tolera até `8s` de p95, por bcrypt + warmup de JVM), o
   início de `steady` era medido com menos que `VUS` VUs realmente
   produzindo carga, diluindo os percentis. Corrigido separando autenticação
   da curva medida: toda VU autentica **imediatamente** no início da sua
   iteração k6 (antes de qualquer espera), `login()` passou a usar
   `HTTP_REQUEST_TIMEOUT` em cada uma das 2 requisições do handshake, e um
   novo relógio de carga `loadStartAt() = exec.scenario.startTime +
   AUTH_PREPARE_MS` (`AUTH_PREPARE_MS = 2×HTTP_REQUEST_TIMEOUT_MS + margem`)
   garante por construção que toda autenticação bem-sucedida termina antes
   de `loadStartAt()` — logo antes do `activationOffsetMs()` de qualquer VU,
   que passou a ser relativo a `loadStartAt()`, nunca mais a
   `exec.scenario.startTime` diretamente. `currentPhase()` também passou a
   medir a partir de `loadStartAt()`. `maxDuration` passou a somar
   `AUTH_PREPARE_MS` ao orçamento total, sem reduzir
   warmup/steady/rampdown/grace — ver [§6](#6-executando) para a fórmula e
   "Prova da janela de preparação de autenticação" abaixo para a validação
   empírica.
2. **"Gate exact operation statuses instead of HTTP failures".** Os 5
   thresholds de erro por operação usavam `http_req_failed`, cuja
   classificação padrão trata qualquer resposta `200-399` como sucesso —
   mas o contrato deste cenário exige especificamente `200`. Se, por
   exemplo, `4%` das respostas `save_scene` regredissem de `200` para
   `204`, `http_req_failed{operation:save_scene}` continuaria em `0%`
   (`204` é "sucesso HTTP" para o k6) e as falhas ficariam diluídas entre os
   demais checks — o contrato quebrado passaria despercebido. Corrigido com
   uma `Rate` customizada (`operation_status_success`, tag `operation`+
   `phase`) alimentada pelo mesmo booleano do `check()` de status exato via
   `checkExactStatus()` — substituindo os 5
   `http_req_failed{operation:X,phase:steady}` por 5
   `operation_status_success{operation:X,phase:steady}: ['rate>0.99']`,
   estritamente mais fortes para este propósito (qualquer status fora do
   range `200-399` que `http_req_failed` já pegaria também reprova aqui, daí
   a remoção dos thresholds antigos em vez de mantê-los em paralelo como
   redundância). `phase` é capturada uma única vez por requisição — inclusive
   antes do `http.asyncRequest()` do refresh, nunca dentro do `.then()` — e
   reutilizada tanto na tag quanto na chamada de `checkExactStatus()`, mesmo
   raciocínio de "dispatch-time phase" já usado nas rodadas anteriores. Ver
   "Prova do contrato de status exato" abaixo para a fault injection.

Os dois achados alteram `loadtest/carga.js` e exigiram nova medição
completa.

### Rodada seguinte — 2 novos P2 do Codex sobre `070d435` (código medido anterior: `56b9303`)

1. **"Bound each orphan-recovery request".** `recoverOrphanedBookIds()`
   declarava uma janela (`ORPHAN_RECOVERY_WINDOW_MS=5000`) e um loop
   temporal, mas o `GET /api/books` de cada `scanOnce()` não tinha timeout
   explícito — uma única consulta lenta podia sozinha consumir (ou
   ultrapassar) a janela inteira sem que o `while (Date.now() < deadline)`
   tivesse chance de reagir, e perto do fim de `SETUP_TIMEOUT` isso podia
   deixar o k6 matar `setup()` externamente antes de `cleanupBooks()` rodar.
   Corrigido em três níveis: (a) cada GET de recuperação agora usa
   `timeout: min(ORPHAN_RECOVERY_REQUEST_TIMEOUT_MS, tempo restante até o
   deadline da janela)` — nunca o timeout default de 60s do k6, nem o
   `HTTP_REQUEST_TIMEOUT` cheio do loop principal quando este exceder a
   própria janela; (b) o `sleep()` entre polls nunca ultrapassa o deadline
   (dorme só o que resta, ou nem dorme); (c) `setup()` calcula um deadline
   absoluto (`setupDeadlineAt`) e, antes de cada nova operação de
   provisionamento, verifica se o orçamento restante cobre
   request-em-andamento + janela de recuperação inteira + cleanup
   sequencial dos livros já conhecidos (`cleanupBudgetMs()`) — se não
   cobrir, falha de forma controlada (preservando o erro original) em vez
   de esperar `SETUP_TIMEOUT` matar `setup()` de fora. Uma validação
   fail-fast rejeita, no carregamento do script, um `SETUP_TIMEOUT`
   matematicamente insuficiente para o `VUS` pedido, antes de criar
   qualquer dado. Os 4 `POST`s de provisionamento (book/section/
   chapter/scene) e o `DELETE` de `cleanupBooks()` passam a usar timeout
   explícito (`HTTP_REQUEST_TIMEOUT`/`CLEANUP_DELETE_TIMEOUT_MS`) — sem
   isso, o cálculo de orçamento acima não teria como ser matematicamente
   defensável, e o achado do Codex explicitamente pedia para não apenas
   deslocar o bloqueio de GET para DELETE. Ver "Prova do bound de
   recuperação de órfãos" e "Prova do orçamento de cleanup" abaixo para a
   fault injection.
2. **"Keep the load-test password out of the process arguments".** Os
   comandos do README passavam `-e LOAD_TEST_PASSWORD="$LOAD_TEST_PASSWORD"`
   (bash) / `-e "LOAD_TEST_PASSWORD=$env:LOAD_TEST_PASSWORD"` (PowerShell)
   mesmo já carregando a senha no ambiente sem eco — isso expande a senha
   como argumento da linha de comando do processo `k6`, visível para
   qualquer ferramenta de inspeção de processo numa máquina multiusuário
   (`ps`, Gerenciador de Tarefas, `/proc/<pid>/cmdline`). Corrigido
   removendo os 6 usos de `-e LOAD_TEST_PASSWORD=...` do README (smoke/10
   VUs/30 VUs × bash/PowerShell): confirmado empiricamente que o k6 já
   expõe toda variável de ambiente do processo via `__ENV` automaticamente,
   sem precisar de `-e` — a senha continua chegando a
   `__ENV.LOAD_TEST_PASSWORD` (`carga.js` não mudou nesse ponto) só que
   agora nunca aparece em `argv`. Não há exemplo de k6 via Docker no
   repositório (escopo não ampliado só para criar um). Ver "Prova da senha
   fora do argv" abaixo.

Só o achado 1 altera `loadtest/carga.js` e exigiu nova medição completa
(novo `measured_code_commit`, novo blob, novo SHA-256, smoke/10 VUs/30 VUs
remedidos nesta rodada) — a janela de recuperação/cleanup fica fora do loop
medido, então a remedição não é esperada para mudar as latências do loop
principal (ver "Comparação com a medição anterior" em
[`resultado.json`](resultado.json)). O achado 2 é só documentação dos
comandos.

**Hierarquia de confiança pós-squash** (achado do Codex, PR #141: um squash
anterior nesta mesma PR — `e8ffa7b`, único pai `c9921c9` — já provou que
`git merge-base --is-ancestor 3bb79ce e8ffa7b` falha; o commit intermediário
onde a medição rodou pode deixar de ser alcançável assim que a branch de
desenvolvimento for removida):

- **`measured_code_commit`**: `746cdbb59147ff11a9bd22d1c2da4c9a37c9bc80` — o
  commit de `loadtest/carga.js` exatamente como executado para gerar os
  números abaixo, working tree limpo, sem nenhuma mudança de código depois.
  **Só serve como proveniência histórica de desenvolvimento** — não é o
  mecanismo de reprodutibilidade pós-squash. Depois que esta PR for
  squash-merged e a branch `feature/k6-realistic-baseline` removida, um
  clone novo não tem garantia de conseguir rodar `git checkout
  746cdbb59147ff11a9bd22d1c2da4c9a37c9bc80 -- loadtest/carga.js` — o commit
  pode não estar mais alcançável a partir de `master` (mesma ressalva já
  confirmada uma vez nesta PR: `e8ffa7b`, único pai `c9921c9`, tornou
  `3bb79ce` inalcançável).
- **`measured_script_path`**: `loadtest/carga.js`.
- **`measured_script_git_blob`**: `8b8a53ebe4207ee7f8ea951dde273cdd741b5154`
  — a âncora **estável** de reprodutibilidade, que sobrevive ao squash desde
  que o conteúdo de `loadtest/carga.js` no commit final seja byte-a-byte
  igual ao medido aqui (é o caso: nenhum commit de evidência depois deste
  muda o comportamento do script). Verifique direto pelo blob, sem depender
  de `measured_code_commit` continuar alcançável — funciona em qualquer
  commit final, incluindo o SHA do squash que esta PR ainda não tem:
  ```bash
  # no commit que você quer auditar (ex.: HEAD de master, depois do merge):
  git rev-parse HEAD:loadtest/carga.js
  # deve imprimir exatamente 8b8a53ebe4207ee7f8ea951dde273cdd741b5154 —
  # se imprimir outro valor, loadtest/carga.js mudou desde a medição.
  ```
  Reconstrua o arquivo medido diretamente do objeto Git (o blob sobrevive
  independente de qual commit o referencia):
  ```bash
  git cat-file blob 8b8a53ebe4207ee7f8ea951dde273cdd741b5154 > carga-medida.js
  ```
- **`measured_script_sha256`**:
  `18bb2fdc32fe5f4d3e483dc7d7ccfdad7e37d58eab745f22d5d657e5f4292b9f` — SHA-256
  dos bytes do blob acima, calculado com:
  ```bash
  python -c "import hashlib,subprocess; d=subprocess.check_output(['git','cat-file','blob','8b8a53ebe4207ee7f8ea951dde273cdd741b5154']); print(hashlib.sha256(d).hexdigest())"
  ```
  ou, direto do blob, sem Python:
  ```bash
  git cat-file blob 8b8a53ebe4207ee7f8ea951dde273cdd741b5154 | sha256sum
  ```
  **Não** use `sha256sum loadtest/carga.js` nem `Get-FileHash` sobre o
  arquivo já checked-out como prova — mesma ressalva de CRLF/LF de rodadas
  anteriores (`dece9f3`), já coberta por `.gitattributes` (`text eol=lf`).
  `git cat-file blob` lê os bytes do objeto armazenado no Git diretamente,
  sem passar pelo checkout — o mesmo em qualquer SO.
- **`evidence_commit`**: o commit imediatamente seguinte nesta branch, que só
  adiciona/atualiza `resultado.json`, `resultados/*.json` e este README — sem
  nenhuma mudança de comportamento do script. Hash exato na descrição da PR
  #141 (não dá para gravar o próprio hash dentro do arquivo que ele versiona
  sem autorreferência).

Em resumo: `measured_code_commit` identifica ONDE a medição aconteceu durante
o desenvolvimento (só histórico); `measured_script_git_blob` identifica de
forma estável OS BYTES do script medido, e é o que sobrevive ao squash — a
verificação de que o comportamento medido ainda está em vigor deve comparar
`git rev-parse <commit-final>:loadtest/carga.js` contra
`measured_script_git_blob`, nunca assumir que `measured_code_commit`
continua alcançável.

Resumo comparativo completo, estruturado, em
[`resultado.json`](resultado.json); JSONs brutos por execução em
[`resultados/resultado-10vus.json`](resultados/resultado-10vus.json) e
[`resultados/resultado-30vus.json`](resultados/resultado-30vus.json).

### Ambiente desta rodada

Energia AC confirmada via
`[System.Windows.Forms.SystemInformation]::PowerStatus.PowerLineStatus`
(`Online`) imediatamente antes das três execuções aqui registradas e
novamente depois — nenhum incidente de throttling de bateria nas execuções
registradas. `crm-marketing-backend-1` (e seus containers de db/frontend)
confirmados parados (`docker ps -a`) antes de medir.

**Uma primeira tentativa de 10 VUs foi descartada por contaminação:**
`save_scene` esteve com mediana saudável (`42.5ms`) mas `p95=1594ms`,
`p99=6590ms`, `max=7382ms` — uma cauda de poucas amostras muito lentas,
enquanto as outras 4 operações (`list_books`/`load_outline`/`load_scene`/
`refresh_outline_after_save`) permaneceram normais. Outro worktree do
IWrite (`iwrite-backend-1`/`iwrite-db-1`/`iwrite-frontend-1`, portas padrão)
estava ativo havia ~20min na mesma máquina durante essa tentativa. Depois de
pausar essa sessão (confirmado `docker stats` com CPU ~0% em todos os
containers antes de remedir), as três execuções (smoke, 10 VUs, 30 VUs)
registradas abaixo rodaram em sequência, sem esse padrão de cauda e sem
outra carga de trabalho pesada ativa na máquina. O JSON bruto da tentativa
descartada não foi versionado.

### Ambiente da rodada seguinte (`56b9303`)

A mesma stack concorrente de outro worktree (`iwrite-backend-1`/
`iwrite-db-1`/`iwrite-frontend-1`, portas padrão) voltou a ficar ativa antes
desta rodada. Diferente da vez anterior (só pausada), desta vez os
containers foram **parados por completo** (`docker ps` não os lista mais,
não só `docker stats` mostrando CPU ociosa) antes das três execuções
registradas em "Resultados desta rodada" abaixo — energia AC confirmada
`Online` antes e depois, `crm-marketing-*` parado. Nenhuma tentativa foi
descartada nesta rodada.

### Ambiente da rodada seguinte (`746cdbb`)

Stack `iwrite-k6-*` já em execução havia 11h+ (`healthy`, reaproveitada sem
reiniciar) — `docker stats` confirmou os 3 containers em CPU `~0%` momentos
antes de cada uma das três execuções. `crm-marketing-*` e a stack de outro
worktree do IWrite (`iwrite-backend-1`/`iwrite-db-1`/`iwrite-frontend-1`)
confirmados `Exited` (`docker ps -a`) durante toda a execução — nenhuma das
duas rodando. Energia AC confirmada `Online`
(`[System.Windows.Forms.SystemInformation]::PowerStatus.PowerLineStatus`)
imediatamente antes das três execuções aqui registradas. Nenhuma tentativa
foi descartada nesta rodada.

### Revalidação pós-integração da master (PR #149) — `measured_code_commit` permanece `746cdbb`

A branch `feature/k6-realistic-baseline` recebeu um merge de `origin/master`
(`0ea05c5a24ceed65938a1df05c7f2f5e15a360cc`), que já contém a PR #149
(autenticação, cadastro público, sessão e rate limiting), através do commit de
merge `1dd985b39d4e018f938edccccfa1522d87625d49` — exclusivamente um merge,
sem edição manual de conteúdo. Isso **não é uma nova medição de código**: o
harness k6 não mudou, só o backend alvo mudou, então esta seção documenta uma
revalidação, não uma remedição.

Confirmado antes de medir:

- `git merge-base --is-ancestor origin/master HEAD` (a partir de `1dd985b`) —
  sucesso: `master@0ea05c5` é ancestral do HEAD da PR.
- `git diff 76e536264f4c763baae7b295f6ab4ab6dae4055a..1dd985b -- loadtest/` —
  vazio: **todo** o diretório `loadtest/` (não só `carga.js`) permaneceu
  byte-a-byte idêntico através do merge.
- `git rev-parse 746cdbb59147ff11a9bd22d1c2da4c9a37c9bc80:loadtest/carga.js`
  e `git rev-parse HEAD:loadtest/carga.js` (com `HEAD=1dd985b`) — o mesmo blob
  (`8b8a53ebe4207ee7f8ea951dde273cdd741b5154`).
- `git cat-file blob 8b8a53ebe4207ee7f8ea951dde273cdd741b5154 | sha256sum` —
  reproduz exatamente `measured_script_sha256`
  (`18bb2fdc32fe5f4d3e483dc7d7ccfdad7e37d58eab745f22d5d657e5f4292b9f`).

Por isso `measured_code_commit` **permanece** `746cdbb59147ff11a9bd22d1c2da4c9a37c9bc80`
— nenhum commit novo foi criado só por causa do merge da master.

**O que mudou no backend integrado, relevante a este cenário:** 4 migrations
novas (`V31__create_user_personas`, `V32__normalize_user_emails`,
`V33__canonicalize_user_emails`,
`V34__backfill_legacy_user_persona_after_email_normalization`), aplicadas com
sucesso no boot do backend isolado (Flyway: schema `30` → `v34`, confirmado no
log de startup). As chaves de configuração do rate limiter de login
(`iwrite.auth.login-rate-limit.max-attempts-per-account/max-attempts-per-origin/window`)
**não mudaram** — o overlay `docker-compose.loadtest.yml` (que eleva esse
limite só na stack isolada, ver [§1](#1-o-que-o-teste-faz)) continuou válido
sem nenhuma alteração, confirmado por zero `429` nas três execuções abaixo. A
PR #149 só adicionou um bloco de configuração novo e independente
(`iwrite.auth.registration-rate-limit`) para `POST /api/auth/register` — não
exercitado por este script, que só autentica via login, nunca registra conta
nova.

**Ambiente desta revalidação:** a stack `iwrite-k6-*` foi recriada do zero
(`docker compose ... up -d --build`) já com o código integrado. A checagem
inicial de energia acusou `Offline` (bateria) — a execução foi **pausada**
até o notebook ser conectado à tomada e o status ser reconfirmado `Online`
antes de qualquer execução medida (smoke, 10 VUs, 30 VUs). Os containers
`crm-marketing-frontend-1`/`backend-1`/`db-1` subiram sozinhos junto com o
Docker Desktop (restart policy) e foram parados (`docker stop`) antes de
medir. As stacks de outros worktrees do IWrite permaneceram `Exited` durante
toda a sessão. Zero resíduo `LOADTEST-` confirmado no Postgres do stack
isolado após cada uma das três execuções.

**Resultado:** smoke, 10 VUs e 30 VUs passaram — 100% dos checks, `0%` de
`http_req_failed`, `vu_auth_success==100%` sem tolerância, zero `429`, os 21
thresholds (5 latência + 5 contrato de status exato em `phase:steady`, 4
globais, 7 auxiliares de auth/setup/teardown) todos `ok:true`, teardown
autenticou e removeu todos os livros sintéticos das duas execuções (10 e 30).
Números completos, comparação com a medição pré-integração e ressalva sobre
variação normal de execução em
[`resultado.json`](resultado.json)`.resumo_comparativo.integracao_master_pr149`
e `.comparacao_com_medicao_anterior_746cdbb_pre_integracao`; JSONs brutos
desta revalidação em
[`resultados/resultado-10vus.json`](resultados/resultado-10vus.json) e
[`resultados/resultado-30vus.json`](resultados/resultado-30vus.json)
(sobrescreveram os da rodada `746cdbb` pré-integração, que media exatamente o
mesmo script contra um backend anterior à PR #149).

### Prova do bound de recuperação de órfãos (achado 1 desta rodada) — fault injection não versionada

Proxy HTTP local throwaway (`fi_proxy.py`, poucas linhas, `http.server` +
`urllib`) na frente do backend real: encaminha cada requisição
imediatamente, mas pode segurar a resposta de uma rota específica por um
tempo configurável antes de repassá-la ao cliente k6 — simula "o servidor
processou, a resposta é que atrasou/se perdeu", sem modificar o backend.

Execução: `VUS=1`, `HTTP_REQUEST_TIMEOUT=2s`, `SETUP_TIMEOUT=15s`, regras do
proxy: `POST /api/books` segura a resposta por `6s` (força timeout
client-side, ambíguo) e **todo** `GET /api/books` (o caminho de
`setup_recover_books`) segura a resposta por `30s` — muito acima de
`ORPHAN_RECOVERY_WINDOW_MS=5000ms`.

Timeline observada nos logs do k6 (timestamps reais):

```
18:14:10  runId gerado, POST /api/books despachado
18:14:12  POST /api/books: "request timeout"           (~2000ms, == HTTP_REQUEST_TIMEOUT — nunca os 6s injetados)
18:14:14  GET /api/books (scan 1): "request timeout"    (~2000ms depois, == ORPHAN_RECOVERY_REQUEST_TIMEOUT_MS — nunca os 30s injetados)
18:14:16  GET /api/books (scan 2): "request timeout"    (~2000ms depois — poll respeitou o intervalo e o teto por request)
18:14:17  "setup() falhou ... após criar 0 livro(s)"     (~1s depois — janela fechou em ~5s totais, sem 3º scan)
```

Confirmado: nenhuma request individual (nem a `POST` ambígua, nem os dois
`GET` de recuperação) esperou perto dos delays injetados (`6s`/`30s`) — cada
uma foi cortada exatamente no teto calculado. A janela de recuperação inteira
durou `~5s` (18:14:12 → 18:14:17), batendo com `ORPHAN_RECOVERY_WINDOW_MS`,
não com o delay injetado. Nenhum terceiro scan foi disparado depois que o
orçamento acabou (a lógica de sleep bounded parou o loop antes do deadline
ser cruzado de novo). Como o `GET` de recuperação também estava bloqueado
nesta injeção deliberadamente adversarial (o próprio mecanismo de
reconciliação ficou sem capacidade de responder dentro da janela), o livro
criado ambiguamente pelo backend real (a injeção segura a resposta, não o
processamento) não pôde ser reconciliado a tempo — resíduo esperado sob esta
condição especificamente adversarial, removido manualmente após a validação
(`DELETE FROM books WHERE title LIKE 'LOADTEST-%'` no Postgres do stack
isolado). Isso demonstra a propriedade que o achado pedia — teto de tempo
por request e pela janela — não uma garantia de que a reconciliação sempre
terá sucesso sob condições arbitrariamente adversas.

### Prova do orçamento de cleanup (achado 1 desta rodada, continuação) — fault injection não versionada

Mesmo proxy, regra diferente: `VUS=5`, `HTTP_REQUEST_TIMEOUT=2s`,
`SETUP_TIMEOUT=20s` (acima do mínimo matemático de `19000ms` para `VUS=5`,
então a validação fail-fast não rejeita a configuração de saída) — todo
`POST /api/books` segura a resposta por `1.7s` (abaixo do timeout, então
cada livro ainda é criado com sucesso, só mais devagar).

Resultado real:

```
18:15:11  runId gerado
18:15:21  "setup() falhou (runId ...) após criar 3 livro(s) sintético(s): Orçamento de
           SETUP_TIMEOUT insuficiente para provisionar com segurança o livro 4/5
           (restam 13653ms, precisa de pelo menos 17000ms ...)"
18:15:21  "Limpeza automática removeu todos os 3 livro(s) órfão(s)."
18:15:22  k6 encerra com código de falha (exit 107)
```

`17000ms` bate exatamente com a fórmula (`HTTP_REQUEST_TIMEOUT_MS(2000) +
ORPHAN_RECOVERY_WINDOW_MS(5000) + cleanupBudgetMs(4)(10000) = 17000`).
Tempo total decorrido: `~11s` (18:15:11 → 18:15:22) — bem abaixo dos `20s`
de `SETUP_TIMEOUT`, confirmando que `setup()` se encerrou pelo próprio
guard-rail, **nunca** pelo k6 matando a função externamente por
`SETUP_TIMEOUT`. Os 3 livros conhecidos foram limpos por completo antes do
`throw` (confirmado via consulta direta ao Postgres: zero resíduo do
`runId`). `k6 run` saiu com código diferente de zero, sem hanging.

### Prova da corrida de commit tardio (achado 1 desta rodada, continuação) — fault injection não versionada

Mesmo proxy, regra de **pré-delay** (atraso antes de encaminhar ao backend
real, não depois): `VUS=1`, `HTTP_REQUEST_TIMEOUT=2s`, `SETUP_TIMEOUT=15s`,
`POST /api/books` só é encaminhado ao backend `2.3s` depois de chegar no
proxy — ou seja, o cliente k6 já desiste (timeout em `2.0s`) antes mesmo do
backend real receber a requisição, e o commit só acontece por volta de
`2.35s`. Um livro decoy de outro `runId`
(`LOADTEST-OTHERRUNDECOY-vu1`, criado manualmente via `curl` antes do teste)
foi deixado no tenant para validar isolamento entre execuções concorrentes.

Resultado: `setup() falhou ... após criar 1 livro(s) sintético(s)` — o `1`
aqui é o `bookIds.length` passado a `cleanupBooks()`, ou seja, `createdBookIds`
estava vazio (a criação nunca foi confirmada como bem-sucedida no cliente)
mas a **recuperação encontrou e removeu** o livro que só commitou
tardiamente — exatamente a corrida "scan 1 → 0 livros, commit tardio, scan
posterior → encontra livro" que a lógica de polling existe para cobrir,
preservada depois do refactor de bounding desta rodada. Confirmado via
consulta direta ao Postgres depois do teste: o livro da execução em falha
foi removido, e o livro decoy `LOADTEST-OTHERRUNDECOY-vu1` (outro `runId`,
título sem o marcador `LOADTEST-<runId>-vu` desta execução) permaneceu
intocado — isolamento entre `runId`s concorrentes confirmado. Ambos os
livros de teste foram removidos manualmente depois da validação.

### Prova da senha fora do argv (achado 2 desta rodada) — validação real

Script mínimo (`__ENV.FOO`) confirmou primeiro que o k6 v2.1.0 expõe
variáveis de ambiente do processo via `__ENV` **mesmo sem nenhum `-e`**:
`export FOO=bar_secret` no shell, `console.log(__ENV.FOO)` no script, sem
`-e FOO=...` na linha de comando — o valor apareceu normalmente no log.

Depois de remover os 6 usos de `-e LOAD_TEST_PASSWORD=...` do README, um
smoke real (`VUS=2`, só `export LOAD_TEST_PASSWORD`, sem `-e` para a senha)
rodou de ponta a ponta: `checks: 100.00% (80/80)`, `http_req_failed: 0.00%`,
teardown removeu os 2 livros — confirmando que `setup()`, cada VU e
`teardown()` continuam autenticando normalmente só com a variável de
ambiente. Durante uma segunda execução idêntica, a linha de comando do
processo `k6.exe` em execução foi inspecionada via
`Get-CimInstance Win32_Process -Filter "Name='k6.exe'" | Select-Object CommandLine`:

```
"C:\Program Files\k6\k6.exe" run -e BASE_URL=http://localhost:8093 -e VUS=2 -e
WARMUP_DURATION=5s -e STEADY_DURATION=10s -e RAMPDOWN_DURATION=5s loadtest/carga.js
```

Confirmado: só flags não sensíveis na linha de comando — nenhuma ocorrência
da senha. `bash`/PowerShell disponíveis na mesma máquina (Git Bash +
PowerShell 5.1) — os dois shells foram exercitados de fato (bash para rodar
o smoke acima, PowerShell para a inspeção de processo e a checagem de
`PowerLineStatus`), não só revisão estática.

### Prova da janela de preparação de autenticação (achado 1 da rodada anterior) — instrumentação temporária + fault injection

Cópia throwaway de `carga.js` logando `auth_start`/`auth_end` (relativos a
`exec.scenario.startTime`) e o instante real de ativação de cada VU,
contra o stack k6 isolado real:

1. **Linha de base (`VUS=1`/`2`/`10`, sem latência artificial):** todas as
   autenticações terminaram em `~70-98ms` — muitíssimo abaixo de
   `load_start=22000ms` (`AUTH_PREPARE_MS` com os defaults). A última VU
   ativou-se sempre exatamente em `load_start+WARMUP_MS` (`steady_start`),
   confirmando `activation_real == activation_expected` em todos os casos
   (`VUS=10`: primeira VU ativa em `22300ms`, última em `25000ms == steady_start`).
2. **Login artificialmente lento, abaixo do timeout** (`VUS=3`,
   `FI_LOGIN_LATENCY_S=5`, `HTTP_REQUEST_TIMEOUT=10s` default): as 3
   autenticações terminaram em `~5091-5093ms` — ainda **muito** abaixo de
   `load_start=22000ms`. As 3 VUs continuaram ativando exatamente nos
   instantes esperados (`23000ms`/`24000ms`/`25000ms`, a última batendo
   com `steady_start=25000ms`), `checks: 100%`, todos os thresholds
   passaram.
3. **Falha terminal de autenticação** (`VUS=3`, senha deliberadamente
   errada só em `authenticateVuOnce()` — `setup()`/`teardown()` continuam
   com a senha real): as 3 VUs falharam com `401` em `~98ms`, sem retry
   (um único log de falha por VU). `checks: 0/0` — **nenhuma operação
   principal foi despachada** por nenhuma das 3 VUs. `vu_auth_success`
   reprovou o run (`rate==1` cruzado), mas `teardown()` ainda autenticou
   com sucesso e removeu os 3 livros sintéticos — confirmado zero resíduo
   `LOADTEST-` no Postgres do stack isolado depois.

Em todos os casos, `max(auth_end)` ficou ordens de grandeza abaixo de
`load_start`, e nenhuma operação principal foi observada antes de
`load_start` — a garantia matemática do achado (autenticação limitada a
`2×HTTP_REQUEST_TIMEOUT_MS`, `load_start` com margem sobre esse teto) se
confirmou empiricamente nos três cenários.

### Prova do contrato de status exato (achado 2 da rodada anterior) — fault injection não versionada

Servidor HTTP local mínimo (`status204_server.py`, poucas linhas) que
responde `200` a `96%` dos `PATCH` recebidos e `204` aos outros `4%`
(a cada 25ª requisição), e um script k6 throwaway replicando
`checkExactStatus()`/`operation_status_success` de `carga.js` contra esse
servidor (100 iterações, tag `operation:save_scene,phase:steady`):

```
http_req_failed                                    ✓ 'rate<0.01' rate=0.00%
  {operation:save_scene}                           ✓ 'rate<0.01' rate=0.00%
operation_status_success{operation:save_scene,phase:steady}
                                                     ✗ 'rate>0.99' rate=96.00%
```

`http_req_failed` global e `http_req_failed{operation:save_scene}`
ficaram em `0.00%` — **ambos passando** — porque o k6 trata `204` como
sucesso HTTP. `operation_status_success{operation:save_scene,phase:steady}`
caiu para `96.00%` e **reprovou** o threshold (`k6 run` saiu com código
`99`) — exatamente a lacuna que o achado descreveu: uma regressão de
contrato que os gates antigos não veriam, capturada pela Rate de status
exato. Uma execução saudável (sem o servidor de 204) confirma `100%` de
`operation_status_success` nas 5 operações — ver "Resultados desta rodada"
abaixo.

### Prova de drenagem e timeout (achado 1 da rodada anterior) — fault injection não versionada

Duas provas separadas, não commitadas:

1. **Timeout observado, não pendurado até `maxDuration`:** um script k6
   mínimo (`http.get` com `timeout: '2s'`) contra um servidor TCP local que
   aceita a conexão mas nunca responde (`blackhole.py`, `socketserver`
   Python de poucas linhas). Resultado: a requisição terminou em `~2.04s`
   com `status=0`, `error="request timeout"`, `error_code=1050` — não ficou
   pendurada até o `maxDuration` de `30s` configurado no script de teste.
2. **Drenagem do último turno:** cópia throwaway de `carga.js` com
   `FI_LATENCY_S` (latência artificial pós-resposta, `< HTTP_REQUEST_TIMEOUT`)
   injetada nas 5 operações do turno, rodada contra o stack k6 isolado real
   (`VUS=1`, `WARMUP_DURATION=STEADY_DURATION=RAMPDOWN_DURATION=1s`,
   `HTTP_REQUEST_TIMEOUT=3s`, `FI_LATENCY_S=2.5`). O único turno da VU
   iniciou em `1164ms`, o laço saiu em `13554ms` (bem depois do
   `deactivateAt` nominal de `3000ms`, mas o turno já em andamento foi
   deixado terminar), e o `refresh_outline_after_save` pendente foi drenado
   em `15386ms` — dentro do orçamento de `25200ms`
   (`maxDurationBudgetMs = WARMUP_MS+STEADY_MS+RAMPDOWN_MS+MAIN_LOOP_GRACE_MS`).
   `checks: 100% (5/5)`, `teardown` completo (`1 livro removido`), todos os
   thresholds passaram, zero resíduo `LOADTEST-` confirmado por `SELECT
   count(*) FROM books WHERE title LIKE 'LOADTEST-%'` no Postgres do stack
   isolado.

### Prova da cobertura de rampa (achado 3 da rodada anterior) — instrumentação temporária

Script k6 mínimo (sem HTTP — só a lógica de escalonamento de
`activationOffsetMs()`/`deactivationOffsetMs()` copiada pós-correção),
`WARMUP_MS=4000`/`STEADY_MS=1000`/`RAMPDOWN_MS=6000` (total `11000ms`),
`VUS=1`/`2`/`10`, logando o tempo real decorrido (`Date.now() -
exec.scenario.startTime`) na ativação e na desativação de cada VU:

| VUS | Última VU — desativação esperada | Última VU — desativação real |
|---|---|---|
| 1 | 11000ms | 11028ms |
| 2 | 11000ms | 11027ms |
| 10 | 11000ms | 11035ms |

Nos três casos a última VU desativa a `~30-40ms` do fim nominal do cenário
(`11000ms`) — jitter de agendamento do k6, não cauda ociosa estrutural. Com
a fórmula anterior (`(vuIndex-1)/VUS`), a última VU de `VUS=2` desativaria
em `8000ms` (`3000ms` de cauda ociosa) e a de `VUS=10` em `6600ms`
(`4400ms` de cauda ociosa) — a tabela completa de ativação/desativação por
VU está na PR #141.

### Prova do fire-and-forget (achado anterior — "release the iteration") — scripts throwaway não versionados

Antes de escolher a correção, dois scripts mínimos (não commitados) contra um
servidor HTTP local de teste com latência configurável confirmaram o
comportamento real do k6 v2.1.0:

1. **Promise solta numa iteração k6 normal** (`options.iterations`, sem laço
   manual): um `http.asyncRequest()` de 2s disparado sem ser aguardado, com
   `.then()` de log. Resultado: `default()` "retorna" aos 300ms (log
   confirma), mas a iteração só é contabilizada como concluída aos ~2000ms —
   `iteration_duration` medido em 2s, não 300ms. A Promise nunca aguardada
   ainda bloqueou a iteração seguinte.
2. **Laço manual dentro de uma única iteração k6** (`per-vu-iterations`,
   `iterations:1`, 5 "turnos" internos, cada um disparando um
   `http.asyncRequest()` de 2s sem aguardar): turnos avançam a cada ~300ms
   (o think time interno), mesmo com a Promise do turno anterior ainda
   pendente — e as 5 Promises resolvem e são contabilizadas nas métricas ao
   final (`http_reqs=5`, todas `status 200`), quando a iteração única
   finalmente termina e o k6 drena o que sobrou.

Reproduzido depois com fault injection real contra o backend local (não
versionada: ~2.5s de latência artificial client-side antes do
`http.asyncRequest()` do refresh, `VUS=1`, think time 0.3-0.5s): em 7
turnos, a diferença entre o dispatch do refresh e o início do turno seguinte
foi sempre igual ao think time sorteado (393ms, 416ms, 483ms, 374ms, 376ms,
407ms, 443ms) — **nunca** próxima dos ~2.5s de latência artificial do
refresh. Todos os 7 refreshes resolveram e foram checados (checks 100%,
35/35), teardown removeu o livro normalmente.

### Prova da fase por dispatch (finding 2) — fault injection não versionada

`VUS=2`, `WARMUP_DURATION=STEADY_DURATION=RAMPDOWN_DURATION=3s` (para forçar
turnos atravessando bordas), com `currentPhase()` logado em cada dispatch.
Capturado um turno cujo `list_books`/`load_outline`/`load_scene` dispararam
em `elapsedMs=3738-3789` (`phase:steady`), mas cujo próprio
`save_scene`/`refresh_outline_after_save` dispararam em
`elapsedMs=6327-6428` (`phase:ramp_down`, já cruzando a borda de 6000ms
durante o debounce de 1200ms) — exatamente o cenário de contaminação que o
Codex descreveu, com a fase correta por requisição em vez de uma fase
"congelada" para o turno inteiro. `teardown()` removeu os 2 livros; checks
100% (40/40), `http_req_failed` 0%.

### Prova da autenticação terminal (finding 3) — fault injection não versionada

`VUS=3`, senha deliberadamente errada só para `authenticateVuOnce()` (uma
cópia temporária que envia `LOGIN_PASSWORD + '-FI3-WRONG'`, enquanto
`setup()`/`teardown()` continuam com a senha real). Um contador global de
tentativas de autenticação confirmou exatamente 3 tentativas — nunca mais que
`VUS` — todas falhando com `401`, `vu_auth_success` registrando `false` uma
única vez por VU (nunca repetidamente), o run reprovando pelos thresholds
(`vu_auth_success rate==1`, `http_req_failed rate<0.01`) **e** `teardown()`
ainda autenticando com sucesso e removendo os 3 livros sintéticos — provando
que o orçamento de rate-limit de login não é mais consumido por retries.

### Execução completa (todas as fases, todas as requisições)

Smoke, auth, setup, as 5 operações principais (`ramp_up`+`steady`+`ramp_down`)
e teardown — `http_req_duration` **sem** tag `phase`:

| | VUs | Duração | Requests | RPS global | p50 (ms) | p90 (ms) | p95 (ms) | p99 (ms) | Erros | Checks | `vu_auth_success` | `turnos_execucao_total` |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Baseline | 10 | maxDuration 4m19.2s (22s auth-prepare + 30s/2m/30s + folga) | 4080 | 20.0 | 5.1 | 20.8 | 26.2 | 40.1 | 0% (0/4080) | 100% (4005/4005) | 100% (10/10) | 801 |
| Carga ampliada | 30 | maxDuration 4m19.2s (22s auth-prepare + 30s/2m/30s + folga) | 12020 | 58.9 | 6.3 | 35.5 | 47.7 | 76.8 | 0% (0/12020) | 100% (11805/11805) | 100% (30/30) | 2361 |

`vu_auth_success` confirma exatamente uma autenticação bem-sucedida por VU
nas duas execuções. Estes percentis incluem auth/setup/teardown **e**
warmup/rampdown — não são a fase estável isolada; para isso, a seção
seguinte. `turnos_execucao_total` é a contagem exata de `root_group.checks
['save_scene status 200'].passes` no summary bruto — todas as fases, ver
`resultado.json`. Nenhuma VU foi interrompida por `maxDuration` nas duas
execuções (todas terminaram bem antes do teto de `4m19.2s`, que agora inclui
os `22s` da janela de preparação de autenticação — ver "Prova da janela de
preparação de autenticação" acima).

### Fase estável (`phase:steady`) — só as operações principais, só os 2 minutos com VUS constante no pico

| Operação | 10 VUs count | 10 VUs p50 | 10 VUs p90 | 10 VUs p95 | 10 VUs p99 | 10 VUs max | 30 VUs count | 30 VUs p50 | 30 VUs p90 | 30 VUs p95 | 30 VUs p99 | 30 VUs max |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `list_books` | 635 | 12.4 | 19.9 | 24.4 | 32.3 | 40.1 | 1880 | 31.7 | 65.1 | 75.8 | 93.8 | 146.0 |
| `load_outline` | 635 | 3.8 | 5.9 | 6.7 | 9.4 | 11.5 | 1881 | 4.0 | 7.0 | 8.2 | 11.7 | 62.6 |
| `load_scene` | 634 | 3.2 | 5.2 | 5.8 | 8.9 | 14.9 | 1881 | 3.6 | 6.3 | 7.3 | 9.9 | 26.1 |
| `save_scene` | 636 | 18.7 | 32.9 | 37.5 | 48.3 | 67.9 | 1881 | 19.3 | 35.4 | 41.4 | 56.4 | 82.5 |
| `refresh_outline_after_save` | 636 | 3.9 | 6.1 | 7.2 | 10.5 | 14.9 | 1881 | 4.7 | 8.8 | 10.4 | 13.9 | 34.1 |

**Todos os 10 thresholds de latência `p95<500ms` (5 operações × 2 execuções)
passaram**, com folga larga. **Os 10 thresholds de contrato de status exato
`operation_status_success{operation:X,phase:steady}: rate>0.99` (achado
"Gate exact operation statuses instead of HTTP failures", 5 operações × 2
execuções) também passaram — 100% de status exato em todas as operações,
nas duas cargas.** `checks` (100%) e `http_req_failed` global (0%) passaram
nas duas execuções — nenhuma requisição falhou.

Os `count` por operação diferem levemente entre si (ex.: `634` vs `636` em
10 VUs) — `currentPhase()` é recalculada a cada dispatch, então um turno
iniciado perto de uma borda de fase pode ter `list_books`/`load_outline`/
`load_scene` tagueadas numa fase enquanto `save_scene`, despachada depois do
debounce de `1200`ms, já cai na fase seguinte (ou vice-versa perto do fim de
`steady`) — comportamento esperado de tag por instante de dispatch, não
inconsistência.

**`turnos_steady`** (número de `save_scene` despachados com
`phase:steady` — lido diretamente do `count` da `Trend`
`http_req_duration{operation:save_scene,phase:steady}`): **636 em 10 VUs,
1881 em 30 VUs** — bem abaixo de `turnos_execucao_total` (801/2361, todas as
fases), como esperado: os `turnos_execucao_total` incluem os turnos de
`ramp_up` e `ramp_down`, que rodam com menos VUs que o pico.

Como o refresh é fire-and-forget genuíno, a propriedade que importa não é
"a próxima requisição espera o refresh" — é "todo save bem-sucedido dispara
exatamente um refresh, e todos os refreshes são observados/concluídos até o
fim do run". Em cada execução, a contagem whole-run do check
`refresh_outline_after_save status 200` bate exatamente com `save_scene
status 200` (801/801 em 10 VUs, 2361/2361 em 30 VUs, zero falhas) —
confirmando que nenhum refresh foi descartado silenciosamente pelo
fire-and-forget.

`iterations` (métrica nativa do k6) conta VUs (sempre `== VUS`, uma única
iteração k6 por VU) — não turnos. A contagem de turnos vive em
`resultado.json` (`turnos_execucao_total` em `execucao_completa`,
`turnos_steady` em `operacoes_principais`).

### Auth/setup/teardown (fora do loop medido)

`auth_csrf`/`auth_login` rodam `VUS+2` vezes (setup + 1/VU + teardown, desde
esta rodada todas as VUs em rajada simultânea no início da janela de
preparação — ver "Prova da janela de preparação de autenticação" acima);
`setup_create_book/section/chapter/scene` e `teardown_delete_book` rodam
`VUS` vezes, um livro por VU:

| Operação | 10 VUs avg | 10 VUs p95 | 30 VUs avg | 30 VUs p95 |
|---|---|---|---|---|
| `auth_csrf` | 5.6ms | 8.5ms | 30.6ms | 63.7ms |
| `auth_login` | 77.7ms | 82.4ms | 138.8ms | 181.9ms |
| `setup_create_book` | 11.2ms | 20.2ms | 10.4ms | 24.3ms |
| `setup_create_section` | 5.2ms | 7.2ms | 5.4ms | 7.9ms |
| `setup_create_chapter` | 6.4ms | 8.6ms | 5.5ms | 7.8ms |
| `setup_create_scene` | 9.0ms | 10.3ms | 9.8ms | 12.5ms |
| `teardown_delete_book` | 25.0ms | 41.6ms | 13.7ms | 26.5ms |

`auth_csrf`/`auth_login` ficam sensivelmente mais lentos em `30 VUs` que na
rodada anterior (p95 `17.7ms`→`63.7ms` / `87.5ms`→`181.9ms`) — efeito
esperado da rajada de autenticação simultânea introduzida pelo achado
"Reach peak load before starting the steady phase" (antes, os logins de VU
se escalonavam ao longo do `WARMUP_DURATION`; agora todos disparam quase ao
mesmo tempo). Ainda muito abaixo dos thresholds auxiliares (`p95<2000ms`/
`p95<8000ms`).

### Debounce do AUTO_SAVE — teste dedicado (herdado de `dece9f3`)

Não re-testado nesta rodada — nenhuma das três correções toca
`AUTO_SAVE_DELAY_MS` nem o ponto em que o debounce é aguardado
(`await delay(AUTO_SAVE_DELAY_MS / 1000)` continua imediatamente antes do
`PATCH`, agora dentro de `runTurn()`). Ver a revisão `dece9f3` deste README
para o teste dedicado original (7/7 turnos com diferença `pre_patch -
pre_delay` sempre ≥ 1200ms).

### Fault injection — achados de rodadas anteriores (`1f2593e`/`8023b2d`)

Não re-testados nesta rodada porque nenhuma das três correções toca
`recoverOrphanedBookIds()` nem os thresholds de erro por operação — ver
`loadtest/README.md` na revisão `8023b2d` (histórico) para os logs originais
desses achados.

### Comparação com a rodada anterior (`fbd98db`)

**Não leia isto como melhoria/regressão de throughput cru.** O relógio da
carga mudou nesta rodada (achado "Reach peak load before starting the
steady phase" — a curva medida só começa depois de uma janela de `22s` de
preparação de autenticação), então a duração total do processo aumenta sem
que a duração da carga medida em si tenha mudado. `rps_global` cai porque
seu denominador de tempo agora inclui os `22s` de preparação. A comparação
abaixo é só factual:

| | 10 VUs: `fbd98db` → `56b9303` | 30 VUs: `fbd98db` → `56b9303` |
|---|---|---|
| Requests totais | 4020 → 4080 (+1.5%) | 11945 → 12020 (+0.6%) |
| RPS global | 22.2 → 20.0 (-9.8%, ver nota acima) | 64.1 → 58.9 (-8.1%, idem) |
| `turnos_execucao_total` | 789 → 801 (+1.5%) | 2346 → 2361 (+0.6%) |
| `turnos_steady` | 628 → 636 (+1.3%) | 1873 → 1881 (+0.4%) |
| `save_scene` p95 (steady) | 56.2ms → 37.5ms | 49.7ms → 41.4ms |
| `auth_login` p95 | 82.6ms → 82.4ms (10 VUs) | 87.5ms → 181.9ms (30 VUs, rajada simultânea) |
| Thresholds de operação principal | 10/10 (latência + status exato) | 10/10 (latência + status exato) |

Diferenças de throughput dentro da variação normal de execução a execução.
`auth_login` p95 em `30 VUs` sobe visivelmente — efeito esperado da rajada
de autenticação simultânea (ver "Auth/setup/teardown" acima), ainda muito
abaixo do threshold auxiliar de `8000ms`. A rodada anterior já reportava
`turnos_execucao_total`/`turnos_steady` separadamente (achado da rodada
anterior a essa), então os dois números permanecem diretamente comparáveis
aqui.

### Comparação com a rodada anterior a essa (`3bb79ce`)

**Não leia isto como melhoria/regressão de throughput cru.** A curva de
ativação/desativação por VU mudou naquela rodada (achado "Cover the full
ramp interval with VU offsets" — a última VU passou a ficar ativa até o fim
exato de cada rampa, em vez de sair `RAMPDOWN_MS/VUS` mais cedo), então a
carga temporal oferecida não era mais idêntica à rodada anterior a ela. A
comparação abaixo é só factual:

| | 10 VUs: `3bb79ce` → `fbd98db` | 30 VUs: `3bb79ce` → `fbd98db` |
|---|---|---|
| Requests totais | 4020 → 4020 (igual) | 11930 → 11945 (+0.1%) |
| RPS global | 22.4 → 22.2 (-0.8%) | 65.7 → 64.1 (-2.5%) |
| `turnos_execucao_total` | 789 → 789 (igual) | 2343 → 2346 (+0.1%) |
| `save_scene` p95 (steady) | 41.5ms → 56.2ms | 46.1ms → 49.7ms |
| Thresholds de operação principal | 10/10 (latência + erro) | 10/10 (latência + erro) |

Diferenças dentro da variação normal de execução a execução. A rodada
anterior não reportava `turnos_steady` (o `turnos_totais` que ela expunha
dentro de `operacoes_principais` já era o valor whole-run, o próprio erro
conceitual corrigido pelo achado "Report steady-state turn counts from
steady samples") — não há um número diretamente comparável para
`turnos_steady=628`/`1873` desta rodada.

**Gargalo principal:** nenhum nas 5 operações principais — passam o teto de
500ms com folga. `list_books` continua sendo a operação relativamente mais
lenta nas duas cargas (cresce com o tamanho da coleção de livros do tenant,
que aumenta com `VUS` por construção do cenário), mas está longe do teto
mesmo em 30 VUs. `auth_login`/`auth_csrf` (fora do loop medido) passaram a
ser o fator que mais cresce com `VUS` nesta rodada, por causa da rajada de
autenticação simultânea — ainda muito abaixo dos thresholds auxiliares.

**Limitações desta execução:**
- Rodada na mesma stack Docker isolada só para este teste (`container_name`/portas
  remapeados via `docker-compose.k6.local.yml`, não versionado —
  `iwrite-k6-db:5443`, `iwrite-k6-backend:8093`, `iwrite-k6-frontend:3009`).
  `crm-marketing-backend-1` ficou parado durante toda a execução; a stack de
  outro worktree do IWrite (`iwrite-backend-1`/`iwrite-db-1`/
  `iwrite-frontend-1`, portas padrão) foi parada por completo (não só
  pausada) antes destas três execuções — não é hardware dedicado.
- `auth_csrf`/`auth_login` passaram a escalar com `VUS` de forma mais
  visível que antes (rajada simultânea de `VUS` logins no início da janela
  de preparação, em vez de escalonados ao longo do warmup) — não
  investigado se isso se torna um gargalo real em `VUS` muito maior que 30.
- O debounce do `AUTO_SAVE` (`AUTO_SAVE_DELAY_MS=1200`) reduz o throughput de
  escrita por VU por construção — este cenário modela a cadência de um autor
  digitando e pausando, não o teto de throughput que o backend consegue
  sustentar. Para medir capacidade máxima, use `AUTO_SAVE_DELAY_MS`
  baixo/zero explicitamente como modo de estresse.
- O efeito prático do fire-and-forget genuíno só se manifesta quando o
  outline está degradado — não reproduzido nestas medições de baseline
  saudável, só via fault injection não versionada (ver acima).
- A rampa de subida/descida por VU (`activationOffsetMs()`/
  `deactivationOffsetMs()`, `vuIndex/VUS`, relativa a `loadStartAt()` desde
  esta rodada) é a discretização exata do alvo contínuo
  `VUs_ativas(t)=VUS×t/WARMUP_MS` só nesses pontos de ativação/desativação —
  não uma cópia do algoritmo interno do k6; a ordem exata de quais VUs
  entram/saem primeiro pode diferir (ver "Prova da cobertura de rampa"
  acima).
- Backend, Postgres e k6 rodam na mesma máquina (sem separação de rede/CPU
  entre gerador de carga e alvo), então parte da latência medida pode ser
  contenção local, não custo real de rede.
- `list_books` cresce com `VUS` por construção do próprio cenário (1 livro
  por VU no tenant do autor de teste) — em execuções com `VUS` bem maior que
  30, esse custo pode se tornar o novo fator dominante. Não investigado
  neste PR (paginação? índice? projeção mais enxuta na listagem?).
- A composição de custo de `save_scene` (`INSERT` em
  `book_word_count_events`, cálculo do rollup diário, escrita da própria
  cena) não foi decomposta — só o efeito agregado foi medido.
- Sem OTel habilitado durante a execução (evita adicionar overhead de
  instrumentação à medição); a decomposição do custo de
  `save_scene`/`list_books`/`load_outline` entre suas etapas internas não
  foi feita neste PR.
- `contentJson` sintético é um único parágrafo curto (7-8 palavras) — não
  representa uma cena longa de verdade.
- `IWRITE_LOADTEST_LOGIN_RATE_LIMIT` (overlay versionado
  `docker-compose.loadtest.yml`) foi mantido no default (`1000`) nesta
  execução — cobre folgadamente `VUS` até `998`; os defaults de produção em
  `application.yml`/`.env.example` não foram alterados — ver
  [§2](#2-pré-requisitos). A rajada de autenticação simultânea desta rodada
  consome esse orçamento de forma mais concentrada no tempo que antes
  (todos os logins de VU dentro de uma janela de dezenas/centenas de ms, não
  mais espalhados pelo warmup).

**Próxima ação recomendada:** repetir 30 VUs (e testar `VUS` maior, ex.
50-100) em hardware totalmente dedicado, sem nenhum outro container Docker
ativo na máquina — inclusive o de outros worktrees. Investigar se a rajada
de autenticação simultânea se torna um gargalo real em `VUS` bem maior que
30 — considerar escalonar levemente os logins dentro da janela de
preparação se isso se confirmar. Rodar o teste com OTel habilitado
(`docker-compose.observability.yml`) e usar os traces correlacionados de
`scene_content_save` para decompor o custo de `save_scene`. Investigar o
crescimento de `list_books`/`load_outline` com o tamanho da coleção de
livros do tenant antes de rodar com `VUS` bem maior que 30. Considerar um
segundo cenário explícito de estresse (`AUTO_SAVE_DELAY_MS` baixo) para
medir o teto de throughput de escrita separadamente do baseline realista.
Considerar versionar (ou converter em teste automatizado leve) a fault
injection do refresh degradado, hoje só documentada na PR #141.

### Follow-up pós-#141 — orçamento de setup (`setupDeadlineAt` tardio, guard só antes do livro, teto de recovery de `5s`)

PR pequena e estritamente delimitada, aberta depois do merge da #141, para
três inconsistências de robustez do lifecycle de `setup()` encontradas na
revisão pós-merge — **não muda o steady-state loop, thresholds de
performance, nem qualquer código de backend/frontend**, por isso a validação
foi dirigida (fault injection contra o guard e a recuperação), sem repetir a
bateria completa de `10`/`30 VUs` da #141.

1. **`setupDeadlineAt` capturado tarde demais.** `setupDeadlineAt` era
   calculado só depois da validação de `LOAD_TEST_PASSWORD`, do `GET /ping` e
   do `login()` inteiro — mas o `setupTimeout` do k6 já conta desde a entrada
   real de `setup()`. Corrigido: `setupStartedAt`/`setupDeadlineAt` agora são
   a primeira coisa calculada em `setup()`, nunca redefinidos depois. `GET
   /ping` passou a usar `HTTP_REQUEST_TIMEOUT` explícito (antes dependia do
   timeout default do k6). Prova (`VUS=1`, `SETUP_TIMEOUT=28s`, `5s` de
   latência injetada via `sleep()` logo após o login): o guard antes do
   primeiro `POST /api/books` mediu `remaining=22868ms` (não os `28000ms`
   cheios) — confirmando que o tempo de login foi de fato descontado do MESMO
   deadline usado depois, e o `setup()` recusou criar o livro antes de
   qualquer `POST /api/books`.
2. **Guard de orçamento só rodava antes do `POST /api/books`.** Nada impedia
   `POST section`/`chapter`/`scene` de consumir o orçamento restante e ainda
   assim disparar a request seguinte sem sobrar tempo para limpar. Corrigido:
   `ensureSetupBudget()` roda imediatamente antes de cada um dos 4 `POST`s de
   provisionamento — só o de `/api/books` reserva a janela de recuperação de
   órfãos inteira (`ORPHAN_RECOVERY_WINDOW_MS`), porque só ele pode gerar um
   órfão de ID desconhecido. Prova (`VUS=1`, `SETUP_TIMEOUT=30s`, `15s` de
   latência injetada após a criação do capítulo): os guards de
   livro/seção/capítulo passaram normalmente, mas o guard antes de `POST
   scene` mediu `remaining=14882ms < required=22000ms` e recusou — `POST
   scene` nunca foi enviado, e a limpeza automática removeu o único livro
   órfão (`Limpeza automática removeu todos os 1 livro(s) órfão(s).`), zero
   resíduo `LOADTEST-` confirmado depois. Repetido com a falha movida para
   antes do `POST chapter` (borda anterior) com o mesmo resultado.
3. **Teto individual do `GET` de recuperação não era realmente `2s`.**
   `ORPHAN_RECOVERY_REQUEST_TIMEOUT_MS = min(HTTP_REQUEST_TIMEOUT_MS,
   ORPHAN_RECOVERY_WINDOW_MS)` dava `min(10000, 5000) = 5000ms` com os
   defaults — uma única consulta lenta podia consumir a janela de `5s`
   inteira sem sobrar chance para um poll seguinte capturar um commit tardio.
   Corrigido: novo teto `ORPHAN_RECOVERY_SINGLE_REQUEST_MAX_MS = 2000`,
   incluído no `Math.min()` junto dos outros dois. Prova (servidor local que
   dorme `4s` antes de responder, apontado no lugar de `GET /api/books`
   dentro de `recoverOrphanedBookIds()`): cada tentativa terminou em
   `~2000-2008ms` (`"request timeout"`, nunca os `4s` do servidor nem os
   `5s` da janela), e **duas tentativas** couberram dentro da janela de `5s`
   — confirmando que um único `GET` não monopoliza mais a recuperação.

A prova da corrida de commit tardio e do isolamento por `runId` (mesmo
proxy de pré-delay da #141: `VUS=1`, `HTTP_REQUEST_TIMEOUT=2s`,
`SETUP_TIMEOUT=15s`, `POST /api/books` só encaminhado ao backend real `2.3s`
depois de chegar no proxy, cliente k6 já desistindo em `2s`) foi revalidada
sobre o código desta rodada: `setup() falhou ... após criar 1 livro(s)
sintético(s)` com `createdBookIds` vazio no momento da falha — a
recuperação encontrou e removeu o livro que só commitou tardiamente. Um
livro decoy de outro `runId` (`LOADTEST-OTHERRUNDECOY-vu1`) permaneceu
intocado, confirmando isolamento entre execuções concorrentes.

Smoke (`VUS=2`) e regressão de `10 VUs` (padrão de estágios) rodados sobre o
código desta rodada, sem fault injection: `checks: 100%`,
`http_req_failed: 0%`, todos os thresholds passaram, teardown removeu todos
os livros, zero `429`, zero resíduo `LOADTEST-` — evidência bruta em
[`resultados/resultado-10vus-setup-budget-followup.json`](resultados/resultado-10vus-setup-budget-followup.json).
**Este arquivo é evidência nova, medida sobre o código deste follow-up —
não substitui nem reinterpreta
[`resultados/resultado-10vus.json`](resultados/resultado-10vus.json), que
continua sendo a evidência histórica da #141 sobre o código daquela PR.**

---

## 10. Validado

- [x] `k6 inspect loadtest/carga.js` sem erros
- [x] Login + CSRF reais (não mockados) contra o backend com o profile `demo`
- [x] Guard de host testado contra os dois exemplos de bypass por user-info
      (`http://localhost:8085@host-externo`, `http://usuario:senha@localhost:8085`)
      — ambos recusados — e contra `http://[::1]:porta`, aceito e conectando
      normalmente
- [x] Nenhuma mensagem de erro do parser imprime a `BASE_URL` bruta: testado
      com uma senha-canário embutida como user-info
      (`http://usuario:SENHA_CANARIO@localhost:porta`) e conferido que o
      valor não aparece em stdout/stderr — só a mensagem genérica
- [x] `carga.js` não importa nenhuma biblioteca remota — `k6 inspect` e
      `k6 run` funcionam sem depender de internet para iniciar
- [x] `list_books`/`load_outline` falhando (fault injection) encerra o
      turno imediatamente: confirmado, com `--out json=` temporário
      (não versionado), que zero requisições `load_scene`/`save_scene`
      acontecem quando um pré-requisito falha
- [x] Tags exportadas testadas com `--out json=` temporário (não versionado):
      zero ocorrências de `"url"` como chave de tag e zero UUIDs/JSESSIONID/
      XSRF-TOKEN em `data.tags` em toda a execução — só `operation`, `name`
      (rota normalizada) e, nas 5 operações principais, `phase`
- [x] Tag `phase` confirmada com exatamente 3 valores possíveis (smoke com
      `--out json=` temporário, VUS=3/5s/8s/5s): 315 `ramp_up` + 1036
      `steady` + 540 `ramp_down` = 1891, batendo com o total de amostras
      tagueadas com `phase` — nenhum quarto valor, nenhuma amostra sem tag
- [x] Refetch fire-and-forget genuíno (`http.asyncRequest()` nunca aguardado,
      nem direto nem via `Promise.all`) validado em 3 camadas (PR #141,
      finding "Release the iteration when think time ends"): (1) dois
      scripts throwaway não versionados provaram que o k6 v2.1.0 sempre drena
      todas as Promises de uma iteração, mesmo as nunca aguardadas, ANTES de
      considerá-la concluída — só um laço manual dentro de uma única
      iteração k6 (a arquitetura adotada) escapa disso; (2) fault injection
      real (~2.5s de latência artificial no refresh, backend local, 7
      turnos): diferença entre dispatch do refresh e início do turno
      seguinte sempre ≈ think time sorteado, nunca ≈ latência do refresh; (3)
      nas 2 execuções medidas (10/30 VUs), `refresh_outline_after_save
      status 200` continua batendo exatamente com `save_scene status 200`
      (801/801 e 2361/2361 — números da rodada atual, `56b9303`; ver
      [§9](#9-resultados-obtidos) para os brutos) — nenhum refresh
      descartado silenciosamente. Ver [§9](#9-resultados-obtidos) para os
      três em detalhe
- [x] `phase` recalculada a cada dispatch de requisição, não mais uma vez por
      turno (PR #141, finding "Tag each operation using its dispatch-time
      phase"): fault injection com estágios curtos (`WARMUP_DURATION=
      STEADY_DURATION=RAMPDOWN_DURATION=3s`, `VUS=2`) capturou um turno cujo
      `list_books`/`load_outline`/`load_scene` (elapsedMs=3738-3789)
      receberam `phase:steady` e cujo próprio `save_scene`/
      `refresh_outline_after_save` (elapsedMs=6327-6428, já cruzando a borda
      de 6000ms durante o debounce) receberam `phase:ramp_down` — a
      contaminação que o finding descreveu, corrigida por construção
- [x] Autenticação de VU sem retry (PR #141, finding "Stop retrying
      authentication after a recorded failure"): fault injection com senha
      deliberadamente errada só para VUs (`VUS=3`, `setup()`/`teardown()`
      com a senha real) confirmou, via contador global de tentativas, que
      cada VU tenta autenticar exatamente 1 vez (nunca mais que `VUS` no
      total) — sem tempestade de retries — e que `teardown()` ainda
      autentica com sucesso e remove os 3 livros mesmo com as 3 VUs falhando
- [x] Falha de `setup()` após a criação de um livro (seção/capítulo/cena de
      uma VU) testada com fault injection: limpeza automática remove **todos**
      os livros já criados até aquele ponto (não só o da VU corrente),
      confirmada por VU, erro original preservado, `k6 run` sai com código
      diferente de zero
- [x] Falha de `teardown()` testada com fault injection (um dos livros já
      ausente): `k6 run` sai com código diferente de zero, listando qual
      `bookId` não foi removido, em vez de só logar
- [x] Retentativa da recuperação de órfão com visibilidade atrasada testada
      com fault injection (script temporário instrumentado, não versionado):
      `setup()` forçado a lançar imediatamente com `createdBookIds=[]`; um
      processo externo cria o livro-fantasma `LOADTEST-<runId>-vu1` só
      **depois** da 1ª varredura. Log prova a race exata: scan #1
      `recovered.size=0`, scan #2 (510ms depois) `recovered.size=1`. Livro
      removido (`Limpeza automática removeu todos os 1 livro(s) órfão(s)`),
      erro original relançado, `k6 run` saiu com código `107`. Um segundo
      teste sem nenhum livro real confirmou 11 varreduras em ~5.1s (janela
      de 5000ms + intervalo de 500ms) e término normal — nunca loop infinito
- [x] A recuperação de órfãos não remove livros de outra execução: um livro
      `LOADTEST-decoyrun999-vu1` criado manualmente antes do teste acima
      permaneceu intacto no banco depois da limpeza automática — só
      removido manualmente ao final do teste
- [x] **(histórico, substituído)** Threshold de erro por operação
      (`http_req_failed{operation:X,phase:steady}`) testado com fault
      injection concentrada em `save_scene` (1 a cada 25 iterações força um
      `sceneId` inválido, VUS=10, 60s de fase estável): `http_req_failed`
      global ficou em 0.68% (passou no threshold global `<1%`) enquanto
      `http_req_failed{operation:save_scene,phase:steady}` ficou em 3.61%
      (rompeu o threshold `<1%`) — `k6 run` saiu com código `99` só por
      causa do threshold por operação. `refresh_outline_after_save`
      continuou disparando só nos saves bem-sucedidos (872/872, zero
      falhas). Zero resíduo `LOADTEST-` após o teardown. Esses 5 thresholds
      `http_req_failed{operation:X,phase:steady}` foram removidos e
      substituídos por `operation_status_success{operation:X,phase:steady}`
      (achado "Gate exact operation statuses instead of HTTP failures",
      PR #141) — ver checklist mais abaixo para a fault injection
      equivalente contra a métrica atual
- [x] `teardown()` remove todos os livros sintéticos nas execuções normais —
      confirmado sem resíduo `LOADTEST-` após todas as execuções (smoke, 10
      VUs, 30 VUs, e os testes de fault injection acima)
- [x] `loadtest/resultado.json` e `loadtest/resultados/*.json` sem cookies,
      credenciais ou conteúdo de cena — `setup()` nunca devolve sessão/CSRF,
      então não há nada para `handleSummary()` sanitizar além do allowlist de
      defesa em profundidade em `setup_data` (só `bookId`/`sceneId` por VU,
      não são tags de métrica e não criam cardinalidade)
- [x] Os três caminhos de summary do k6 testados explicitamente —
      `RESULT_PATH`, `--summary-export=<arquivo>` e
      `K6_SUMMARY_EXPORT=<arquivo>` — com zero ocorrências de `JSESSIONID`,
      `XSRF-TOKEN`, `authHeaders` ou `Cookie` nos três arquivos gerados
- [x] Um livro/cena por VU confirmado via `--out json=` temporário (não
      versionado): `VUS` `bookId`s distintos criados em `setup()`, cada VU
      lendo/escrevendo exclusivamente o seu (`data[__VU - 1]`), sem
      colisão/reuso de `bookId` entre VUs
- [x] `SETUP_TIMEOUT`/`TEARDOWN_TIMEOUT` validados: valores malformados
      (`10x`, `1..5s`, `abc`) rejeitados antes de rodar, com mensagem clara;
      valores válidos, incluindo fracionários (`10m`, `90s`, `0.5m`, `1.5s`)
      aceitos e refletidos em `k6 inspect`
- [x] `THINK_TIME_MIN_S`/`THINK_TIME_MAX_S` validados com `k6 inspect`
      (falha antes de qualquer HTTP, no carregamento do módulo): `abc`,
      `NaN`, `Infinity` rejeitados como "não é um número finito";
      `-1` rejeitado como negativo; `THINK_TIME_MIN_S > THINK_TIME_MAX_S`
      (ex. `2` / `1`) rejeitado explicitamente citando os dois valores;
      `0`/`0`, `1.5`/`1.5` (min==max) e `0.15`/`0.75` (decimais) aceitos sem
      erro
- [x] `measured_script_git_blob`/`measured_script_sha256` conferidos sobre os
      bytes do **blob do Git**, não do working tree: `git rev-parse
      56b9303e53a5087dbc744a03eea7fe48f11a5efa:loadtest/carga.js` bate com
      `measured_script_git_blob`, e `git cat-file blob
      91fc59f76227f95139054e15ce00125436172550` (via Python/`hashlib`) bate
      com `measured_script_sha256` registrado em `resultado.json` e neste
      README — mesma metodologia validada em `dece9f3` (ver nota em
      [§9](#9-resultados-obtidos))
- [x] Janela de preparação de autenticação (PR #141, finding "Reach peak
      load before starting the steady phase"): instrumentação temporária
      confirmou `max(auth_end)` sempre `<<< load_start` (`VUS=1/2/10`,
      inclusive com `5s` de latência artificial de login) e a última VU
      sempre ativando exatamente em `load_start+WARMUP_MS`; fault injection
      com senha errada (`VUS=3`) confirmou `checks=0/0` (nenhuma operação
      principal despachada), `vu_auth_success` reprovando o run sem retry
      storm, e `teardown()` ainda limpando os 3 livros — ver "Prova da
      janela de preparação de autenticação" em [§9](#9-resultados-obtidos)
- [x] Contrato de status exato por operação (PR #141, finding "Gate exact
      operation statuses instead of HTTP failures"): fault injection contra
      um servidor local regredindo `4%` dos `PATCH` de `200` para `204`
      confirmou `http_req_failed` global e por operação em `0.00%` (ambos
      passando) enquanto `operation_status_success{operation:save_scene,
      phase:steady}` caiu para `96.00%` e reprovou o threshold — ver "Prova
      do contrato de status exato" em [§9](#9-resultados-obtidos)
- [x] Timeout explícito por requisição (PR #141, finding "Let the final turn
      drain before maxDuration"): `http.get` com `timeout: '2s'` contra um
      servidor TCP que aceita a conexão mas nunca responde terminou em
      `~2.04s` com `error="request timeout"` (`error_code=1050`) — não ficou
      pendurado até `maxDuration`; turno com latência artificial próxima do
      timeout (`< HTTP_REQUEST_TIMEOUT`) completou e drenou o refresh
      pendente dentro do orçamento calculado, sem ser morto por
      `maxDuration` — ver "Prova de drenagem e timeout" em
      [§9](#9-resultados-obtidos)
- [x] Reprodutibilidade ancorada no blob, não no commit intermediário (PR
      #141, finding "Point reproducibility commands at a reachable commit"):
      README não instrui mais `git checkout <measured_code_commit>` como
      único caminho — `measured_script_git_blob` é a âncora primária,
      verificável via `git rev-parse <qualquer-commit>:loadtest/carga.js`
- [x] Cobertura completa do intervalo de rampa (PR #141, finding "Cover the
      full ramp interval with VU offsets"): instrumentação temporária
      confirmou que a última VU desativa a `~30-40ms` (jitter de
      agendamento) do fim nominal do cenário para `VUS=1/2/10` — ver "Prova
      da cobertura de rampa" em [§9](#9-resultados-obtidos)
- [x] Contagem de turnos separada por fase (PR #141, finding "Report
      steady-state turn counts from steady samples"): `resultado.json`
      reporta `turnos_execucao_total` (whole-run, `execucao_completa`) e
      `turnos_steady` (só `phase:steady`, `operacoes_principais`) como
      campos distintos, o segundo lido do `count` da `Trend`
      `http_req_duration{operation:save_scene,phase:steady}`
- [x] Debounce do `AUTO_SAVE` (`AUTO_SAVE_DELAY_MS=1200`, default) testado com
      instrumentação temporária (`console.log` de timestamp ao redor do
      `await delay()`, não versionada): diferença `pre_patch - pre_delay`
      sempre ≥ 1200ms em 7/7 iterações de um smoke `VUS=1` com think time
      curto (0.1-0.2s) — nenhum PATCH disparado ignorando o debounce mesmo
      quando o think time sozinho permitiria saves mais rápidos
- [x] `AUTO_SAVE_DELAY_MS` validado com `k6 inspect` (mesma regra de
      `THINK_TIME_MIN_S`/`MAX_S`): valor não finito ou negativo rejeitado
      antes de qualquer HTTP
- [x] Allowlist `SAFE_HOSTS` testada explicitamente: `localhost`,
      `127.0.0.1`, `[::1]` e `host.docker.internal` passam sem override;
      `backend` falha sem `ALLOW_UNSAFE_TARGET` e passa com o valor exato do
      override; um host externo (`evil.example.com`) continua falhando;
      user-info continua rejeitado; nenhuma mensagem de erro imprime a
      `BASE_URL` bruta em nenhum dos casos acima (testado com `k6 inspect`
      para cada combinação)
- [x] Exemplo de limpeza manual por IDs no README corrigido para UUIDs
      entre aspas com `::uuid` explícito — `books.id` é `uuid`
      (`V1__create_books.sql`), então o literal inteiro anterior (`11, 12,
      13`) teria falhado por incompatibilidade de tipo se alguém tivesse
      copiado e colado
- [x] `vu_auth_success` (threshold `rate==1`) confirmado: execução normal
      reprova o `k6 run` diante de qualquer falha de login de VU — e, desde
      esta rodada, sem retry (ver item de autenticação sem retry acima); em
      execução saudável, cada VU registra exatamente uma autenticação
      bem-sucedida
- [x] `refresh_outline_after_save` confirmado só disparando após
      `save_scene status 200` — zero requisições dessa operação quando o
      `PATCH` falha (fault injection)
- [x] Alternância de `wordCountDelta` confirmada diretamente em
      `book_word_count_events.manuscript_word_delta` na rodada `2838fe0`
      (execução de controle isolada, VUS=1): sequência real
      `8, -1, +1, -1, +1, -1, ...` — nunca `0` a partir do segundo save da
      mesma VU. Não re-verificada em `dbd5904`: a lógica de conteúdo/contagem
      de palavras não foi tocada por este diff (só `phase` e o refetch
      assíncrono mudaram)
- [x] Thresholds `phase:steady` das 5 operações principais confirmados
      passando nas duas execuções (10 e 30 VUs) — `k6 inspect` mostra as 5
      chaves `http_req_duration{operation:X,phase:steady}` nos `thresholds`
      (ver [§7](#7-thresholds)); o resumo curto no terminal ("Todos os
      thresholds passaram") e os JSONs brutos em `resultados/*.json`
      confirmam
- [x] Resultados gerados com working tree limpo, exatamente no commit
      registrado como `measured_code_commit` em `resultado.json`
- [x] `runId` impresso no log do k6 (`runId desta execução: <id>`)
      imediatamente após ser gerado, antes do primeiro `POST /api/books` —
      confirmado nas 3 execuções medidas e nos 3 testes de fault injection;
      mensagens de falha de `setup()` (criação de livro/seção/capítulo/cena)
      incluem o `runId`
- [x] Energia AC confirmada
      (`[System.Windows.Forms.SystemInformation]::PowerStatus.PowerLineStatus
      = Online`) antes das três execuções e novamente depois da execução de
      30 VUs desta rodada — sem incidente de throttling de bateria (achado
      de uma rodada anterior, `8023b2d`, corrigido operacionalmente desde
      então)
- [x] Zero resíduo `LOADTEST-` confirmado após as 3 execuções medidas
      (smoke, 10 VUs, 30 VUs) desta rodada, via `SELECT count(*) FROM books
      WHERE title LIKE 'LOADTEST-%'`
- [x] Recuperação de órfãos bounded por request (PR #141, finding "Bound
      each orphan-recovery request"): fault injection contra um proxy local
      throwaway que segura a resposta de `GET /api/books` por `30s`
      confirmou que cada `scanOnce()` nunca esperou mais que
      `ORPHAN_RECOVERY_REQUEST_TIMEOUT_MS` (`2000ms` com
      `HTTP_REQUEST_TIMEOUT=2s`) e que a janela inteira de recuperação
      terminou em `~5s` (o valor de `ORPHAN_RECOVERY_WINDOW_MS`), nunca perto
      do delay injetado — ver "Prova do bound de recuperação de órfãos" em
      [§9](#9-resultados-obtidos)
- [x] Orçamento de cleanup reservado dentro de `SETUP_TIMEOUT` (mesmo
      finding): fault injection com `VUS=5`/`SETUP_TIMEOUT=20s` e cada `POST
      /api/books` artificialmente lento (`1.7s`, abaixo do
      `HTTP_REQUEST_TIMEOUT=2s`) confirmou que `setup()` recusou a 4ª
      tentativa de provisionamento com uma mensagem explícita
      ("Orçamento de SETUP_TIMEOUT insuficiente...") após só `~10s`
      decorridos — nunca chegando perto dos `20s` de `SETUP_TIMEOUT` — e que
      os 3 livros já criados foram limpos por completo antes do `throw`; ver
      "Prova do orçamento de cleanup" em [§9](#9-resultados-obtidos)
- [x] Corrida de commit tardio continua resolvida após o refactor de
      bounding (mesmo finding): fault injection atrasando a chegada do `POST
      /api/books` ao backend real além do `HTTP_REQUEST_TIMEOUT` (commit só
      acontece depois que o cliente já desistiu) confirmou que uma consulta
      de recuperação posterior — dentro da janela — encontrou e removeu o
      livro; ver "Prova da corrida de commit tardio" em
      [§9](#9-resultados-obtidos)
- [x] Isolamento entre `runId`s concorrentes preservado (mesmo finding): um
      livro decoy sob outro marcador (`LOADTEST-OTHERRUNDECOY-vu1`) sobreviveu
      intocado à recuperação/cleanup de uma execução com falha — confirmado
      via consulta direta ao Postgres antes/depois
- [x] Timeout explícito nos 4 `POST`s de provisionamento e no `DELETE` de
      `cleanupBooks()` (mesmo finding, "não desloque o bloqueio de GET para
      DELETE"): confirmado via `k6 inspect` e pela fault injection do
      orçamento de cleanup acima, que depende exatamente desses timeouts
      para o cálculo de `cleanupBudgetMs()` bater com o comportamento real
- [x] Senha fora da linha de comando do processo (PR #141, finding "Keep the
      load-test password out of the process arguments"): confirmado
      empiricamente que o k6 expõe toda variável de ambiente do processo via
      `__ENV` mesmo sem nenhum `-e` (script mínimo lendo `__ENV.FOO` com só
      `export FOO=...` no shell); depois de remover os 6 usos de `-e
      LOAD_TEST_PASSWORD=...` do README, um smoke real (`VUS=2`) autenticou
      normalmente (setup, VU e teardown, checks 100%) usando só
      `export LOAD_TEST_PASSWORD`; inspeção da linha de comando do processo
      `k6.exe` em execução (`Get-CimInstance Win32_Process`) confirmou
      ausência da senha — só flags não sensíveis (`BASE_URL`, `VUS`,
      `WARMUP_DURATION`, ...) — ver "Prova da senha fora do argv" em
      [§9](#9-resultados-obtidos)
- [x] `measured_script_git_blob`/`measured_script_sha256` desta rodada
      (`8b8a53ebe4207ee7f8ea951dde273cdd741b5154`/
      `18bb2fdc32fe5f4d3e483dc7d7ccfdad7e37d58eab745f22d5d657e5f4292b9f`)
      conferidos sobre os bytes do blob do Git (`git rev-parse
      746cdbb59147ff11a9bd22d1c2da4c9a37c9bc80:loadtest/carga.js` e `git
      cat-file blob 8b8a53ebe4207ee7f8ea951dde273cdd741b5154 | sha256sum`),
      nunca do working tree
- [x] Revalidação pós-integração da master/PR #149 (ver "Revalidação
      pós-integração da master (PR #149)" em [§9](#9-resultados-obtidos)):
      `origin/master@0ea05c5` confirmado ancestral do merge commit
      `1dd985b`; `loadtest/` inteiro confirmado byte-a-byte idêntico
      através do merge (`git diff` vazio, blob de `carga.js` idêntico); as 4
      migrations novas da PR #149 aplicadas com sucesso no boot; smoke, 10
      VUs e 30 VUs contra o backend integrado — checks 100%,
      `vu_auth_success==100%`, zero `429`, zero resíduo `LOADTEST-`, os 21
      thresholds `ok:true` nas duas execuções; `measured_code_commit`
      permaneceu `746cdbb` (nenhuma medição de código nova, só de backend)
