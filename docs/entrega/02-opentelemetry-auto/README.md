# Requisito 02 — OpenTelemetry: instrumentação automática e exportação OTLP

## 1. Objetivo

Instrumentar o backend do IWrite sem reescrever cada controller/service manualmente, exportando traces, métricas e logs por OTLP, com configuração segura e substituível por ambiente.

O requisito não é simplesmente “ter uma biblioteca OpenTelemetry no `pom.xml`”. O objetivo é provar que uma requisição real gera spans HTTP/JDBC, métricas de runtime e logs correlacionáveis, mantendo a aplicação funcional quando a telemetria está desligada.

## 2. Estado

**✅ Implementado e validado com OpenTelemetry Java Agent 2.30.0.**

Documentação técnica: [`../../opentelemetry-implementation.md`](../../opentelemetry-implementation.md).

Guia oficial preservado da disciplina: [`../../opentelemetry.md`](../../opentelemetry.md).

## 3. Arquitetura

```text
Spring Boot / Java 21
   |
   | -javaagent:/app/otel/opentelemetry-javaagent.jar
   v
OpenTelemetry Java Agent 2.30.0
   |
   | auto-instrumentação
   |-- HTTP server
   |-- JDBC/PostgreSQL
   |-- Hibernate
   |-- Java HTTP client
   |-- JVM/process metrics
   |-- Logback
   |
   | OTLP/HTTP
   v
backend de observabilidade
   |-- Tempo
   |-- Loki
   `-- Prometheus/Mimir
```

## 4. Por que Java Agent

O Java Agent permite instrumentar bibliotecas/frameworks em runtime sem espalhar código de observabilidade por controllers e repositories.

Isso preserva separação de responsabilidades:

- telemetria automática fica no agente;
- telemetria específica de negócio fica em `BusinessTelemetry`;
- regras de negócio continuam independentes do SDK de produção.

## 5. Versão e integridade do agente

O `Dockerfile` fixa a versão **2.30.0** do OpenTelemetry Java Agent.

A documentação registra também o SHA-256 esperado do artefato:

```text
9d6bc2ad8dd8fb7f730984988e57b8ac0a82d81c7b3b8ae795378718733a509d
```

O jar fica em:

```text
/app/otel/opentelemetry-javaagent.jar
```

Isso reduz duas classes de problema: atualização silenciosa de versão e download de conteúdo diferente do esperado.

## 6. Telemetria desligada por padrão

`IWRITE_OTEL_ENABLED=false` é o comportamento padrão.

Quando desligado:

- o agente não é anexado;
- nenhuma variável `OTEL_*` é exigida;
- a aplicação continua funcionando normalmente;
- não há dependência operacional do Grafana/collector para iniciar o produto.

Essa propriedade foi deliberada para que uma integração externa de observabilidade nunca vire pré-requisito para desenvolvimento normal.

## 7. Telemetria habilitada

Quando `IWRITE_OTEL_ENABLED=true`, `docker/start.sh` valida configuração antes de iniciar o processo Java.

Variáveis principais:

```text
OTEL_SERVICE_NAME
OTEL_EXPORTER_OTLP_ENDPOINT
OTEL_EXPORTER_OTLP_HEADERS   # quando autenticação é exigida
OTEL_EXPORTER_OTLP_PROTOCOL
OTEL_TRACES_EXPORTER
OTEL_METRICS_EXPORTER
OTEL_LOGS_EXPORTER
```

Defaults usados quando habilitado:

```env
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
OTEL_TRACES_EXPORTER=otlp
OTEL_METRICS_EXPORTER=otlp
OTEL_LOGS_EXPORTER=otlp
```

## 8. Fail-safe / fail-fast da configuração

O projeto diferencia dois cenários:

- OTel desabilitado: nenhuma configuração externa necessária;
- OTel habilitado: variáveis essenciais ausentes causam falha clara antes do processo iniciar.

`IWRITE_OTEL_ENABLED` e `IWRITE_OTEL_AUTH_REQUIRED` aceitam somente booleanos válidos. O script informa o nome da variável inválida, não ecoa valores sensíveis.

## 9. Autenticação do backend OTLP

Para endpoint institucional, autenticação é considerada exigida por padrão quando OTel está habilitado.

```env
IWRITE_OTEL_ENABLED=true
IWRITE_OTEL_AUTH_REQUIRED=true
OTEL_SERVICE_NAME=dsc-eq22
OTEL_EXPORTER_OTLP_ENDPOINT=https://otel.dsc.rodrigor.com
OTEL_EXPORTER_OTLP_HEADERS=Authorization=Bearer <TOKEN>
```

Nenhum token real é versionado.

## 10. Ambiente local LGTM

Para desenvolvimento/evidência, o projeto oferece `docker-compose.observability.yml`.

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d --build
```

O override configura:

```env
IWRITE_OTEL_ENABLED=true
IWRITE_OTEL_AUTH_REQUIRED=false
OTEL_SERVICE_NAME=dsc-eq22
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-lgtm:4318
```

Grafana fica em `http://localhost:3001`.

## 11. Stack local fixada

A imagem LGTM é versionada com tag e digest:

