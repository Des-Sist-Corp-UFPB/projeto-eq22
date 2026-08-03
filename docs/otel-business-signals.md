# Sinais de negócio no OpenTelemetry (issue #125)

Complementa `docs/opentelemetry.md`, que cobre a instalação do agente. Aqui estão apenas os sinais **manuais** de negócio: dois spans e duas métricas emitidos por dois fluxos críticos do IWrite.

| Fluxo | Método instrumentado | Rota real |
|---|---|---|
| Salvamento de conteúdo de cena | `SceneService.updateContent(UUID, SceneContentRequest)` | `PATCH /api/scenes/{sceneId}/content` |
| Análise assistida de cena | `SceneAnalysisService.analyze(UUID, SceneAnalysisRequest)` | `POST /api/scenes/{sceneId}/ai-analysis` |

A instrumentação está nos serviços, não nos controllers, e não altera idempotência, revisão otimista, locks, checkpoints de versão, ledger de palavras, transações, ordem de validação, retorno das APIs, fallback do LLM ou sanitização da resposta da IA.

<a id="dependencias"></a>

## Dependências e versões

| Artefato | Versão | Escopo |
|---|---|---|
| `io.opentelemetry:opentelemetry-api` | 1.64.0 | compile |
| `io.opentelemetry:opentelemetry-sdk-testing` | 1.64.0 | **test** |
| OpenTelemetry Java Agent (`Dockerfile`) | 2.30.0 | runtime do container |

**Fonte de compatibilidade:** o `opentelemetry-instrumentation-bom` 2.30.0 — a mesma release do Java Agent embarcado no `Dockerfile` — importa `io.opentelemetry:opentelemetry-bom:1.64.0`. A versão é fixada no `pom.xml` pela propriedade `opentelemetry.version`, que sobrepõe o 1.43.0 gerenciado pelo Spring Boot 3.4.1.

Verificação:

```bash
curl -s https://repo1.maven.org/maven2/io/opentelemetry/instrumentation/\
opentelemetry-instrumentation-bom/2.30.0/opentelemetry-instrumentation-bom-2.30.0.pom \
  | grep -A2 opentelemetry-bom

./mvnw -s .mvn/local-settings.xml dependency:tree -Dincludes=io.opentelemetry
```

Só a **API** entra em `compile`. Nenhum SDK de produção concorre com o agente: `BusinessTelemetry` consome `GlobalOpenTelemetry.get()`, que é a instância instalada pelo agente quando ele está anexado e a implementação **no-op** quando não está. SDK e exporters em memória existem apenas em escopo de teste.

## Componente central

`com.iwrite.observability.BusinessTelemetry` concentra span, cronômetro (`System.nanoTime()`), counter, histograma, chaves de atributo permitidas, normalização de resultado, buckets de tamanho e tratamento seguro de falhas. Os serviços só abrem uma `Operation`, marcam atributos e classificam o resultado.

```java
try (BusinessTelemetry.Operation telemetry = businessTelemetry.sceneAnalysis()) {
    // corpo de negócio inalterado
}
```

A `Operation` torna o span **corrente** enquanto está aberta, então spans automáticos gerados dentro dela (JDBC, HTTP client) ficam aninhados sob o span de negócio, que por sua vez é filho do span HTTP do agente.

### Ciclo de vida do span em `updateContent`: acompanha a transação, não o retorno do método

`SceneAnalysisService.analyze` não é `@Transactional`, então `try`-with-resources fecha a `Operation` no retorno do método sem perda: não há commit pendente depois dele.

`SceneService.updateContent` **é** `@Transactional`. O proxy do Spring inicia a transação antes de chamar o método e só faz `flush`/commit **depois** que ele retorna. Fechar a `Operation` com `try`-with-resources nesse caso classificaria o span como `success` antes de o commit acontecer — uma falha exclusiva do flush/commit apareceria para o cliente como erro HTTP enquanto o span e a métrica de negócio já teriam sido gravados como sucesso.

Por isso `updateContent` não usa `try`-with-resources. Em vez disso:

