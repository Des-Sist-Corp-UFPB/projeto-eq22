# Teste de Carga e Performance (k6) — issue #129

Cenário realista contra a API real do IWrite, autenticado com a sessão de servidor
introduzida na PR #139 (cookie `JSESSIONID` + CSRF de duplo envio via `XSRF-TOKEN`).
Substitui o teste anterior, que só exercitava `/ping`.

> ⚠️ **Rode SEMPRE contra o seu ambiente LOCAL.** O script recusa qualquer
> `BASE_URL` que não resolva para `localhost`, `127.0.0.1`, `::1`,
> `host.docker.internal` ou `backend` — ver [Segurança de destino](#4-segurança-de-destino).
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
4. `PATCH /api/scenes/{sceneId}/content` — tag `operation=save_scene`
5. think time curto (0.3–1s, configurável)

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
`setup()` repassada a todas), uma execução com `VUS=N` agora faz até `N+1`
logins (as `N` VUs + o login interno de `setup()`) contra a mesma conta, e
esses logins tendem a se concentrar dentro do `WARMUP_DURATION` — o que pode
estourar o rate limiter de login
(`IWRITE_LOGIN_RATE_LIMIT_MAX_PER_ACCOUNT`/`_ORIGIN`, padrão 8/20 por janela
de 1 minuto — ver `.env.example`). Por isso `docker-compose.k6.local.yml`
(gitignored, só para a stack Docker isolada deste teste) eleva os dois
limites para este ambiente **local e isolado** — os defaults de produção em
`application.yml`/`.env.example` não são alterados.

`GET /ping` continua no script, mas só como smoke check **inicial** dentro do
`setup()`: se o ambiente não responder, o teste aborta antes de criar qualquer
dado.

---

## 2. Pré-requisitos

Suba o backend localmente com o overlay de demonstração (cria os usuários
`autor-a@iwrite.local` / `autor-b@iwrite.local` — ver
[`docs/demonstracao-multi-tenant.md`](../docs/demonstracao-multi-tenant.md)):

```bash
# copie .env.example para .env e preencha IWRITE_DEMO_SEED_ENABLED=true e as
# duas senhas (sem valor padrão) antes de subir
docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d --build
```

Tenha o k6 disponível (`k6.io/docs` para instalação, ou use a imagem
`grafana/k6` via Docker apontando `BASE_URL` para `host.docker.internal`).

---

## 3. Autenticação

O script reproduz exatamente o handshake que `web/e2e/auth.setup.ts` usa contra
o backend real:

1. `GET /api/auth/csrf` → emite o cookie `XSRF-TOKEN` (o Spring Security emite
   esse cookie em qualquer requisição que passe pelo `CsrfFilter`, então o
   `/ping` inicial já pode tê-lo emitido — o script lê do cookie jar do k6,
   não da resposta de uma chamada específica).
2. `POST /api/auth/login` com `{ "email": ..., "password": ... }` e o header
   `X-XSRF-TOKEN` — devolve o cookie de sessão `JSESSIONID`.
3. Toda chamada autenticada subsequente envia `Cookie: JSESSIONID=...;
   XSRF-TOKEN=...` e `X-XSRF-TOKEN: ...` manualmente (VUs não compartilham o
   cookie jar do `setup()` — é assim que o k6 funciona), construído a partir do
   que `setup()` devolveu.

**Credenciais vêm só de variável de ambiente, nunca de arquivo versionado:**

| Variável | Obrigatória | Padrão |
|---|---|---|
| `LOAD_TEST_PASSWORD` | **sim** | nenhum — o script aborta sem ela |
| `LOAD_TEST_EMAIL` | não | `autor-a@iwrite.local` |

Use a mesma senha do seed demo:

```bash
# bash
export LOAD_TEST_PASSWORD="$IWRITE_DEMO_AUTOR_A_PASSWORD"
```

O script nunca imprime a senha (só o e-mail aparece nos logs, em caso de erro
de login).

---

## 4. Segurança de destino

