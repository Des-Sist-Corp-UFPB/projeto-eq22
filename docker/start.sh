#!/bin/sh
# Inicia backend (Spring Boot) e frontend (Next.js) no container.
# Com IWRITE_OTEL_ENABLED=true, valida a configuração OTLP e anexa o
# OpenTelemetry Java Agent somente ao processo Java. IWRITE_OTEL_AUTH_REQUIRED
# controla se OTEL_EXPORTER_OTLP_HEADERS é obrigatório; ausente, assume 'true'
# (seguro por padrão). Só o LGTM local define 'false' explicitamente.
# Nunca imprime valores de OTEL_EXPORTER_OTLP_HEADERS ou outros secrets.
set -eu

check_mode="${1:-}"

OTEL_AGENT="${IWRITE_OTEL_AGENT_PATH:-/app/otel/opentelemetry-javaagent.jar}"
otel_enabled="${IWRITE_OTEL_ENABLED:-false}"

case "$otel_enabled" in
  true|false) ;;
  *)
    echo "IWRITE_OTEL_ENABLED deve ser 'true' ou 'false'" >&2
    exit 1
    ;;
esac

# Seguro por padrão: com OTel habilitado, IWRITE_OTEL_AUTH_REQUIRED ausente
# assume 'true' (exige headers). Só o LGTM local (docker-compose.observability.yml)
# define 'false' explicitamente. Com OTel desabilitado, o default 'false' não
# importa, pois nenhuma variável OTel é checada.
if [ "$otel_enabled" = "true" ]; then
  otel_auth_required="${IWRITE_OTEL_AUTH_REQUIRED:-true}"
else
  otel_auth_required="${IWRITE_OTEL_AUTH_REQUIRED:-false}"
fi

case "$otel_auth_required" in
  true|false) ;;
  *)
    echo "IWRITE_OTEL_AUTH_REQUIRED deve ser 'true' ou 'false'" >&2
    exit 1
    ;;
esac

if [ "$otel_enabled" = "true" ]; then
  if [ -z "${OTEL_SERVICE_NAME:-}" ]; then
    echo "IWRITE_OTEL_ENABLED=true exige a variável OTEL_SERVICE_NAME" >&2
    exit 1
  fi
  if [ -z "${OTEL_EXPORTER_OTLP_ENDPOINT:-}" ]; then
    echo "IWRITE_OTEL_ENABLED=true exige a variável OTEL_EXPORTER_OTLP_ENDPOINT" >&2
    exit 1
  fi
  if [ "$otel_auth_required" = "true" ] && [ -z "${OTEL_EXPORTER_OTLP_HEADERS:-}" ]; then
    echo "IWRITE_OTEL_AUTH_REQUIRED=true exige a variável OTEL_EXPORTER_OTLP_HEADERS" >&2
    exit 1
  fi
  if [ ! -f "$OTEL_AGENT" ]; then
    echo "Agente OpenTelemetry ausente: $OTEL_AGENT" >&2
    exit 1
  fi
  # Defaults aplicados apenas quando não fornecidos externamente.
  : "${OTEL_EXPORTER_OTLP_PROTOCOL:=http/protobuf}"
  : "${OTEL_TRACES_EXPORTER:=otlp}"
  : "${OTEL_METRICS_EXPORTER:=otlp}"
  : "${OTEL_LOGS_EXPORTER:=otlp}"
  export OTEL_EXPORTER_OTLP_PROTOCOL OTEL_TRACES_EXPORTER OTEL_METRICS_EXPORTER OTEL_LOGS_EXPORTER
fi

# Modo de validação usado pelos testes e pelo smoke test do container.
if [ "$check_mode" = "--check" ]; then
  echo "Configuração OTel válida (IWRITE_OTEL_ENABLED=${otel_enabled}, IWRITE_OTEL_AUTH_REQUIRED=${otel_auth_required})"
  exit 0
fi

if [ "$otel_enabled" = "true" ]; then
  set -- -javaagent:"$OTEL_AGENT" -jar /app/backend/app.jar
else
  set -- -jar /app/backend/app.jar
fi

# Modo de teste: imprime os argumentos do java um por linha, sem executar nada.
if [ "$check_mode" = "--print-java-args" ]; then
  for arg in "$@"; do
    echo "$arg"
  done
  exit 0
fi

# Nesta imagem o único salto entre o navegador e o backend é o frontend do mesmo container, em
# 127.0.0.1 — só esse peer é confiável para informar o endereço real do cliente. Nunca a faixa
# privada inteira: qualquer outro caminho até o backend voltaria a poder forjar a origem. Sem os
# dois pontos no `=`, um valor explícito em runtime substitui este default (o Compose separado
# define o serviço `frontend`), e um valor explicitamente vazio desliga a confiança por completo.
: "${IWRITE_AUTH_TRUSTED_PROXIES=127.0.0.1}"
export IWRITE_AUTH_TRUSTED_PROXIES

SERVER_PORT=8085 java "$@" &
backend_pid=$!
# web/server.mjs, não `next start`: descarta Forwarded/X-Forwarded-For vindos do cliente e injeta
# um X-Forwarded-For canônico a partir do socket, que é o que a linha acima passa a confiar.
PORT=8080 node /app/frontend/server.mjs &
frontend_pid=$!
trap 'kill "$backend_pid" "$frontend_pid" 2>/dev/null || true' INT TERM EXIT
while kill -0 "$backend_pid" 2>/dev/null && kill -0 "$frontend_pid" 2>/dev/null; do
  sleep 1
done
echo 'Backend or frontend exited unexpectedly'
exit 1