```java
BusinessTelemetry.Operation telemetry = businessTelemetry.sceneContentSave();
boolean closesWithTransaction = telemetry.deferCloseToTransaction();
try {
    return updateContent(sceneId, request, telemetry);
} catch (ConflictException conflict) {
    telemetry.failure(BusinessTelemetry.RESULT_CONFLICT, conflict);
    throw conflict;
} catch (RuntimeException failure) {
    telemetry.failure(BusinessTelemetry.RESULT_FAILURE, failure);
    throw failure;
} finally {
    if (!closesWithTransaction) {
        telemetry.close();
    }
}
```

`Operation.deferCloseToTransaction()` registra um `TransactionSynchronization` via `TransactionSynchronizationManager` quando há sincronização transacional ativa, e devolve `true`. Nesse caso o método **não fecha o span ao retornar**: o `afterCompletion` do callback é quem fecha, depois que o proxy já fez `flush` e commit (ou rollback) — a duração do span passa a incluir esse tempo. Quando não há transação ativa (ou o registro falha), `deferCloseToTransaction()` devolve `false` e o `finally` fecha a `Operation` imediatamente, exatamente como antes.

Como o `afterCompletion` roda no mesmo thread, antes de o proxy devolver o controle ao chamador, o `Scope` é sempre fechado exatamente uma vez, no lugar certo — nunca vaza para fora da requisição.

No callback:

| `afterCompletion(status)` | Efeito no resultado já registrado |
|---|---|
| `STATUS_COMMITTED` | Mantém o resultado como estava (inclui `success`, `no_change`, `idempotent_retry`, `conflict`) |
| `STATUS_ROLLED_BACK` ou `STATUS_UNKNOWN` | Um resultado já classificado por `failure(...)` (ex.: `conflict`) é preservado; qualquer outro — inclusive `success`, `no_change` e `idempotent_retry` — é rebaixado para `failure`. O span sempre recebe `StatusCode.ERROR`. Nenhuma mensagem ou tipo de exceção é inventado: o callback não tem acesso seguro à causa. |

Falha ao registrar a sincronização (infraestrutura de telemetria) nunca propaga: `deferCloseToTransaction()` engole `RuntimeException` e devolve `false`, caindo no fechamento imediato pelo `finally`.

## Spans

| Span | Quando |
|---|---|
| `iwrite.scene.content.save` | uma execução de `updateContent`, incluindo caminhos de conflito, sem alteração e retry idempotente |
| `iwrite.scene.analysis` | uma execução de `analyze`, incluindo erro de validação e falha do provider |

Em falha o span recebe `StatusCode.ERROR` e no máximo o **nome simples da classe** da exceção em `iwrite.error.type`. `recordException` **não** é usado: ele anexaria `exception.message` e `exception.stacktrace` ao span sem sanitização.

## Métricas

| Métrica | Instrumento | Unidade |
|---|---|---|
| `iwrite.business.operation.count` | counter (`Long`) | `{operation}` |
| `iwrite.business.operation.duration` | histogram (`Double`) | **`ms` (milissegundos)** |

A duração é medida com `System.nanoTime()` e convertida para milissegundos em ponto flutuante. Ambas as métricas usam exatamente **duas** labels:

```text
operation
result
```

Nenhum ID, revisão, tamanho exato ou string livre vira label. Como o exportador Prometheus/Mimir normaliza nome e unidade, as séries aparecem como `iwrite_business_operation_count_total` e `iwrite_business_operation_duration_milliseconds_*`.

## Atributos permitidos

Só estas chaves podem ser escritas; qualquer outra é descartada.

### `iwrite.scene.content.save`

| Atributo | Valores |
|---|---|
| `iwrite.operation` | `scene_content_save` |
| `iwrite.result` | `success`, `conflict`, `no_change`, `idempotent_retry`, `failure` |
| `iwrite.scene.source` | `manual_save`, `autosave`, `restore`, `other` |
| `iwrite.scene.content_size_bucket` | `empty`, `small`, `medium`, `large`, `truncated` |
| `iwrite.scene.content_changed` | booleano |
| `iwrite.error.type` | nome simples da classe da exceção (só em falha) |

`truncated` faz parte do vocabulário do bucket, mas o salvamento não trunca conteúdo: na prática esse valor só aparece na análise assistida.

### `iwrite.scene.analysis`

