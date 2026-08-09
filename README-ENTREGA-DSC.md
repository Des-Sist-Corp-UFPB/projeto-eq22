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

## 1. Resumo executivo

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

Os guias oficiais da disciplina permanecem separados da documentação específica do projeto:

- [`docs/opentelemetry.md`](docs/opentelemetry.md) — guia oficial de telemetria;
- [`docs/opentelemetry-logs.md`](docs/opentelemetry-logs.md) — guia oficial de logs/Loki;
- os arquivos `docs/opentelemetry-implementation.md`, `docs/otel-business-signals.md` e `docs/otel-correlated-logs.md` descrevem **o que o IWrite efetivamente implementa**.

---

## 2. Autenticação e multi-tenancy

### Implementação

A identidade e o tenant efetivo são resolvidos no backend. `tenantId`, `userId` e `role` enviados pelo cliente não são fonte de autoridade.

Fluxo principal:

```text
JSESSIONID (HttpOnly)
  -> Spring Security / SecurityContext
  -> IWriteUserDetails
  -> AuthenticatedCurrentUserProvider
  -> tenant_memberships relida por requisição
  -> services/repositories escopados por tenant
```

### Propriedades verificáveis

- sessão de servidor;
- proteção CSRF nas mutações;
- resolução server-authoritative do tenant;
- recursos de outro tenant usam semântica não enumerável;
- revogação de membership invalida acesso;
- testes de integração cobrem isolamento;
- demonstração específica versionada.

### Onde verificar

- [`docs/authentication-multitenancy.md`](docs/authentication-multitenancy.md)
- [`docs/demonstracao-multi-tenant.md`](docs/demonstracao-multi-tenant.md)
- `src/main/java/com/iwrite/auth/`
- `src/test/java/com/iwrite/auth/`

---

## 3. OpenTelemetry — instrumentação automática

O backend usa **OpenTelemetry Java Agent 2.30.0** para auto-instrumentação de HTTP, JDBC/PostgreSQL, métricas JVM/processo e integração de logs. A telemetria é opcional e fica desabilitada por padrão.

Arquitetura local de evidência:

```text
Spring Boot + Java Agent
        |
        | OTLP/HTTP
        v
grafana/otel-lgtm
        |-- Tempo
        |-- Loki
        `-- Prometheus/Mimir
```

O `Dockerfile` fixa a versão do agente e valida seu SHA-256; `docker/start.sh` anexa `-javaagent` somente quando `IWRITE_OTEL_ENABLED=true`. A CI executa `sh docker/start.test.sh`.

Documentação: [`docs/opentelemetry-implementation.md`](docs/opentelemetry-implementation.md).

---

## 4. Telemetria manual de negócio

O componente `BusinessTelemetry` instrumenta dois fluxos reais:

| Operação | Rota | Span |
|---|---|---|
| salvamento de conteúdo | `PATCH /api/scenes/{sceneId}/content` | `iwrite.scene.content.save` |
| análise de cena | `POST /api/scenes/{sceneId}/ai-analysis` | `iwrite.scene.analysis` |

Métricas próprias:

```text
iwrite.business.operation.count
iwrite.business.operation.duration
```

As dimensões de negócio são limitadas a `operation` e `result`. IDs, conteúdo, prompts e strings livres não são labels.

Na evidência de análise com stub atrasado, o span externo do provider respondeu pela maior parte da latência, permitindo diagnosticar objetivamente o gargalo da operação.

Documento: [`docs/otel-business-signals.md`](docs/otel-business-signals.md).

---

## 5. Grafana / Tempo / Loki / métricas

O ambiente local de evidência é iniciado com:

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d --build
```

Grafana: `http://localhost:3001`.

A stack foi usada para validar:

- `service.name=dsc-eq22`;
- spans HTTP e JDBC automáticos;
- spans manuais de negócio;
- queries SQL sanitizadas;
- métricas JVM/HTTP;
- métricas customizadas;
- logs estruturados;
- correlação log → `trace_id` → Tempo;
- diagnóstico de operação lenta.

---

## 6. Logs estruturados e entregável 4

Eventos de negócio são emitidos via SLF4J 2 key-value pairs e exportados pelo appender do Java Agent.

O projeto diferencia `INFO`, `WARN` e `ERROR` por semântica operacional, não apenas por sucesso/falha.

### Divergência explícita

O item 4 do guia oficial `docs/opentelemetry-logs.md` pede `logger.error(..., exception)` com a exceção no Loki. O IWrite **não reproduz esse item literalmente**: erros tratados não passam `Throwable` ao logger e não exportam stack trace/mensagem de exceção.

A decisão, os motivos de privacidade e o impacto na avaliação estão registrados em:

[`docs/entregavel-4-logs-error.md`](docs/entregavel-4-logs-error.md)

O projeto não tenta marcar esse subitem como atendido literalmente.

---

## 7. Umami

A integração fica em `web/src/lib/analytics/` e usa o tracker institucional apenas quando habilitado por ambiente.

Propriedades importantes:

