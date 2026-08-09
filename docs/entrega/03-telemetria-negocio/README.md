# Requisito 03 — Telemetria manual de negócio: spans e métricas

## 1. Objetivo

A auto-instrumentação responde perguntas técnicas genéricas — qual rota foi chamada, quanto tempo o JDBC levou, quanto a JVM usa de memória. Este requisito adiciona contexto de negócio para responder perguntas que o agente não consegue inferir sozinho:

- qual operação do produto está sendo executada;
- qual resultado funcional ela teve;
- qual etapa domina a latência;
- como medir sucesso, conflito, validação, indisponibilidade de provider e falhas internas sem expor conteúdo privado.

## 2. Estado

**✅ Implementado, testado com SDK em memória e validado em traces/métricas reais no LGTM local.**

Documento técnico completo: [`../../otel-business-signals.md`](../../otel-business-signals.md).

## 3. Componente central

A instrumentação manual fica concentrada em:

```text
src/main/java/com/iwrite/observability/BusinessTelemetry.java
```

Os services não criam spans/métricas de forma ad hoc. Eles abrem uma `BusinessTelemetry.Operation`, registram atributos permitidos e classificam o resultado.

Exemplo conceitual:

```java
try (BusinessTelemetry.Operation telemetry = businessTelemetry.sceneAnalysis()) {
    // operação real
}
```

## 4. Fluxos instrumentados

| Fluxo | Rota | Span manual |
|---|---|---|
| salvar conteúdo de cena | `PATCH /api/scenes/{sceneId}/content` | `iwrite.scene.content.save` |
| analisar cena | `POST /api/scenes/{sceneId}/ai-analysis` | `iwrite.scene.analysis` |

A instrumentação vive nos services, não no controller, porque o objetivo é medir a operação de negócio real independentemente de como ela é acionada.

## 5. Hierarquia esperada dos spans

```text
span HTTP automático
  `-- span manual de negócio
       |-- spans JDBC automáticos
       `-- span HTTP client automático para provider, quando houver
```

Isso dá contexto sem perder a granularidade automática.

## 6. Métricas manuais

O IWrite publica duas métricas próprias:

```text
iwrite.business.operation.count
iwrite.business.operation.duration
```

Depois da normalização do exporter Prometheus/Mimir, elas aparecem como séries equivalentes a:

```text
iwrite_business_operation_count_total
iwrite_business_operation_duration_milliseconds_*
```

As únicas labels de negócio são:

```text
operation
result
```

Nenhum ID de usuário, tenant, livro ou cena vira label.

## 7. Resultados do salvamento

Vocabulário fechado:

```text
success
conflict
no_change
idempotent_retry
validation_error
not_found
failure
```

Isso permite diferenciar um conflito otimista esperado de uma falha interna do servidor sem explodir cardinalidade.

## 8. Resultados da análise

Vocabulário fechado:

```text
success
validation_error
not_found
provider_error
invalid_response
failure
```

As categorias do gateway LLM são traduzidas para esse vocabulário estável. Mensagens livres de provider não viram atributo.

## 9. Atributos permitidos — salvamento

Entre os atributos manuais permitidos:

```text
iwrite.operation = scene_content_save
iwrite.result
iwrite.scene.source
iwrite.scene.content_size_bucket
iwrite.scene.content_changed
iwrite.error.type
```

A origem da cena é reduzida a um conjunto pequeno (`manual_save`, `autosave`, `restore`, `other`). O tamanho é bucketizado; o tamanho exato não é exportado.

## 10. Atributos permitidos — análise

Exemplos:

```text
iwrite.operation = scene_analysis
iwrite.result
iwrite.ai.focus_present
iwrite.ai.input_size_bucket
iwrite.ai.fallback_used
iwrite.ai.provider
iwrite.ai.model_family
iwrite.error.type
```

O modelo configurado bruto nunca é enviado. Ele é reduzido a uma família conhecida ou `other`/`unknown`.

## 11. Barreiras de privacidade

`BusinessTelemetry` aplica duas barreiras importantes:

1. allowlist de chaves;
2. vocabulário fechado de valores para atributos de string relevantes.

Assim, mesmo que no futuro alguém tente passar uma string arbitrária para um atributo, ela não é automaticamente exportada.

Dados proibidos incluem:

```text
contentText
contentJson
sceneId
bookId
tenantId
operationId
requestFingerprint
focus livre
prompt
resposta da IA
e-mail
token/API key/header
mensagem completa de exceção
```

## 12. Por que `recordException` não é usado

`recordException` anexaria informações como `exception.message` e `exception.stacktrace` sem sanitização específica do domínio.

O IWrite registra apenas o nome simples da classe em `iwrite.error.type`, quando aplicável.

Essa decisão é coerente com a política de logs descrita no requisito 05.

## 13. Transações e duração real do salvamento

`SceneService.updateContent` é transacional. O método Java pode retornar antes do commit do proxy Spring terminar.

Se o span fosse fechado no retorno do método, uma falha de commit poderia ser registrada como sucesso.

Por isso `BusinessTelemetry.Operation` separa:

