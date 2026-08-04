# Logs estruturados correlacionados com traces (issue #126)

Complementa [`opentelemetry.md`](opentelemetry.md) (instalação do agente) e [`otel-business-signals.md`](otel-business-signals.md) (spans e métricas de negócio). Aqui está apenas a terceira perna: **eventos de log estruturados**, exportados por OTLP ao Loki e correlacionados com os traces do Tempo.

O objetivo é responder, no Loki, perguntas operacionais — "quantos salvamentos deram conflito na última hora?", "esse conflito veio de qual requisição?" — sem que nenhuma linha de log contenha conteúdo do manuscrito, prompt, resposta da IA, identificador de usuário ou credencial.

## Caminho do dado

```text
código (SLF4J 2: log.atInfo().addKeyValue(...))
  └─> Logback (implementação SLF4J do Spring Boot)
        ├─> console (padrão do Spring Boot, para desenvolvimento)
        └─> instrumentação Logback do OpenTelemetry Java Agent
              └─> OTLP/HTTP  ->  otel-lgtm  ->  Loki
```

Nada disso é código nosso: o agente instrumenta o Logback em tempo de carga e converte cada `ILoggingEvent` em um log record OTLP, já com `trace_id`, `span_id` e `trace_flags` do contexto ativo.

<a id="sem-segundo-appender"></a>

### Por que nenhum segundo appender foi adicionado

O `opentelemetry-logback-appender-1.0` **já está embutido no Java Agent 2.30.0** e é instalado automaticamente. Declarar um `OpenTelemetryAppender` no `logback-spring.xml` faria os dois caminhos coexistirem e **cada evento seria exportado duas vezes** — quebrando a exigência de "exatamente um evento de conclusão por operação" e dobrando o volume no Loki.