| Atributo | Valores |
|---|---|
| `iwrite.operation` | `scene_analysis` |
| `iwrite.result` | `success`, `validation_error`, `provider_error`, `invalid_response`, `failure` |
| `iwrite.ai.focus_present` | booleano |
| `iwrite.ai.input_size_bucket` | `small`, `medium`, `large`, `truncated` |
| `iwrite.ai.fallback_used` | booleano |
| `iwrite.ai.provider` | `openai`, `disabled` |
| `iwrite.ai.model_family` | `gpt-4o`, `gpt-4.1`, `gpt-5`, `other`, `unknown` |
| `iwrite.error.type` | nome simples da classe da exceção (só em falha) |

### Buckets de tamanho

Medidos em caracteres Java do texto (`contentText` no salvamento; texto enviado ao modelo na análise).

| Bucket | Faixa |
|---|---|
| `empty` | 0 |
| `small` | 1 – 1 999 |
| `medium` | 2 000 – 19 999 |
| `large` | ≥ 20 000 |
| `truncated` | entrada excedeu o limite do modelo (12 000 caracteres) e foi cortada |

O tamanho exato nunca é registrado.

### Como os resultados são decididos

| Fluxo | Resultado | Gatilho |
|---|---|---|
| salvamento | `idempotent_retry` | `operationId` já reservado com o mesmo fingerprint |
| salvamento | `no_change` | conteúdo idêntico ao persistido |
| salvamento | `conflict` | `ConflictException` (revisão obsoleta) ou `WordCountEventConflictException` |
| salvamento | `failure` | qualquer outra `RuntimeException` |
| salvamento | `success` | nenhum dos anteriores |
| análise | `validation_error` | `BadRequestException` (ex.: cena sem texto) |
| análise | `invalid_response` | categoria `INVALID_STRUCTURED_RESPONSE` do gateway |
| análise | `provider_error` | categorias `PROVIDER_TIMEOUT`, `PROVIDER_UNAVAILABLE`, `PROVIDER_REQUEST_REJECTED` |
| análise | `failure` | `CONFIGURATION_ERROR`, `FEATURE_DISABLED`, `AUDIT_PERSISTENCE_FAILURE`, `INTERNAL_EXECUTION_ERROR` ou exceção não classificada |

A classificação vem da **categoria estável** do `LlmExecutionGateway`, nunca da mensagem da exceção.

## Dados proibidos

Nunca aparecem como atributo, evento, nome de span, nome de métrica, label ou mensagem manual:

`sceneId`, `bookId`, `tenantId`, `contentJson`, `contentText`, título, resumo, `focus`, prompt, resposta da IA, e-mail, token, API key, header, `operationId`, `requestFingerprint`, revisão exata, tamanho exato, mensagem completa de exceção e qualquer texto livre do usuário.

Barreiras no `BusinessTelemetry` garantem isso mesmo diante de um erro de chamada futuro:

1. **Chave na allowlist** — atributo com chave fora da lista acima é descartado.
2. **Vocabulário fechado por atributo (barreira principal)** — `iwrite.scene.source`, os buckets de tamanho, `iwrite.ai.provider` e `iwrite.ai.model_family` só aceitam um conjunto pequeno e explícito de valores (`CLOSED_VOCABULARIES`); qualquer string fora desse conjunto é descartada, **independentemente do formato**. É essa barreira, e não uma lista de prefixos proibidos, que impede uma credencial colocada em `OPENAI_MODEL` de aparecer em `iwrite.ai.model_family`: `BusinessTelemetry.modelFamily(...)` normaliza o identificador configurado para um dos cinco valores da tabela acima e **nunca** encaminha o valor bruto para o span.
3. **Filtro de formato (só para `iwrite.error.type`)** — o único atributo de string sem vocabulário fechado, porque só recebe o nome simples de uma classe de exceção. A string precisa casar `[A-Za-z0-9][A-Za-z0-9._:/-]{0,63}`, não ter formato UUID e não começar com um prefixo de credencial conhecido (`sk-`, `ghp_`, `github_pat_`, `Bearer-`, `eyJ...`, etc.) — essa última checagem é uma camada adicional, não a barreira principal. Texto com espaços ou acentos, e-mail (`@`) e JSON falham no filtro e são descartados silenciosamente.

Resultado fora do vocabulário da operação é normalizado para `failure`, então uma string livre jamais vira label de métrica.

## Falha de telemetria não derruba a operação

