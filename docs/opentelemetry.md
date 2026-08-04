# OpenTelemetry no IWrite

O container do backend inclui o [OpenTelemetry Java Agent](https://github.com/open-telemetry/opentelemetry-java-instrumentation) para auto-instrumentação de HTTP, JDBC/PostgreSQL, métricas da JVM e logs, com exportação por OTLP. Dois ambientes são suportados: um LGTM local (Grafana + Tempo + Loki + Prometheus/Mimir) sem autenticação, para desenvolvimento e evidências, e um backend institucional externo com autenticação por header. Nenhum collector, Grafana, Tempo, Loki ou Prometheus é adicionado ao `Dockerfile` ou ao deploy de produção — o `docker-compose.observability.yml` é um override **somente para desenvolvimento**.

- **Versão do agente:** 2.30.0 (fixa no `Dockerfile`)
- **SHA-256 validado no build:** `9d6bc2ad8dd8fb7f730984988e57b8ac0a82d81c7b3b8ae795378718733a509d`
- **Local no runtime:** `/app/otel/opentelemetry-javaagent.jar`
- **Inicialização:** `docker/start.sh` anexa `-javaagent` somente ao processo Java (Spring Boot). O Next.js não é instrumentado.

## Desabilitado (padrão)

Com `IWRITE_OTEL_ENABLED` ausente ou `false`:

- o comportamento do container é exatamente o atual;
- o agente não é carregado;
- nenhuma variável `OTEL_*` é exigida.

## Habilitado

Com `IWRITE_OTEL_ENABLED=true`, o `docker/start.sh` exige, antes de iniciar qualquer processo:

| Variável | Sempre exigida | Exemplo (sem valores reais) |
|---|---|---|
| `OTEL_SERVICE_NAME` | sim | `dsc-eq22` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | sim | `https://otlp.exemplo-institucional.invalid` |
| `OTEL_EXPORTER_OTLP_HEADERS` | apenas se `IWRITE_OTEL_AUTH_REQUIRED=true` | `Authorization=Bearer <TOKEN>` |

`IWRITE_OTEL_ENABLED` e `IWRITE_OTEL_AUTH_REQUIRED` só aceitam `true` ou `false`; qualquer outro valor falha antes de iniciar, com mensagem que nomeia a variável mas nunca ecoa o valor recebido.

**Seguro por padrão:** com `IWRITE_OTEL_ENABLED=true` e `IWRITE_OTEL_AUTH_REQUIRED` ausente, o default é `true` — exige `OTEL_EXPORTER_OTLP_HEADERS`. Isso evita habilitar OTel contra o endpoint institucional sem token por engano. Só o LGTM local (via `docker-compose.observability.yml`) define `IWRITE_OTEL_AUTH_REQUIRED=false` explicitamente. Com `IWRITE_OTEL_ENABLED=false`, `IWRITE_OTEL_AUTH_REQUIRED` não tem efeito.

Se uma variável obrigatória estiver ausente, o container falha antes de iniciar com uma mensagem que cita apenas o nome da variável — nunca valores, headers ou tokens.

Quando habilitado, o script aplica estes defaults **apenas se a variável não tiver sido definida externamente**:

```env
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
OTEL_TRACES_EXPORTER=otlp
OTEL_METRICS_EXPORTER=otlp
OTEL_LOGS_EXPORTER=otlp
```

### Ambiente local (LGTM, sem autenticação)

```env
IWRITE_OTEL_ENABLED=true
IWRITE_OTEL_AUTH_REQUIRED=false
OTEL_SERVICE_NAME=dsc-eq22
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-lgtm:4318
```

Suba com o override de observabilidade (adiciona um `grafana/otel-lgtm` local e aponta o backend para ele; não afeta `Dockerfile` nem produção):

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml up --build
```

A UI do Grafana fica em `http://localhost:3001` (a porta 3000 do host já é usada pelo frontend Next.js; a porta interna do container `otel-lgtm` continua 3000, sem efeito no endpoint OTLP interno). Sem headers/token — o LGTM local não exige autenticação.

**Imagem do LGTM (fixa por versão + digest):** `grafana/otel-lgtm:0.30.0@sha256:46ca028e294bd728e8e930a28e887f640a8f2a9533cc283f79bcc6ab73d2ffd8`, validada em 2026-08-01 (`docker image inspect grafana/otel-lgtm:latest --format '{{json .RepoDigests}}'`, que na data da validação apontava para a tag `0.30.0`). Uso **exclusivo para desenvolvimento/evidências** — não faz parte do `Dockerfile` nem do deploy de produção.

### Servidor institucional (com autenticação)

```env
IWRITE_OTEL_ENABLED=true
# IWRITE_OTEL_AUTH_REQUIRED ausente assume 'true' (seguro por padrão); pode
# ser omitida aqui, mostrada só para clareza.
IWRITE_OTEL_AUTH_REQUIRED=true
OTEL_SERVICE_NAME=dsc-eq22
OTEL_EXPORTER_OTLP_ENDPOINT=https://otel.dsc.rodrigor.com
OTEL_EXPORTER_OTLP_HEADERS=Authorization=Bearer <TOKEN>
```

## Segurança

- Token e headers ficam exclusivamente em secret ou variável de ambiente ignorada pelo Git (`.env` está no `.gitignore`; use `.env.example` como modelo).
- O script nunca imprime valores de `OTEL_EXPORTER_OTLP_HEADERS`, nem os valores recebidos para `IWRITE_OTEL_ENABLED`/`IWRITE_OTEL_AUTH_REQUIRED` quando inválidos.
- `docker-compose.observability.yml` não define nenhum token/header — é só para o LGTM local sem autenticação.
- O MCP permanece desabilitado por padrão (`IWRITE_MCP_ENABLED=false`); `/sse` e `/mcp/message` não são expostos.
- **Sanitização de `db.statement`:** o agente já sanitiza por padrão; `docker-compose.observability.yml` define `OTEL_INSTRUMENTATION_COMMON_DB_STATEMENT_SANITIZER_ENABLED=true` explicitamente para deixar essa proteção visível na configuração, em vez de depender só do default. Com ela, valores literais do SQL são substituídos por `?` antes de virar span attribute; parâmetros JDBC vinculados (bind parameters) não são capturados; conteúdo de manuscrito nunca deve aparecer em `db.statement`, só a forma parametrizada da query.

## Diagnóstico

Validar a configuração sem iniciar os processos:

```bash
docker run --rm iwrite-otel-test /app/start.sh --check
docker run --rm -e IWRITE_OTEL_ENABLED=true iwrite-otel-test /app/start.sh --check   # falha citando OTEL_SERVICE_NAME
```

No Git Bash do Windows, exporte `MSYS_NO_PATHCONV=1` antes desses comandos; caso contrário o shell converte `/app/start.sh` em caminho Windows.

Testes do script de inicialização (variáveis obrigatórias, `IWRITE_OTEL_AUTH_REQUIRED`, booleanos inválidos, agente ausente, `-javaagent` como argumento único e não-vazamento de secrets):

```bash
sh docker/start.test.sh
```

Com o agente ativo, as primeiras linhas do log do backend incluem `opentelemetry-javaagent` e a versão. Falhas de exportação aparecem no log como erros do exporter OTLP, sem interromper a aplicação.

## Comandos de evidência (Tempo/Prometheus/Loki)

Só as portas 3001 (Grafana), 4317 e 4318 (OTLP) são publicadas no host pelo `docker-compose.observability.yml`; as APIs de consulta do Tempo (3200), Prometheus/Mimir (9090) e Loki (3100) ficam só na rede interna do compose. Os comandos abaixo usam `docker compose exec otel-lgtm curl ...` (o `curl` já vem na imagem). Rode com o stack de observabilidade no ar:

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d --build
```

Gere tráfego real primeiro (ex.: `GET /api/books` autenticado pela identidade de desenvolvimento).

**Localizar traces por `service.name=dsc-eq22` e abrir o trace de `GET /api/books`:**

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml exec otel-lgtm \
  curl -s 'http://localhost:3200/api/search?tags=service.name%3Ddsc-eq22' | head -c 2000
```

Pegue um `traceID` do resultado acima e consulte o trace completo:

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml exec otel-lgtm \
  curl -s 'http://localhost:3200/api/traces/<TRACE_ID>' | head -c 4000
```

**Confirmar o span JDBC filho:** no JSON do trace acima, procure um span com `SPAN_KIND_CLIENT`, `db.system=postgresql` e `parentSpanId` igual ao `spanId` do span raiz `GET /api/books` (`SPAN_KIND_SERVER`). O `db.statement` deve conter apenas placeholders (`?`), nunca valores literais.

**Consultar `jvm_memory_used_bytes`:**

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml exec otel-lgtm \
  curl -s 'http://localhost:9090/api/v1/query?query=jvm_memory_used_bytes%7Bservice_name%3D%22dsc-eq22%22%7D' | head -c 2000
```

**Consultar a métrica HTTP (`http_server_request_duration_seconds_count`):**

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml exec otel-lgtm \
  curl -s 'http://localhost:9090/api/v1/query?query=http_server_request_duration_seconds_count%7Bhttp_route%3D%22%2Fapi%2Fbooks%22%7D' | head -c 2000
```

**Consultar logs do serviço (Loki):**

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml exec otel-lgtm \
  curl -s -G 'http://localhost:3100/loki/api/v1/query_range' \
  --data-urlencode 'query={service_name="dsc-eq22"}' \
  --data-urlencode 'limit=50' | head -c 3000
```

**Procurar indicadores sensíveis sem imprimir secrets:** verificação booleana — procura só por indicadores genéricos (`Authorization`, `Bearer`), nunca pelo token institucional real, e nunca imprime o trecho encontrado, só se algo bateu:

```bash
if docker compose \
  -f docker-compose.yml \
  -f docker-compose.observability.yml \
  exec -T otel-lgtm sh -c \
  "curl -s -G 'http://localhost:3100/loki/api/v1/query_range' \
    --data-urlencode 'query={service_name=\"dsc-eq22\"}' \
    --data-urlencode 'limit=1000' |
    grep -Eq 'Authorization|Bearer'"
then
  echo "Possível termo sensível encontrado"
  exit 1
else
  echo "Nenhum indicador sensível encontrado"
fi
```

Sintaxe validada contra o LGTM real: com um canário local (`echo 'Authorization: Bearer canary' | grep -Eq ...` dentro do mesmo container) o comando reporta "Possível termo sensível encontrado" e sai com status 1, sem nunca imprimir o valor encontrado; sem correspondência, reporta "Nenhum indicador sensível encontrado" e sai com status 0. Use tokens-canário falsos só nesse tipo de teste específico de vazamento — nunca informe ou substitua pelo token institucional real, aqui ou em qualquer comando desta página.

Todos os comandos acima usam placeholders (`<TRACE_ID>`) — nenhum token, conteúdo privado ou ID pessoal real.

## Sinais de negócio

Os spans e métricas **manuais** dos fluxos críticos (salvamento de conteúdo de cena e análise assistida) estão documentados em [`otel-business-signals.md`](otel-business-signals.md): nomes, atributos permitidos, dados proibidos, buckets, consultas do Tempo/Prometheus, passos de reprodução e diagnóstico do gargalo.

## Logs estruturados

Os **eventos de log** correlacionados com traces estão em [`otel-correlated-logs.md`](otel-correlated-logs.md): key-value pairs do SLF4J 2, a configuração `capture-key-value-pair-attributes`, campos permitidos e proibidos, níveis por ambiente, consultas LogQL verificadas contra o LGTM real, correlação log → Tempo, e por que nenhum segundo `OpenTelemetryAppender` foi adicionado (o Java Agent já instala o dele).

Duas ressalvas que afetam quem for escrever consultas: só `service_name` é label indexado — os atributos do log record chegam como structured metadata, com pontos convertidos em underscores (`iwrite.result` → `iwrite_result`) — e `otel.event.name` é consumido pelo agente como `EventName` do log record, não ficando consultável neste pipeline.

## Limitações conhecidas

- O download do agente no build vem do GitHub Releases; o SHA-256 fixo garante reprodutibilidade do conteúdo, não a disponibilidade da rede.
- O `.jar` do agente adiciona ~24 MB à imagem mesmo com OTel desabilitado.
- A validação de configuração vive em `docker/start.sh`, não na aplicação Spring: rodar o `.jar` diretamente fora do container ignora essas checagens.
