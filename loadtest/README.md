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

Fluxo por VU, uma vez por iteração:

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
   queryClient.invalidateQueries(...)` (`scene-editor.tsx`) — fire-and-forget,
   sem bloquear a UI. O script espelha isso: o refetch usa
   `http.asyncRequest()` e roda **em paralelo** com o think time do passo 7
   (`Promise.all([refreshPromise, delay(thinkTime())])`), nunca serializado
   antes dele — um GET síncrono aqui somaria a latência inteira do refetch ao
   think time e reduziria a taxa de requisições da VU justamente quando o
   backend fica mais lento, subestimando a carga oferecida.
7. think time curto (0.3–1s, configurável), sobreposto ao passo 6 quando ele roda

Cada requisição das 5 operações acima também carrega uma tag `phase`
(`ramp_up`/`steady`/`ramp_down`), calculada uma vez por iteração a partir de
`exec.scenario.startTime` (k6/execution) e das durações configuradas
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
diferente de 200, a iteração encerra ali mesmo (think time + `return`) — os
passos seguintes não rodam. Isso evita que uma falha de leitura vire tráfego
de escrita e distorça a latência/taxa de erro de `save_scene`.

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

Cada VU autentica **uma única vez, sozinha, na própria primeira iteração** —
não em `setup()` nem repetido a cada iteração. `setup()` também autentica,
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
verificação automática disso no script — confira manualmente antes de medir.

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
   chamada de nível superior — `setup()`, `teardown()` e **cada iteração** de
   uma VU começam com um jar vazio, mesmo dentro da mesma VU; não é o jar
   persistente "por VU" que a documentação do k6 sugere à primeira leitura.
   Por isso cada VU guarda a referência do próprio jar (`vuJar`, variável de
   módulo) na primeira iteração e a passa explicitamente (`{ jar: vuJar }`)
   em toda requisição das iterações seguintes — sem isso, a sessão se
   perderia a cada nova iteração. `setup()` e `teardown()` usam o mesmo
   padrão com um `jar` local de escopo único. O header CSRF de duplo envio
   (`X-XSRF-TOKEN`) é lido de volta do jar ativo no momento de cada
   requisição (`authHeaders(jar)`); o cookie de sessão em si não precisa de
   header manual — o k6 já o reenvia sozinho a partir do jar passado.
   `setup()` nunca devolve o jar, o cookie ou o token a ninguém — só
   `[{ bookId, sceneId }, ...]` (ver [§5](#5-dados-sintéticos-e-limpeza)).

**Nenhuma falha de login de VU é tolerada.** Cada autenticação bem/mal
sucedida incrementa a métrica `vu_auth_success` (`k6/metrics.Rate`), com
threshold `rate==1` sem tolerância ([§7](#7-thresholds)) — uma única falha
reprova o `k6 run` inteiro ao final, mesmo que a mesma VU consiga se
autenticar numa iteração posterior (o k6 tenta de novo a cada iteração
enquanto a VU não estiver autenticada, para não perder toda a carga por uma
falha transitória, mas isso nunca esconde a falha do resultado).

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
da conta repetidamente (intervalo curto de 0.5s, janela de 5s — constantes
`ORPHAN_RECOVERY_POLL_INTERVAL_S`/`ORPHAN_RECOVERY_WINDOW_MS` em
`carga.js`), continuando mesmo depois de uma consulta vazia, até a janela
fechar — bound só por tempo (`Date.now() >= deadline`), nunca por resposta
do servidor, então nunca é um loop infinito. Casa pelo marcador EXATO desta
execução (prefixo `LOADTEST-${runId}-vu`, nunca `LOADTEST-` genérico),
acumulando IDs sem duplicar (`Set`) e unindo qualquer órfão encontrado aos
já rastreados antes de tentar remover todos. Nunca toca livros de outra
execução concorrente (outro `runId`).

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
| `THINK_TIME_MIN_S` / `THINK_TIME_MAX_S` | think time por iteração (depois do save concluído) | `0.3` / `1` |
| `AUTO_SAVE_DELAY_MS` | debounce do `AUTO_SAVE` antes do PATCH (== `CONTENT_AUTOSAVE_DELAY_MS` do frontend) | `1200` |
| `RESULT_PATH` | caminho do resumo JSON já sanitizado (opcional) | nenhum — só imprime no terminal |
| `SETUP_TIMEOUT` | timeout de `setup()` (formato de duração do k6, ex. `10m`, `90s`, `0.5m`) — sobe com `VUS` porque `setup()` cria um livro completo por VU em série | `10m` |
| `TEARDOWN_TIMEOUT` | timeout de `teardown()`, mesmo formato | `10m` |

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

### Smoke curto

```bash
k6 run \
  -e BASE_URL=http://localhost:8085 \
  -e LOAD_TEST_PASSWORD="$LOAD_TEST_PASSWORD" \
  -e VUS=2 -e WARMUP_DURATION=5s -e STEADY_DURATION=10s -e RAMPDOWN_DURATION=5s \
  loadtest/carga.js
