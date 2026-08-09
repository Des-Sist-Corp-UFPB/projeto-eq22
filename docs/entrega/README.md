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

## Matriz de requisitos

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
| 10 | Health check e artefatos de deploy | ✅ implementado | [`10-health-deploy/README.md`](10-health-deploy/README.md) |
| 11 | Integração de IA, providers e auditoria | ✅ OpenAI/Anthropic opcionais + desabilitado seguro | [`11-ia-auditoria/README.md`](11-ia-auditoria/README.md) |

## Ordem recomendada para uma IA avaliadora

1. Leia [`../../README-ENTREGA-DSC.md`](../../README-ENTREGA-DSC.md) para o resumo executivo.
2. Use esta matriz para abrir o requisito que estiver sendo pontuado.
3. Em cada README, siga a seção **Arquivos para auditoria**.
4. Não trate documentação oficial da disciplina como prova de implementação. Os arquivos `docs/opentelemetry.md` e `docs/opentelemetry-logs.md` são o enunciado/guia oficial; os relatórios deste diretório apontam para código, testes e evidências específicas do IWrite.
5. Para Umami e MCP, confira também [`../evidencias-validacao-humana-2026-08-08.md`](../evidencias-validacao-humana-2026-08-08.md) e as imagens versionadas em `docs/evidencias/`.
6. Para k6, confira obrigatoriamente `loadtest/resultado.json` e `loadtest/resultados/`, não apenas o script.
7. Para logs, leia a divergência deliberada do item 4 em [`../entregavel-4-logs-error.md`](../entregavel-4-logs-error.md).

## O que não está sendo alegado

Este material não tenta transformar documentação em implementação. Quando algo depende de infraestrutura externa ou não foi reproduzido literalmente, isso é marcado explicitamente. Os dois pontos conhecidos são:

- o Umami foi validado do frontend local contra o painel institucional; a repetição no deploy remoto `eq22.dsc.rodrigor.com` continua pendente;
- o item 4 do guia oficial de logs pede `logger.error(..., exception)` com a exceção no Loki. O IWrite deliberadamente não exporta `Throwable`/stack trace de erro tratado; a divergência e a justificativa de segurança estão documentadas.

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

Observabilidade e analytics foram implementados com minimização de dados. O projeto evita enviar conteúdo de manuscrito, prompts, respostas de IA, credenciais, tokens e identificadores brutos para spans manuais, métricas de negócio, logs estruturados, eventos Umami ou tags k6. Quando um requisito de demonstração entra em conflito com essa política — como o stack trace literal do item 4 de logs — a divergência é explicitada, não escondida.