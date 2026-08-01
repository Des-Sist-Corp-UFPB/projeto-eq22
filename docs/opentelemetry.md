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
| `OTEL_SERVICE_NAME` | sim | `iwrite-backend` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | sim | `https://otlp.exemplo-institucional.invalid` |
| `OTEL_EXPORTER_OTLP_HEADERS` | apenas se `IWRITE_OTEL_AUTH_REQUIRED=true` | `Authorization=Bearer <TOKEN>` |

`IWRITE_OTEL_ENABLED` e `IWRITE_OTEL_AUTH_REQUIRED` só aceitam `true` ou `false`; qualquer outro valor falha antes de iniciar, com mensagem que nomeia a variável mas nunca ecoa o valor recebido.

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

### Servidor institucional (com autenticação)

```env
IWRITE_OTEL_ENABLED=true
IWRITE_OTEL_AUTH_REQUIRED=true
OTEL_SERVICE_NAME=iwrite-backend
OTEL_EXPORTER_OTLP_ENDPOINT=https://otel.dsc.rodrigor.com
OTEL_EXPORTER_OTLP_HEADERS=Authorization=Bearer <TOKEN>
```

## Segurança

- Token e headers ficam exclusivamente em secret ou variável de ambiente ignorada pelo Git (`.env` está no `.gitignore`; use `.env.example` como modelo).
- O script nunca imprime valores de `OTEL_EXPORTER_OTLP_HEADERS`, nem os valores recebidos para `IWRITE_OTEL_ENABLED`/`IWRITE_OTEL_AUTH_REQUIRED` quando inválidos.
- `docker-compose.observability.yml` não define nenhum token/header — é só para o LGTM local sem autenticação.
- O MCP permanece desabilitado por padrão (`IWRITE_MCP_ENABLED=false`); `/sse` e `/mcp/message` não são expostos.

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

## Limitações conhecidas

- O download do agente no build vem do GitHub Releases; o SHA-256 fixo garante reprodutibilidade do conteúdo, não a disponibilidade da rede.
- O `.jar` do agente adiciona ~24 MB à imagem mesmo com OTel desabilitado.
- A validação de configuração vive em `docker/start.sh`, não na aplicação Spring: rodar o `.jar` diretamente fora do container ignora essas checagens.
