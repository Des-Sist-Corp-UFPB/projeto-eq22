# Requisito 05 — Logs estruturados, Loki e correlação log ↔ trace

## 1. Objetivo

O requisito de logs não é apenas “ter `logger.info`”. O objetivo é fazer eventos úteis do domínio chegarem ao Loki de forma estruturada, pesquisável e correlacionável com traces, sem transformar o sistema de logs em uma superfície de vazamento de manuscritos, prompts, credenciais ou stack traces desnecessários.

## 2. Estado

**✅ Ingestão, estruturação e correlação implementadas e validadas.**

Há uma única divergência deliberada do guia oficial: o item 4 pede `logger.error(..., exception)` com a exceção no Loki. O IWrite não exporta `Throwable` de erro tratado. Essa decisão está documentada separadamente em:

[`../../entregavel-4-logs-error.md`](../../entregavel-4-logs-error.md)

Documento técnico principal:

[`../../otel-correlated-logs.md`](../../otel-correlated-logs.md)

Guia oficial preservado:

[`../../opentelemetry-logs.md`](../../opentelemetry-logs.md)

## 3. Pipeline

```text
SLF4J 2 / Logback
   |
   | key-value pairs
   v
OpenTelemetry Java Agent
   |
   | OTLP logs + trace_id/span_id
   v
LGTM / Loki
   |
   v
Grafana Explore
```

O Java Agent já contém a instrumentação necessária para o Logback. O projeto não adiciona um segundo appender OTel, porque isso duplicaria eventos.

## 4. Por que nenhum segundo appender foi criado

O Java Agent 2.30.0 instala a instrumentação Logback em runtime.

Adicionar manualmente `OpenTelemetryAppender` em `logback-spring.xml` criaria dois caminhos de exportação do mesmo evento.

Consequências evitadas:

- eventos duplicados;
- contagens erradas;
- custo/volume dobrado;
- evidências inconsistentes no Loki.

Por isso o projeto usa o appender do agente como fonte única de exportação.

## 5. Eventos estruturados

O IWrite usa a API fluente do SLF4J 2:

```java
log.atInfo()
    .addKeyValue("otel.event.name", "iwrite.scene.content.save")
    .addKeyValue("iwrite.operation", "scene_content_save")
    .addKeyValue("iwrite.result", "success")
    .addKeyValue("iwrite.duration_ms", durationMs)
    .log("Business operation completed");
```

A mensagem é constante. Os dados pesquisáveis vivem nos key-value pairs.

## 6. Captura de key-value pairs

O ambiente de observabilidade habilita explicitamente:

```text
OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_CAPTURE_KEY_VALUE_PAIR_ATTRIBUTES=true
```

Sem isso, os pares existiriam no evento Logback mas não seriam exportados como atributos OTLP.

## 7. Eventos de negócio

O projeto documenta quatro famílias principais:

```text
iwrite.scene.content.save
iwrite.scene.analysis
iwrite.mcp.invocation
iwrite.llm.execution
```

Cada uma possui vocabulário controlado de operação, resultado, categoria e atributos.

## 8. Salvamento de cena

Campos principais:

```text
iwrite.operation = scene_content_save
iwrite.result
iwrite.duration_ms
iwrite.scene.source
iwrite.scene.content_size_bucket
iwrite.scene.content_changed
iwrite.error.type
```

Resultados possíveis incluem sucesso, retry idempotente, no-change, conflito, validação, not-found e falha interna.

## 9. Análise de cena

O evento de análise registra apenas metadados controlados:

```text
iwrite.operation = scene_analysis
iwrite.result
iwrite.ai.focus_present
iwrite.ai.input_size_bucket
iwrite.ai.fallback_used
iwrite.ai.provider
iwrite.ai.model_family
```

Prompt, resposta e `focus` livre não são exportados.

## 10. MCP

Eventos MCP registram tool/resource type, resultado, duração e categoria pública de erro.

Não registram:

- parâmetros livres;
- IDs como campo de log de negócio;
- títulos;
- resposta completa da tool;
- conteúdo da cena.

## 11. LLM

O gateway LLM registra execução com provider/família do modelo, feature, versão de prompt, status, categoria de erro, duração e tokens quando disponíveis.

O identificador bruto do modelo configurado não é exportado. Ele passa por normalização de família.

## 12. Severidade

Política geral:

| Nível | Significado |
|---|---|
| `INFO` | operação cumpriu o que era esperado |
| `WARN` | resultado tratado/esperado que merece atenção |
| `ERROR` | falha interna inesperada |

Exemplos:

- conflito otimista -> `WARN`;
- provider indisponível -> `WARN`;
- feature de IA desabilitada -> não vira `ERROR` por requisição;
- falha de configuração/auditoria interna -> `ERROR`.

A severidade não é simplesmente “sucesso = INFO, qualquer falha = ERROR”.

## 13. Por que `FEATURE_DISABLED` não é ERROR

O provider de IA pode estar desabilitado por configuração normal.

Se toda tentativa de análise em uma instalação com IA desligada gerasse `ERROR`, um ambiente saudável produziria alertas falsos continuamente.

Por isso esse caso é classificado como resultado tratado.

## 14. Campos proibidos

A documentação define como proibidos, em mensagem, atributos, MDC, argumentos e throwable:

```text
conteúdo de cena
título privado
prompt
resposta de IA
focus livre
e-mail
nome de usuário
tenantId/userId/bookId/sceneId/operationId
API key/token/header/cookie
URL com query sensível
mensagem de exceção
stack trace de erro tratado
```

## 15. Throwable de erros tratados

Erros tratados registram apenas tipo/categoria sanitizada.

