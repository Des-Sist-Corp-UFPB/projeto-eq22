#!/bin/sh
# Inicia backend (Spring Boot) e frontend (Next.js) no container.
# Com IWRITE_OTEL_ENABLED=true, valida a configuração OTLP e anexa o
# OpenTelemetry Java Agent somente ao processo Java.
# Nunca imprime valores de OTEL_EXPORTER_OTLP_HEADERS ou outros secrets.
set -eu

OTEL_AGENT="${IWRITE_OTEL_AGENT_PATH:-/app/otel/opentelemetry-javaagent.jar}"
JAVA_OTEL_OPTS=""

if [ "${IWRITE_OTEL_ENABLED:-false}" = "true" ]; then
  for required in OTEL_SERVICE_NAME OTEL_EXPORTER_OTLP_ENDPOINT OTEL_EXPORTER_OTLP_HEADERS; do
    eval "value=\${${required}:-}"
    if [ -z "${value}" ]; then
      echo "IWRITE_OTEL_ENABLED=true exige a variável ${required}" >&2
      exit 1
    fi
  done
  if [ ! -f "${OTEL_AGENT}" ]; then
    echo "Agente OpenTelemetry ausente: ${OTEL_AGENT}" >&2
    exit 1
  fi
  export OTEL_EXPORTER_OTLP_PROTOCOL="http/protobuf"
  JAVA_OTEL_OPTS="-javaagent:${OTEL_AGENT}"
fi

# Modo de validação usado pelos testes e pelo smoke test do container.
if [ "${1:-}" = "--check" ]; then
  echo "Configuração OTel válida (IWRITE_OTEL_ENABLED=${IWRITE_OTEL_ENABLED:-false})"
  exit 0
fi

SERVER_PORT=8085 java ${JAVA_OTEL_OPTS} -jar /app/backend/app.jar &
backend_pid=$!
/app/frontend/node_modules/.bin/next start /app/frontend -p 8080 -H 0.0.0.0 &
frontend_pid=$!
trap 'kill "$backend_pid" "$frontend_pid" 2>/dev/null || true' INT TERM EXIT
while kill -0 "$backend_pid" 2>/dev/null && kill -0 "$frontend_pid" 2>/dev/null; do
  sleep 1
done
echo 'Backend or frontend exited unexpectedly'
exit 1