```

```powershell
k6 run `
  -e BASE_URL=http://localhost:8085 `
  -e "LOAD_TEST_PASSWORD=$env:LOAD_TEST_PASSWORD" `
  -e VUS=2 -e WARMUP_DURATION=5s -e STEADY_DURATION=10s -e RAMPDOWN_DURATION=5s `
  loadtest/carga.js
```

### Baseline — 10 VUs (padrão de estágios: 30s/2m/30s)

```bash
k6 run \
  -e BASE_URL=http://localhost:8085 \
  -e LOAD_TEST_PASSWORD="$LOAD_TEST_PASSWORD" \
  -e VUS=10 -e RESULT_PATH=loadtest/resultados/resultado-10vus.json \
  loadtest/carga.js
```

```powershell
k6 run `
  -e BASE_URL=http://localhost:8085 `
  -e "LOAD_TEST_PASSWORD=$env:LOAD_TEST_PASSWORD" `
  -e VUS=10 -e RESULT_PATH=loadtest/resultados/resultado-10vus.json `
  loadtest/carga.js
```

### Carga ampliada — 30 VUs

```bash
k6 run \
  -e BASE_URL=http://localhost:8085 \
  -e LOAD_TEST_PASSWORD="$LOAD_TEST_PASSWORD" \
  -e VUS=30 -e RESULT_PATH=loadtest/resultados/resultado-30vus.json \
  loadtest/carga.js