`BASE_URL` só é aceita sem override se o host resolver para um destes:
`localhost`, `127.0.0.1`, `::1`, `host.docker.internal`, `backend`. A
resolução do host **não usa regex ingênua** — o parser (`parseSafeHost` em
`carga.js`) só aceita esquema `http:`/`https:`, resolve corretamente literais
IPv6 (`[::1]`) e **rejeita qualquer URL com user-info** (`user@host` ou
`user:senha@host`) mesmo que o host à direita seja local: uma URL como
`http://localhost:8085@host-externo` não é "reinterpretada" para extrair o
host real, ela é recusada inteira, porque tentar adivinhar o host por trás de
credenciais é exatamente a superfície que um parser baseado em regex
simples pode errar.

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

`teardown()` autentica de novo (sessão própria, não reaproveita a de nenhuma
VU nem a de `setup()`) e apaga **todos** os livros da execução (cascata apaga
seção/capítulo/cena via `CascadeType.ALL` + `orphanRemoval`) — **reprova a
execução** (lança e faz o `k6 run` sair com código diferente de zero) se
qualquer `DELETE` não retornar `204`, listando quais livros não foram
removidos — um teardown que só loga e segue deixaria livros `LOADTEST-` para
trás sem que nenhum threshold acusasse nada, já que essas chamadas acontecem
fora do loop de VUs medido.

Se o teste for interrompido (Ctrl+C, k6 morto, timeout) antes do `teardown()`
rodar, limpe manualmente:

```bash
# lista os livros LOADTEST- residuais de uma sessão autenticada (substitua os
# cookies pelos da sua sessão de teste)
curl -s -b cookies.txt "$BASE_URL/api/books" | grep -o '"title":"LOADTEST-[^"]*"'

# ou direto no banco do ambiente local (nunca em produção)
docker exec iwrite-db psql -U postgres -d iwrite -c \
  "delete from books where title like 'LOADTEST-%';"
```

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
| `THINK_TIME_MIN_S` / `THINK_TIME_MAX_S` | think time por iteração | `0.3` / `1` |
| `RESULT_PATH` | caminho do resumo JSON já sanitizado (opcional) | nenhum — só imprime no terminal |
| `SETUP_TIMEOUT` | timeout de `setup()` (formato de duração do k6, ex. `10m`, `90s`) — sobe com `VUS` porque `setup()` cria um livro completo por VU em série | `10m` |
| `TEARDOWN_TIMEOUT` | timeout de `teardown()`, mesmo formato | `10m` |

`SETUP_TIMEOUT`/`TEARDOWN_TIMEOUT` são validados antes de rodar (regex de
duração do k6: unidades `ms`/`s`/`m`/`h` encadeadas) — um valor malformado
falha imediatamente com uma mensagem clara, em vez de o k6 rejeitar
silenciosamente as `options` ou cair no timeout padrão de 60s.

Validação estática antes de rodar:

```bash
k6 inspect loadtest/carga.js
```

### Smoke curto

```bash
k6 run -e BASE_URL=http://localhost:8085 -e LOAD_TEST_PASSWORD=$IWRITE_DEMO_AUTOR_A_PASSWORD \
  -e VUS=2 -e WARMUP_DURATION=5s -e STEADY_DURATION=10s -e RAMPDOWN_DURATION=5s \
  loadtest/carga.js
```

### Baseline — 10 VUs (padrão de estágios: 30s/2m/30s)

```bash
k6 run -e BASE_URL=http://localhost:8085 -e LOAD_TEST_PASSWORD=$IWRITE_DEMO_AUTOR_A_PASSWORD \
  -e VUS=10 -e RESULT_PATH=loadtest/resultados/resultado-10vus.json \
  loadtest/carga.js
```

### Carga ampliada — 30 VUs

