# OpenTelemetry no IWrite

O container do backend inclui o [OpenTelemetry Java Agent](https://github.com/open-telemetry/opentelemetry-java-instrumentation) para auto-instrumentação de HTTP, JDBC/PostgreSQL, métricas da JVM e logs, com exportação por OTLP para um backend de observabilidade externo (institucional). Nenhum collector, Grafana, Tempo, Loki ou Prometheus é adicionado ao Docker Compose.

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

| Variável | Exemplo (sem valores reais) |
|---|---|
| `OTEL_SERVICE_NAME` | `iwrite-backend` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `https://otlp.exemplo-institucional.invalid` |
| `OTEL_EXPORTER_OTLP_HEADERS` | `Authorization=Bearer <TOKEN>` |

Se uma variável obrigatória estiver ausente, o container falha antes de iniciar com uma mensagem que cita apenas o nome da variável — nunca valores, headers ou tokens.

O script define `OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`. Traces, métricas e logs usam os exporters OTLP padrão do agente (`otlp` para os três sinais).

## Segurança

- Token e headers ficam exclusivamente em secret ou variável de ambiente ignorada pelo Git (`.env` está no `.gitignore`; use `.env.example` como modelo).
- O script nunca imprime valores de `OTEL_EXPORTER_OTLP_HEADERS`.
- O MCP permanece desabilitado por padrão (`IWRITE_MCP_ENABLED=false`); `/sse` e `/mcp/message` não são expostos.

## Diagnóstico

Validar a configuração sem iniciar os processos:

```bash
docker run --rm iwrite-otel-test /app/start.sh --check
docker run --rm -e IWRITE_OTEL_ENABLED=true iwrite-otel-test /app/start.sh --check   # falha citando OTEL_SERVICE_NAME
```

Testes do script de inicialização (validação de variáveis, agente ausente e não-vazamento de secrets):

```bash
sh docker/start.test.sh
```

Com o agente ativo, as primeiras linhas do log do backend incluem `opentelemetry-javaagent` e a versão. Falhas de exportação aparecem no log como erros do exporter OTLP, sem interromper a aplicação.
