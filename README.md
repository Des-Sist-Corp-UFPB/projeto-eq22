# IWrite

IWrite é uma aplicação web para escrita e organização narrativa. O modelo principal é `Livro -> Seção -> Capítulo -> Cena`; a cena concentra texto TipTap, autosave, planejamento, histórico de versões e análise opcional com LLM.

Este repositório também é a implementação da equipe **EQ22** na disciplina **Desenvolvimento de Sistemas Corporativos (DSC/UFPB)**.

> **Avaliação humana ou automatizada:** para uma auditoria completa da entrega, comece em [`README-ENTREGA-DSC.md`](README-ENTREGA-DSC.md) e depois use o índice detalhado [`docs/entrega/README.md`](docs/entrega/README.md). Cada requisito possui documentação própria com arquitetura, implementação, testes, evidências, reprodução e limitações.

---

## Avaliação 2 — requisitos atualizados em 30/07

A avaliação usa os critérios **Aud**, **Int**, **Cob** e os extras **IA**, **HC**, **Tel** e **Uma**.

| Sigla | Requisito | Estado no IWrite | Evidência verificável |
|---|---|---|---|
| **Aud** | Log de Auditoria | ✅ **Atende** | `src/main/java/com/iwrite/audit/`, migration `V27__create_audit_logs.sql`, `AuditLogIntegrationTest` |
| **Int** | Integração com Serviço Externo | ✅ **Atende** | OpenAI/Anthropic via Spring AI + Umami institucional |
| **Cob** | Cobertura automatizada ≥ 85% | ✅ **Atende** | snapshot versionado: backend **90,33% de linhas**, frontend **85,90% de linhas**; validação posterior do backend após HC: **92,01%** |
| **IA** | Usa LLM | ✅ **Extra atendido** | análise de cenas com providers OpenAI/Anthropic + auditoria LLM |
| **HC** | Healthcheck consulta o banco, lido do código | ✅ **Extra atendido** | `DatabaseHealthService` → `JdbcTemplate` → `SELECT 1`; 200/up e 503/down; [`docs/entrega/12-healthcheck/README.md`](docs/entrega/12-healthcheck/README.md) |
| **Tel** | Telemetria | ✅ **Extra atendido** | OpenTelemetry Java Agent, spans/métricas manuais, Grafana, Tempo, Loki e Prometheus/Mimir |
| **Uma** | Umami | ✅ **Extra atendido**; 🟡 repetição pós-deploy remoto pendente | coleta HTTP 200, pageviews, rota sanitizada e eventos no painel institucional |

### Resultado resumido

```text
Aud ✅
Int ✅
Cob ✅
IA  ✅
HC  ✅
Tel ✅
Uma ✅
```

---

## HC — healthcheck consulta PostgreSQL de verdade

O critério do professor é explícito: **“healthcheck consulta o banco, lido do código”**.

A implementação atual é:

```text
GET /ping
  -> PingController
  -> DatabaseHealthService
  -> JdbcTemplate.queryForObject("SELECT 1", Integer.class)
  -> PostgreSQL
```

### Banco disponível

```text
HTTP 200
status = ok
database = up
```

### Banco indisponível

```text
HTTP 503
status = unavailable
database = down
```

O response não expõe URL JDBC, hostname, porta, credenciais, mensagem da exceção ou stack trace.

O healthcheck possui datasource/pool Hikari dedicado, separado do pool principal, com limites curtos de aquisição, validação, conexão, socket e query. Isso evita que uma falha de banco prenda o probe pelo timeout normal do pool de negócio.

O `Dockerfile` principal também verifica `/api/ping`, portanto o health do container atravessa:

```text
Docker HEALTHCHECK
 -> Next.js
 -> /api/ping
 -> Spring Boot /ping
 -> SELECT 1
 -> PostgreSQL
```