```bash
k6 run -e BASE_URL=http://localhost:8085 -e LOAD_TEST_PASSWORD=$IWRITE_DEMO_AUTOR_A_PASSWORD \
  -e VUS=30 -e RESULT_PATH=loadtest/resultados/resultado-30vus.json \
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
http_req_failed          < 1%
checks                   > 99%
http_req_duration p(95)  < 500ms   (global)
http_req_duration p(95)  < 500ms   (por operação principal: list_books, load_outline, load_scene, save_scene)
http_req_duration p(95)  < 2000ms  (auth/setup/teardown — fora do loop medido)
http_req_duration p(95)  < 8000ms  (auth_login — bcrypt + cold start da JVM, ver §9)
```

Qualquer violação faz o `k6 run` sair com código diferente de zero — apropriado
para gate de CI/CD. Os thresholds de auth/setup/teardown existem sobretudo
para que o k6 reporte as métricas dessas tags separadas das operações
principais no summary (ele só cria uma série por tag quando há threshold
associado), não como um orçamento de performance rígido — são operações que
rodam 1x ou `VUS` vezes por execução, nunca sob a carga em regime.

---

## 8. Como ler o resultado

- **Tags exportadas:** só `operation` (dimensão funcional: `list_books`,
  `save_scene`, `auth_login`, ...) e `name` (rota HTTP normalizada, ex. `GET
  /api/scenes/{sceneId}`) — nunca a URL concreta. A tag automática `url` do
  k6 está desabilitada via `systemTags`, então nenhum `bookId`, `sceneId`,
  `runId`, título, conteúdo, usuário ou tenant vaza para uma tag, mesmo que o
  resultado seja exportado para um time-series output (`--out json=...`) ou o
  k6 cloud — validado rodando um smoke com `--out json=` e conferindo que
  nenhum UUID aparece em `data.tags` (arquivo temporário, nunca versionado).
- **`http_req_duration{operation:...}`** — cada operação tem sua própria série
  de percentis (`avg`, `min`, `med`=p50, `p(90)`, `p(95)`, `p(99)`, `max`),
  graças a essas tags e a `summaryTrendStats` configurado no script.
- **`http_req_duration` (sem tag) e `http_reqs.rate`** — agregam **todas** as
  requisições da execução: smoke, auth, setup do livro/seção/capítulo/cenas,
  as 4 operações principais **e** o teardown. Não representam a latência do
  loop principal sozinho — para isso, leia as séries individuais de
  `list_books`/`load_outline`/`load_scene`/`save_scene`. `resultado.json`
  mantém essa distinção explícita (`execucao_completa` vs.
  `operacoes_principais` vs. `auth_setup_teardown`).
- **`checks`** — as 4 asserções de status das operações principais
  (`list_books`, `load_outline`, `load_scene`, `save_scene`).
- Para investigar a operação mais lenta (`save_scene`, ver §9), use a
  observabilidade já existente do projeto (OTel + Loki/Tempo/Grafana via
  `docker-compose.observability.yml`) e procure os eventos de negócio
  `scene_content_save` no serviço do backend — ver
  [`docs/otel-correlated-logs.md`](../docs/otel-correlated-logs.md) para o
  formato dos logs estruturados.

---

## 9. Resultados obtidos

Execuções reais, ambiente local isolado (ver limitações abaixo). **Estes
números são de um cenário diferente do da medição anterior** — 1 livro por
VU em vez de um único livro compartilhado, e sessão própria por VU em vez de
uma sessão de `setup()` repassada a todas — então não são comparáveis
diretamente aos números antigos deste README; a mudança de cenário por si só
já altera o comportamento medido, especialmente em `save_scene`.

- **`measured_code_commit`**: `16d2ef638701cb939d6ac4e49f51c0325869d3d3` — o
  commit de `loadtest/carga.js` exatamente como executado para gerar os
  números abaixo, com working tree limpo, sem nenhuma mudança de código
  depois. Reproduzir: `git checkout 16d2ef6 -- loadtest/carga.js`.
- **`evidence_commit`**: o commit imediatamente seguinte nesta branch, que só
  adiciona/atualiza `resultado.json`, `resultados/*.json` e este README — sem
  nenhuma mudança de comportamento do script. Hash exato na descrição da PR
  #141 (não dá para gravar o próprio hash dentro do arquivo que ele versiona
  sem autorreferência).

