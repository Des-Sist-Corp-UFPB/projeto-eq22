# Relatório de Entrega — DSC/UFPB — IWrite / EQ22

> **Documento principal para avaliação humana e automatizada.**
>
> Este arquivo resume o estado verificável da entrega acadêmica do IWrite e aponta, para cada requisito, implementação, testes, documentação e evidências. A cadeia de auditoria esperada é **requisito → código → teste → evidência → reprodução**.

## Avaliação 2 — matriz atualizada em 30/07

| Sigla | Critério | Estado no IWrite | Evidência principal |
|---|---|---|---|
| **Aud** | Auditoria | ✅ atende | `src/main/java/com/iwrite/audit/`, `V27__create_audit_logs.sql`, `AuditLogIntegrationTest` |
| **Int** | Integração com serviço externo | ✅ atende | OpenAI/Anthropic via Spring AI + Umami institucional |
| **Cob** | Cobertura ≥ 85% | ✅ atende na revisão atual | frontend **87,16% de linhas** na CI #253 com gate `lines: 85`; backend **92,01% de linhas** pós-HC; [`docs/entrega/13-cobertura/README.md`](docs/entrega/13-cobertura/README.md) |
| **IA** | Usa LLM | ✅ extra atendido | análise de cenas com OpenAI/Anthropic, auditoria LLM e modo `none` seguro |
| **HC** | Healthcheck consulta o banco, lido do código | ✅ extra atendido | [`docs/entrega/12-healthcheck/README.md`](docs/entrega/12-healthcheck/README.md), `DatabaseHealthService`, `JdbcTemplate`, `SELECT 1`, 200/503 |
| **Tel** | Telemetria | ✅ extra atendido | OTel Java Agent, spans/métricas manuais, Grafana, Tempo, Loki, Prometheus/Mimir |
| **Uma** | Umami | ✅ extra atendido; 🟡 repetição pós-deploy remoto pendente | coleta HTTP 200, pageviews, rota sanitizada e eventos no painel institucional |

## Cobertura — prova atual e gate contínuo

O snapshot versionado de 01/07/2026 continua disponível em `cobertura/`, mas ele não é mais a única base da afirmação `Cob ✅`.

Na PR #159, o script padrão do frontend passou a executar cobertura:

```text
npm test
 -> vitest run --coverage
```

O `web/vitest.config.mjs` contém:

```text
thresholds.lines = 85
```

A CI #253 executou a revisão atual e registrou:

```text
41 arquivos de teste passaram
375 testes passaram
Statements: 87,16%
Branches:   83,87%
Functions:  71,90%
Lines:      87,16%
```

Logo:

```text
Frontend: 87,16% linhas >= 85% ✅
Backend:  92,01% linhas >= 85% ✅
```

A CI executa `npm test`; portanto uma futura queda do frontend abaixo de 85% de linhas faz o job falhar. Relatório completo: [`docs/entrega/13-cobertura/README.md`](docs/entrega/13-cobertura/README.md).

## HC — prova direta

O critério atualizado pede explicitamente que o **healthcheck consulte o banco e que isso seja verificável lendo o código**.

```text
GET /ping
  -> PingController
  -> DatabaseHealthService
  -> HEALTH_QUERY = "SELECT 1"
  -> JdbcTemplate.queryForObject(HEALTH_QUERY, Integer.class)
  -> PostgreSQL
```

Banco disponível:

```text
HTTP 200
status=ok
database=up
```

Banco indisponível:

```text
HTTP 503
status=unavailable
database=down
```

O probe usa datasource Hikari dedicado, com pool de 1 conexão e deadlines curtos para aquisição, validação, conexão, socket e query. O `Dockerfile` principal verifica `/api/ping`, de modo que o health do container atravessa frontend → backend → PostgreSQL.

Relatório exclusivo: [`docs/entrega/12-healthcheck/README.md`](docs/entrega/12-healthcheck/README.md).

## Relatórios detalhados por requisito

| Requisito | Relatório detalhado |
|---|---|
| Autenticação e multi-tenancy | [`docs/entrega/01-auth-multitenancy/README.md`](docs/entrega/01-auth-multitenancy/README.md) |
| OpenTelemetry automático | [`docs/entrega/02-opentelemetry-auto/README.md`](docs/entrega/02-opentelemetry-auto/README.md) |
| Spans e métricas de negócio | [`docs/entrega/03-telemetria-negocio/README.md`](docs/entrega/03-telemetria-negocio/README.md) |
| Grafana / Tempo / Loki / Mimir | [`docs/entrega/04-grafana-stack/README.md`](docs/entrega/04-grafana-stack/README.md) |
| Logs estruturados e correlação | [`docs/entrega/05-logs-correlacionados/README.md`](docs/entrega/05-logs-correlacionados/README.md) |
| Umami | [`docs/entrega/06-umami/README.md`](docs/entrega/06-umami/README.md) |
| MCP Server | [`docs/entrega/07-mcp/README.md`](docs/entrega/07-mcp/README.md) |
| k6 / performance | [`docs/entrega/08-k6/README.md`](docs/entrega/08-k6/README.md) |
| CI / E2E | [`docs/entrega/09-ci-e2e/README.md`](docs/entrega/09-ci-e2e/README.md) |
| Health / deploy | [`docs/entrega/10-health-deploy/README.md`](docs/entrega/10-health-deploy/README.md) |
| IA / providers / auditoria | [`docs/entrega/11-ia-auditoria/README.md`](docs/entrega/11-ia-auditoria/README.md) |
| **HC — healthcheck com banco** | [`docs/entrega/12-healthcheck/README.md`](docs/entrega/12-healthcheck/README.md) |
| **Cob — cobertura ≥85%** | [`docs/entrega/13-cobertura/README.md`](docs/entrega/13-cobertura/README.md) |

