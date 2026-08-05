# Teste de Carga e Performance (k6) — issue #129

Cenário realista contra a API real do IWrite, autenticado com a sessão de servidor
introduzida na PR #139 (cookie `JSESSIONID` + CSRF de duplo envio via `XSRF-TOKEN`).
Substitui o teste anterior, que só exercitava `/ping`.

> ⚠️ **Rode SEMPRE contra o seu ambiente LOCAL.** O script recusa qualquer
> `BASE_URL` que não resolva para `localhost`, `127.0.0.1`, `::1`,
> `host.docker.internal` ou `backend` — ver [Segurança de destino](#segurança-de-destino).
> **Nunca** aponte para Render, produção ou o servidor acadêmico compartilhado
> (`https://eqNN.dsc.rodrigor.com`): o Postgres é compartilhado com outras equipes.

---

## 1. O que o teste faz

Fluxo por VU, uma vez por iteração:

1. `GET /api/books` — tag `operation=list_books`
2. `GET /api/books/{bookId}/outline` — tag `operation=load_outline`
3. `PATCH /api/scenes/{sceneId}/content` — tag `operation=save_scene`
4. think time curto (0.3–1s, configurável)

Sem chamadas de IA. Cada VU escreve **somente na própria cena** (uma cena por VU
máximo, criada no `setup()`), controlando `contentRevision` e gerando um
`operationId` novo a cada `PATCH` — exatamente o que a UI real faz ao salvar.

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
`localhost`, `127.0.0.1`, `::1`, `host.docker.internal`, `backend`. Qualquer
outro host faz o script abortar **antes de criar qualquer dado ou logar**, com
uma mensagem explicando como liberar deliberadamente:

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

`teardown()` apaga o livro (cascata apaga seção/capítulo/cenas via
`CascadeType.ALL` + `orphanRemoval`). Se o teste for interrompido (Ctrl+C, k6
morto, timeout) antes do `teardown()` rodar, limpe manualmente:

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
  -e VUS=10 --summary-export=loadtest/resultados/resultado-10vus.json \
  loadtest/carga.js
node loadtest/scrub-summary.js loadtest/resultados/resultado-10vus.json
```

### Carga ampliada — 30 VUs

```bash
k6 run -e BASE_URL=http://localhost:8085 -e LOAD_TEST_PASSWORD=$IWRITE_DEMO_AUTOR_A_PASSWORD \
  -e VUS=30 --summary-export=loadtest/resultados/resultado-30vus.json \
  loadtest/carga.js
node loadtest/scrub-summary.js loadtest/resultados/resultado-30vus.json
```

**Sempre rode `scrub-summary.js` antes de commitar um resultado.** O
`--summary-export` do k6 inclui integralmente o retorno de `setup()` no campo
`setup_data` — isto é, o cookie de sessão e o token CSRF usados na execução. O
script remove esses campos e mantém só `runId`/`sceneCount`. Sem esse passo
você comita uma sessão viva (ainda que efêmera) no repositório.

`loadtest/resultado.json` é uma cópia do resultado de 10 VUs, mantida pela
convenção anterior de entrega (raiz de `loadtest/`).

---

## 7. Thresholds

```text
http_req_failed          < 1%
checks                   > 99%
http_req_duration p(95)  < 500ms   (global)
http_req_duration p(95)  < 500ms   (por operação: list_books, load_outline, save_scene)
```

Qualquer violação faz o `k6 run` sair com código diferente de zero — apropriado
para gate de CI/CD.

---

## 8. Como ler o resultado

- **`http_req_duration{operation:...}`** — cada operação tem sua própria série
  de percentis (`avg`, `min`, `med`=p50, `p(90)`, `p(95)`, `p(99)`, `max`),
  graças às tags fixas e a `summaryTrendStats` configurado no script. IDs,
  título, usuário, tenant e conteúdo **não** são usados como tag — só o nome
  da operação.
- **`http_reqs.rate`** — RPS agregado do run inteiro (inclui as 3 operações).
- **`checks`** — cada uma das 3 asserções de status por operação.
- Para investigar a operação mais lenta (`save_scene`, ver §9), use a
  observabilidade já existente do projeto (OTel + Loki/Tempo/Grafana via
  `docker-compose.observability.yml`) e procure os eventos de negócio
  `scene_content_save` no serviço do backend — ver
  [`docs/otel-correlated-logs.md`](../docs/otel-correlated-logs.md) para o
  formato dos logs estruturados.

---

## 9. Resultados obtidos

Execuções reais, ambiente local isolado (containers dedicados, ver
limitações abaixo) — arquivos completos em
[`resultados/resultado-10vus.json`](resultados/resultado-10vus.json) e
[`resultados/resultado-30vus.json`](resultados/resultado-30vus.json).

| | Commit | k6 | VUs | Duração | RPS | p50 (ms) | p90 (ms) | p95 (ms) | p99 (ms) | Erros | Checks |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Baseline | `c9921c9` | v2.1.0 | 10 | 3m (30s/2m/30s) | 31.3 | 25.4 | 90.3 | 122.6 | 232.4 | 0% (0/5870) | 100% (5853/5853) |
| Carga ampliada | `c9921c9` | v2.1.0 | 30 | 3m (30s/2m/30s) | 83.8 | 30.3 | 181.1 | 286.0 | 560.9 | 0% (0/15676) | 100% (15639/15639) |

Por operação (p95, ms):

| Operação | 10 VUs | 30 VUs |
|---|---|---|
| `list_books` | 61.7 | 180.5 |
| `load_outline` | 44.3 | 127.9 |
| `save_scene` | **190.0** | **420.8** |

Todos os thresholds configurados (global e por operação) passaram nas duas
execuções.

**Gargalo principal:** `PATCH /api/scenes/{id}/content` (`save_scene`) é
consistentemente a operação mais lenta — cerca de 3× o `list_books` em ambas
as cargas — e a que mais degrada com o aumento de VUs (p95 sobe 2.2× de 10
para 30 VUs, contra ~2× nas leituras). Esperado: é a única escrita do
cenário, e passa por auditoria (`@AuditedOperation`), versionamento de cena e
ledger de contagem de palavras — mais trabalho por requisição que os `GET`s.
Não foi isolado neste PR qual dessas etapas domina o custo; a investigação via
Loki/Tempo (eventos `scene_content_save`) fica como próxima ação.

**Limitações desta execução:**
- Rodada em uma stack Docker isolada só para este teste (`docker-compose -p
  iwrite-k6`, portas remapeadas), na mesma máquina de desenvolvimento
  concorrendo com outros containers (outro worktree do IWrite + um projeto
  não relacionado) — os números absolutos não refletem hardware dedicado.
- Backend, Postgres e k6 rodam na mesma máquina (sem separação de rede/CPU
  entre gerador de carga e alvo), então parte da latência medida pode ser
  contenção local, não custo real de rede.
- Sem OTel habilitado durante a execução (evita adicionar overhead de
  instrumentação à medição); a investigação do gargalo via traces é um passo
  separado, não feito neste PR.
- `max` de ambas as execuções (5.1–5.4s) aparece só no `http_req_duration`
  agregado, fora das séries por operação — provavelmente uma requisição de
  setup (criação de livro/seção/capítulo/cenas, fora do loop principal) atingida
  por cold start do container; não investigado a fundo.

**Próxima ação recomendada:** rodar o teste com OTel habilitado
(`docker-compose.observability.yml`) e usar os traces correlacionados de
`scene_content_save` para decompor o tempo do `PATCH` entre auditoria,
versionamento e ledger de palavras, e então decidir se algum desses passos
pode sair do caminho síncrono do save.

---

## 10. Validado

- [x] `k6 inspect loadtest/carga.js` sem erros
- [x] Login + CSRF reais (não mockados) contra o backend com o profile `demo`
- [x] `teardown()` remove o livro sintético — confirmado sem resíduo
      `LOADTEST-` após as 3 execuções (`GET /api/books` só retorna os livros
      seed dos dois autores demo)
- [x] `loadtest/resultado.json` e `loadtest/resultados/*.json` sem cookies,
      credenciais ou conteúdo de cena (ver `loadtest/scrub-summary.js`)
- [x] Resultados reproduzíveis: mesmo script, mesma stack local, thresholds
      passam de forma consistente