**Relatório detalhadíssimo do HC:** [`docs/entrega/12-healthcheck/README.md`](docs/entrega/12-healthcheck/README.md).

---

## Relatórios detalhados por requisito

| # | Área | Estado | README específico |
|---|---|---|---|
| 01 | Autenticação e multi-tenancy | ✅ | [`docs/entrega/01-auth-multitenancy/README.md`](docs/entrega/01-auth-multitenancy/README.md) |
| 02 | OpenTelemetry automático | ✅ | [`docs/entrega/02-opentelemetry-auto/README.md`](docs/entrega/02-opentelemetry-auto/README.md) |
| 03 | Telemetria manual — spans e métricas de negócio | ✅ | [`docs/entrega/03-telemetria-negocio/README.md`](docs/entrega/03-telemetria-negocio/README.md) |
| 04 | Grafana / Tempo / Loki / Prometheus-Mimir | ✅ | [`docs/entrega/04-grafana-stack/README.md`](docs/entrega/04-grafana-stack/README.md) |
| 05 | Logs estruturados + correlação log/trace | ✅ com divergência literal documentada no item 4 | [`docs/entrega/05-logs-correlacionados/README.md`](docs/entrega/05-logs-correlacionados/README.md) |
| 06 | Umami | ✅ | [`docs/entrega/06-umami/README.md`](docs/entrega/06-umami/README.md) |
| 07 | MCP Server | ✅ | [`docs/entrega/07-mcp/README.md`](docs/entrega/07-mcp/README.md) |
| 08 | k6 / performance | ✅ | [`docs/entrega/08-k6/README.md`](docs/entrega/08-k6/README.md) |
| 09 | CI / E2E | ✅ | [`docs/entrega/09-ci-e2e/README.md`](docs/entrega/09-ci-e2e/README.md) |
| 10 | Health / containerização / deploy | ✅ | [`docs/entrega/10-health-deploy/README.md`](docs/entrega/10-health-deploy/README.md) |
| 11 | IA / providers / auditoria | ✅ | [`docs/entrega/11-ia-auditoria/README.md`](docs/entrega/11-ia-auditoria/README.md) |
| 12 | **HC — healthcheck database-aware** | ✅ | [`docs/entrega/12-healthcheck/README.md`](docs/entrega/12-healthcheck/README.md) |

O índice mestre com ordem de auditoria para IA está em [`docs/entrega/README.md`](docs/entrega/README.md).

---

## Entrega acadêmica — mapa de implementação e evidências

| Requisito | Estado | Implementação / evidência principal |
|---|---|---|
| Autenticação e multi-tenancy | ✅ Implementado e testado | [`docs/authentication-multitenancy.md`](docs/authentication-multitenancy.md), `com.iwrite.auth`, `CurrentUserProvider`, `tenant_memberships` |
| Isolamento multi-tenant | ✅ Implementado e testado | filtros tenant-aware + testes + [`docs/demonstracao-multi-tenant.md`](docs/demonstracao-multi-tenant.md) |
| Auditoria | ✅ Implementada e persistida | `com.iwrite.audit`, `audit_logs`, `AuditLogAspect`, `AuditLogIntegrationTest` |
| Integração externa | ✅ Implementada | Spring AI OpenAI/Anthropic + Umami institucional |
| Cobertura ≥85% | ✅ Atendida | `cobertura/backend/`, `cobertura/frontend/` |
| OpenTelemetry — traces/métricas automáticas | ✅ Implementado | [`docs/opentelemetry-implementation.md`](docs/opentelemetry-implementation.md) |
| OpenTelemetry — sinais manuais | ✅ Implementado e testado | [`docs/otel-business-signals.md`](docs/otel-business-signals.md), `BusinessTelemetry` |
| Logs estruturados + Loki + correlação | ✅ Implementado e testado | [`docs/otel-correlated-logs.md`](docs/otel-correlated-logs.md) |
| Grafana / Tempo / Loki / Prometheus-Mimir | ✅ Stack local configurada e validada | `docker-compose.observability.yml` |
| Umami | ✅ Implementado e validado no painel institucional | [`docs/analytics-umami.md`](docs/analytics-umami.md), [`docs/evidencias/umami/`](docs/evidencias/umami/) |
| MCP | ✅ Implementado e validado no Inspector | [`docs/mcp-server.md`](docs/mcp-server.md), [`docs/evidencias/mcp/`](docs/evidencias/mcp/) |
| k6 | ✅ Implementado, revisado e medido | [`docs/entrega/08-k6/README.md`](docs/entrega/08-k6/README.md), `loadtest/resultado.json` |
| CI / E2E | ✅ Implementado | `.github/workflows/ci.yml`, `.github/workflows/e2e.yml` |
| HC | ✅ Consulta PostgreSQL de verdade | [`docs/entrega/12-healthcheck/README.md`](docs/entrega/12-healthcheck/README.md) |