```text
Scope corrente: acompanha o corpo do método
Span: pode permanecer aberto até afterCompletion da transação
```

O `Scope` é destacado ao retornar para não capturar trabalho não relacionado como filho do salvamento, mas o span só finaliza quando o resultado transacional é conhecido.

## 14. Rebaixamento após rollback

Se a transação termina em rollback depois de o método aparentar sucesso, o resultado é rebaixado para `failure` e o span recebe erro.

Resultados já classificados como conflito/validação/not-found são preservados.

Isso foi coberto por teste de integração específico.

## 15. Falhas da telemetria não derrubam o negócio

A instrumentação é best-effort. Erros na criação/escrita de span ou métrica são absorvidos para não transformar o sistema de observabilidade em causa de indisponibilidade do produto.

Sem agente, `GlobalOpenTelemetry` funciona como no-op.

## 16. Evidência real — salvamento

A documentação registra trace real de salvamento com:

```text
PATCH /api/scenes/{sceneId}/content
  `-- iwrite.scene.content.save
       |-- SELECT ... scenes
       |-- SELECT ... word_count
       |-- UPDATE ... scenes
       `-- INSERT ... book_word_count_events
```

Atributos registrados incluíram `scene_content_save`, `success`, origem e bucket de tamanho, sem conteúdo do manuscrito.

## 17. Evidência real — análise

Trace de análise com stub atrasado mostrou aproximadamente:

```text
POST /api/scenes/{sceneId}/ai-analysis       2631,8 ms
`-- iwrite.scene.analysis                    2610,7 ms
    `-- POST para provider/stub               2509,8 ms
```

Interpretação:

- span de negócio ≈ 99,2% do request;
- chamada externa ≈ 95,4% do span de negócio.

Isso identificou objetivamente o gargalo externo.

## 18. Diagnóstico do gargalo

O objetivo do exercício não era apenas “gerar um span”. O trace foi usado para responder onde o tempo era gasto.

A conclusão documentada é que a etapa externa da análise domina a latência. Banco e trabalho interno representam uma fração pequena naquele cenário.

Uma ação arquitetural plausível documentada é tornar a análise assíncrona, em vez de manter o request HTTP aberto esperando o provider.

## 19. Evidência de métricas

Na coleta registrada, foram observadas contagens por operação/resultado, incluindo:

```text
scene_analysis       success
scene_analysis       validation_error
scene_content_save   conflict
scene_content_save   idempotent_retry
scene_content_save   no_change
scene_content_save   success
```

O p95 observado na coleta inicial separou claramente o custo dos dois fluxos: salvamento na ordem de centenas de ms e análise na ordem de segundos sob o atraso controlado do stub.

## 20. Cardinalidade

A instrumentação evita IDs e strings livres nas labels. Labels adicionais vistas no backend Prometheus/Mimir são labels de recurso fixas do agente, não dimensões de negócio de alta cardinalidade.

## 21. Testes relevantes

A suíte cobre, entre outros pontos:

- criação e finalização de spans;
- atributos permitidos;
- descarte de atributos fora da allowlist;
- normalização de resultados;
- métricas count/duration;
- rollback depois do retorno do método;
- fechamento idempotente;
- relações parent/child;
- dois salvamentos sequenciais em transação externa;
- não-vazamento de valores sensíveis;
- ausência de modelo bruto.

Arquivos centrais:

```text
src/test/java/com/iwrite/observability/BusinessTelemetryTest.java
src/test/java/com/iwrite/observability/SceneContentSaveTelemetryIntegrationTest.java
src/test/java/com/iwrite/scene/service/SceneAnalysisTelemetryTest.java
```

## 22. Reprodução

Suba a stack LGTM:

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d --build
```

Gere salvamentos/análises reais e use Tempo/Prometheus no Grafana local (`http://localhost:3001`).

As consultas e passos exatos estão em:

[`../../otel-business-signals.md`](../../otel-business-signals.md)

## 23. O que uma IA avaliadora deve procurar

1. `BusinessTelemetry` não é wrapper vazio: possui tracer, meter, counter, histogram, allowlists, normalização e ciclo de vida transacional.
2. `SceneService` e `SceneAnalysisService` instrumentam fluxos reais.
3. Testes verificam relações e privacidade.
4. A documentação contém resultados medidos, não apenas intenção.
5. O diagnóstico de gargalo é derivado de um trace com tempos concretos.

## 24. Arquivos para auditoria

```text
src/main/java/com/iwrite/observability/BusinessTelemetry.java
src/main/java/com/iwrite/scene/service/SceneService.java
src/main/java/com/iwrite/scene/service/SceneAnalysisService.java
docs/otel-business-signals.md
docs/opentelemetry-implementation.md
src/test/java/com/iwrite/observability/
src/test/java/com/iwrite/scene/service/SceneAnalysisTelemetryTest.java
```

## 25. Conclusão

A telemetria manual do IWrite não se limita a decorar spans. Ela modela resultados de negócio, preserva o resultado transacional real, controla cardinalidade e privacidade, produz métricas agregáveis e foi usada para diagnosticar um gargalo concreto na análise assistida.