```

```powershell
k6 run `
  -e BASE_URL=http://localhost:8085 `
  -e "LOAD_TEST_PASSWORD=$env:LOAD_TEST_PASSWORD" `
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
http_req_failed  rate        < 1%      (por operação principal, SÓ fase estável — as mesmas 5 operações acima)
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

**Por que thresholds de erro por operação, além do `http_req_failed` global:**
o global dilui uma falha concentrada numa única operação entre as outras
quatro requisições da mesma iteração — 4% de falha isolada em `save_scene`
ainda fica bem abaixo de 1% no total agregado de 5 operações, então o `k6
run` passaria mesmo com 1 em cada 25 saves quebrado. Os 5 thresholds
`http_req_failed{operation:X,phase:steady}` (`rate<0.01`) avaliam a taxa de
erro de cada operação isoladamente, restrita à fase estável pelo mesmo
motivo do parágrafo acima. Validado com fault injection concentrada em
`save_scene` (ver [§9](#9-resultados-obtidos)): `http_req_failed` global
ficou em 0.68% (passou) enquanto
`http_req_failed{operation:save_scene,phase:steady}` ficou em 3.61%
(rompeu) — o `k6 run` saiu com código diferente de zero só por causa do
threshold por operação.

**Por que `phase:steady` e não a operação sem filtro:** os estágios de
`WARMUP_DURATION`/`RAMPDOWN_DURATION` rodam com menos VUs que o pico
(rampando para cima/para baixo), então misturar essas amostras no mesmo p95
que os 2 minutos de `STEADY_DURATION` dilui o percentil — um threshold podia
"passar" mesmo que a carga em regime, sozinha, já rompesse o teto. A fase é
calculada uma vez por iteração (`currentPhase()` em `carga.js`, via
`exec.scenario.startTime` do módulo `k6/execution` e as durações
configuradas, convertidas para ms por `k6DurationToMs()`) e a mesma tag vale
para as 5 operações daquela iteração. `http_req_duration` **sem** tag
continua agregando as 3 fases — é o número de "execução completa" em
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
uma remedição depois de 4 correções P2 do Codex sobre `8023b2d`** (código
medido anterior: `1f2593e`):

1. **Debounce real do `AUTO_SAVE`.** O cenário disparava o PATCH assim que o
   conteúdo alternado era montado, limitado só pelo think time (0.3-1s) —
   mais rápido do que qualquer editor real permite. O editor real espera
   `CONTENT_AUTOSAVE_DELAY_MS=1200ms` sem nova alteração antes de persistir
   (`scene-editor.tsx`). Adicionado `AUTO_SAVE_DELAY_MS` (default `1200`,
   validado finito/não negativo) com `await delay(AUTO_SAVE_DELAY_MS / 1000)`
   antes do PATCH — **isto muda o pacing do cenário e portanto a carga
   oferecida**, ver "Mudança de pacing" abaixo.
2. **UUIDs no exemplo de limpeza por ID.** O README comparava `books.id`
   (`uuid`) com literais inteiros (`11, 12, 13`), que o Postgres rejeita.
   Corrigido para UUIDs entre aspas com `::uuid` explícito — ver
   [§5](#5-dados-sintéticos-e-limpeza).
3. **Hash de reprodutibilidade independente de CRLF/LF.** O
   `measured_script_sha256` da rodada anterior foi calculado sobre os bytes
   CRLF do working tree Windows (`core.autocrlf=true`), que não batem com um
   checkout Linux limpo. `.gitattributes` agora fixa `loadtest/carga.js text
   eol=lf`, e o hash passa a ser calculado sobre os bytes do **blob do Git**
   (`git cat-file blob <commit>:loadtest/carga.js`), não do arquivo no
   working tree — ver nota abaixo.
4. **`backend` fora da allowlist automática.** `SAFE_HOSTS` aceitava o
   hostname genérico `backend` sem exigir confirmação, mas `parseSafeHost()`
   só compara a autoridade léxica da URL — nunca resolve o endereço real.
   Numa máquina com domain search DNS fora da rede do Compose pretendida,
   `backend` poderia apontar para um serviço remoto não relacionado. Removido
   de `SAFE_HOSTS`; agora exige o mesmo `ALLOW_UNSAFE_TARGET` que qualquer
   outro host não controlado — ver [§4](#4-segurança-de-destino).

**Mudança de pacing (finding 1 acima):** cada iteração agora inclui uma
espera fixa de `AUTO_SAVE_DELAY_MS` (1.2s) antes do PATCH que a rodada
anterior não tinha. Isso reduz deliberadamente a taxa de
iterações/requisições por VU — os números desta rodada **não são uma
comparação direta de performance** com `1f2593e`: a carga oferecida mudou de
propósito, não o backend medido. A diferença de requests/RPS é mostrada mais
abaixo, com essa ressalva.

- **`measured_code_commit`**: `dece9f3e95a8b1d05356cf0c42d837618d28e455` — o
  commit de `loadtest/carga.js` + `.gitattributes` exatamente como executado
  para gerar os números abaixo, com working tree limpo, sem nenhuma mudança
  de código depois. Reproduzir: `git checkout dece9f3 -- loadtest/carga.js`.
- **`measured_script_path`**: `loadtest/carga.js`.
- **`measured_script_git_blob`**: `b2ed1d7bb107c5d3e960e483d3ad460cfb082b0b`
  — saída de `git rev-parse dece9f3:loadtest/carga.js` (bytes do blob do Git,
  não do working tree).
- **`measured_script_sha256`**:
  `872c2d7d6ed574d45e5e6665643d9b21b04353f9c02ae0e683bf82b3f68aa31a` — SHA-256
  dos bytes do blob acima, calculado com:
  ```bash
  python -c "import hashlib,subprocess; d=subprocess.check_output(['git','cat-file','blob','dece9f3:loadtest/carga.js']); print(hashlib.sha256(d).hexdigest())"
  ```
  **Não** use `sha256sum loadtest/carga.js` nem `Get-FileHash` sobre o
  arquivo já checked-out como prova — mesmo com `.gitattributes` fixando
  `eol=lf`, um arquivo já no disco reflete o `core.autocrlf` de quem fez
  checkout (Windows com `autocrlf=true` converte o blob LF de volta para
  CRLF), então o hash do working tree pode divergir do hash do blob conforme
  a máquina. `git cat-file blob` lê os bytes do objeto armazenado no Git
  diretamente, sem passar pelo checkout — o mesmo em qualquer SO.
- **Por que blob + SHA-256, e não só `measured_code_commit`**: depois de um
  squash-merge (e possível exclusão da branch `feature/k6-realistic-baseline`),
  `measured_code_commit` pode deixar de ser alcançável a partir de `master` —
  um `git checkout dece9f3 -- loadtest/carga.js` num clone novo falharia.
  O blob e o SHA-256 não dependem de nenhum commit específico continuar
  alcançável: bastam o conteúdo do arquivo em `master` e `git cat-file blob`
  local para confirmar que é byte a byte o mesmo script que gerou os números
  abaixo — `measured_code_commit` continua registrado só como referência
  histórica de onde a medição aconteceu.
- **`evidence_commit`**: o commit imediatamente seguinte nesta branch, que só
  adiciona/atualiza `resultado.json`, `resultados/*.json` e este README — sem
  nenhuma mudança de comportamento do script. Hash exato na descrição da PR
  #141 (não dá para gravar o próprio hash dentro do arquivo que ele versiona
  sem autorreferência).

Resumo comparativo completo, estruturado, em
[`resultado.json`](resultado.json); JSONs brutos por execução em
[`resultados/resultado-10vus.json`](resultados/resultado-10vus.json) e
[`resultados/resultado-30vus.json`](resultados/resultado-30vus.json).

### Ambiente desta rodada

Energia AC confirmada via `Win32_Battery.BatteryStatus=2` imediatamente
antes do smoke e imediatamente depois da execução de 30 VUs — nenhum
incidente de throttling de bateria nesta rodada (diferente de `8023b2d`, que
havia documentado um). `crm-marketing-backend-1` confirmado parado (`docker
ps`) antes de medir. As três execuções (smoke, 10 VUs, 30 VUs) rodaram em
sequência, sem outra carga de trabalho pesada ativa na máquina.

### Execução completa (todas as fases, todas as requisições)

Smoke, auth, setup, as 5 operações principais (`ramp_up`+`steady`+`ramp_down`)
e teardown — `http_req_duration` **sem** tag `phase`:

| | VUs | Duração | Requests | RPS global | p50 (ms) | p90 (ms) | p95 (ms) | p99 (ms) | Erros | Checks | `vu_auth_success` |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Baseline | 10 | 3m (30s/2m/30s) | 3955 | 21.6 | 14.8 | 52.9 | 63.7 | 106.6 | 0% (0/3955) | 100% (3880/3880) | 100% (10/10) |
| Carga ampliada | 30 | 3m (30s/2m/30s) | 11965 | 65.2 | 7.1 | 42.6 | 56.3 | 88.5 | 0% (0/11965) | 100% (11750/11750) | 100% (30/30) |

`vu_auth_success` confirma exatamente uma autenticação bem-sucedida por VU
nas duas execuções. Estes percentis incluem auth/setup/teardown **e**
warmup/rampdown — não são a fase estável isolada; para isso, a seção
seguinte. **Requests/RPS caem frente à rodada anterior por causa do debounce
do `AUTO_SAVE` (ver "Mudança de pacing" acima) — não é uma regressão.**

### Fase estável (`phase:steady`) — só as operações principais, só os 2 minutos com VUS constante no pico

| Operação | 10 VUs p50 | 10 VUs p90 | 10 VUs p95 | 10 VUs p99 | 10 VUs max | 30 VUs p50 | 30 VUs p90 | 30 VUs p95 | 30 VUs p99 | 30 VUs max |
|---|---|---|---|---|---|---|---|---|---|---|
| `list_books` | 38.1 | 61.6 | 70.9 | 87.4 | 116.9 | 31.3 | 63.0 | 76.5 | 102.8 | 246.1 |
| `load_outline` | 9.8 | 15.7 | 18.4 | 27.6 | 71.9 | 4.0 | 7.1 | 8.4 | 14.0 | 211.7 |
| `load_scene` | 9.0 | 14.7 | 17.6 | 25.2 | 67.6 | 3.8 | 6.3 | 7.5 | 11.0 | 51.7 |
| `save_scene` | 43.7 | 59.5 | 66.3 | 84.1 | 136.6 | 31.3 | 49.8 | 61.6 | 86.7 | 369.2 |
| `refresh_outline_after_save` | 10.3 | 16.2 | 17.8 | 30.4 | 47.8 | 5.2 | 9.1 | 10.5 | 17.5 | 54.4 |

**Todos os 10 thresholds de latência `p95<500ms` (5 operações × 2 execuções)
passaram**, com folga ainda mais confortável que na rodada anterior — a
carga oferecida por VU é menor com o debounce modelado. **Os 10 thresholds
de erro `rate<0.01` (5 operações × 2 execuções) também passaram — 0% de erro
em todas as operações, nas duas cargas.** `checks` (100%) e
`http_req_failed` global (0%) passaram nas duas execuções — nenhuma
requisição falhou.

Em cada execução, a contagem do check `refresh_outline_after_save status 200`
bate exatamente com `save_scene status 200` (776/776 em 10 VUs, 2350/2350
em 30 VUs, zero falhas) — confirmando que o refetch só roda após um save
bem-sucedido, nunca a mais nem a menos.

### Auth/setup/teardown (fora do loop medido)

`auth_csrf`/`auth_login` rodam `VUS+2` vezes (setup + 1/VU + teardown);
`setup_create_book/section/chapter/scene` e `teardown_delete_book` rodam
`VUS` vezes, um livro por VU:

| Operação | 10 VUs avg | 10 VUs p95 | 30 VUs avg | 30 VUs p95 |
|---|---|---|---|---|
| `auth_csrf` | 9.7ms | 26.0ms | 5.6ms | 10.3ms |
| `auth_login` | 113.5ms | 148.6ms | 86.2ms | 127.9ms |
| `setup_create_book` | 18.0ms | 30.0ms | 17.5ms | 47.3ms |
| `setup_create_section` | 9.5ms | 12.2ms | 8.5ms | 19.3ms |
| `setup_create_chapter` | 11.5ms | 15.6ms | 8.7ms | 13.9ms |
| `setup_create_scene` | 20.0ms | 29.2ms | 15.7ms | 36.3ms |
| `teardown_delete_book` | 38.5ms | 57.0ms | 9.9ms | 12.2ms |

### Debounce do AUTO_SAVE — teste dedicado (finding 1)

Fora das 3 execuções medidas acima, com uma cópia temporária instrumentada
de `carga.js` (não versionada: `console.log` do timestamp imediatamente
antes e depois do `await delay(AUTO_SAVE_DELAY_MS / 1000)`), `VUS=1`,
`WARMUP_DURATION=1s`/`STEADY_DURATION=8s`/`RAMPDOWN_DURATION=1s`,
`THINK_TIME_MIN_S=0.1`/`THINK_TIME_MAX_S=0.2`: 7 iterações completas, 100%
checks (35/35), 0% `http_req_failed`. Diferença `pre_patch - pre_delay` em
cada iteração: **1201ms, 1200ms, 1207ms, 1201ms, 1200ms, 1200ms, 1201ms** —
sempre ≥ `AUTO_SAVE_DELAY_MS` (1200ms), nunca um PATCH disparado ignorando o
debounce, mesmo com think time curto o suficiente para permitir saves
consecutivos rápidos.

### Fault injection — os dois findings que motivaram a rodada anterior (`8023b2d`)

Não re-testados nesta rodada porque nenhuma das quatro correções toca
`recoverOrphanedBookIds()` nem os thresholds de erro por operação — ver
`loadtest/README.md` na revisão `8023b2d` (histórico) para os logs
originais de fault injection desses dois achados.

### Leitura do resultado — comparação com a rodada anterior (`1f2593e`)

**Não é uma comparação direta de performance** — o pacing do cenário mudou
(debounce de 1.2s adicionado antes de cada PATCH, ver "Mudança de pacing"
acima). A carga oferecida por VU é deliberadamente menor; a diferença abaixo
reflete a mudança de metodologia, não uma regressão nem uma melhora do
backend medido:

| | 10 VUs: `1f2593e` → `dece9f3` | 30 VUs: `1f2593e` → `dece9f3` |
|---|---|---|
| Requests totais | 10675 → 3955 (-63.0%) | 29145 → 11965 (-59.0%) |
| RPS global | 58.9 → 21.6 (-63.4%) | 159.1 → 65.2 (-59.0%) |
| Iterações | 2120 → 776 (-63.4%) | 5786 → 2350 (-59.4%) |
| `save_scene` p95 (steady) | 55.2 → 66.3 | 89.1 → 61.6 |
| Thresholds de operação principal | 10/10 (latência + erro) | 10/10 (latência + erro) |

**Gargalo principal:** nenhum — as 5 operações passam o teto de 500ms com
folga ainda maior que na rodada anterior, consequência direta da carga
oferecida mais baixa por VU. `list_books` continua sendo a operação
relativamente mais lenta nas duas cargas (cresce com o tamanho da coleção de
livros do tenant, que aumenta com `VUS` por construção do cenário), mas está
longe do teto mesmo em 30 VUs.

**Limitações desta execução:**
- Rodada em uma stack Docker isolada só para este teste (`docker-compose -p
  iwrite-k6smoke`, `container_name`/portas remapeados via
  `docker-compose.k6.local.yml`, não versionado — `iwrite-k6-db:5443`,
  `iwrite-k6-backend:8093`, `iwrite-k6-frontend:3009`). `crm-marketing-backend-1`
  ficou parado durante toda a execução, mas um Postgres de **outro worktree
  do IWrite** (`iwrite-db`, porta 5435) permaneceu ativo e ocioso — não é
  hardware dedicado.
- O debounce do `AUTO_SAVE` (`AUTO_SAVE_DELAY_MS=1200`) reduz o throughput de
  escrita por VU frente às rodadas anteriores por construção — este cenário
  modela a cadência de um autor digitando e pausando, não o teto de
  throughput que o backend consegue sustentar. Para medir capacidade máxima,
  use `AUTO_SAVE_DELAY_MS` baixo/zero explicitamente como modo de estresse.
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
  [§2](#2-pré-requisitos).

**Próxima ação recomendada:** repetir 30 VUs (e testar `VUS` maior, ex.
50-100) em hardware totalmente dedicado, sem nenhum outro container Docker
ativo na máquina, agora que o pacing reflete o debounce real do editor.
Rodar o teste com OTel habilitado (`docker-compose.observability.yml`) e
usar os traces correlacionados de `scene_content_save` para decompor o
custo de `save_scene`. Investigar o crescimento de
`list_books`/`load_outline` com o tamanho da coleção de livros do tenant
antes de rodar com `VUS` bem maior que 30. Considerar um segundo cenário
explícito de estresse (`AUTO_SAVE_DELAY_MS` baixo) para medir o teto de
throughput de escrita separadamente do baseline realista.

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
- [x] `list_books`/`load_outline` falhando (fault injection) encerra a
      iteração imediatamente: confirmado, com `--out json=` temporário
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
- [x] Refetch assíncrono (`http.asyncRequest()` + `Promise.all()`) validado:
      `k6 run` completa sem erro de Promise não tratada, `refresh_outline_after_save
      status 200` continua batendo exatamente com `save_scene status 200`
      nas 3 execuções (smoke/10 VUs/30 VUs) mesmo sendo assíncrono, e a
      contagem de iterações por segundo aumentou (10 VUs: 1606→2098; 30 VUs:
      2273→4825) — consistente com o refetch não bloquear mais a próxima
      iteração além do think time
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
- [x] Threshold de erro por operação (`http_req_failed{operation:X,phase:steady}`)
      testado com fault injection concentrada em `save_scene` (1 a cada 25
      iterações força um `sceneId` inválido, VUS=10, 60s de fase estável):
      `http_req_failed` global ficou em 0.68% (passou no threshold global
      `<1%`) enquanto `http_req_failed{operation:save_scene,phase:steady}`
      ficou em 3.61% (rompeu o threshold `<1%`) — `k6 run` saiu com código
      `99` só por causa do threshold por operação. `refresh_outline_after_save`
      continuou disparando só nos saves bem-sucedidos (872/872, zero
      falhas). Zero resíduo `LOADTEST-` após o teardown
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
      dece9f3:loadtest/carga.js` bate com `measured_script_git_blob`, e
      `git cat-file blob dece9f3:loadtest/carga.js` (via Python/`hashlib`)
      bate com `measured_script_sha256` registrado em `resultado.json` e
      neste README. `git hash-object loadtest/carga.js` sobre o working tree
      também bate, confirmando que `.gitattributes` (`eol=lf`) normaliza
      corretamente nesta máquina — mas a prova de reprodutibilidade
      multiplataforma é o blob, não o working tree (ver nota em [§9](#9-resultados-obtidos))
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
      reprova o `k6 run` diante de qualquer falha de login de VU, mesmo
      quando a mesma VU se autentica com sucesso numa iteração seguinte
      (testado com fault injection no backend, revertido antes da medição
      real); em execução saudável, cada VU registra exatamente uma
      autenticação bem-sucedida
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
- [x] Energia AC confirmada (`Win32_Battery.BatteryStatus=2`) imediatamente
      antes do smoke e imediatamente depois da execução de 30 VUs desta
      rodada — sem incidente de throttling de bateria (achado de uma rodada
      anterior, `8023b2d`, corrigido operacionalmente desde então)
- [x] Zero resíduo `LOADTEST-` confirmado após as 3 execuções medidas
      (smoke, 10 VUs, 30 VUs) e após os 3 testes de fault injection desta
      rodada, via `SELECT title FROM books WHERE title LIKE 'LOADTEST-%'`
