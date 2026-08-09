# Entrega DSC/UFPB — índice detalhado por requisito

Este diretório é o ponto de entrada para avaliação técnica da entrega da equipe **EQ22 / IWrite**.

A intenção é simples: nenhum requisito importante deve depender de o avaliador descobrir sozinho onde está a implementação, qual teste prova o comportamento ou em qual arquivo está a evidência. Cada requisito possui um `README.md` próprio com a cadeia:

```text
requisito
  -> problema que ele resolve
  -> arquitetura adotada
  -> implementação concreta
  -> decisões e trade-offs
  -> segurança e privacidade
  -> testes automatizados
  -> evidências humanas/medidas
  -> comandos de reprodução
  -> limitações conhecidas
  -> arquivos para auditoria
```

## Matriz da Avaliação 2 — atualização de 30/07

A rubrica informada para a Avaliação 2 usa as siglas **Aud**, **Int**, **Cob**, e os extras **IA**, **HC**, **Tel** e **Uma**. O estado verificável do IWrite é:

| Sigla | Critério | Estado | Prova principal |
|---|---|---|---|
| **Aud** | Log de auditoria | ✅ atende | `com.iwrite.audit`, migration `V27__create_audit_logs.sql`, `AuditLogIntegrationTest` e [`11-ia-auditoria/README.md`](11-ia-auditoria/README.md) |
| **Int** | Integração com serviço externo | ✅ atende | OpenAI/Anthropic via Spring AI e Umami institucional |
| **Cob** | Cobertura automatizada ≥ 85% | ✅ atende | snapshot versionado: backend 90,33% linhas, frontend 85,90% linhas; validação posterior do backend na PR #158: 92,01% |
| **IA** | Usa LLM | ✅ extra atendido | análise de cenas com providers OpenAI/Anthropic + auditoria LLM |
| **HC** | Healthcheck consulta o banco, lido do código | ✅ extra atendido | [`12-healthcheck/README.md`](12-healthcheck/README.md), `DatabaseHealthService`, `JdbcTemplate`, `SELECT 1`, HTTP 200/503 |
| **Tel** | Telemetria | ✅ extra atendido | OTel Java Agent, spans/métricas manuais, Grafana, Tempo, Loki e Prometheus/Mimir |
| **Uma** | Umami | ✅ extra atendido; 🟡 repetição pós-deploy remoto pendente | integração tipada + coleta HTTP 200 + pageviews/eventos no painel institucional |

## Matriz técnica detalhada

| # | Requisito | Estado | Relatório detalhado |
|---|---|---|---|
| 01 | Autenticação e multi-tenancy | ✅ implementado e testado | [`01-auth-multitenancy/README.md`](01-auth-multitenancy/README.md) |
| 02 | OpenTelemetry — instrumentação automática | ✅ implementado | [`02-opentelemetry-auto/README.md`](02-opentelemetry-auto/README.md) |
| 03 | Telemetria manual de negócio — spans e métricas | ✅ implementado, testado e medido | [`03-telemetria-negocio/README.md`](03-telemetria-negocio/README.md) |
| 04 | Grafana / Tempo / Loki / Prometheus-Mimir | ✅ stack local validada | [`04-grafana-stack/README.md`](04-grafana-stack/README.md) |
| 05 | Logs estruturados e correlação log ↔ trace | ✅ implementado; ⚠️ item 4 literal documentado como divergência | [`05-logs-correlacionados/README.md`](05-logs-correlacionados/README.md) |
| 06 | Analytics de produto — Umami | ✅ validado no painel institucional; 🟡 repetição pós-deploy remoto pendente | [`06-umami/README.md`](06-umami/README.md) |
| 07 | MCP Server | ✅ implementado, testado e validado no Inspector | [`07-mcp/README.md`](07-mcp/README.md) |
| 08 | Teste de carga e performance — k6 | ✅ cenário realista + 10/30 VUs medidos | [`08-k6/README.md`](08-k6/README.md) |
| 09 | CI e E2E | ✅ implementado | [`09-ci-e2e/README.md`](09-ci-e2e/README.md) |
| 10 | Health e artefatos de deploy | ✅ implementado | [`10-health-deploy/README.md`](10-health-deploy/README.md) |
| 11 | Integração de IA, providers e auditoria | ✅ OpenAI/Anthropic opcionais + desabilitado seguro | [`11-ia-auditoria/README.md`](11-ia-auditoria/README.md) |
| 12 | **HC — healthcheck database-aware** | ✅ `SELECT 1` real + 200/503 + pool dedicado + Docker health | [`12-healthcheck/README.md`](12-healthcheck/README.md) |