Toda escrita de span/métrica está em `try/catch` que engole `RuntimeException`. Se o tracer falhar na criação, a `Operation` cai para `Span.getInvalid()` + `Scope.noop()` e o fluxo de negócio segue normalmente. Sem agente, tudo vira no-op. `deferCloseToTransaction()` segue a mesma regra: se registrar a sincronização falhar, ela devolve `false` em vez de propagar, e o `finally` do chamador fecha a `Operation` imediatamente.

## Testes

```bash
./mvnw -s .mvn/local-settings.xml test -Dtest='BusinessTelemetryTest,SceneAnalysisTelemetryTest,SceneContentSaveTelemetryIntegrationTest'
```

| Arquivo | Cobre |
|---|---|
| `BusinessTelemetryTest` | span + counter + histograma, aninhamento sob pai, sucesso ≠ falha, só nome de classe da exceção, descarte de valor/chave inválidos, normalização de resultado, labels limitadas, buckets, no-op sem SDK, `close()` idempotente, ciclo de vida `deferCloseToTransaction()` (span aberto até a conclusão, commit vira sucesso, rollback rebaixa sucesso/`no_change`/`idempotent_retry` para falha, `conflict` já classificado sobrevive ao rollback, `STATUS_UNKNOWN` nunca vira sucesso, ausência de transação não vaza span/`Scope`, falha ao registrar a sincronização não quebra o negócio), `modelFamily(...)` normaliza para o vocabulário fechado, vocabulário fechado aceita todo valor válido e rejeita todo canário de credencial em qualquer atributo exportado |
| `SceneAnalysisTelemetryTest` | serviço real com SDK em memória: sucesso, aninhamento, fallback só booleano, `validation_error`, `provider_error` sem vazar mensagem, `invalid_response`, bucket `truncated`, ausência de conteúdo/título/focus/resposta/UUID, `iwrite.ai.model_family` para modelo reconhecido/desconhecido/ausente/em formato de credencial |
| `SceneContentSaveTelemetryIntegrationTest` | fluxo real contra PostgreSQL: `success`, `autosave` vs `manual_save`, `no_change`, `idempotent_retry`, `conflict`, aninhamento sob span HTTP, rollback de uma transação externa **depois** de `updateContent` já ter retornado com sucesso rebaixa o span para `failure` |

Os testes usam exporters em memória e verificam os spans e métricas **efetivamente produzidos** — nenhum deles procura strings no código-fonte.

## Reproduzir localmente

O stack de evidências combina três arquivos: base, observabilidade (LGTM) e um stub local do provider de IA. O stub existe **somente** para evidência: nunca entra no `Dockerfile` nem em deploy, e é o único lugar do repositório com atraso artificial.

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.observability.yml \
  -f docker-compose.llm-stub.yml \
  up --build -d
```

Grafana em `http://localhost:3001`. Sem o terceiro arquivo, a análise assistida responde com provider desabilitado — útil para reproduzir o caminho de falha.

### Sucesso — salvamento de conteúdo

```bash
BOOK=$(curl -s -X POST localhost:8085/api/books -H 'Content-Type: application/json' \
  -d '{"title":"Evidencia OTel"}' | sed -E 's/.*"id":"([^"]+)".*/\1/')
SECTION=$(curl -s -X POST localhost:8085/api/books/$BOOK/sections -H 'Content-Type: application/json' \
  -d '{"title":"Parte","type":"PART","sortOrder":0}' | sed -E 's/.*"id":"([^"]+)".*/\1/')
CHAPTER=$(curl -s -X POST localhost:8085/api/sections/$SECTION/chapters -H 'Content-Type: application/json' \
  -d '{"title":"Capitulo","sortOrder":0}' | sed -E 's/.*"id":"([^"]+)".*/\1/')
SCENE=$(curl -s -X POST localhost:8085/api/chapters/$CHAPTER/scenes -H 'Content-Type: application/json' \
  -d '{"title":"Cena","status":"DRAFT","sortOrder":0,"contentJson":"{}","contentText":"texto inicial"}' \
  | sed -E 's/.*"id":"([^"]+)".*/\1/')

curl -s -X PATCH localhost:8085/api/scenes/$SCENE/content -H 'Content-Type: application/json' \
  -d "{\"contentJson\":\"{}\",\"contentText\":\"texto novo\",\"source\":\"MANUAL_SAVE\",\"expectedContentRevision\":0,\"operationId\":\"$(uuidgen)\"}"
```