Resumo comparativo completo, estruturado, em
[`resultado.json`](resultado.json); JSONs brutos por execução em
[`resultados/resultado-10vus.json`](resultados/resultado-10vus.json) e
[`resultados/resultado-30vus.json`](resultados/resultado-30vus.json).

**Execução completa** (todas as requisições — smoke, auth, setup, as 4
operações principais e teardown):

| | VUs | Duração | Requests | RPS global | p50 (ms) | p90 (ms) | p95 (ms) | p99 (ms) | Erros | Checks |
|---|---|---|---|---|---|---|---|---|---|---|
| Baseline | 10 | 3m (30s/2m/30s) | 7587 | 41.1 | 29.6 | 80.1 | 110.9 | 214.8 | 0% (0/7587) | 100% (7512/7512) |
| Carga ampliada | 30 | 3m (30s/2m/30s) | 17667 | 96.2 | 64.2 | 208.2 | 302.7 | 507.4 | 0% (0/17667) | 100% (17452/17452) |

Esses percentis incluem auth/setup/teardown — não são a latência do loop
principal. Diferente da medição anterior, `auth_login` já não domina o `max`
global: com uma sessão por VU (`VUS+1` logins por execução, não mais 1),
só a primeira amostra carrega algum custo de warmup, e o restante já é
bcrypt contra um backend aquecido — ver tabela de auth/setup/teardown
abaixo. Para a latência do loop principal isolada, a tabela seguinte:

**Operações principais** (p95/p99 em ms, medidas só dentro do loop de VUs —
sem misturar com auth/setup/teardown):

| Operação | 10 VUs p95 | 10 VUs p99 | 30 VUs p95 | 30 VUs p99 |
|---|---|---|---|---|
| `list_books` | 173.4 | 290.7 | 405.5 | 671.5 |
| `load_outline` | 49.4 | 91.4 | 242.9 | 425.8 |
| `load_scene` | 43.5 | 76.1 | 201.8 | 347.2 |
| `save_scene` | 125.1 | 215.2 | 313.8 | 522.1 |

Todos os thresholds passaram nas duas execuções. `save_scene` — que na
medição anterior (livro único compartilhado) era a operação mais lenta e a
única perto do teto — cai para bem abaixo das três leituras em 10 VUs
(125.1ms vs. 385.1ms antes) e volta a ficar comparável a `list_books`/`load_outline`
em 30 VUs: o efeito esperado de eliminar a serialização artificial no lock
pessimista de linha de um único livro (ver "Gargalo" abaixo). Em troca,
`list_books` se aproxima do teto em 30 VUs (p95 405.5ms, p99 671.5ms) — não
por contenção de escrita, mas porque o cenário agora cria `VUS` livros no
tenant do autor de teste, e listar/serializar essa coleção maior cresce com
`VUS` por construção.

**Auth/setup/teardown** (fora do loop medido — `auth_csrf`/`auth_login`
rodam `VUS+1` vezes; `setup_create_book/section/chapter/scene` e
`teardown_delete_book` rodam `VUS` vezes, um livro por VU):

| Operação | 10 VUs avg | 10 VUs p95 | 30 VUs avg | 30 VUs p95 |
|---|---|---|---|---|
| `auth_csrf` | 20.0ms | 55.0ms | 6.8ms | 10.8ms |
| `auth_login` | 170.5ms | 263.5ms | 139.3ms | 414.3ms |
| `setup_create_book` | 84.7ms | 137.7ms | 24.2ms | 42.5ms |
| `setup_create_section` | 52.5ms | 84.6ms | 11.8ms | 20.8ms |
| `setup_create_chapter` | 73.2ms | 135.5ms | 12.2ms | 21.3ms |
| `setup_create_scene` | 94.3ms | 144.6ms | 24.4ms | 41.2ms |
| `teardown_delete_book` | 48.5ms | 54.7ms | 34.2ms | 60.9ms |