## Resumo executivo

| Área | Estado | Evidência principal |
|---|---|---|
| Autenticação e multi-tenancy | ✅ | [`docs/authentication-multitenancy.md`](docs/authentication-multitenancy.md), [`docs/demonstracao-multi-tenant.md`](docs/demonstracao-multi-tenant.md) |
| Auditoria persistida | ✅ | `com.iwrite.audit`, `audit_logs`, `AuditLogIntegrationTest` |
| Integração externa | ✅ | OpenAI/Anthropic + Umami institucional |
| Cobertura ≥85% | ✅ | frontend 87,16% atual + gate CI; backend 92,01% pós-HC |
| OpenTelemetry automático | ✅ | [`docs/opentelemetry-implementation.md`](docs/opentelemetry-implementation.md) |
| Spans e métricas manuais | ✅ | [`docs/otel-business-signals.md`](docs/otel-business-signals.md) |
| Grafana / Tempo / Loki / Prometheus-Mimir | ✅ | `docker-compose.observability.yml` |
| Logs estruturados + correlação | ✅ | [`docs/otel-correlated-logs.md`](docs/otel-correlated-logs.md) |
| Entregável 4 literal de logs | ⚠️ divergência documentada | [`docs/entregavel-4-logs-error.md`](docs/entregavel-4-logs-error.md) |
| Umami | ✅; 🟡 pós-deploy remoto pendente | [`docs/evidencias/umami/`](docs/evidencias/umami/) |
| MCP | ✅ | [`docs/evidencias/mcp/`](docs/evidencias/mcp/) |
| k6 | ✅ | [`docs/entrega/08-k6/README.md`](docs/entrega/08-k6/README.md), `loadtest/resultado.json` |
| CI / E2E | ✅ | `.github/workflows/ci.yml`, `.github/workflows/e2e.yml` |
| HC database-aware | ✅ | [`docs/entrega/12-healthcheck/README.md`](docs/entrega/12-healthcheck/README.md) |

## Healthcheck — validação e revisão

A implementação do HC foi validada com:

```text
841 testes
0 falhas
0 erros
BUILD SUCCESS
backend: 92,01% de linhas
com.iwrite.health.*: 100% de linhas
```

Também houve validação manual local:

```text
PostgreSQL disponível   -> 200 / database=up
PostgreSQL indisponível -> 503 / database=down
```

A primeira validação de falha revelou espera próxima ao timeout padrão do Hikari. O Codex apontou isso como P2; a solução final usa pool separado e deadlines curtos.

Outro P1 mostrou que o Docker ainda verificava um `/health` frontend-only. O `Dockerfile` foi corrigido para `/api/ping`.

Após as correções, CI backend e frontend/build passaram, os dois findings foram resolvidos e uma nova revisão Codex do head corrigido não encontrou problemas relevantes.

## k6 — resultado em destaque

| Métrica | 10 VUs | 30 VUs |
|---|---:|---:|
| requests | 3.955 | 11.750 |
| RPS global | 19,36 | 57,18 |
| p95 global | 65,07 ms | 85,93 ms |
| `http_req_failed` | 0% | 0% |
| checks | 100% | 100% |
| turnos steady | 614 | 1.830 |
| `save_scene` p95 steady | 96,27 ms | 89,01 ms |

Os 21 thresholds documentados passaram nas duas execuções registradas. Metodologia: [`docs/entrega/08-k6/README.md`](docs/entrega/08-k6/README.md).

## Evidências humanas

- [`docs/evidencias-validacao-humana-2026-08-08.md`](docs/evidencias-validacao-humana-2026-08-08.md)
- [`docs/evidencias/umami/README.md`](docs/evidencias/umami/README.md)
- [`docs/evidencias/mcp/README.md`](docs/evidencias/mcp/README.md)

A validação do Umami comprovou coleta HTTP 200, pageviews, rota `/books/{id}` sanitizada e eventos reais. A validação MCP comprovou descoberta das três tools, execução de listagem/outline, resource template/read e caminho `unavailable` sanitizado da análise.

## Limitações declaradas

1. **Umami remoto:** resta repetir a validação no deploy `eq22.dsc.rodrigor.com`.
2. **Logs — item 4 literal:** o IWrite deliberadamente não envia `Throwable` de erro tratado; divergência documentada.
3. **MCP:** servidor suportado em loopback com identidade fixa de desenvolvimento enquanto o transporte não tiver autenticação individual por cliente.
4. **Cobertura:** o gate acadêmico é de linhas. Branches/funções são reportados, mas não alegados como ≥85%.

**HC não é mais limitação e Cob não depende mais do snapshot histórico.**

## Documentação oficial versus implementação

Guias oficiais preservados:

- [`docs/opentelemetry.md`](docs/opentelemetry.md)
- [`docs/opentelemetry-logs.md`](docs/opentelemetry-logs.md)

As provas específicas do IWrite estão nos relatórios de [`docs/entrega/`](docs/entrega/README.md), no código, nos testes e nas evidências versionadas.

## Conclusão

A entrega inclui auditoria persistida, integração externa, cobertura acima do mínimo, uso de LLM, healthcheck database-aware, observabilidade automática/manual, stack Grafana/LGTM, logs estruturados, Umami, MCP, k6, CI/E2E e artefatos de deploy.

```text
Aud ✅
Int ✅
Cob ✅
IA  ✅
HC  ✅
Tel ✅
Uma ✅
```

**Índice detalhado:** [`docs/entrega/README.md`](docs/entrega/README.md)