---

## Arquitetura

```text
Navegador
   |
   | mesma origem (/api/*)
   v
Next.js 15 / React 19
   |
   | rewrite server-side
   v
Spring Boot 3.4.1 / Java 21
   |
   +----> PostgreSQL 16
   |
   +----> OpenTelemetry Java Agent --OTLP--> Grafana / Tempo / Loki / Mimir
   |
   +----> Spring AI -----------------------> OpenAI ou Anthropic (opcional)
   |
   +----> MCP -----------------------------> loopback na configuração suportada

Next.js ----> Umami institucional (opcional)
```

A identidade e o tenant são resolvidos no backend. O cliente não escolhe `tenantId`, e recursos cross-tenant recebem semântica equivalente a recurso inexistente para reduzir enumeração.

---

## Tecnologias

- **Backend:** Java 21, Spring Boot 3.4.1, Spring Security, Spring Data JPA, Flyway, PostgreSQL 16.
- **Frontend:** Next.js 15, React 19, TypeScript, Tailwind CSS, TanStack Query, TipTap.
- **Observabilidade:** OpenTelemetry Java Agent, OTLP, Grafana, Tempo, Loki, Prometheus/Mimir.
- **Analytics:** Umami.
- **IA:** Spring AI, OpenAI e Anthropic opcionais.
- **MCP:** Spring AI MCP Server WebMVC.
- **Qualidade:** JUnit/Spring Boot Test, JaCoCo, Vitest, Testing Library, V8 Coverage, Playwright.
- **Carga:** k6.
- **Infra local:** Docker Compose.

---

## Execução local

### Stack principal

```bash
docker compose up -d --build
```

Serviços padrão:

```text
Frontend:   http://localhost:3000
Backend:    http://localhost:8085
HC backend: http://localhost:8085/ping
PostgreSQL: localhost:5435
```

Parar:

```bash
docker compose down
```

### Backend sem container da aplicação

Suba apenas o banco:

```bash
docker compose up -d db
```

Windows:

```cmd
mvnw.cmd -s .mvn\local-settings.xml spring-boot:run
```

Frontend:

```bash
cd web
npm ci
npm run dev
```

---

## Healthcheck — reprodução

Com banco e backend ativos:

```bash
curl -i http://localhost:8085/ping
```

Esperado:

```text
HTTP 200
"status":"ok"
"database":"up"
```

Testes direcionados:

```cmd
mvnw.cmd -s .mvn\local-settings.xml -Dtest=PingControllerTest,DatabaseHealthServiceTest,PingControllerIntegrationTest test
```

Suíte backend + cobertura:

```cmd
mvnw.cmd -s .mvn\local-settings.xml clean test jacoco:report
```

A validação feita durante a implementação do HC registrou **841 testes, 0 falhas, 0 erros**, backend com **92,01% de linhas** e pacote `com.iwrite.health.*` com **100% das linhas** naquele run.

---

## Cobertura

