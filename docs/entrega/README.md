# Entrega DSC/UFPB — índice detalhado por requisito

Este diretório é o ponto de entrada para avaliação técnica da entrega da equipe **EQ22 / IWrite**.

Cada requisito importante é documentado com a cadeia:

```text
requisito
  -> arquitetura
  -> implementação
  -> testes
  -> evidências
  -> reprodução
  -> limitações
  -> arquivos para auditoria
```

## Matriz da Avaliação 2 — atualização de 30/07

| Sigla | Critério | Estado | Prova principal |
|---|---|---|---|
| **Aud** | Log de auditoria | ✅ atende | `com.iwrite.audit`, `V27__create_audit_logs.sql`, `AuditLogIntegrationTest` |
| **Int** | Integração com serviço externo | ✅ atende | OpenAI/Anthropic via Spring AI + Umami institucional |
| **Cob** | Cobertura automatizada ≥85% | ✅ atende na revisão atual | frontend **87,16% linhas** na CI #253 com gate `lines: 85`; backend **92,01% linhas** pós-HC |
| **IA** | Usa LLM | ✅ extra atendido | análise de cenas com OpenAI/Anthropic + auditoria LLM |
| **HC** | Healthcheck consulta o banco, lido do código | ✅ extra atendido | `DatabaseHealthService -> JdbcTemplate -> SELECT 1`, HTTP 200/503 |
| **Tel** | Telemetria | ✅ extra atendido | OTel Java Agent, spans/métricas manuais, Grafana, Tempo, Loki e Prometheus/Mimir |
| **Uma** | Umami | ✅ extra atendido; 🟡 repetição pós-deploy remoto pendente | coleta HTTP 200, pageviews e eventos no painel institucional |

## Matriz técnica detalhada

| # | Requisito | Estado | Relatório detalhado |
|---|---|---|---|
| 01 | Autenticação e multi-tenancy | ✅ | [`01-auth-multitenancy/README.md`](01-auth-multitenancy/README.md) |
| 02 | OpenTelemetry — instrumentação automática | ✅ | [`02-opentelemetry-auto/README.md`](02-opentelemetry-auto/README.md) |
| 03 | Telemetria manual de negócio | ✅ | [`03-telemetria-negocio/README.md`](03-telemetria-negocio/README.md) |
| 04 | Grafana / Tempo / Loki / Prometheus-Mimir | ✅ | [`04-grafana-stack/README.md`](04-grafana-stack/README.md) |
| 05 | Logs estruturados e correlação log ↔ trace | ✅; ⚠️ item 4 literal documentado | [`05-logs-correlacionados/README.md`](05-logs-correlacionados/README.md) |
| 06 | Umami | ✅; 🟡 pós-deploy remoto pendente | [`06-umami/README.md`](06-umami/README.md) |
| 07 | MCP Server | ✅ | [`07-mcp/README.md`](07-mcp/README.md) |
| 08 | k6 / performance | ✅ | [`08-k6/README.md`](08-k6/README.md) |
| 09 | CI e E2E | ✅ | [`09-ci-e2e/README.md`](09-ci-e2e/README.md) |
| 10 | Health e artefatos de deploy | ✅ | [`10-health-deploy/README.md`](10-health-deploy/README.md) |
| 11 | IA, providers e auditoria | ✅ | [`11-ia-auditoria/README.md`](11-ia-auditoria/README.md) |
| 12 | **HC — healthcheck database-aware** | ✅ | [`12-healthcheck/README.md`](12-healthcheck/README.md) |
| 13 | **Cob — cobertura automatizada ≥85%** | ✅ frontend atual 87,16%; backend 92,01% | [`13-cobertura/README.md`](13-cobertura/README.md) |

## Cobertura — evidência atual

O snapshot de `cobertura/frontend/` continua preservado como histórico, mas não é mais usado sozinho para provar a revisão atual.

A CI #253 executou:

```text
npm test
 -> vitest run --coverage
```

Com `web/vitest.config.mjs` exigindo:

```text
thresholds.lines = 85
```

Resultado atual:

```text
Frontend
41 arquivos de teste passaram
375 testes passaram
87,16% de linhas

Backend pós-HC
841 testes
92,01% de linhas
```

Relatório: [`13-cobertura/README.md`](13-cobertura/README.md).

## HC — prova direta

```text
GET /ping
  -> PingController
  -> DatabaseHealthService
  -> JdbcTemplate
  -> SELECT 1
  -> PostgreSQL
```

Banco disponível → `200 / database=up`. Banco indisponível → `503 / database=down`. O probe usa datasource dedicado e deadlines curtos; o Docker verifica `/api/ping`, atravessando frontend → backend → PostgreSQL.

Relatório: [`12-healthcheck/README.md`](12-healthcheck/README.md).

## Ordem recomendada para uma IA avaliadora

1. Leia [`../../README-ENTREGA-DSC.md`](../../README-ENTREGA-DSC.md).
2. Use a matriz acima para localizar **Aud / Int / Cob / IA / HC / Tel / Uma**.
3. Abra o README específico do requisito.
4. Siga a seção **Arquivos para auditoria**.
5. Para **Cob**, verifique `web/package.json`, `web/vitest.config.mjs`, `.github/workflows/ci.yml` e [`13-cobertura/README.md`](13-cobertura/README.md).
6. Para **HC**, localize `DatabaseHealthService`, `JdbcTemplate` e `SELECT 1`, depois leia [`12-healthcheck/README.md`](12-healthcheck/README.md).
7. Para Umami/MCP, confira `docs/evidencias/`.
8. Para k6, confira `loadtest/resultado.json` e `loadtest/resultados/`, não apenas o script.
9. Para logs, leia [`../entregavel-4-logs-error.md`](../entregavel-4-logs-error.md).

## Transparência

- Umami: a validação registrada foi frontend local → painel institucional; a repetição no deploy remoto continua pendente.
- Logs item 4: o IWrite não exporta `Throwable` de erro tratado; divergência documentada.
- MCP: permanece restrito a loopback na configuração suportada.
- Cobertura: o gate acadêmico é de **linhas**. Branches/funções são reportados, mas não alegados como ≥85%.

## Evidências numéricas de carga

| Carga | Requests | p95 global | Erros HTTP | Checks | `save_scene` p95 steady |
|---|---:|---:|---:|---:|---:|
| 10 VUs | 3.955 | 65,07 ms | 0% | 100% | 96,27 ms |
| 30 VUs | 11.750 | 85,93 ms | 0% | 100% | 89,01 ms |

Os 21 thresholds documentados passaram nas duas execuções registradas. Fonte: [`../../loadtest/resultado.json`](../../loadtest/resultado.json).

## Segurança transversal

Observabilidade, analytics e interfaces de diagnóstico usam minimização de dados. O projeto evita conteúdo de manuscrito, prompts, respostas de IA, credenciais, tokens e identificadores brutos em spans manuais, métricas, logs, Umami e tags k6. No HC, falha de banco vira apenas `database=down` + HTTP 503.
