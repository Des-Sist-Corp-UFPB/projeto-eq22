# Requisito 10 — Health, containerização e artefatos de deploy

## 1. Objetivo

Documentar a base de execução e deploy do IWrite: Dockerfiles, Compose, entrypoint, proxy frontend/backend, probe público e integração do health do container com as dependências obrigatórias da aplicação.

O requisito acadêmico específico **HC — “healthcheck consulta o banco, lido do código”** possui agora um relatório próprio e muito mais detalhado em:

**[`../12-healthcheck/README.md`](../12-healthcheck/README.md)**

Este documento concentra a visão de deploy e mostra como o HC se encaixa na topologia completa.

---

## 2. Estado

**✅ Implementado e testado.**

Artefatos principais:

```text
Dockerfile
web/Dockerfile
docker-compose.yml
docker/start.sh
GET /ping
/api/ping -> BACKEND_ORIGIN/ping
DatabaseHealthService
```

O `/ping` deixou de ser um liveness superficial. Agora ele consulta PostgreSQL com `SELECT 1`, retorna `200/up` quando o banco responde e `503/down` quando a consulta falha.

---

## 3. Topologia principal

```text
Browser / Docker HEALTHCHECK
        |
        v
Next.js frontend
        |
        | /api/* rewrite
        v
Spring Boot backend
        |
        v
PostgreSQL 16
```

Capacidades opcionais conectadas ao backend/frontend:

```text
OpenTelemetry Java Agent -> Grafana / Tempo / Loki / Mimir
Spring AI                -> OpenAI / Anthropic
Next.js                   -> Umami
MCP                       -> loopback em configuração suportada
```

O deploy normal não depende de Grafana, Umami, MCP ou provider LLM para responder ao healthcheck.

---

## 4. Backend containerizado

O backend usa Java 21 e é empacotado pelo `Dockerfile`/entrypoint versionado.

A observabilidade é opcional:

- sem `IWRITE_OTEL_ENABLED`, o backend funciona sem collector;
- com OTel habilitado, `docker/start.sh` valida a configuração e anexa o Java Agent;
- tokens/headers OTLP não devem ser impressos pelo entrypoint.

O backend normal continua usando seu datasource principal para operações de produto. O healthcheck possui datasource/pool dedicado para evitar que um probe lento altere os limites do pool de negócio.

---

## 5. Frontend containerizado

O frontend possui `web/Dockerfile` separado e runtime Next.js.

A comunicação browser -> backend permanece em mesma origem lógica por meio de rewrite server-side.

Configuração principal:

```text
BACKEND_ORIGIN
```

O backend origin não precisa ser exposto diretamente ao navegador.

---

## 6. Docker Compose

`docker-compose.yml` organiza:

```text
PostgreSQL
backend
frontend
```

O PostgreSQL possui seu próprio healthcheck com `pg_isready`.

O backend depende do banco saudável durante startup, mas isso não substitui o HC da aplicação: `pg_isready` prova que o container PostgreSQL responde; `/ping` prova que **o IWrite consegue realizar um round trip SQL**.

Overlays existentes:

```text
docker-compose.observability.yml
docker-compose.demo.yml
docker-compose.loadtest.yml
docker-compose.e2e.yml
```

Essa separação evita acoplar demo, carga, observabilidade ou E2E ao deploy normal.

---

## 7. Endpoint `/ping`

Endpoint público:

```http
GET /ping
```

Fluxo atual:

```text
PingController
 -> DatabaseHealthService
 -> JdbcTemplate
 -> SELECT 1
 -> PostgreSQL
```

Contrato saudável:

```text
HTTP 200
status=ok
database=up
```

Contrato degradado:

```text
HTTP 503
status=unavailable
database=down
```

O corpo não expõe mensagem JDBC, URL, host, senha, stack trace ou detalhes do pool.

Para a prova acadêmica completa de HC, consulte [`../12-healthcheck/README.md`](../12-healthcheck/README.md).

---

## 8. Por que o healthcheck não usa rota protegida