Pelo mesmo motivo o projeto continua sem `logback-spring.xml`: a configuração default do Spring Boot basta, e não há nenhum SDK OpenTelemetry de produção no `pom.xml` (só a API — ver [dependências](otel-business-signals.md#dependencias)). `BusinessTelemetry` não registra appender nenhum; apenas chama SLF4J.

## Mensagem não é atributo

Esta é a distinção central do desenho:

| | Mensagem | Atributo estruturado (key-value pair) |
|---|---|---|
| Exemplo | `"Business operation completed"` | `iwrite.result="conflict"` |
| Onde vai parar | corpo (`line`) do log record | atributo do log record |
| Como se consulta | busca por substring, frágil | filtro por campo, estável |
| Muda com o dado? | **nunca** | sim, dentro de vocabulário fechado |

A mensagem é uma constante do código (`BusinessTelemetry.EVENT_MESSAGE`). Existe para ser legível no console, não para ser parseada. **Nenhuma consulta do Loki depende de extrair campos da mensagem**, e nenhum JSON é montado à mão em string.

Todo campo pesquisável é um key-value pair da API fluente do SLF4J 2:

```java
log.atInfo()
        .addKeyValue("otel.event.name", "iwrite.scene.content.save")
        .addKeyValue("iwrite.operation", "scene_content_save")
        .addKeyValue("iwrite.result", "success")
        .addKeyValue("iwrite.duration_ms", durationMs)
        .log("Business operation completed");
```

### Configuração que faz os key-value pairs virarem atributos

Sem isso, os pares existem no Logback mas não são exportados. Habilitado em `docker-compose.observability.yml`:

```yaml
OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_CAPTURE_KEY_VALUE_PAIR_ATTRIBUTES: "true"
```

Propriedade de sistema equivalente:

```text
otel.instrumentation.logback-appender.experimental.capture-key-value-pair-attributes=true
```

**Deliberadamente não habilitadas**, e documentadas como proibidas em `.env.example`:

| Configuração | Por que não |
|---|---|
| `capture-mdc-attributes=*` | exporta o MDC inteiro, incluindo entradas que nunca passaram por revisão de privacidade |
| `capture-arguments=true` (`experimental-log-attributes`) | exporta todos os argumentos de todos os logs da aplicação — texto livre, parâmetros e conteúdo |
| `capture-code-attributes=true` | volume sem retorno operacional aqui |

O critério é o mesmo dos spans: **allowlist explícita, nunca captura irrestrita.**

## `trace_id` do OpenTelemetry ≠ `llmExecutionId`

Dois identificadores diferentes circulam pelos logs. Confundi-los levaria a procurar no Tempo um trace que não existe:

| | `trace_id` / `span_id` | `llmExecutionId` |
|---|---|---|
| Quem gera | OpenTelemetry Java Agent | `LlmExecutionGateway` (`UUID.randomUUID()`) |
| O que identifica | a requisição distribuída inteira | uma linha da tabela de auditoria de execução LLM |
| Formato | 32 / 16 dígitos hex | UUID |
| Onde aparece | injetado pelo agente no log record | MDC, renderizado só na linha do console |
| Serve para | pular do log para o trace no Tempo | cruzar log com o registro de auditoria no banco |
| Exportado como atributo OTLP? | sim, pelo agente | **não** |

Como o UUID fica fora dos atributos exportados, a linha do console é a **única** superfície onde ele ainda pode ser lido — então a correlação "log → linha de auditoria" depende de renderizá-lo lá. Isso é feito em `application.yml`:

```yaml
logging:
  pattern:
    level: "%5p %replace([%X{llmExecutionId:-}]){'\\[\\]',''}"
```

O agente exporta a **mensagem formatada**, não o padrão do appender, então isso não vaza para o OTLP; e o `%replace` evita colchetes vazios nas linhas que não têm o MDC. `ExecutionIdLogPatternTest` lê o padrão do próprio `application.yml` e falha se ele for removido ou quebrado.

Antes desta mudança a chave de MDC se chamava `llmTraceId` e as mensagens do gateway escreviam `traceId=<uuid>`, sugerindo equivalência com o `trace_id` distribuído. Ambos foram renomeados para `llmExecutionId`. O UUID **não** entra nos key-value pairs: é de alta cardinalidade, não é correlação distribuída, e o MDC não é capturado pelo agente — ele permanece onde sempre foi útil, no log local.

`trace_id`, `span_id` e `trace_flags` **não são inventados por nós**. Não existe identificador substituto no código: sem o agente anexado, os campos simplesmente não aparecem.

## Eventos

Quatro eventos, todos com `otel.event.name` fixo e controlado no código.

### `iwrite.scene.content.save` — evento primário

Emitido por `BusinessTelemetry.Operation#finish()`, o mesmo ponto onde o resultado final e as métricas são definidos. Rota real: `PATCH /api/scenes/{sceneId}/content`.

O MCP é desabilitado por padrão (`IWRITE_MCP_ENABLED=false`), então ele não pode ser a evidência principal — este fluxo pode.

**O resultado reflete a transação de verdade.** `updateContent` é `@Transactional` e registra a finalização com `deferEndToTransaction()`; o evento sai em `afterCompletion`, depois que commit ou rollback já é conhecido. Um rollback que só acontece após o método retornar produz `failure`, nunca `success`. O guard de idempotência (`ended`) garante **exatamente um evento por operação**, mesmo que `close()` seja chamado defensivamente depois.

Nenhuma `TransactionSynchronization` nova foi criada, e o `Scope` do span de negócio **não** é reaberto nem mantido até o commit — o bug corrigido na PR #138 continua corrigido. Como consequência o evento é emitido sob o span HTTP do agente: o `span_id` é o do span servidor e o `trace_id` é o mesmo do span de negócio. A navegação log → trace funciona; o que não existe é um link direto log → span de negócio.

| Campo | Tipo | Valores |
|---|---|---|
| `otel.event.name` | string | `iwrite.scene.content.save` |
| `iwrite.operation` | string | `scene_content_save` |
| `iwrite.result` | string | `success`, `no_change`, `idempotent_retry`, `conflict`, `failure` |
| `iwrite.duration_ms` | long | duração de parede |
| `iwrite.scene.source` | string | `manual_save`, `autosave`, `restore`, `other` |
| `iwrite.scene.content_size_bucket` | string | `empty`, `small`, `medium`, `large`, `truncated` |
| `iwrite.scene.content_changed` | boolean | — |
| `iwrite.error.type` | string | **apenas** o nome simples da classe da exceção |

Os vocabulários são exatamente os de `BusinessTelemetry` — os mesmos objetos `Set` que filtram os atributos do span. Um valor fora do vocabulário é descartado antes de chegar ao span **e** ao log, porque o log é montado a partir do que o span aceitou.

### `iwrite.scene.analysis`

Mesmo mecanismo, para `POST /api/scenes/{sceneId}/ai-analysis`. Campos adicionais: `iwrite.ai.focus_present`, `iwrite.ai.input_size_bucket`, `iwrite.ai.fallback_used`, `iwrite.ai.provider`, `iwrite.ai.model_family`. Resultados: `success`, `validation_error`, `provider_error`, `invalid_response`, `failure`.

`FEATURE_DISABLED` entra em `provider_error`, não em `failure` — ver [níveis](#níveis).

### `iwrite.mcp.invocation`

`McpInvocationSupport`, só quando o servidor MCP está habilitado.

| Campo | Valores |
|---|---|
| `otel.event.name` | `iwrite.mcp.invocation` |
| `iwrite.mcp.tool` | literal fixo declarado no código da ferramenta |
| `iwrite.mcp.resource_type` | `BOOK`, `SCENE` |
| `iwrite.result` | `success`, `failure` |
| `iwrite.error.category` | `not_found`, `invalid_request`, `unavailable`, `rate_limited`, `internal` |
| `iwrite.duration_ms` | long |

Parâmetros, `focus`, respostas, títulos e IDs enviados pelo cliente **nunca** aparecem — inclusive o `resourceId`, que vem do cliente e é de alta cardinalidade.

### `iwrite.llm.execution`

`LlmExecutionGateway`. Campos: `iwrite.llm.feature`, `iwrite.ai.provider`, `iwrite.ai.model_family`, `iwrite.llm.prompt_version`, `iwrite.llm.status`, `iwrite.error.category`, `iwrite.duration_ms`, `iwrite.llm.input_tokens`, `iwrite.llm.output_tokens`, `iwrite.llm.total_tokens`, `iwrite.ai.fallback_used`.

O **modelo configurado nunca é exportado bruto**. `LlmExecutionSpec` aceita qualquer identificador curto sem espaço, então uma credencial colocada por engano em `OPENAI_MODEL` passaria na validação; só `BusinessTelemetry.modelFamily(...)` (`gpt-4o`, `gpt-4.1`, `gpt-5`, `other`, `unknown`) chega ao evento. O provider passa por `BusinessTelemetry.providerName(...)`, que colapsa qualquer valor fora de `{openai, disabled}` em `unknown`.

## Campos proibidos

Em **mensagem, atributos, MDC, argumentos e throwable** — todas as cinco superfícies são inspecionadas pelos testes:

conteúdo de cena · título de livro ou cena · prompt · resposta da IA · `focus` livre · e-mail · nome de usuário · tenant ID · user ID · book ID · scene ID · operation ID · API key · token · header · cookie · URL com query · mensagem de exceção · stack trace de erro tratado.

Erros tratados registram só `iwrite.error.type` (nome simples da classe) ou `iwrite.error.category` (enum). **O `Throwable` nunca é passado ao logger** nesses casos, então não há `throwableProxy` e, portanto, nenhum stack trace exportado.

Nenhum identificador de alta cardinalidade vira label indexado do Loki — ver a seção de LogQL, onde o formato real recebido está documentado.

## Níveis

| Nível | Quando | Exemplos |
|---|---|---|
| `INFO` | a operação fez o que foi pedido | `success`, `no_change`, `idempotent_retry` |
| `WARN` | resultado esperado e tratado | `conflict`, `validation_error`, `provider_error`, `invalid_response`, erro MCP classificado |
| `ERROR` | falha interna inesperada | `failure` |

Conflito otimista é `WARN` **sem stack trace** — é resultado previsto de escrita concorrente, não defeito.

O princípio, em todos os quatro eventos, é que o nível vem de **quão esperado é o desfecho**, nunca de sucesso/insucesso. Sem isso um alerta baseado em `ERROR` nunca dispararia para um deployment quebrado, porque um timeout de provider ocuparia a mesma severidade.

| Evento | `ERROR` | `WARN` |
|---|---|---|
| `iwrite.scene.content.save` | `failure` | `conflict` |
| `iwrite.scene.analysis` | `failure` (só `CONFIGURATION_ERROR`, `AUDIT_PERSISTENCE_FAILURE`, `INTERNAL_EXECUTION_ERROR`) | `validation_error`, `provider_error`, `invalid_response` |
| `iwrite.llm.execution` | `INTERNAL_EXECUTION_ERROR`, `AUDIT_PERSISTENCE_FAILURE`, `CONFIGURATION_ERROR`, categoria `null` | `PROVIDER_TIMEOUT`, `PROVIDER_UNAVAILABLE`, `PROVIDER_REQUEST_REJECTED`, `INVALID_STRUCTURED_RESPONSE`, `FEATURE_DISABLED` |
| `iwrite.mcp.invocation` | `internal` | `not_found`, `invalid_request`, `unavailable`, `rate_limited` |

Uma categoria não classificada (`null`) conta como interna: um desfecho que não conseguimos nomear não é, por definição, esperado.

**`FEATURE_DISABLED` é o caso que mais importa acertar.** O assistente desligado é o deployment padrão, então toda requisição de análise devolve 503. Ele é classificado como `provider_error` (não `failure`), senão uma instalação perfeitamente saudável produziria um evento `ERROR` por requisição.

Por ambiente, sem aumentar ruído em produção:

```properties
# produção (default; não precisa declarar)
logging.level.com.iwrite.business.events=INFO

# reduzir volume mantendo conflitos e falhas
logging.level.com.iwrite.business.events=WARN
```

Ou por variável: `LOGGING_LEVEL_COM_IWRITE_BUSINESS_EVENTS=WARN`.

`DEBUG` de SQL e bind parameters do Hibernate continuam **desligados** — não foram tocados por esta mudança e não devem ser ligados para investigar logs: exportariam valores literais de query.

## Reprodução local

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.observability.yml \
  up -d --build
```

Grafana em <http://localhost:3001>, backend em <http://localhost:8085>, frontend em <http://localhost:3000>. A identidade de desenvolvimento (`IWRITE_DEVELOPMENT_CURRENT_USER_ENABLED=true`) dispensa login.

Crie livro → seção → capítulo → cena pelas rotas reais (`POST /api/books`, `POST /api/books/{bookId}/sections`, `POST /api/sections/{sectionId}/chapters`, `POST /api/chapters/{chapterId}/scenes`) e então gere os quatro casos:

```bash
SC=<SCENE_ID>
save(){ curl -s -o /dev/null -w '%{http_code}\n' \
  -X PATCH "http://localhost:8085/api/scenes/$SC/content" \
  -H 'Content-Type: application/json' -d "$1"; }

OP1=$(python -c 'import uuid;print(uuid.uuid4())')

# 1. success            -> 200
save "{\"contentText\":\"texto\",\"contentJson\":null,\"source\":\"MANUAL_SAVE\",\"expectedContentRevision\":0,\"operationId\":\"$OP1\"}"
# 2. idempotent_retry   -> 200  (mesmo operationId, mesma requisição)
save "{\"contentText\":\"texto\",\"contentJson\":null,\"source\":\"MANUAL_SAVE\",\"expectedContentRevision\":0,\"operationId\":\"$OP1\"}"
# 3. no_change          -> 200  (novo operationId, mesmo conteúdo, revisão atual)
save "{\"contentText\":\"texto\",\"contentJson\":null,\"source\":\"AUTO_SAVE\",\"expectedContentRevision\":1,\"operationId\":\"$(python -c 'import uuid;print(uuid.uuid4())')\"}"
# 4. conflict           -> 409  (expectedContentRevision desatualizado)
save "{\"contentText\":\"outro\",\"contentJson\":null,\"source\":\"MANUAL_SAVE\",\"expectedContentRevision\":0,\"operationId\":\"$(python -c 'import uuid;print(uuid.uuid4())')\"}"

# 5. erro tratado de análise -> 503 com o provider desabilitado (nenhuma chamada paga)
curl -s -o /dev/null -w '%{http_code}\n' -X POST \
  "http://localhost:8085/api/scenes/$SC/ai-analysis" \
  -H 'Content-Type: application/json' -d '{"focus":"ritmo"}'
```

## LogQL

**Os nomes abaixo foram lidos do formato real recebido, não presumidos.** Duas descobertas importam para escrever qualquer consulta:

1. **Só `service_name` é label indexado.** `GET /loki/api/v1/labels` retorna apenas ele entre os campos que nos interessam. Todos os atributos do log record chegam como **structured metadata** — devolvidos dentro do objeto `stream` na resposta da API, mas filtrados com a sintaxe de label filter (`|`), não com selector de stream (`{}`). Nenhum campo de alta cardinalidade vira label de índice.
2. **Pontos viram underscores:** `iwrite.result` → `iwrite_result`, `iwrite.scene.source` → `iwrite_scene_source`.
3. **`otel.event.name` não é consultável.** O agente consome esse par como o campo `EventName` do log record OTLP (comportamento da especificação, não bug), e o pipeline do `otel-lgtm` não o expõe como atributo. Ele continua sendo definido — é o que dá identidade ao evento no protocolo — mas **os seletores que funcionam de fato são `scope_name` e `iwrite_operation`.** As consultas abaixo usam esses.

Execute com o stack no ar (a API do Loki, porta 3100, só existe na rede interna do compose):

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml exec otel-lgtm \
  curl -s -G 'http://localhost:3100/loki/api/v1/query_range' \
  --data-urlencode 'query=<QUERY>' --data-urlencode 'limit=50'
```

| Objetivo | Query |
|---|---|
| Todos os eventos de salvamento | `{service_name="dsc-eq22"} \| iwrite_operation="scene_content_save"` |
| Somente conflitos | `{service_name="dsc-eq22"} \| iwrite_operation="scene_content_save" \| iwrite_result="conflict"` |
| Somente falhas e resultados tratados | `{service_name="dsc-eq22"} \| scope_name="com.iwrite.business.events" \| severity_text=~"WARN\|ERROR"` |
| Todos os eventos MCP | `{service_name="dsc-eq22"} \| scope_name="com.iwrite.mcp.invocations"` |
| Todos os eventos LLM | `{service_name="dsc-eq22"} \| scope_name="com.iwrite.llm.gateway.LlmExecutionGateway"` |
| Um `trace_id` específico | `{service_name="dsc-eq22"} \| trace_id="<TRACE_ID>"` |
| Contagem por resultado (1 evento por operação) | `sum by (iwrite_result) (count_over_time({service_name="dsc-eq22"} \| iwrite_operation="scene_content_save" [1h]))` |

A consulta de contagem é a verificação de não-duplicação: com os quatro casos da reprodução acima ela retorna exatamente `success=1`, `no_change=1`, `idempotent_retry=1`, `conflict=1`.

## Correlação log → Tempo

1. Consulte um evento no Loki e copie o `trace_id` devolvido no `stream`.
2. No Grafana, o detalhe da linha no painel do Loki traz o link para o Tempo. Fora da UI:

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml exec otel-lgtm \
  curl -s 'http://localhost:3200/api/traces/<TRACE_ID>'
```

O trace contém a **mesma requisição HTTP** e o **span de negócio** correspondente. Para o `trace_id` de um log de conflito, os spans relevantes são:

```text
PATCH /api/scenes/{sceneId}/content   SPAN_KIND_SERVER   http.route=/api/scenes/{sceneId}/content
iwrite.scene.content.save             SPAN_KIND_INTERNAL iwrite.operation=scene_content_save
                                                         iwrite.result=conflict
```

O `iwrite.result` do span bate com o `iwrite_result` do log, que é a prova de que log e trace descrevem a mesma operação.

## Testes

`StructuredLogEventsTest` e `LlmExecutionGatewayTest` inspecionam eventos Logback **reais** capturados por um `ListAppender<ILoggingEvent>` (`CapturedLogs`), nunca o texto do código-fonte. Cada asserção de privacidade percorre todas as superfícies do evento — nome do logger, padrão da mensagem, mensagem formatada, chaves e valores dos key-value pairs, mapa de MDC, array de argumentos e a cadeia inteira de `throwableProxy` — com canários que representam credencial, e-mail, UUID, conteúdo de manuscrito, prompt e mensagem de provider.

Cobertura: sucesso `INFO`; conflito `WARN` sem mensagem nem stack trace; rollback posterior ao retorno vira `failure`; `no_change` e `idempotent_retry` permanecem `INFO` ao commitar; erro inesperado expõe só o nome simples da classe; o evento carrega **somente** a allowlist; valores rejeitados pelo vocabulário não chegam ao log; fechamento idempotente não duplica; falha da infraestrutura de logging (via `TurboFilter` que lança) não derruba o negócio; MCP e LLM não carregam campos livres.

## Limitações e volume

- **Um evento por operação de negócio concluída.** O volume acompanha o número de salvamentos e análises, não o de requisições HTTP. Autosave é o driver dominante: um projeto ativo gera um evento por autosave.
- O `span_id` do evento é o do **span HTTP**, não o do span de negócio, porque o `Scope` do span de negócio é (corretamente) fechado no retorno do método. O `trace_id` é o mesmo, então a navegação log → trace funciona; o que não existe é link log → span de negócio direto.
- `capture-key-value-pair-attributes` é configuração **experimental** do agente: o nome pode mudar entre versões maiores. Está fixada junto da versão do agente (2.30.0) no `Dockerfile`.
- `otel.event.name` não é consultável neste pipeline (ver LogQL). Se um dia for necessário filtrar por nome de evento diretamente, o caminho é a configuração de `otlp_config` do Loki, não mais um campo no código.
- Sem o agente anexado (`IWRITE_OTEL_ENABLED=false`, o padrão), os eventos continuam saindo no console pelo Logback, mas sem `trace_id`/`span_id` e sem exportação.
- Os eventos MCP não foram exercitados contra o LGTM real porque o servidor MCP é desabilitado por padrão; a consulta documentada segue a mesma regra de nomenclatura verificada para os demais (`scope_name`), e o formato do evento é coberto por teste unitário.
- Os logs de falha de persistência de auditoria do `LlmExecutionGateway` continuam como mensagens com placeholders (não key-value pairs) e mantêm o `auditId` no corpo: são caminhos de erro raros, e o `auditId` é a chave da linha órfã na tabela de auditoria — não é dado de usuário. Convertê-los não foi feito para não ampliar o escopo.
- O Loki do `otel-lgtm` local é efêmero: `docker compose down` descarta os logs. Não use este stack como evidência persistente.