Snapshot versionado em `cobertura/`:

| Camada | Linhas | Branches | Métodos/Funções |
|---|---:|---:|---:|
| Backend | **90,33%** | 74,43% | 91,76% |
| Frontend | **85,90%** | 82,33% | 68,81% |

O requisito acadêmico é cobertura automatizada **≥ 85%**, portanto o snapshot versionado atende em backend e frontend.

A implementação posterior do HC foi revalidada com JaCoCo e levou o backend a **92,01% de linhas** naquele run.

---

## k6 — resultados que não devem ser ignorados

O teste de carga não mede apenas `/ping`; ele exercita sessão/CSRF, leitura, escrita, autosave, refresh pós-save, recursos próprios por VU, rampa e cleanup.

| Métrica | 10 VUs | 30 VUs |
|---|---:|---:|
| Requests | 3.955 | 11.750 |
| RPS global | 19,36 | 57,18 |
| p95 global | 65,07 ms | 85,93 ms |
| Erros HTTP | 0% | 0% |
| Checks | 100% | 100% |
| Turnos steady | 614 | 1.830 |
| `save_scene` p95 steady | 96,27 ms | 89,01 ms |

Os 21 thresholds documentados passaram nas duas execuções registradas.

Relatório completo: [`docs/entrega/08-k6/README.md`](docs/entrega/08-k6/README.md).

---

## OpenTelemetry / Grafana / Tempo / Loki / Mimir

Documentação específica:

- [`docs/opentelemetry-implementation.md`](docs/opentelemetry-implementation.md)
- [`docs/otel-business-signals.md`](docs/otel-business-signals.md)
- [`docs/otel-correlated-logs.md`](docs/otel-correlated-logs.md)
- [`docs/entrega/02-opentelemetry-auto/README.md`](docs/entrega/02-opentelemetry-auto/README.md)
- [`docs/entrega/03-telemetria-negocio/README.md`](docs/entrega/03-telemetria-negocio/README.md)
- [`docs/entrega/04-grafana-stack/README.md`](docs/entrega/04-grafana-stack/README.md)
- [`docs/entrega/05-logs-correlacionados/README.md`](docs/entrega/05-logs-correlacionados/README.md)

