#!/bin/sh
# Validações de docker/start.sh (modo --check). Executar: sh docker/start.test.sh
set -eu
cd "$(dirname "$0")"

SECRET="supersecrettoken123"
fails=0

check() {
  desc="$1"
  expected_status="$2"
  expected_text="$3"
  shift 3
  set +e
  output=$(env -i PATH="$PATH" "$@" sh ./start.sh --check 2>&1)
  status=$?
  set -e
  ok=1
  [ "$status" -eq "$expected_status" ] || ok=0
  case "$output" in *"$expected_text"*) ;; *) ok=0 ;; esac
  case "$output" in *"$SECRET"*) ok=0; desc="$desc (SECRET VAZOU NA SAÍDA)" ;; esac
  if [ "$ok" -eq 1 ]; then
    echo "PASS: $desc"
  else
    echo "FAIL: $desc (status=$status, saida=$output)"
    fails=$((fails + 1))
  fi
}

fake_agent=$(mktemp)
trap 'rm -f "$fake_agent"' EXIT

check "OTel desabilitado sem variáveis mantém inicialização" 0 "IWRITE_OTEL_ENABLED=false"

check "habilitado sem OTEL_SERVICE_NAME falha citando a variável" 1 "OTEL_SERVICE_NAME" \
  IWRITE_OTEL_ENABLED=true \
  OTEL_EXPORTER_OTLP_ENDPOINT=https://otlp.example.invalid \
  "OTEL_EXPORTER_OTLP_HEADERS=Authorization=Bearer $SECRET"

check "habilitado sem OTEL_EXPORTER_OTLP_ENDPOINT falha citando a variável" 1 "OTEL_EXPORTER_OTLP_ENDPOINT" \
  IWRITE_OTEL_ENABLED=true \
  OTEL_SERVICE_NAME=iwrite-backend \
  "OTEL_EXPORTER_OTLP_HEADERS=Authorization=Bearer $SECRET"

check "habilitado sem OTEL_EXPORTER_OTLP_HEADERS falha citando a variável" 1 "OTEL_EXPORTER_OTLP_HEADERS" \
  IWRITE_OTEL_ENABLED=true \
  OTEL_SERVICE_NAME=iwrite-backend \
  OTEL_EXPORTER_OTLP_ENDPOINT=https://otlp.example.invalid

check "configuração válida passa" 0 "IWRITE_OTEL_ENABLED=true" \
  IWRITE_OTEL_ENABLED=true \
  OTEL_SERVICE_NAME=iwrite-backend \
  OTEL_EXPORTER_OTLP_ENDPOINT=https://otlp.example.invalid \
  "OTEL_EXPORTER_OTLP_HEADERS=Authorization=Bearer $SECRET" \
  "IWRITE_OTEL_AGENT_PATH=$fake_agent"

check "agente ausente falha com diagnóstico" 1 "Agente OpenTelemetry ausente" \
  IWRITE_OTEL_ENABLED=true \
  OTEL_SERVICE_NAME=iwrite-backend \
  OTEL_EXPORTER_OTLP_ENDPOINT=https://otlp.example.invalid \
  "OTEL_EXPORTER_OTLP_HEADERS=Authorization=Bearer $SECRET" \
  IWRITE_OTEL_AGENT_PATH=/caminho/inexistente/agent.jar

if [ "$fails" -gt 0 ]; then
  echo "$fails teste(s) falharam"
  exit 1
fi
echo "Todos os testes passaram"