O `Throwable` não é passado ao logger nesses casos, evitando `throwableProxy` e stack trace exportado.

Essa escolha é a divergência explícita do entregável 4 e não é escondida do avaliador.

## 16. Entregáveis oficiais

| Item oficial | Estado |
|---|---|
| log real no Loki filtrado por `service_name` | ✅ |
| evento estruturado e filtro por campo | ✅ |
| correlação log ↔ trace | ✅ |
| `logger.error(..., exception)` com stack trace de erro tratado | ⚠️ não reproduzido literalmente |

A justificativa detalhada está em `docs/entregavel-4-logs-error.md`.

## 17. Formato real recebido no Loki

A implementação foi ajustada ao formato observado no LGTM real.

Descobertas documentadas:

1. `service_name` é label indexado relevante;
2. atributos do evento chegam como structured metadata;
3. pontos nos nomes viram underscores;
4. `otel.event.name` é consumido como `EventName` e não ficou disponível como atributo consultável nesse pipeline.

Exemplo:

```text
iwrite.result -> iwrite_result
```

## 18. Queries verificadas

### Eventos de salvamento

```logql
{service_name="dsc-eq22"} | iwrite_operation="scene_content_save"
```

### Conflitos

```logql
{service_name="dsc-eq22"} | iwrite_operation="scene_content_save" | iwrite_result="conflict"
```

### WARN/ERROR

```logql
{service_name="dsc-eq22"} | scope_name="com.iwrite.business.events" | severity_text=~"WARN|ERROR"
```

### Trace específico

```logql
{service_name="dsc-eq22"} | trace_id="<TRACE_ID>"
```

## 19. Correlação com Tempo

O Java Agent injeta `trace_id` e `span_id` no log record.

Procedimento:

```text
Loki -> selecionar log -> trace_id -> Tempo -> request completo
```

No trace é possível encontrar o span HTTP, o span manual da operação e os spans JDBC/HTTP client associados.

## 20. Correlação sem UI

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml exec otel-lgtm \
  curl -s 'http://localhost:3200/api/traces/<TRACE_ID>'
```

Isso permite verificar a relação mesmo se a UI do Grafana mudar.

## 21. `trace_id` versus `llmExecutionId`

O projeto documenta explicitamente que são identificadores diferentes.

`trace_id`:

- vem do OpenTelemetry;
- identifica a requisição distribuída;
- serve para Tempo.

`llmExecutionId`:

- vem do gateway de auditoria;
- identifica uma execução LLM persistida;
- não é substituto de trace distribuído.

Essa distinção evita correlação incorreta.

## 22. Testes de privacidade

`StructuredLogEventsTest` captura eventos Logback reais com `ListAppender<ILoggingEvent>`.

As asserções percorrem várias superfícies:

- logger name;
- message pattern;
- formatted message;
- key-value pairs;
- MDC;
- argumentos;
- throwable proxy.

São usados canários para representar credenciais, UUIDs, conteúdo, prompts e mensagens de provider.

## 23. Testes de comportamento

A suíte cobre também:

- sucesso `INFO`;
- conflito `WARN`;
- rollback -> `failure`;
- `no_change` e retry idempotente;
- falha inesperada;
- allowlist de atributos;
- fechamento idempotente;
- falha da infraestrutura de logging sem derrubar negócio;
- eventos MCP/LLM sem campos livres.

## 24. Não-duplicação

A documentação fornece query de contagem por `iwrite_result` para confirmar um evento por operação concluída.

Isso é importante porque a arquitetura evita appender duplicado e usa guard de idempotência na finalização da operação.

## 25. Segurança operacional

O projeto também evita habilitar debug de SQL/bind parameters para investigar logs. Isso poderia vazar valores literais enviados ao banco.

A estratégia é observar spans e eventos estruturados, não aumentar logging indiscriminadamente.

## 26. Reprodução

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d --build
```

Depois gere operações reais de salvamento/análise e consulte Loki no Grafana em `http://localhost:3001`.

O documento técnico contém comandos completos para gerar success, idempotent retry, no-change, conflict e análise indisponível.

## 27. O que uma IA avaliadora deve verificar

1. `BusinessTelemetry` emite key-value pairs reais.
2. O compose habilita captura dos pares.
3. Não existe segundo appender duplicando exportação.
4. O logger usa vocabulários controlados.
5. Os testes verificam `throwableProxy` e dados sensíveis.
6. A documentação contém LogQL baseado no formato observado.
7. A divergência do item 4 está declarada explicitamente.

## 28. Arquivos para auditoria

```text
docs/opentelemetry-logs.md
docs/otel-correlated-logs.md
docs/entregavel-4-logs-error.md
src/main/java/com/iwrite/observability/BusinessTelemetry.java
src/main/java/com/iwrite/llm/gateway/LlmExecutionGateway.java
src/main/java/com/iwrite/mcp/
src/test/java/com/iwrite/observability/StructuredLogEventsTest.java
src/test/java/com/iwrite/llm/gateway/LlmExecutionGatewayTest.java
docker-compose.observability.yml
```

## 29. Limitações

- LGTM local é efêmero;
- `otel.event.name` não ficou consultável no pipeline local usado;
- structured metadata depende do comportamento da versão atual do stack;
- MCP não foi publicado remotamente e seus eventos não são evidência de produção;
- item 4 literal não é atendido por decisão de segurança.

## 30. Conclusão

Os logs do IWrite foram tratados como dado operacional estruturado, correlacionado e sujeito a política de privacidade. A implementação permite investigação por resultado, operação, severidade e trace sem registrar conteúdo livre do usuário. A única divergência do exercício oficial é explícita e documentada, em vez de ser escondida atrás de um checkbox.