Produz `iwrite.result=success`, `iwrite.scene.content_changed=true`, `iwrite.scene.source=manual_save`.

### Falha — conflito de revisão

Repetir o `PATCH` acima com o mesmo `expectedContentRevision:0` e um `operationId` novo produz HTTP 409 e `iwrite.result=conflict`, `iwrite.error.type=ConflictException`.

Enviar o mesmo `operationId` e o mesmo corpo duas vezes produz `iwrite.result=idempotent_retry`; enviar conteúdo idêntico ao persistido produz `no_change`.

### Sucesso e falha — análise assistida

```bash
curl -s -X POST localhost:8085/api/scenes/$SCENE/ai-analysis -H 'Content-Type: application/json' \
  -d '{"focus":"ritmo"}'
```

Com o stub no ar: `iwrite.result=success`, `iwrite.ai.provider=openai`, `iwrite.ai.fallback_used=false`, `iwrite.ai.focus_present=true`. Sem o stub: `iwrite.result=failure` com `iwrite.ai.provider=disabled`. Com uma cena sem texto: `iwrite.result=validation_error`.

## Consultas de investigação

As APIs do Tempo (3200) e do Prometheus/Mimir (9090) não são publicadas no host; use `docker compose exec otel-lgtm curl`. Abreviando `docker compose -f docker-compose.yml -f docker-compose.observability.yml -f docker-compose.llm-stub.yml` como `DC`:

**Traces que contêm o span de salvamento:**

```bash
$DC exec otel-lgtm curl -s --get 'http://localhost:3200/api/search' \
  --data-urlencode 'q={ name = "iwrite.scene.content.save" }'
```

**Só as análises que falharam no provider:**

```bash
$DC exec otel-lgtm curl -s --get 'http://localhost:3200/api/search' \
  --data-urlencode 'q={ name = "iwrite.scene.analysis" && span.iwrite.result = "provider_error" }'
```

**Salvamentos lentos (> 250 ms):**

```bash
$DC exec otel-lgtm curl -s --get 'http://localhost:3200/api/search' \
  --data-urlencode 'q={ name = "iwrite.scene.content.save" && duration > 250ms }'
```

**Trace completo (inclui os spans JDBC filhos):**

```bash
$DC exec otel-lgtm curl -s 'http://localhost:3200/api/traces/<TRACE_ID>'
```

**Contagem de sucesso e falha por fluxo:**

```promql
sum by (operation, result) (iwrite_business_operation_count_total)
```

```bash
$DC exec otel-lgtm curl -s --get 'http://localhost:9090/api/v1/query' \
  --data-urlencode 'query=sum by (operation, result) (iwrite_business_operation_count_total)'
```

**Taxa de erro da análise:**

```promql
sum(rate(iwrite_business_operation_count_total{operation="scene_analysis",result!="success"}[5m]))
  / sum(rate(iwrite_business_operation_count_total{operation="scene_analysis"}[5m]))
```

**Latência p95 dos dois fluxos:**

```promql
histogram_quantile(0.95,
  sum by (le, operation) (rate(iwrite_business_operation_duration_milliseconds_bucket[5m])))
```

**Latência média por resultado:**

```promql
sum by (operation, result) (rate(iwrite_business_operation_duration_milliseconds_sum[5m]))
  / sum by (operation, result) (rate(iwrite_business_operation_duration_milliseconds_count[5m]))
```

**Conferir que as labels não têm cardinalidade dinâmica** — o resultado deve conter apenas `operation`, `result` e as labels de recurso injetadas pelo agente (`service_name`, `job`, `instance`):

```bash
$DC exec otel-lgtm curl -s --get 'http://localhost:9090/api/v1/series' \
  --data-urlencode 'match[]=iwrite_business_operation_count_total'
```

## Evidência coletada (2026-08-01, LGTM local)