**Gargalo principal:** já não é uma única operação dominante nas duas
cargas. Em 10 VUs, todas as quatro operações principais têm folga confortável
frente ao teto de 500ms. Em 30 VUs, `list_books` (p95 405.5ms) e `save_scene`
(p95 313.8ms) são as mais perto do limite, por razões diferentes:
`save_scene` continua sendo a única escrita do cenário (auditoria via
`@AuditedOperation`, versionamento de `contentJson`/`contentText`, ledger de
contagem de palavras — mais trabalho por requisição que uma leitura, mesmo
sem a contenção do livro compartilhado); `list_books` cresce porque o
cenário agora mantém `VUS` livros simultâneos no tenant do autor de teste
(mais os do seed demo), e listar/serializar essa coleção maior é mais caro
quanto maior `VUS`. Nenhum dos dois foi decomposto neste PR.

**Limitações desta execução:**
- Rodada em uma stack Docker isolada só para este teste (`docker-compose -p
  iwrite-k6`, portas remapeadas), na mesma máquina de desenvolvimento
  concorrendo com um container não relacionado (`crm-marketing`) — os
  números absolutos variam com a contenção do host no momento da execução.
- Backend, Postgres e k6 rodam na mesma máquina (sem separação de rede/CPU
  entre gerador de carga e alvo), então parte da latência medida pode ser
  contenção local, não custo real de rede.
- `list_books` cresce com `VUS` por construção do próprio cenário (1 livro
  por VU no tenant do autor de teste) — em execuções com `VUS` bem maior que
  30, esse custo pode se tornar o novo fator dominante antes mesmo de
  qualquer contenção real de escrita. Não investigado neste PR (paginação?
  índice? projeção mais enxuta na listagem?).
- Sem OTel habilitado durante a execução (evita adicionar overhead de
  instrumentação à medição); a decomposição do custo de `save_scene`/`list_books`
  entre suas etapas internas não foi feita neste PR.
- `contentJson` sintético é um único parágrafo curto — não representa uma
  cena longa de verdade. `save_scene` sob um payload realisticamente maior
  tende a ser mais lento ainda que o medido aqui.
- `IWRITE_LOGIN_RATE_LIMIT_MAX_PER_ACCOUNT`/`_ORIGIN` foram elevados só nesta
  stack local isolada (`docker-compose.k6.local.yml`, gitignored) para
  acomodar `VUS+1` logins por execução — os defaults de produção em
  `application.yml`/`.env.example` não foram alterados (ver [§3](#3-autenticação)).

**Próxima ação recomendada:** investigar o crescimento de `list_books` com o
tamanho da coleção de livros do tenant antes de rodar com `VUS` bem maior
que 30. Rodar o teste com OTel habilitado
(`docker-compose.observability.yml`) e usar os traces correlacionados de
`scene_content_save` e da consulta de `list_books` para decompor os dois
custos. Vale também medir `save_scene` com um `contentJson` de tamanho mais
realista (múltiplos parágrafos) e, dado que ambas as operações têm margem
estreita em 30 VUs, rodar em hardware não compartilhado para obter um número
de referência estável.

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
      zero ocorrências de `"url"` como chave de tag e zero UUIDs em
      `data.tags` em toda a execução — só `operation` e `name` (rota
      normalizada)
- [x] Falha de `setup()` após a criação de um livro (seção/capítulo/cena de
      uma VU) testada com fault injection: limpeza automática remove **todos**
      os livros já criados até aquele ponto (não só o da VU corrente),
      confirmada por VU, erro original preservado, `k6 run` sai com código
      diferente de zero
- [x] Falha de `teardown()` testada com fault injection (um dos livros já
      ausente): `k6 run` sai com código diferente de zero, listando qual
      `bookId` não foi removido, em vez de só logar
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
- [x] `SETUP_TIMEOUT`/`TEARDOWN_TIMEOUT` validados: valor malformado (ex.
      `10x`) rejeitado antes de rodar, com mensagem clara; valores válidos
      (`10m`, `90s`) aceitos e refletidos em `k6 inspect`
- [x] Resultados gerados com working tree limpo, exatamente no commit
      registrado como `measured_code_commit` em `resultado.json`
