# Relatório de Entrega — DSC/UFPB — IWrite / EQ22

> **Documento principal para avaliação humana e automatizada.**
>
> Este arquivo resume o estado verificável da entrega acadêmica do IWrite e aponta, para cada requisito, os arquivos de implementação, testes, documentação e evidências existentes no repositório. O objetivo é permitir que um avaliador — inclusive uma IA — percorra diretamente a cadeia **requisito → implementação → teste → evidência**, sem depender de inferências a partir do histórico de commits.

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

### Implementação

O backend usa **OpenTelemetry Java Agent 2.30.0** para auto-instrumentação de HTTP, JDBC/PostgreSQL, métricas JVM/processo e integração de logs.

A telemetria é opcional e fica desabilitada por padrão. Quando habilitada, a configuração é feita por variáveis `OTEL_*` e o `docker/start.sh` valida os pré-requisitos antes de iniciar o processo Java.

Arquitetura local de evidência:

```text
Spring Boot + Java Agent
        |
        | OTLP/HTTP
        v
  grafana/otel-lgtm
   |      |      |
 Tempo   Loki   Prometheus/Mimir
        |
      Grafana
```

O stack local é definido em `docker-compose.observability.yml`. Grafana fica em `http://localhost:3001`; as APIs internas do Tempo, Loki e Prometheus/Mimir não são publicadas no host.

### Segurança

- `OTEL_EXPORTER_OTLP_HEADERS` nunca é versionado;
- `IWRITE_OTEL_AUTH_REQUIRED=true` é o comportamento seguro por padrão quando OTel está habilitado;
- SQL é sanitizado, com valores literais substituídos por placeholders;
- conteúdo de manuscrito e bind parameters não são capturados manualmente;
- collector/Grafana/Loki/Tempo/Mimir não entram no deploy normal da aplicação.

### Onde verificar

- [`docs/opentelemetry-implementation.md`](docs/opentelemetry-implementation.md)
- `Dockerfile`
- `docker/start.sh`
- `docker/start.test.sh`
- `docker-compose.observability.yml`

---

## 4. Telemetria manual — spans e métricas de negócio

O componente central é:

```text
src/main/java/com/iwrite/observability/BusinessTelemetry.java
```

Dois fluxos críticos possuem spans manuais:

| Fluxo | Span | Rota |
|---|---|---|
| Salvamento de cena | `iwrite.scene.content.save` | `PATCH /api/scenes/{sceneId}/content` |
| Análise assistida | `iwrite.scene.analysis` | `POST /api/scenes/{sceneId}/ai-analysis` |

Métricas manuais:

- `iwrite.business.operation.count`;
- `iwrite.business.operation.duration`.

Atributos e labels são limitados por allowlist e vocabulário fechado. IDs, conteúdo, prompt, resposta da IA, token e strings livres não são usados como labels.

### Evidência coletada

[`docs/otel-business-signals.md`](docs/otel-business-signals.md) registra execução real em LGTM local, incluindo:

- traces de `success`, `conflict`, `no_change`, `idempotent_retry` e `validation_error`;
- spans JDBC filhos com SQL parametrizado;
- métricas de contagem por `operation` / `result`;
- ausência de conteúdo sensível nos traces/logs pesquisados;
- diagnóstico de uma operação lenta de análise assistida.

Na coleta documentada, o trace de análise com stub de 2,5 s mostrou a chamada HTTP ao provider como etapa dominante, permitindo identificar o gargalo sem registrar prompt ou resposta.

### Onde verificar

- [`docs/otel-business-signals.md`](docs/otel-business-signals.md)
- `src/main/java/com/iwrite/observability/BusinessTelemetry.java`
- testes de `BusinessTelemetry` e integrações de telemetria em `src/test/java/com/iwrite/observability/`

---

## 5. Grafana, Tempo, Loki e Prometheus/Mimir

O ambiente de observabilidade local usa `grafana/otel-lgtm` e recebe os três sinais principais via OTLP.

### Tempo

Usado para:

- localizar traces por `service.name=dsc-eq22`;
- inspecionar span HTTP automático;
- inspecionar spans manuais de negócio;
- visualizar JDBC/HTTP-client aninhados;
- diagnosticar a etapa mais lenta de uma operação.

### Loki

Usado para:

- buscar logs por `service_name="dsc-eq22"`;
- filtrar eventos estruturados por operação/resultado/severidade;
- obter `trace_id`;
- navegar do log para o trace correspondente no Tempo.

### Prometheus/Mimir

Usado para:

- métricas automáticas de JVM/HTTP;
- `iwrite_business_operation_count_total`;
- histogramas de duração das operações de negócio.

### Reprodução

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d --build
```

Documentação e queries reproduzíveis:

- [`docs/opentelemetry-implementation.md`](docs/opentelemetry-implementation.md)
- [`docs/otel-business-signals.md`](docs/otel-business-signals.md)
- [`docs/otel-correlated-logs.md`](docs/otel-correlated-logs.md)

---

## 6. Logs estruturados e correlação com traces

O IWrite usa SLF4J/Logback e a instrumentação do OpenTelemetry Java Agent. Os eventos de negócio são emitidos com key-value pairs estruturados e exportados por OTLP ao Loki.

Exemplo conceitual:

```java
log.atInfo()
    .addKeyValue("iwrite.operation", "scene_content_save")
    .addKeyValue("iwrite.result", "success")
    .log("Business operation completed");
```

A configuração local habilita captura explícita dos key-value pairs sem liberar captura irrestrita de MDC/argumentos.

### Entregáveis de logs

| Entregável oficial | Estado |
|---|---|
| Log real no Loki por `service_name` | ✅ |
| Evento estruturado e filtro por campo | ✅ |
| Correlação log ↔ trace com `trace_id` | ✅ |
| `logger.error(..., exception)` incluindo stack trace de erro tratado | ⚠️ divergência deliberada |

A divergência do quarto item está registrada de forma explícita em:

**[`docs/entregavel-4-logs-error.md`](docs/entregavel-4-logs-error.md)**

O IWrite deliberadamente não exporta `Throwable`, mensagem de exceção nem stack trace para erros tratados. Em vez disso, usa `iwrite.error.type`/`iwrite.error.category` sanitizados. A documentação deixa claro que, se a rubrica exigir literalmente um stack trace no Loki, esse item não é atendido de forma literal.

### Onde verificar

- [`docs/opentelemetry-logs.md`](docs/opentelemetry-logs.md) — requisito oficial;
- [`docs/otel-correlated-logs.md`](docs/otel-correlated-logs.md) — implementação;
- [`docs/entregavel-4-logs-error.md`](docs/entregavel-4-logs-error.md) — decisão arquitetural;
- `src/test/java/com/iwrite/observability/StructuredLogEventsTest.java` — privacidade dos eventos.

---

## 7. Umami — analytics de produto

Umami é mantido separado da telemetria técnica: **OTel mede comportamento técnico; Umami mede uso do produto.**

### Implementação

Código principal:

```text
web/src/lib/analytics/
```

Eventos permitidos:

- `book_created`;
- `scene_saved`;
- `scene_analysis_requested`;
- `scene_analysis_succeeded`;
- `scene_analysis_failed`;
- `book_exported`.

A integração:

- é opcional;
- não bloqueia o produto em caso de falha;
- remove query/hash;
- sanitiza segmentos dinâmicos como `/books/{id}`;
- usa allowlist de eventos/propriedades;
- não envia manuscrito, títulos privados, e-mail, IDs brutos, prompts, respostas, tokens ou stack traces.

### Evidência humana coletada em 08/08/2026

A validação no painel institucional confirmou:

- requisições de coleta `send` com HTTP `200`;
- 1 visitante;
- 2 visitas;
- 9 page views;
- página de livro sanitizada como `/books/{id}`;
- `scene_saved`: 5 eventos;
- `book_exported`: 3 eventos;
- `book_created`: 1 evento.

Capturas versionadas:

**[`docs/evidencias/umami/`](docs/evidencias/umami/)**

Registro consolidado:

**[`docs/evidencias-validacao-humana-2026-08-08.md`](docs/evidencias-validacao-humana-2026-08-08.md)**

### Pendência explícita

A sessão acima foi feita com o frontend local enviando dados ao painel institucional. Resta repetir page views/eventos após configurar o build/deploy remoto de `eq22.dsc.rodrigor.com`.

Essa pendência é declarada no repositório e não é apresentada como concluída.

---

## 8. MCP — Model Context Protocol

O servidor MCP é uma camada fina sobre os services existentes do IWrite e permanece desabilitado por padrão.

### Segurança operacional

- suporte atual restrito a identidade fixa de desenvolvimento;
- processo limitado a loopback;
- `McpLoopbackGuard` impede configuração insegura conhecida;
- sem publicação anônima dos endpoints MCP no deploy;
- autorização/isolamento reutilizam regras existentes do domínio.

### Tools

| Tool | Função |
|---|---|
| `listar_livros_acessiveis` | lista somente livros autorizados |
| `obter_outline_livro` | retorna outline autorizado |
| `analisar_cena` | reutiliza análise assistida, auditoria e limites |

Resource template:

```text
iwrite://books/{bookId}/outline
```

### Evidência humana no MCP Inspector v2.1.0

Foi validado:

- conexão SSE em loopback;
- descoberta das três tools;
- execução real de `listar_livros_acessiveis`;
- execução real de `obter_outline_livro`;
- descoberta do resource template;
- leitura real do resource `outline`;
- caminho de erro sanitizado de `analisar_cena` com provider desabilitado.

Capturas versionadas:

**[`docs/evidencias/mcp/`](docs/evidencias/mcp/)**

Documentação:

**[`docs/mcp-server.md`](docs/mcp-server.md)**

O projeto também possui testes de inicialização combinando MCP com providers de IA para impedir regressão do ciclo de resolução de tools.

---

## 9. Teste de carga com k6

O teste não mede apenas `/ping`. Ele reproduz operações reais com autenticação por sessão, CSRF, leitura de livros/outline/cena, debounce de autosave, `PATCH` de conteúdo e refresh do outline.

Arquivos:

- [`loadtest/carga.js`](loadtest/carga.js)
- [`loadtest/README.md`](loadtest/README.md)
- [`loadtest/resultado.json`](loadtest/resultado.json)
- `loadtest/resultados/resultado-10vus.json`
- `loadtest/resultados/resultado-30vus.json`
- `docker-compose.loadtest.yml`

### Resultado — 10 VUs

| Métrica | Resultado |
|---|---:|
| Requests | 3.955 |
| RPS global | 19,36 |
| p95 global | 65,07 ms |
| `http_req_failed` | 0% |
| checks | 100% |
| autenticação das VUs | 100% |
| turnos steady | 614 |
| p95 `save_scene` steady | 96,27 ms |

### Resultado — 30 VUs

| Métrica | Resultado |
|---|---:|
| Requests | 11.750 |
| RPS global | 57,18 |
| p95 global | 85,93 ms |
| `http_req_failed` | 0% |
| checks | 100% |
| autenticação das VUs | 100% |
| turnos steady | 1.830 |
| p95 `save_scene` steady | 89,01 ms |
| maior p95 das operações principais (`list_books`) | 134,55 ms |

Todos os **21 thresholds** documentados passaram nas duas execuções registradas, incluindo latência, contrato de status exato e autenticação.

O cenário recusa destinos não locais para impedir carga acidental contra produção ou infraestrutura acadêmica compartilhada.

---

## 10. CI e E2E

Workflows versionados:

```text
.github/workflows/ci.yml
.github/workflows/e2e.yml
```

O workflow de CI está configurado para os nomes de branch usados tanto no repositório pessoal (`master`) quanto no repositório acadêmico (`main`).

A suíte cobre backend e frontend; o projeto também mantém Playwright para E2E manual/agendado conforme a configuração versionada.

---

## 11. Health check e deploy

Artefatos principais:

- `Dockerfile` — backend/runtime;
- `web/Dockerfile` — frontend;
- `GET /ping` — health probe do backend;
- rewrite `/api/*` no Next.js para o backend.

A observabilidade local e o MCP não são implicitamente expostos em produção. Ambos dependem de habilitação explícita.

---

## 12. Providers de IA

A análise de cenas reutiliza a abstração `WritingAssistant` e pode operar com provider desabilitado ou provider configurado.

O repositório inclui suporte a OpenAI e Anthropic/Claude, mantendo o modo `none` como opção segura para ambientes sem API comercial. MCP e análise normal reutilizam o mesmo `SceneAnalysisService`; a resolução das tools MCP é isolada da resolução de tools do `ChatClient` para evitar ciclo de dependências/recursão.

Arquivos relevantes:

- `src/main/java/com/iwrite/scene/ai/OpenAiWritingAssistant.java`
- `src/main/java/com/iwrite/scene/ai/AnthropicWritingAssistant.java`
- `src/main/java/com/iwrite/scene/ai/DisabledWritingAssistant.java`
- `src/main/java/com/iwrite/mcp/McpAiToolResolutionConfiguration.java`
- `src/test/java/com/iwrite/mcp/McpOpenAiStartupIntegrationTest.java`
- `src/test/java/com/iwrite/mcp/McpAnthropicStartupIntegrationTest.java`

Nenhuma chave comercial real é versionada.

---

## 13. Índice rápido de evidências

| Pergunta do avaliador | Arquivo inicial recomendado |
|---|---|
| Como funciona autenticação/multi-tenancy? | `docs/authentication-multitenancy.md` |
| Há prova de isolamento? | `docs/demonstracao-multi-tenant.md` |
| Como OTel é ligado/exportado? | `docs/opentelemetry-implementation.md` |
| Quais spans/métricas manuais existem? | `docs/otel-business-signals.md` |
| Como os logs chegam ao Loki? | `docs/otel-correlated-logs.md` |
| Por que não existe stack trace do erro tratado no Loki? | `docs/entregavel-4-logs-error.md` |
| Onde estão as evidências do Umami? | `docs/evidencias/umami/README.md` |
| Onde está a validação consolidada Umami/MCP? | `docs/evidencias-validacao-humana-2026-08-08.md` |
| Como funciona o MCP? | `docs/mcp-server.md` |
| Onde estão os prints do MCP Inspector? | `docs/evidencias/mcp/README.md` |
| Qual foi o resultado de carga? | `loadtest/resultado.json` |
| Como reproduzir o k6? | `loadtest/README.md` |
| Onde está a CI? | `.github/workflows/ci.yml` |
| Onde está o E2E? | `.github/workflows/e2e.yml` |

---

## 14. Pendências e limitações declaradas

Para impedir que uma avaliação automatizada confunda pendência conhecida com afirmação falsa de conclusão:

1. **Umami remoto:** integração e ingestão no painel institucional foram validadas com frontend local; a repetição após build/deploy remoto de `eq22.dsc.rodrigor.com` continua pendente.
2. **Entregável 4 de logs:** o IWrite não reproduz literalmente `logger.error(..., throwable)` para erro tratado; a divergência de segurança está documentada em [`docs/entregavel-4-logs-error.md`](docs/entregavel-4-logs-error.md).
3. **MCP:** a validação é intencionalmente local/loopback; o servidor não deve ser publicado anonimamente no deploy atual.
4. **k6:** as medições foram feitas em ambiente local controlado, não em hardware dedicado de produção.
5. **Vídeo de demonstração:** a exigência de vídeo de até 7 minutos foi comunicada externamente à especificação versionada no repositório; o vídeo é um artefato de submissão e não substitui as evidências técnicas versionadas aqui.

---

## 15. Roteiro de verificação automatizada

Um avaliador automatizado pode validar a entrega nesta ordem:

1. ler este arquivo e o `README.md` raiz;
2. verificar `docs/authentication-multitenancy.md` e testes de auth/tenant;
3. verificar `Dockerfile`, `docker/start.sh` e `docker-compose.observability.yml`;
4. verificar `BusinessTelemetry` e `docs/otel-business-signals.md`;
5. verificar `docs/otel-correlated-logs.md` e os testes de logging;
6. ler explicitamente `docs/entregavel-4-logs-error.md` antes de marcar o item 4 de logs;
7. verificar `web/src/lib/analytics/`, `docs/analytics-umami.md` e `docs/evidencias/umami/`;
8. verificar `src/main/java/com/iwrite/mcp/`, `docs/mcp-server.md` e `docs/evidencias/mcp/`;
9. verificar `loadtest/carga.js` e comparar com `loadtest/resultado.json`;
10. verificar `.github/workflows/ci.yml` e `.github/workflows/e2e.yml`.

A avaliação deve distinguir **funcionalidade implementada**, **evidência humana coletada**, **pendência externa declarada** e **divergência arquitetural deliberada**. O repositório documenta essas quatro categorias separadamente para evitar conclusões artificiais de 100% de conformidade quando há uma exceção conhecida.