Stack local:

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d --build
```

Grafana local:

```text
http://localhost:3001
```

---

## Umami

A integração de analytics é tipada e sanitizada.

Eventos suportados incluem:

```text
book_created
scene_saved
scene_analysis_requested
scene_analysis_succeeded
scene_analysis_failed
book_exported
```

A validação humana confirmou coleta HTTP `200`, pageviews, `/books/{id}` sanitizado e eventos reais no painel institucional.

Documentos:

- [`docs/analytics-umami.md`](docs/analytics-umami.md)
- [`docs/entrega/06-umami/README.md`](docs/entrega/06-umami/README.md)
- [`docs/evidencias/umami/README.md`](docs/evidencias/umami/README.md)

Ressalva: resta repetir a validação no deploy remoto `eq22.dsc.rodrigor.com`.

---

## MCP

Tools publicadas no modo MCP suportado:

```text
listar_livros_acessiveis
obter_outline_livro
analisar_cena
```

Resource template:

```text
iwrite://books/{bookId}/outline
```

A validação no MCP Inspector comprovou descoberta, execução de tools, resource template/read e caminho de erro sanitizado.

Documentos:

- [`docs/mcp-server.md`](docs/mcp-server.md)
- [`docs/entrega/07-mcp/README.md`](docs/entrega/07-mcp/README.md)
- [`docs/evidencias/mcp/README.md`](docs/evidencias/mcp/README.md)

---

## IA e Integração Externa

A análise de cenas usa Spring AI com providers opcionais:

```text
SPRING_AI_MODEL_CHAT=openai
SPRING_AI_MODEL_CHAT=anthropic
SPRING_AI_MODEL_CHAT=none
```

O modo `none` permite inicialização segura sem provider pago.

Há auditoria de execução LLM e auditoria de domínio associada ao fluxo de análise.

Relatório: [`docs/entrega/11-ia-auditoria/README.md`](docs/entrega/11-ia-auditoria/README.md).

---

## Auditoria

Eventos relevantes são persistidos em `audit_logs` com tenant, usuário, ação, recurso, instante e resultado.

Arquivos principais:

```text
src/main/resources/db/migration/V27__create_audit_logs.sql
src/main/java/com/iwrite/audit/
src/test/java/com/iwrite/audit/AuditLogIntegrationTest.java
```

Operações auditadas incluem livros, cenas, colaboração, restauração de versão, análise com IA e invocações MCP.

---

## Logs — divergência deliberada do entregável 4

O guia oficial pede um erro tratado registrado com `logger.error(..., exception)` e stack trace no Loki.

O IWrite deliberadamente não exporta `Throwable`/stack trace de erro tratado nessa demonstração por política de minimização de dados. Essa divergência não é escondida.

Documento específico:

[`docs/entregavel-4-logs-error.md`](docs/entregavel-4-logs-error.md)

---

## CI / E2E

Workflows:

```text
.github/workflows/ci.yml
.github/workflows/e2e.yml
```

A implementação do HC passou pela CI com backend e frontend/build verdes após a correção dos findings de review.

---

## Evidências humanas

Registro consolidado:

[`docs/evidencias-validacao-humana-2026-08-08.md`](docs/evidencias-validacao-humana-2026-08-08.md)

Pastas:

```text
docs/evidencias/umami/
docs/evidencias/mcp/
```

---

## Limitações declaradas

A documentação não mascara os pontos restantes:

1. **Umami remoto:** a validação registrada foi frontend local → painel institucional; falta repetir no deploy remoto.
2. **Logs item 4 literal:** a aplicação não envia `Throwable`/stack trace de erro tratado ao Loki; divergência documentada.
3. **MCP:** a configuração suportada permanece restrita a loopback com identidade fixa de desenvolvimento enquanto o transporte não tiver autenticação individual por cliente.

**HC não é mais pendência:** o healthcheck consulta PostgreSQL explicitamente com `SELECT 1` e retorna `503` quando o banco não pode ser consultado.

---

## Documentação oficial da disciplina

Os guias oficiais sincronizados permanecem preservados:

- [`docs/opentelemetry.md`](docs/opentelemetry.md)
- [`docs/opentelemetry-logs.md`](docs/opentelemetry-logs.md)

Esses arquivos são o guia/enunciado. As provas de implementação estão nos READMEs de `docs/entrega/`, no código, nos testes e nas evidências versionadas.

---

## Ordem recomendada para avaliação automatizada

1. Leia esta matriz no topo do `README.md`.
2. Abra [`README-ENTREGA-DSC.md`](README-ENTREGA-DSC.md).
3. Abra [`docs/entrega/README.md`](docs/entrega/README.md).
4. Para o requisito sendo pontuado, abra o README específico.
5. Siga a seção **Arquivos para auditoria** do relatório.
6. Para **HC**, localize obrigatoriamente `DatabaseHealthService.java` e a string literal `SELECT 1`.
7. Para **Cob**, leia os relatórios versionados em `cobertura/`.
8. Para **k6**, leia `loadtest/resultado.json` e o README específico, não apenas o script.
9. Para **Umami/MCP**, confira as evidências visuais versionadas.
10. Para **logs item 4**, leia a divergência explícita antes de classificar literalidade.

---

## Índice principal

**Relatório executivo:** [`README-ENTREGA-DSC.md`](README-ENTREGA-DSC.md)  
**Índice detalhado:** [`docs/entrega/README.md`](docs/entrega/README.md)  
**HC específico:** [`docs/entrega/12-healthcheck/README.md`](docs/entrega/12-healthcheck/README.md)