- auto-track bruto desabilitado;
- URLs sanitizadas;
- UUIDs viram `{id}`;
- query/hash removidos;
- allowlist de eventos/propriedades;
- fila limitada antes do carregamento;
- navegação client-side observada;
- tracker é fail-open.

Validação de 08/08/2026:

```text
coleta HTTP: 200
views: 9
eventos: 9
scene_saved: 5
book_exported: 3
book_created: 1
/books/{id}: observado no painel
```

Evidências: [`docs/evidencias/umami/`](docs/evidencias/umami/).

Pendência declarada: repetir a validação no deploy remoto.

---

## 8. MCP

Servidor MCP WebMVC na mesma aplicação Spring Boot, desabilitado por padrão.

Tools:

```text
listar_livros_acessiveis
obter_outline_livro
analisar_cena
```

Resource:

```text
iwrite://books/{bookId}/outline
```

Por não existir autenticação individual do transporte MCP, o runtime suportado exige identidade fixa de desenvolvimento + bind de loopback. `McpLoopbackGuard` recusa configurações inseguras.

Validação humana no MCP Inspector v2.1.0 comprovou descoberta, execução das tools de leitura, resource template/read e erro sanitizado da análise quando a IA estava desabilitada.

Evidências: [`docs/evidencias/mcp/`](docs/evidencias/mcp/).

---

## 9. k6 — carga e performance

O cenário não testa apenas health check. Cada VU possui sessão e livro/cena próprios e executa:

```text
list_books
load_outline
load_scene
debounce de autosave
save_scene
refresh_outline_after_save
think time
```

A autenticação usa sessão/CSRF real; o harness impede alvo remoto, protege senha/sessão, limpa dados sintéticos e possui thresholds funcionais e de performance.

### Resultados pós-integração

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

Os **21 thresholds** registrados passaram nas duas execuções.

Artefatos:

- [`loadtest/README.md`](loadtest/README.md)
- [`loadtest/resultado.json`](loadtest/resultado.json)
- `loadtest/resultados/`

O relatório detalhado do requisito explica ainda fault injection, recuperação de órfãos, segurança de secrets, status exato, fases e reprodutibilidade pós-squash.

---

## 10. CI e E2E

CI em `.github/workflows/ci.yml`:

- roda em `master` e `main`;
- PostgreSQL 16 real como service;
- testa entrypoint OTel;
- Java 21 + Maven tests;
- Node 20 + `npm ci` + testes + build;
- artifact do build frontend.

E2E em `.github/workflows/e2e.yml`:

- manual + agendado;
- stack Docker completa;
- senhas demo aleatórias por execução e mascaradas;
- Chromium Playwright;
- waits de backend/frontend;
- artifacts em falha;
- cleanup com `if: always()`.

---

## 11. Health / deploy

O backend expõe `GET /ping` sem sessão para liveness. O Next possui rewrite `/api/ping` para manter o modelo de mesma origem.

O projeto possui `Dockerfile`, `web/Dockerfile`, Compose base e overlays separados para observabilidade/demo/carga/E2E.

A configuração de observabilidade não é requisito para a aplicação iniciar.

---

## 12. IA, providers e auditoria

A análise de cena possui providers opcionais OpenAI/Anthropic e modo `none` seguro. REST e MCP reutilizam `SceneAnalysisService` e `LlmExecutionGateway`.

A auditoria LLM centraliza categorias, duração e estado; prompt/resposta não são exportados em telemetria/logs. O modelo bruto é normalizado para família antes de virar atributo.

Uma dependência circular MCP + ChatClient foi corrigida em `McpAiToolResolutionConfiguration`, com testes de startup específicos para MCP + OpenAI e MCP + Anthropic.

---

## 13. Evidências humanas consolidadas

[`docs/evidencias-validacao-humana-2026-08-08.md`](docs/evidencias-validacao-humana-2026-08-08.md) registra:

- ambiente usado;
- Umami institucional;
- coleta HTTP 200;
- page views e eventos;
- sanitização `/books/{id}`;
- MCP Inspector;
- descoberta/executação de tools;
- resource template/read;
- erro sanitizado da análise.

Prints são separados por domínio:

```text
docs/evidencias/umami/
docs/evidencias/mcp/
```

---

## 14. Roteiro de auditoria automatizada

Uma IA avaliadora deve, preferencialmente:

1. abrir [`docs/entrega/README.md`](docs/entrega/README.md);
2. abrir o README detalhado do requisito sob avaliação;
3. verificar os arquivos de implementação listados na seção **Arquivos para auditoria** daquele relatório;
4. verificar os testes citados;
5. comparar afirmações numéricas com os artefatos versionados;
6. distinguir guias oficiais da disciplina de documentação específica do IWrite;
7. respeitar limitações explicitamente declaradas — especialmente Umami pós-deploy e item 4 literal de logs.

---

## 15. Conclusão

A entrega acadêmica não está concentrada em um único commit ou screenshot. Ela inclui infraestrutura de observabilidade, instrumentação manual, analytics, protocolo MCP, segurança multi-tenant, um harness k6 extensivamente revisado, automação de qualidade e evidências reproduzíveis.

Para avaliação detalhada, o índice principal é:

**[`docs/entrega/README.md`](docs/entrega/README.md)**