Stack: `docker-compose.yml` + `docker-compose.observability.yml` + `docker-compose.llm-stub.yml`, agente 2.30.0 confirmado no log do backend (`opentelemetry-javaagent - version: 2.30.0`). Todo o tráfego passou pelas rotas reais (`PATCH /api/scenes/{sceneId}/content` e `POST /api/scenes/{sceneId}/ai-analysis`). IDs abaixo pertencem a dados descartáveis criados só para esta coleta.

### Traces

Os cinco resultados foram localizados por TraceQL, cada um pelo próprio atributo:

| Consulta | Resultado |
|---|---|
| `{ name = "iwrite.scene.content.save" && span.iwrite.result = "success" }` | encontrado |
| `… = "conflict" }` | encontrado |
| `… = "no_change" }` | encontrado |
| `… = "idempotent_retry" }` | encontrado |
| `{ name = "iwrite.scene.analysis" && span.iwrite.result = "validation_error" }` | encontrado |

Trace de salvamento bem-sucedido (`PATCH`, 976 ms no total):

```text
976.5ms  PATCH /api/scenes/{sceneId}/content          (agente, SPAN_KIND_SERVER)
828.1ms  └─ iwrite.scene.content.save                 (manual, com.iwrite.observability)
             iwrite.operation           = scene_content_save
             iwrite.result              = success
             iwrite.scene.source        = manual_save
             iwrite.scene.content_size_bucket = small
             iwrite.scene.content_changed = true
  9.3ms     ├─ SELECT iwrite            (jdbc) select ... from scenes ... where s1_0.id=? and b1_0.tenant_id=?
  2.7ms     ├─ SELECT iwrite.scenes     (jdbc) select coalesce(sum(s1_0.word_count),?) from scenes s1_0 where s1_0.book_id=?
  3.8ms     ├─ UPDATE iwrite.scenes     (jdbc) update scenes set ... content_revision=? ...
  9.9ms     └─ INSERT iwrite.book_word_count_events   (jdbc)
```

Trace de análise (chamada morna, stub com 2 500 ms de atraso):

```text
2631.8ms  POST /api/scenes/{sceneId}/ai-analysis      (agente)
2610.7ms  └─ iwrite.scene.analysis                    (manual)  → 99,2 % do request
2509.8ms       └─ POST                                (java-http-client) → 95,4 % do span de negócio
```

Span de falha de validação (`iwrite.result=validation_error`), exatamente como esperado — status de erro, classe da exceção e **nenhum evento**:

```text
status = STATUS_CODE_ERROR
iwrite.operation       = scene_analysis
iwrite.result          = validation_error
iwrite.ai.focus_present = false
iwrite.error.type      = BadRequestException
events                 = []
```

### Métricas

```text
operation            result             valor
scene_analysis       success            4
scene_analysis       validation_error   1
scene_content_save   conflict           1
scene_content_save   idempotent_retry   1
scene_content_save   no_change          1
scene_content_save   success            7
```

p95 no intervalo (`histogram_quantile(0.95, …)`): `scene_content_save` ≈ 241 ms, `scene_analysis` ≈ 4 875 ms.

Labels efetivamente publicadas em `iwrite_business_operation_count_total`:

```text
__name__, operation, result, host_name, instance, job, service_instance_id, service_name, service_version
```

Só `operation` e `result` vêm da instrumentação. As demais são labels de **recurso** injetadas pelo agente, todas fixas por instância — nenhuma cresce com tráfego, usuário ou conteúdo.

### Ausência de conteúdo sensível

Varredura nos seis traces do período procurando os textos privados usados no teste (`"texto novo salvo manualmente"`, `"autosave incremental"`, título do livro, `focus` enviado, resposta do modelo e a credencial falsa do stub): **nenhuma ocorrência**. A checagem de indicadores de credencial no Loki (`Authorization|Bearer|sk-`) também retornou vazio. Todos os `db.statement` aparecem parametrizados, só com `?`.

## Evidência da correção dos dois P2 do Codex (2026-08-03, LGTM local, head `db57fe1` + correções)

Mesma stack (`docker compose -p iwrite-otel -f docker-compose.yml -f docker-compose.observability.yml -f docker-compose.llm-stub.yml`), agente 2.30.0. Reproduz as duas correções descritas em "Ciclo de vida do span em `updateContent`" e "Atributos permitidos" acima.

### 1. O span de salvamento agora inclui o commit da transação

Trace de salvamento bem-sucedido (`PATCH`, 396 ms no total):