Rotas de domínio exigem sessão, tenant e autorização. Um probe de infraestrutura não deve depender de:

- credencial demo;
- cookie;
- membership;
- CSRF;
- livro/cena existente.

Por isso `/ping` permanece `permitAll`, sem tornar nenhuma API de negócio pública.

---

## 9. Rewrite `/api/ping`

`web/next.config.ts` possui regra específica:

```text
/api/ping -> BACKEND_ORIGIN/ping
```

Ela aparece antes do catch-all `/api/:path*`.

Isso permite testar o backend através da mesma topologia frontend -> backend usada pela aplicação/container.

---

## 10. Docker HEALTHCHECK

O review da implementação database-aware revelou um problema importante: o `Dockerfile` principal ainda verificava o antigo `/health` do frontend, que retornava `200` sem consultar backend nem PostgreSQL.

O finding foi corrigido.

O `Dockerfile` agora verifica:

```text
http://127.0.0.1:8080/api/ping
```

Portanto:

```text
Docker HEALTHCHECK
 -> frontend
 -> /api/ping
 -> rewrite
 -> backend /ping
 -> SELECT 1
 -> PostgreSQL
```

Falha de frontend, backend ou banco pode tornar a imagem combinada unhealthy.

---

## 11. Timeouts do healthcheck

A validação manual inicial mostrou que, com PostgreSQL indisponível, o probe podia aguardar aproximadamente o timeout padrão de aquisição de conexão do Hikari.

Após finding de review, o HC recebeu datasource/pool dedicado com limites curtos de:

- aquisição de conexão;
- validação Hikari;
- `connectTimeout` PostgreSQL;
- `socketTimeout` PostgreSQL;
- query timeout do `JdbcTemplate`.

O pool principal da aplicação não foi reduzido.

Detalhes e testes: [`../12-healthcheck/README.md`](../12-healthcheck/README.md).

---

## 12. `BACKEND_ORIGIN`

O Next.js resolve o backend server-side.

Benefícios:

- backend interno não precisa ser URL pública do browser;
- cookies/sessão permanecem coerentes com a estratégia same-origin;
- ambientes Docker podem trocar o destino por configuração;
- configuração inválida falha de forma explícita.

`NEXT_PUBLIC_API_URL` permanece apenas como compatibilidade legada quando aplicável; a configuração preferencial é `BACKEND_ORIGIN`.

---

## 13. CORS

Mesmo com rewrite, o backend continua aplicando sua política CORS.

Uma nova origem pública deve ser refletida em:

```text
APP_CORS_ALLOWED_ORIGINS
```

Isso evita o cenário em que chamadas server-to-server funcionam, mas o navegador recebe `403` por origem inválida.

---

## 14. Entrypoint

Arquivo:

```text
docker/start.sh
```

Responsabilidades incluem startup do backend e ativação opcional do Java Agent.

Teste automático:

```bash
sh docker/start.test.sh
```

A CI executa essa validação.

---

## 15. E2E

O workflow E2E espera `/ping` antes de iniciar Playwright.

Com HC database-aware, “backend pronto” significa agora:

1. servidor Spring responde;
2. datasource do probe consegue conexão;
3. `SELECT 1` conclui.

Isso reduz falsos positivos de startup.

---

## 16. k6

O k6 usa `/ping` como smoke inicial, não como benchmark representativo.

Isso é intencional:

```text
/ping = gate de saúde antes da carga
rotas reais = carga principal
```

O cenário real de carga está detalhado em [`../08-k6/README.md`](../08-k6/README.md).

---

## 17. Observabilidade

A stack OTel/Grafana é opcional ao deploy normal.