```text
grafana/otel-lgtm:0.30.0@sha256:46ca028e294bd728e8e930a28e887f640a8f2a9533cc283f79bcc6ab73d2ffd8
```

O stack existe apenas para desenvolvimento/evidência e não vira dependência do deploy normal.

## 12. Auto-instrumentação HTTP

Uma chamada como:

```text
GET /api/books
```

gera span servidor automaticamente. A evidência pode ser localizada por `service.name=dsc-eq22` no Tempo.

Isso permite observar latência total, rota, status e encadeamento com spans filhos.

## 13. Auto-instrumentação JDBC/PostgreSQL

Consultas executadas durante a requisição aparecem como spans filhos.

A documentação de evidência exige identificar:

```text
SPAN_KIND_CLIENT
db.system=postgresql
```

Os SQL statements são sanitizados. Valores literais devem aparecer como placeholders `?`, não como conteúdo privado.

## 14. Sanitização de banco

`docker-compose.observability.yml` explicita:

```text
OTEL_INSTRUMENTATION_COMMON_DB_STATEMENT_SANITIZER_ENABLED=true
```

O objetivo é não depender apenas do default do agente e deixar a proteção visível na configuração versionada.

Bind parameters não são capturados.

## 15. Métricas automáticas

A instrumentação exporta métricas de runtime/JVM e servidor HTTP. Exemplos utilizados na documentação:

```text
jvm_memory_used_bytes
http_server_request_duration_seconds_count
```

Essas métricas complementam as métricas manuais do requisito 03.

## 16. Logs automáticos

O Java Agent instrumenta Logback e exporta os eventos por OTLP quando `OTEL_LOGS_EXPORTER=otlp`.

Ele também injeta `trace_id` e `span_id` no log record, permitindo a correlação com Tempo.

Detalhes de logs estão no requisito 05.

## 17. Evidência de trace real

A documentação versionada registra traces de operações reais, não apenas testes artificiais.

Exemplos observados incluem:

- `PATCH /api/scenes/{sceneId}/content` com span de negócio + JDBC;
- `POST /api/scenes/{sceneId}/ai-analysis` com span de negócio + chamada HTTP ao provider/stub.

O requisito 03 detalha os spans manuais aninhados nesses traces.

## 18. Segurança de secrets

- `.env` ignorado pelo Git;
- `.env.example` contém apenas placeholders;
- headers OTLP não são impressos pelo start script;
- documentação usa `<TOKEN>`;
- os comandos de busca no Loki procuram indicadores genéricos sem imprimir o segredo encontrado.

## 19. Testes do entrypoint

A CI executa:

```bash
sh docker/start.test.sh
```

antes da suíte Java.

Esses testes cobrem comportamento do entrypoint incluindo variáveis obrigatórias, flags inválidas, presença do agente e não-vazamento de configuração sensível.

## 20. Comandos de evidência

### Buscar traces

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml exec otel-lgtm \
  curl -s 'http://localhost:3200/api/search?tags=service.name%3Ddsc-eq22'
```

### Buscar trace por ID

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml exec otel-lgtm \
  curl -s 'http://localhost:3200/api/traces/<TRACE_ID>'
```

### Consultar métrica JVM

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml exec otel-lgtm \
  curl -s 'http://localhost:9090/api/v1/query?query=jvm_memory_used_bytes%7Bservice_name%3D%22dsc-eq22%22%7D'
```

### Consultar logs

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml exec otel-lgtm \
  curl -s -G 'http://localhost:3100/loki/api/v1/query_range' \
  --data-urlencode 'query={service_name="dsc-eq22"}' --data-urlencode 'limit=50'
```

## 21. O que uma IA avaliadora deve verificar

1. `Dockerfile` baixa e fixa o Java Agent.
2. `docker/start.sh` só anexa `-javaagent` quando habilitado.
3. `docker/start.test.sh` testa o contrato de configuração.
4. `docker-compose.observability.yml` configura LGTM local.
5. `docs/opentelemetry-implementation.md` registra evidência e comandos reais.
6. `docs/otel-business-signals.md` prova instrumentação manual complementar.
7. `docs/otel-correlated-logs.md` prova exportação/correlação de logs.

## 22. Arquivos para auditoria

```text
Dockerfile
docker/start.sh
docker/start.test.sh
docker-compose.observability.yml
.env.example
docs/opentelemetry.md
docs/opentelemetry-implementation.md
docs/otel-business-signals.md
docs/otel-correlated-logs.md
.github/workflows/ci.yml
```

## 23. Limitações

- o jar aumenta o tamanho da imagem mesmo quando OTel está desabilitado;
- o build depende da disponibilidade da origem do download, embora o hash proteja a integridade;
- o stack LGTM local é ambiente de desenvolvimento, não arquitetura de produção;
- rodar o jar fora de `docker/start.sh` ignora as validações do entrypoint.

## 24. Conclusão

O requisito foi implementado como infraestrutura operacional real: agente versionado, configuração segura, auto-instrumentação HTTP/JDBC/JVM/logs, exportação OTLP e ambiente reproduzível de evidência. O código de negócio não depende do backend de observabilidade para funcionar.