```text
396.1ms  PATCH /api/scenes/{sceneId}/content          (agente)
349.9ms  └─ iwrite.scene.content.save                 (manual)
              iwrite.result = success
   …        ├─ SELECT/UPDATE/INSERT (jdbc + hibernate, ~20 spans)
 66.4ms      └─ Transaction.commit                    (hibernate-6.0)
```

`Transaction.commit` (66,4 ms) é filho direto de `iwrite.scene.content.save` — o commit acontece **dentro** do span de negócio, não depois dele. Antes da correção o span fechava no retorno do método Java, antes do proxy `@Transactional` chamar commit; agora `Operation.deferCloseToTransaction()` só fecha em `afterCompletion`, então o commit está incluído na duração e uma falha de commit seria classificada como `failure`, não `success` (comportamento validado por teste, não reproduzido ao vivo — ver limitações abaixo).

Também encontrados nesta rodada, cada um pelo próprio atributo:

| Consulta TraceQL | Resultado |
|---|---|
| `{ name = "iwrite.scene.content.save" && span.iwrite.result = "success" }` | encontrado |
| `{ name = "iwrite.scene.content.save" && span.iwrite.result = "conflict" }` | encontrado |
| `{ name = "iwrite.scene.analysis" && span.iwrite.result = "success" }` | encontrado |
| `{ name = "iwrite.scene.analysis" && span.iwrite.ai.model_family = "gpt-4o" }` | encontrado (modelo configurado `gpt-4o-mini`, sem override) |
| `{ name = "iwrite.scene.analysis" && span.iwrite.ai.model_family = "other" }` | encontrado (backend recriado com `OPENAI_MODEL=sk-test-canary`) |

Métricas com labels só `operation`/`result` confirmadas de novo via `iwrite_business_operation_count_total` — nenhuma label nova alterou a cardinalidade.

### 2. Nenhum atributo `iwrite.ai.model` (bruto ou não) chega ao span; `iwrite.ai.model_family` sempre no vocabulário fechado

Com `OPENAI_MODEL=sk-test-canary` configurado no backend (via override de `docker compose`, não commitado), o span de análise correspondente trouxe:

```text
iwrite.ai.provider      = openai
iwrite.ai.model_family  = other
```

Não existe atributo `iwrite.ai.model` em nenhum span — o atributo foi removido, substituído por `iwrite.ai.model_family`.

Busca pelos canários no JSON completo dos dois traces de análise (modelo reconhecido e modelo-canário) e nos logs do Loki do período:

```text
sk-test-canary                          -> não encontrado
sk-proj-test-canary                     -> não encontrado
Bearer-test-canary                      -> não encontrado
ghp_test_canary                         -> não encontrado
github_pat_test_canary                  -> não encontrado
eyJhbGciOiJIUzI1NiJ9.test.signature     -> não encontrado
email@example.com                       -> não encontrado
```

Nenhum canário apareceu em nenhum span, atributo, evento ou linha de log — nem mesmo o canário que foi realmente usado como valor de `OPENAI_MODEL` nesta rodada (`sk-test-canary`).

### Limitações desta rodada

- **Falha de commit não foi provocada ao vivo.** Forçar um `flush`/commit falhar sem alterar código de produção (sem `sleep`, sem `flush()` artificial, sem endpoint novo) não é reproduzível de forma limpa via API neste ambiente; essa cobertura vem do teste de integração `SceneContentSaveTelemetryIntegrationTest.rollbackAfterTheTransactionalMethodReturnsDemotesSuccessToFailure`, que rola de volta uma transação real do Postgres **depois** que `updateContent` já retornou com sucesso e comprova que o span vira `failure`.
- **O override `OPENAI_MODEL=sk-test-canary`** existiu apenas no container local desta coleta (arquivo de override fora do repositório), nunca em `docker-compose.llm-stub.yml` nem em qualquer arquivo versionado.

<a id="gargalo"></a>

## Gargalo

**A etapa externa da análise assistida domina a latência.** Não é hipótese: no trace morno, o span de negócio `iwrite.scene.analysis` responde por 99,2 % do request HTTP e a chamada HTTP de saída ao provider responde por 95,4 % desse span (2 509,8 ms de 2 610,7 ms). Todo o trabalho de banco do fluxo — carga da cena, verificação de acesso e os dois registros de auditoria do LLM — soma menos de 120 ms. O p95 medido confirma a diferença de ordem de grandeza entre os dois fluxos: ~241 ms no salvamento contra ~4 875 ms na análise.

