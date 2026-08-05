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

Fluxo por VU, uma vez por iteração:

1. `GET /api/books` — tag `operation=list_books`
2. `GET /api/books/{bookId}/outline` — tag `operation=load_outline`
3. `GET /api/scenes/{sceneId}` — tag `operation=load_scene` (o mesmo que o
   `SceneEditor` real faz ao abrir uma cena — `getScene(sceneId)` em
   `web/src/features/scenes/api/scenes-api.ts`)
4. `PATCH /api/scenes/{sceneId}/content` — tag `operation=save_scene`
5. think time curto (0.3–1s, configurável)

Sem chamadas de IA. Cada VU escreve **somente na própria cena** (uma cena por VU
máximo, criada no `setup()`). A `expectedContentRevision` do `PATCH` vem sempre
da leitura do passo 3, **nunca de um cache local** — assim uma falha ambígua no
`PATCH` anterior (ex.: a escrita foi aplicada no servidor mas a resposta se
perdeu) nunca produz uma sequência artificial de conflitos de revisão: a
próxima escrita sempre parte do estado real e atual do servidor. Um novo
`operationId` é gerado a cada `PATCH`.

O `contentJson` enviado é o mesmo documento ProseMirror que o editor real
produz e versiona (`web/src/features/scenes/editor/tiptap-editor.tsx`,
`plainTextToDocument`), não apenas `contentText` — a versão anterior deste
script só enviava texto puro, o que subestimava o custo real do caminho de
save (ver [§9](#9-resultados-obtidos)).

Login acontece **uma única vez em `setup()`**, não por VU nem por iteração: a
sessão (`JSESSIONID`) e o token CSRF (`XSRF-TOKEN`) são obtidos ali e
repassados a todas as VUs via `data`. Isso reflete o uso real (uma sessão de
servidor dura a visita inteira) e evita contaminar o rate limiter de login
(`IWRITE_LOGIN_RATE_LIMIT_MAX_PER_ACCOUNT`/`_ORIGIN`, padrão 8/20 por janela de
1 minuto — ver `.env.example`).

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

`setup()` cria, sob o marcador `LOADTEST-<runId>` (nunca reaproveita livro ou
cena existente):

- 1 livro (`LOADTEST-<runId>`)
- 1 seção + 1 capítulo (hierarquia mínima)
- 1 cena por VU máximo (`VUS`), cada uma escrita só pelo VU correspondente
  (`sceneIds[(__VU - 1) % sceneIds.length]`)

**Se seção, capítulo ou alguma cena falhar depois que o livro já existe**, o
`setup()` tenta excluir o livro sintético (removendo em cascata o que já
tiver sido criado sob ele) antes de relançar o erro original e reprovar o
teste — necessário porque `teardown()` nunca roda quando `setup()` lança. O
log da tentativa de limpeza contém só `runId`/`bookId`/status HTTP, nunca
cookies ou o token CSRF.

`teardown()` apaga o livro (cascata apaga seção/capítulo/cenas via
`CascadeType.ALL` + `orphanRemoval`) e **reprova a execução** (lança e faz o
`k6 run` sair com código diferente de zero) se o `DELETE` final não retornar
`204` — um teardown que só loga e segue deixaria o livro `LOADTEST-` para trás
sem que nenhum threshold acusasse nada, já que essa chamada acontece fora do
loop de VUs medido.

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

**Sanitização é automática, não um passo manual.** `carga.js` define
`handleSummary()`, que assume toda a saída do k6 (terminal e arquivo) e
remove `setup_data` (onde vive o cookie de sessão e o token CSRF da execução)
antes de serializar qualquer coisa — inclusive quando um threshold falha ou
`setup()`/`teardown()` lança. Não existe mais um passo de "rodar e depois
sanitizar": não há como gerar um resumo com `JSESSIONID`/`XSRF-TOKEN` dentro,
porque o próprio script nunca os inclui na saída, ponto algum.

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

- **`http_req_duration{operation:...}`** — cada operação tem sua própria série
  de percentis (`avg`, `min`, `med`=p50, `p(90)`, `p(95)`, `p(99)`, `max`),
  graças às tags fixas e a `summaryTrendStats` configurado no script. IDs,
  título, usuário, tenant e conteúdo **não** são usados como tag — só o nome
  da operação.
- **`http_reqs.rate`** — RPS agregado do run inteiro (inclui as 4 operações
  principais **e** auth/setup/teardown).
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

Execuções reais, ambiente local isolado (containers dedicados, ver
limitações abaixo) — commit `eee876a`. Resumo comparativo completo, estruturado,
em [`resultado.json`](resultado.json); JSONs brutos por execução em
[`resultados/resultado-10vus.json`](resultados/resultado-10vus.json) e
[`resultados/resultado-30vus.json`](resultados/resultado-30vus.json).

**Operações principais** (medidas sob carga, dentro do loop de VUs):

| | VUs | Duração | RPS | p50 (ms) | p90 (ms) | p95 (ms) | p99 (ms) | Erros | Checks |
|---|---|---|---|---|---|---|---|---|---|
| Baseline | 10 | 3m (30s/2m/30s) | 30.8 | 57.9 | 228.1 | 328.4 | 542.9 | 0% (0/5809) | 100% (5792/5792) |
| Carga ampliada | 30 | 3m (30s/2m/30s) | 53.8 | 215.7 | 603.3 | 754.9 | 1036.6 | 0% (0/10221) | 100% (10184/10184) |

Por operação principal (p95, ms):

| Operação | 10 VUs | 30 VUs |
|---|---|---|
| `list_books` | 148.0 | 485.0 |
| `load_outline` | 117.7 | 421.3 |
| `load_scene` | 99.9 | 441.8 |
| `save_scene` | **518.0** ✗ | **985.7** ✗ |

**Auth/setup/teardown** (fora do loop medido — rodam 1x, ou `VUS` vezes no
caso de `setup_create_scene`):

| Operação | 10 VUs | 30 VUs |
|---|---|---|
| `auth_csrf` | 24.2ms | 10.5ms |
| `auth_login` | 5272.3ms | 5303.4ms |
| `setup_create_book` | 194.8ms | 110.0ms |
| `setup_create_section` | 123.7ms | 94.4ms |
| `setup_create_chapter` | 104.7ms | 92.7ms |
| `setup_create_scene` (p95) | 303.5ms | 143.3ms |
| `teardown_delete_book` | 353.1ms | 629.9ms |

`http_req_failed` e `checks` passaram nas duas execuções — **zero erros**, só
os thresholds de latência é que foram violados (`save_scene` nas duas cargas;
o `http_req_duration` global também na de 30 VUs). O `k6 run` saiu com código
diferente de zero em ambas, como esperado quando um threshold é violado.

**Gargalo principal:** `PATCH /api/scenes/{id}/content` (`save_scene`) é
consistentemente a operação mais lenta e a que mais degrada com o aumento de
VUs (p95 sobe de 518ms para 986ms, ~1.9× de 10 para 30 VUs). Depois da
correção que passou a enviar o mesmo `contentJson` que o editor real produz
(antes o script só enviava `contentText`), o custo medido de `save_scene`
subiu de forma visível frente à medição anterior deste PR — o benchmark
anterior estava subestimando esse caminho por não exercitar o payload real.
`save_scene` é a única escrita do cenário: passa por auditoria
(`@AuditedOperation`), versionamento de cena e ledger de contagem de
palavras. Não foi isolado neste PR qual dessas etapas domina o custo.

**Limitações desta execução:**
- Rodada em uma stack Docker isolada só para este teste (`docker-compose -p
  iwrite-k6`, portas remapeadas), na mesma máquina de desenvolvimento
  concorrendo com outros containers (outro worktree do IWrite + um projeto
  não relacionado) — os números absolutos não refletem hardware dedicado.
- Backend, Postgres e k6 rodam na mesma máquina (sem separação de rede/CPU
  entre gerador de carga e alvo), então parte da latência medida pode ser
  contenção local, não custo real de rede.
- `auth_login` levou ~5.3s em ambas as execuções — bcrypt (deliberadamente
  lento) rodando uma única vez por execução contra um host sob contenção de
  CPU. Não faz parte do cenário medido sob carga e não deve ser lido como
  latência típica de login; por isso tem orçamento próprio (`p(95)<8000ms`),
  bem mais folgado que o das operações principais.
- Sem OTel habilitado durante a execução (evita adicionar overhead de
  instrumentação à medição); a decomposição do custo de `save_scene` entre
  auditoria/versionamento/ledger de palavras não foi feita neste PR.
- `contentJson` sintético é um único parágrafo curto — não representa uma
  cena longa de verdade. `save_scene` sob um payload realisticamente maior
  tende a ser mais lento ainda que o medido aqui.

**Próxima ação recomendada:** rodar o teste com OTel habilitado
(`docker-compose.observability.yml`) e usar os traces correlacionados de
`scene_content_save` para decompor o tempo do `PATCH` entre auditoria,
versionamento e ledger de palavras, e então decidir se algum desses passos
pode sair do caminho síncrono do save. Vale também medir com um `contentJson`
de tamanho mais realista (múltiplos parágrafos).

---

## 10. Validado

- [x] `k6 inspect loadtest/carga.js` sem erros
- [x] Login + CSRF reais (não mockados) contra o backend com o profile `demo`
- [x] Guard de host testado contra os dois exemplos de bypass por user-info
      (`http://localhost:8085@host-externo`, `http://usuario:senha@localhost:8085`)
      — ambos recusados — e contra `http://[::1]:porta`, aceito e conectando
      normalmente
- [x] Falha de `setup()` após a criação do livro (seção/capítulo/cena)
      testada com fault injection: limpeza automática do livro órfão
      confirmada, erro original preservado, `k6 run` sai com código
      diferente de zero
- [x] Falha de `teardown()` testada com fault injection (livro já ausente):
      `k6 run` sai com código diferente de zero em vez de só logar
- [x] `teardown()` remove o livro sintético nas execuções normais —
      confirmado sem resíduo `LOADTEST-` após todas as execuções (smoke, 10
      VUs, 30 VUs, e os dois testes de fault injection acima)
- [x] `loadtest/resultado.json` e `loadtest/resultados/*.json` sem cookies,
      credenciais ou conteúdo de cena (sanitização automática via
      `handleSummary()`, não um passo manual)
- [x] Resultados reproduzíveis: mesmo script, mesma stack local, mesma
      violação de threshold em `save_scene` nas duas execuções