Ambiente local de observabilidade:

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d --build
```

A indisponibilidade de Grafana/Tempo/Loki/Mimir não deve transformar `/ping` em falha, pois esses componentes não são dependências obrigatórias para o produto servir escrita.

---

## 18. Serviços externos e health

O HC **não chama** OpenAI, Anthropic, Umami ou MCP.

Motivo:

- LLM é opcional;
- Umami é analytics;
- MCP é opcional e restrito na configuração suportada;
- health profundo de terceiros tornaria o probe lento/frágil;
- o critério acadêmico específico pede consulta ao banco.

PostgreSQL é a dependência obrigatória verificada pelo HC.

---

## 19. Secrets

Configurações sensíveis entram por ambiente/secret manager.

Não devem ser versionados:

- tokens OTLP;
- chaves OpenAI/Anthropic;
- senhas reais;
- credenciais administrativas;
- segredos de deploy.

O healthcheck também não devolve esses dados ao cliente em caso de falha.

---

## 20. Reprodutibilidade

Stack básica:

```bash
docker compose up -d --build
```

Parar:

```bash
docker compose down
```

Backend direto:

```bash
curl -i http://localhost:8085/ping
```

Via frontend/rewrite:

```bash
curl -i http://localhost:3000/api/ping
```

---

## 21. Testes do HC associados ao deploy

Arquivos:

```text
src/test/java/com/iwrite/health/controller/PingControllerTest.java
src/test/java/com/iwrite/health/controller/PingControllerIntegrationTest.java
src/test/java/com/iwrite/health/service/DatabaseHealthServiceTest.java
```

A validação final da implementação registrou 841 testes backend, zero falhas/erros, cobertura total de linhas acima de 85% e 100% das linhas do pacote de health naquele run.

A CI do head corrigido passou backend e frontend/build.

---

## 22. Findings de review que melhoraram o deploy

Dois achados do Codex foram incorporados antes do merge:

**P1:** Docker health ainda apontava para `/health` frontend-only.  
**Correção:** passou a usar `/api/ping` e atravessar frontend/backend/banco.

**P2:** probe poderia aguardar ~30 s quando banco estivesse indisponível.  
**Correção:** datasource/pool exclusivo com deadlines curtos de pool, driver e statement.

Depois das correções, nova revisão automatizada não encontrou problemas relevantes.

---

## 23. O que uma IA avaliadora deve verificar

1. `Dockerfile` e `web/Dockerfile` existem.
2. `docker-compose.yml` contém db/backend/frontend.
3. `web/next.config.ts` possui rewrite `/api/ping`.
4. `PingController` expõe `/ping` público.
5. `DatabaseHealthService` executa `SELECT 1`.
6. falha de banco resulta em HTTP 503.
7. `Dockerfile` usa `/api/ping` no `HEALTHCHECK`.
8. o pool do probe é separado e limitado.
9. testes unitários e de integração cobrem o comportamento.
10. para o critério HC, leia obrigatoriamente [`../12-healthcheck/README.md`](../12-healthcheck/README.md).

---

## 24. Arquivos para auditoria

```text
Dockerfile
web/Dockerfile
docker/start.sh
docker/start.test.sh
docker-compose.yml
docker-compose.observability.yml
docker-compose.demo.yml
docker-compose.loadtest.yml
docker-compose.e2e.yml
web/next.config.ts
src/main/java/com/iwrite/health/controller/PingController.java
src/main/java/com/iwrite/health/service/DatabaseHealthService.java
src/main/java/com/iwrite/auth/SecurityConfig.java
src/test/java/com/iwrite/health/controller/PingControllerTest.java
src/test/java/com/iwrite/health/controller/PingControllerIntegrationTest.java
src/test/java/com/iwrite/health/service/DatabaseHealthServiceTest.java
.github/workflows/ci.yml
.github/workflows/e2e.yml
```

---

## 25. Conclusão

A camada de deploy do IWrite possui containerização reproduzível, proxy frontend/backend, configuração por ambiente, entrypoint testado e health integrado às dependências obrigatórias.

O antigo conceito de `/ping` como simples liveness foi superado. O estado atual verifica PostgreSQL de verdade, retorna `503` quando necessário e participa do `Docker HEALTHCHECK` da imagem combinada.

Para a auditoria acadêmica específica do extra **HC**, o documento canônico é:

**[`docs/entrega/12-healthcheck/README.md`](../12-healthcheck/README.md)**