O atraso foi provocado de forma controlada pelo stub local (2 500 ms fixos), que **não existe em produção**; em produção o mesmo span mede a latência real do provider. O ponto que a evidência estabelece é estrutural e independe do valor: a instrumentação isola corretamente a etapa externa como dominante, e o span manual entrega esse diagnóstico sem expor prompt nem resposta.

**Hipótese de correção:** tornar a análise assíncrona em vez de manter o request HTTP aberto durante toda a chamada ao provider — aceitar a requisição com `202`, executar no gateway e entregar o resultado por polling ou push. Isso libera a thread do Tomcat e desacopla o p95 do endpoint da latência do provider. Como o `LlmExecutionGateway` já persiste início e término da execução com `traceId`, o estado necessário para essa entrega já existe. Medida secundária: reduzir `OPENAI_READ_TIMEOUT` (hoje 60 s) para um valor mais próximo do p99 observado, de modo que uma chamada travada falhe rápido em vez de segurar a conexão por um minuto.

### Query PostgreSQL filha identificada

```sql
select coalesce(sum(s1_0.word_count), ?) from scenes s1_0 where s1_0.book_id = ?
```

**Finalidade:** `SceneService.updateContent` chama `sceneRepository.sumWordCountByBookId(bookId)` para obter o total de palavras do livro **antes** da escrita, valor que entra no evento do ledger (`totalBefore + delta`) e alimenta o rollup diário de progresso. É executada em toda gravação que altera conteúdo e também no caminho `no_change`.

**Plano real** (livro de teste com 400 cenas, 48 000 palavras):

```text
Aggregate  (cost=36.39..36.40 rows=1) (actual time=0.334..0.336 rows=1)
  ->  Seq Scan on scenes s1_0  (cost=0.00..35.50 rows=356) (actual time=0.013..0.297 rows=400)
        Filter: (book_id = '…'::uuid)
Execution Time: 0.399 ms
```

O planejador **ignora** `idx_scenes_book_id` e varre a tabela `scenes` inteira — a mesma tabela que guarda `content_json` e `content_text`. O custo é O(cenas do livro) e cresce junto com o manuscrito.

**Hipótese de otimização:** o valor já é conhecido sem recalcular. O ledger `book_word_count_events` mantém o total corrente por livro, então ler o último total registrado troca o agregado por uma leitura indexada de uma linha. Alternativa mais barata de implementar, mantendo a query: um índice de cobertura `create index on scenes (book_id) include (word_count)` permite index-only scan e evita tocar as páginas largas de `scenes`.

**O que a medição mostra hoje:** com 400 cenas o agregado custa 1,8 ms de um span de negócio de 202 ms — ou seja, **ainda não é o gargalo nesta escala**. A otimização é preventiva, e o sinal para reavaliar já existe: `iwrite.scene.content.save` sobe junto com o tamanho do livro sem que nenhuma outra etapa cresça.

## Limitações das evidências

- **O atraso do provider é sintético.** Os 2 500 ms vêm do stub local, não de um modelo real. A proporção entre as etapas é evidência sólida; o valor absoluto não é.
- **`sumWordCountByBookId` foi medida com 400 cenas em Postgres local com cache quente.** Um manuscrito real maior, disco mais lento ou concorrência mudam o número; a forma do plano (Seq Scan) é o que se sustenta.
- **`iwrite.ai.fallback_used` é sempre `false` hoje.** `LlmCallResult.withFallbackUsed()` não é acionado por nenhum caminho de produção. O atributo existe para quando um fallback for introduzido; os testes cobrem os dois valores.
- **Volume pequeno.** Cerca de uma dúzia de requisições, uma instância, um tenant. Serve para provar existência, aninhamento, classificação e cardinalidade dos sinais — não para caracterizar performance de produção.
- **`truncated` não foi observado no salvamento**, apenas no vocabulário. Só a análise trunca entrada.
- Os IDs e textos usados são de dados descartáveis criados para a coleta; nenhum conteúdo privado real aparece aqui.
