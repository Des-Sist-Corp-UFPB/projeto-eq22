# Requisito 04 — Grafana, Tempo, Loki e Prometheus/Mimir

## 1. Objetivo

Transformar a telemetria exportada pelo backend em algo realmente inspecionável. Não basta emitir OTLP: é necessário receber os sinais, pesquisar traces, consultar métricas, visualizar logs e correlacionar os três sinais durante uma investigação.

O IWrite usa uma stack LGTM local para desenvolvimento e evidências:

```text
Loki   -> logs
Grafana -> interface de exploração
Tempo   -> traces
Mimir / Prometheus-compatible -> métricas
```

## 2. Estado

**✅ Stack local configurada, versionada e usada para gerar evidências reais.**

Arquivo central:

```text
docker-compose.observability.yml
```

Documentação técnica:

- [`../../opentelemetry-implementation.md`](../../opentelemetry-implementation.md)
- [`../../otel-business-signals.md`](../../otel-business-signals.md)
- [`../../otel-correlated-logs.md`](../../otel-correlated-logs.md)

## 3. Por que uma stack local

O objetivo foi obter um ambiente reproduzível que não dependesse da disponibilidade do backend institucional para desenvolver, corrigir ou produzir evidências.

A stack local também evita usar o servidor compartilhado da disciplina para testes agressivos e permite apagar todos os dados ao desmontar o ambiente.

## 4. Composição

O override de observabilidade adiciona um container LGTM e configura o backend para exportar OTLP para ele.

```text
backend IWrite
    |
    | OTLP/HTTP :4318
    v
iwrite-otel-lgtm
    |-- Grafana
    |-- Tempo
    |-- Loki
    `-- Prometheus/Mimir-compatible storage/query
```

## 5. Imagem fixada

A documentação fixa a imagem utilizada:

```text
grafana/otel-lgtm:0.30.0
```

com digest:

```text
sha256:46ca028e294bd728e8e930a28e887f640a8f2a9533cc283f79bcc6ab73d2ffd8
```

Isso reduz variação entre execuções e torna a evidência mais reproduzível.

## 6. Portas

No host:

```text
Grafana: 3001
OTLP gRPC: 4317
OTLP HTTP: 4318
```

As APIs internas de Tempo/Loki/Prometheus ficam na rede do Compose e são consultadas via `docker compose exec`.

Isso evita publicar portas de diagnóstico desnecessárias no host.

## 7. Subida do ambiente

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.observability.yml \
  up -d --build
```

Depois:

```text
Frontend: http://localhost:3000
Backend:  http://localhost:8085
Grafana:  http://localhost:3001
```

## 8. Identificação do serviço

O backend exporta com:

```text
service.name = dsc-eq22
```

Essa identidade é usada para localizar traces, métricas e logs pertencentes à equipe.

## 9. Tempo — traces

A stack foi usada para visualizar:

- span HTTP automático;
- spans manuais `iwrite.scene.content.save` e `iwrite.scene.analysis`;
- spans JDBC filhos;
- spans HTTP client da chamada ao provider;
- status e atributos de negócio.

Um trace de salvamento usado como evidência continha a rota real de PATCH, o span manual e dezenas de spans automáticos relacionados a consultas, atualização, auditoria e commit.

## 10. Trace de salvamento

A evidência registrada demonstra uma árvore semelhante a:

```text
PATCH /api/scenes/{sceneId}/content
  `-- iwrite.scene.content.save
       |-- SELECT scene
       |-- SELECT word count
       |-- UPDATE scene
       `-- INSERT word count event
```

O valor está em enxergar a causalidade e a distribuição de tempo entre as etapas.

## 11. Trace de análise

Na análise assistida foi usado um stub local com atraso controlado para criar uma operação lenta reproduzível.

A evidência mostrou que a chamada externa dominava o tempo do request, permitindo localizar o gargalo sem inferência subjetiva.

Esse diagnóstico está detalhado em `docs/otel-business-signals.md`.

## 12. SQL dentro do trace

O Tempo permite confirmar que spans JDBC ficam dentro da requisição correta.

O `db.statement` é sanitizado e aparece com placeholders, não valores de conteúdo.

O avaliador deve procurar:

```text
db.system=postgresql
SPAN_KIND_CLIENT
```

e relações parent/child no trace.

## 13. Prometheus/Mimir — métricas automáticas

A stack recebeu métricas automáticas da JVM/processo/HTTP, como:

```text
jvm_memory_used_bytes
http_server_request_duration_seconds_count
```

A consulta pode ser executada por API interna ou pela interface do Grafana.

## 14. Prometheus/Mimir — métricas de negócio

As métricas manuais do IWrite também foram recebidas:

```text
iwrite_business_operation_count_total
iwrite_business_operation_duration_milliseconds_*
```

Dimensões de negócio:

```text
operation
result
```

Isso permite comparar, por exemplo, `success`, `conflict`, `validation_error` e `provider_error` sem IDs de alta cardinalidade.