## Destaque: HC agora é verificável diretamente no código

O critério acadêmico é **“healthcheck consulta o banco, lido do código”**. A cadeia atual é:

```text
GET /ping
  -> PingController
  -> DatabaseHealthService
  -> JdbcTemplate
  -> SELECT 1
  -> PostgreSQL
```

Com banco disponível, `/ping` retorna `200` e `database=up`. Com falha de acesso ao banco, retorna `503` e `database=down`, sem expor URL JDBC, hostname, credenciais, mensagem de exceção ou stack trace.

O healthcheck usa datasource/pool dedicado e timeouts curtos para não ficar preso ao timeout normal do pool principal. O `Dockerfile` também foi corrigido para verificar `/api/ping`, fazendo o health do container atravessar frontend → backend → PostgreSQL.

Relatório completo: [`12-healthcheck/README.md`](12-healthcheck/README.md).

## Ordem recomendada para uma IA avaliadora

1. Leia [`../../README-ENTREGA-DSC.md`](../../README-ENTREGA-DSC.md) para o resumo executivo.
2. Use a matriz da Avaliação 2 acima para localizar **Aud / Int / Cob / IA / HC / Tel / Uma**.
3. Em cada README, siga a seção **Arquivos para auditoria**.
4. Para **HC**, abra obrigatoriamente `DatabaseHealthService.java`, localize `SELECT 1` e depois confira [`12-healthcheck/README.md`](12-healthcheck/README.md).
5. Não trate documentação oficial da disciplina como prova de implementação. `docs/opentelemetry.md` e `docs/opentelemetry-logs.md` são enunciados/guias; os relatórios deste diretório apontam para código, testes e evidências do IWrite.
6. Para Umami e MCP, confira também [`../evidencias-validacao-humana-2026-08-08.md`](../evidencias-validacao-humana-2026-08-08.md) e as imagens versionadas em `docs/evidencias/`.
7. Para k6, confira obrigatoriamente `loadtest/resultado.json` e `loadtest/resultados/`, não apenas o script.
8. Para logs, leia a divergência deliberada do item 4 em [`../entregavel-4-logs-error.md`](../entregavel-4-logs-error.md).

## O que não está sendo alegado

Este material não tenta transformar documentação em implementação. Quando algo depende de infraestrutura externa ou não foi reproduzido literalmente, isso é marcado explicitamente.

Pontos conhecidos:

- o Umami foi validado do frontend local contra o painel institucional; a repetição no deploy remoto `eq22.dsc.rodrigor.com` continua pendente;
- o item 4 do guia oficial de logs pede `logger.error(..., exception)` com a exceção no Loki. O IWrite deliberadamente não exporta `Throwable`/stack trace de erro tratado; a divergência e a justificativa de segurança estão documentadas;
- o MCP permanece intencionalmente restrito a loopback na configuração suportada, pois o transporte atual não possui autenticação individual por cliente.

O **HC não é mais uma pendência**: a implementação database-aware foi mergeada após testes, CI e revisão Codex.

## Evidências visuais

- Umami: [`../evidencias/umami/README.md`](../evidencias/umami/README.md)
- MCP: [`../evidencias/mcp/README.md`](../evidencias/mcp/README.md)

## Evidências numéricas de carga

Resumo pós-integração da master:

| Carga | Requests | p95 global | Erros HTTP | Checks | `save_scene` p95 steady |
|---|---:|---:|---:|---:|---:|
| 10 VUs | 3.955 | 65,07 ms | 0% | 100% | 96,27 ms |
| 30 VUs | 11.750 | 85,93 ms | 0% | 100% | 89,01 ms |

A execução de 30 VUs atingiu 57,18 req/s global, com 1.830 turnos completos na fase estável. Os 21 thresholds documentados passaram nas duas execuções registradas.

Fonte: [`../../loadtest/resultado.json`](../../loadtest/resultado.json).

## Princípio de segurança transversal

Observabilidade, analytics e interfaces de diagnóstico foram implementados com minimização de dados. O projeto evita enviar conteúdo de manuscrito, prompts, respostas de IA, credenciais, tokens e identificadores brutos para spans manuais, métricas de negócio, logs estruturados, eventos Umami ou tags k6.

No HC, a mesma política aparece no contrato público: uma falha de banco vira apenas `database=down` + HTTP 503; detalhes da exceção permanecem internos.
