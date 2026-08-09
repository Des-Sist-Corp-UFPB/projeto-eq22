# Relatório de Entrega — DSC/UFPB — IWrite / EQ22

> **Documento principal para avaliação humana e automatizada.**
>
> Este arquivo resume o estado verificável da entrega acadêmica do IWrite e aponta, para cada requisito, os arquivos de implementação, testes, documentação e evidências existentes no repositório. O objetivo é permitir que um avaliador — inclusive uma IA — percorra diretamente a cadeia **requisito → implementação → teste → evidência**, sem depender de inferências a partir do histórico de commits.

## Relatórios detalhados por requisito

Para auditoria profunda, cada requisito possui um `README.md` próprio em [`docs/entrega/`](docs/entrega/README.md). Esses relatórios detalham arquitetura, implementação, decisões, segurança, testes, evidências, comandos de reprodução, resultados medidos e limitações conhecidas.

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

> **Para avaliação automatizada:** comece em `docs/entrega/README.md`. Cada relatório termina com uma lista de arquivos para auditoria e diferencia claramente evidência observada, comportamento coberto por teste e limitação conhecida.

## Resumo executivo

| Área | Estado | Evidência principal |
|---|---|---|
| Autenticação e multi-tenancy | ✅ Implementado e testado | [`docs/authentication-multitenancy.md`](docs/authentication-multitenancy.md), [`docs/demonstracao-multi-tenant.md`](docs/demonstracao-multi-tenant.md) |
| OpenTelemetry automático | ✅ Implementado | [`docs/opentelemetry-implementation.md`](docs/opentelemetry-implementation.md), `Dockerfile`, `docker/start.sh` |
| Spans e métricas manuais de negócio | ✅ Implementado, testado e medido | [`docs/otel-business-signals.md`](docs/otel-business-signals.md), `BusinessTelemetry` |
| Grafana / Tempo / Loki / Prometheus-Mimir | ✅ Stack local configurada e validada | `docker-compose.observability.yml`, [`docs/opentelemetry-implementation.md`](docs/opentelemetry-implementation.md) |
| Logs estruturados + correlação log/trace | ✅ Implementado e testado | [`docs/otel-correlated-logs.md`](docs/otel-correlated-logs.md) |
| Entregável 4 de logs (`logger.error(..., exception)`) | ⚠️ Divergência deliberada e documentada | [`docs/entregavel-4-logs-error.md`](docs/entregavel-4-logs-error.md) |
| Analytics de produto com Umami | ✅ Implementado e validado no painel institucional; 🟡 repetição pós-deploy remoto pendente | [`docs/analytics-umami.md`](docs/analytics-umami.md), [`docs/evidencias/umami/`](docs/evidencias/umami/) |
| Servidor MCP | ✅ Implementado, testado e validado no Inspector | [`docs/mcp-server.md`](docs/mcp-server.md), [`docs/evidencias/mcp/`](docs/evidencias/mcp/) |
| Teste de carga k6 | ✅ Implementado e medido em 10/30 VUs | [`loadtest/README.md`](loadtest/README.md), [`loadtest/resultado.json`](loadtest/resultado.json) |
| CI / E2E | ✅ Implementado | `.github/workflows/ci.yml`, `.github/workflows/e2e.yml` |
| Health check / artefatos de deploy | ✅ Implementado | `Dockerfile`, `web/Dockerfile`, `GET /ping` |

## Resultado de carga em destaque

O cenário k6 exercita sessão/CSRF real, leitura e escrita do fluxo do editor, um livro/cena por VU, debounce de autosave, refresh pós-save, fases de carga e cleanup. O harness também foi endurecido após múltiplos achados do Codex sobre modelagem, segurança de secrets, fault injection e reprodutibilidade.

| Métrica | 10 VUs | 30 VUs |
|---|---:|---:|
| requests | 3.955 | 11.750 |
| RPS global | 19,36 | 57,18 |
| p95 global | 65,07 ms | 85,93 ms |
| `http_req_failed` | 0% | 0% |
| checks | 100% | 100% |
| autenticação das VUs | 100% | 100% |
| turnos steady | 614 | 1.830 |
| `save_scene` p95 steady | 96,27 ms | 89,01 ms |

Os 21 thresholds documentados passaram nas duas execuções registradas. A metodologia completa, incluindo recuperação de órfãos, status exato, proteção de argv/summaries, rampa e revalidação pós-integração, está em [`docs/entrega/08-k6/README.md`](docs/entrega/08-k6/README.md).

## Evidências humanas

Umami e MCP possuem evidências visuais versionadas e um registro consolidado:

- [`docs/evidencias-validacao-humana-2026-08-08.md`](docs/evidencias-validacao-humana-2026-08-08.md)
- [`docs/evidencias/umami/README.md`](docs/evidencias/umami/README.md)
- [`docs/evidencias/mcp/README.md`](docs/evidencias/mcp/README.md)

A validação do Umami comprovou coleta HTTP 200, page views, rota `/books/{id}` sanitizada e eventos reais. A validação MCP comprovou descoberta das três tools, execução de listagem/outline, resource template/read e caminho `unavailable` sanitizado da análise.

## Limitações declaradas

Este relatório não mascara pendências:

1. **Umami remoto:** a validação registrada usou frontend local enviando ao painel institucional; resta repetir no deploy `eq22.dsc.rodrigor.com`.
2. **Logs — item 4 literal:** o guia oficial pede `logger.error(..., exception)` com stack trace de erro tratado no Loki. O IWrite deliberadamente não envia `Throwable` nesse caso. A divergência está documentada em [`docs/entregavel-4-logs-error.md`](docs/entregavel-4-logs-error.md).
3. **MCP:** o transporte atual não possui autenticação individual por cliente; por isso o servidor só é suportado em loopback com identidade fixa de desenvolvimento e não deve ser publicado por reverse proxy.

## Documentação oficial versus implementação

Os arquivos abaixo são guias oficiais sincronizados da disciplina e permanecem preservados:

- [`docs/opentelemetry.md`](docs/opentelemetry.md)
- [`docs/opentelemetry-logs.md`](docs/opentelemetry-logs.md)

A implementação específica do IWrite é descrita nos demais documentos e, principalmente, nos relatórios em [`docs/entrega/`](docs/entrega/README.md).

## Conclusão

A entrega inclui segurança multi-tenant, observabilidade automática e manual, stack Grafana/LGTM, logs estruturados, analytics Umami, servidor MCP, teste de carga extensivamente revisado, CI/E2E, health/deploy e integração/auditoria de IA. O índice detalhado para verificação é:

**[`docs/entrega/README.md`](docs/entrega/README.md)**