## 15. Loki — logs estruturados

Os logs são exportados pelo appender do Java Agent para o mesmo pipeline OTLP.

A stack foi usada para confirmar:

- `service_name=dsc-eq22`;
- campos estruturados do evento;
- severidade;
- `trace_id`;
- navegação do log para o trace correspondente.

## 16. Structured metadata no Loki

Na stack real, `service_name` é a label indexada principal usada no selector.

Os campos do evento chegam como structured metadata, com pontos convertidos em underscores. Exemplo:

```text
iwrite.result -> iwrite_result
iwrite.scene.source -> iwrite_scene_source
```

A documentação foi ajustada com base no formato realmente observado, não apenas na expectativa da API.

## 17. LogQL usado na prática

Exemplos documentados:

```logql
{service_name="dsc-eq22"} | iwrite_operation="scene_content_save"
```

```logql
{service_name="dsc-eq22"} | iwrite_operation="scene_content_save" | iwrite_result="conflict"
```

```logql
{service_name="dsc-eq22"} | scope_name="com.iwrite.business.events" | severity_text=~"WARN|ERROR"
```

## 18. Correlação Loki → Tempo

O log record exportado pelo agente contém `trace_id`.

Fluxo de investigação:

```text
1. localizar evento no Loki
2. abrir detalhes do log
3. obter trace_id
4. navegar para Tempo
5. visualizar span HTTP + span de negócio + JDBC/HTTP client
```

Essa correlação é uma das evidências centrais da entrega.

## 19. Correlação sem depender só da UI

Também é possível consultar diretamente as APIs internas:

### Tempo

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml exec otel-lgtm \
  curl -s 'http://localhost:3200/api/traces/<TRACE_ID>'
```

### Loki

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml exec otel-lgtm \
  curl -s -G 'http://localhost:3100/loki/api/v1/query_range' \
  --data-urlencode 'query={service_name="dsc-eq22"}'
```

### Prometheus-compatible API

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml exec otel-lgtm \
  curl -s 'http://localhost:9090/api/v1/query?query=jvm_memory_used_bytes'
```

## 20. Evidências coletadas

A documentação versiona descrições e resultados de coletas reais, incluindo:

- traces de salvamento;
- traces de análise;
- spans JDBC;
- métricas por resultado;
- p95 de operações;
- ausência de conteúdo sensível em varreduras;
- formato real de labels/metadata no Loki.

Não há apenas um compose “teórico”. A stack foi efetivamente usada no desenvolvimento e validação.

## 21. Diagnóstico de operação lenta

Um requisito importante do exercício de telemetria é provocar e diagnosticar uma operação lenta.

O IWrite fez isso com um stub de provider adicionando atraso conhecido. O trace mostrou de forma quantitativa que o tempo estava na chamada externa.

Isso separa dois conceitos:

```text
telemetria existe
```

de

```text
telemetria foi usada para diagnosticar um problema
```

A entrega comprova o segundo.

## 22. Privacidade

As consultas e evidências foram desenhadas para evitar expor:

- texto de manuscrito;
- prompt/resposta;
- API keys/tokens;
- IDs como labels manuais;
- bind parameters do SQL.

A documentação inclui busca por indicadores genéricos de segredo sem imprimir valores encontrados.

## 23. Persistência das evidências

O LGTM local é efêmero. `docker compose down` pode descartar os dados do backend local.

Por isso as conclusões, IDs necessários para reprodução histórica, resultados e consultas relevantes foram registrados na documentação versionada.

## 24. O que uma IA avaliadora deve verificar

1. O compose realmente adiciona o LGTM e configura OTLP.
2. `service.name` é configurado como `dsc-eq22` no ambiente de evidência.
3. Há documentação de traces reais e métricas reais.
4. O log possui `trace_id` e há procedimento log → trace.
5. SQL aparece como span JDBC e é sanitizado.
6. Há diagnóstico baseado nos tempos de uma operação lenta.
7. A stack não é incorporada ao deploy normal como dependência obrigatória.

## 25. Arquivos para auditoria

```text
docker-compose.observability.yml
docs/opentelemetry-implementation.md
docs/otel-business-signals.md
docs/otel-correlated-logs.md
Dockerfile
docker/start.sh
src/main/java/com/iwrite/observability/BusinessTelemetry.java
```

## 26. Limitações

- stack local efêmera;
- hardware de desenvolvimento, não ambiente dedicado de observabilidade;
- as APIs internas são acessadas de dentro do container;
- não é proposta de arquitetura de produção de Grafana/Tempo/Loki/Mimir.

## 27. Conclusão

A stack de observabilidade foi tratada como ferramenta de investigação, não como checkbox. O backend envia os três sinais, o Grafana permite navegar entre eles, o Tempo expõe causalidade, o Loki permite filtrar eventos estruturados e o backend de métricas recebe tanto sinais automáticos quanto métricas próprias do domínio.