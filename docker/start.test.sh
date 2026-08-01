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
  case "$output" in *"Authorization"*) ok=0; desc="$desc (HEADER VAZOU NA SAÍDA)" ;; esac
  if [ "$ok" -eq 1 ]; then
    echo "PASS: $desc"
  else
    echo "FAIL: $desc (status=$status, saida=$output)"
    fails=$((fails + 1))
  fi
}

fake_agent=$(mktemp)
trap 'rm -f "$fake_agent" "$agent_with_space" 2>/dev/null || true; rmdir "$space_dir" 2>/dev/null || true' EXIT

# 1. OTel desligado sem variáveis
check "OTel desligado sem variáveis mantém inicialização" 0 "IWRITE_OTEL_ENABLED=false"

# 2. OTel ligado sem service name
check "ligado sem OTEL_SERVICE_NAME falha citando a variável" 1 "OTEL_SERVICE_NAME" \
  IWRITE_OTEL_ENABLED=true \
  OTEL_EXPORTER_OTLP_ENDPOINT=https://otlp.example.invalid \
  "OTEL_EXPORTER_OTLP_HEADERS=Authorization=Bearer $SECRET"

# 3. OTel ligado sem endpoint
check "ligado sem OTEL_EXPORTER_OTLP_ENDPOINT falha citando a variável" 1 "OTEL_EXPORTER_OTLP_ENDPOINT" \
  IWRITE_OTEL_ENABLED=true \
  OTEL_SERVICE_NAME=iwrite-backend \
  "OTEL_EXPORTER_OTLP_HEADERS=Authorization=Bearer $SECRET"

# 4a. OTel ligado sem IWRITE_OTEL_AUTH_REQUIRED (default 'true') e sem headers -> falha
check "ligado sem IWRITE_OTEL_AUTH_REQUIRED (default seguro) exige headers" 1 "OTEL_EXPORTER_OTLP_HEADERS" \
  IWRITE_OTEL_ENABLED=true \
  OTEL_SERVICE_NAME=iwrite-backend \
  OTEL_EXPORTER_OTLP_ENDPOINT=https://otlp.example.invalid \
  "IWRITE_OTEL_AGENT_PATH=$fake_agent"

# 4b. OTel ligado sem IWRITE_OTEL_AUTH_REQUIRED (default 'true'), mas com headers -> passa
check "ligado sem IWRITE_OTEL_AUTH_REQUIRED (default seguro) e com headers passa" 0 "IWRITE_OTEL_ENABLED=true" \
  IWRITE_OTEL_ENABLED=true \
  OTEL_SERVICE_NAME=iwrite-backend \
  OTEL_EXPORTER_OTLP_ENDPOINT=https://otlp.example.invalid \
  "OTEL_EXPORTER_OTLP_HEADERS=Authorization=Bearer $SECRET" \
  "IWRITE_OTEL_AGENT_PATH=$fake_agent"

# 4. OTel ligado, auth desligada e sem headers -> passa (ex.: LGTM local)
check "ligado com IWRITE_OTEL_AUTH_REQUIRED=false dispensa headers" 0 "IWRITE_OTEL_ENABLED=true" \
  IWRITE_OTEL_ENABLED=true \
  IWRITE_OTEL_AUTH_REQUIRED=false \
  OTEL_SERVICE_NAME=iwrite-backend \
  OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-lgtm:4318 \
  "IWRITE_OTEL_AGENT_PATH=$fake_agent"

# 5. OTel ligado, auth ligada e sem headers -> falha
check "ligado com IWRITE_OTEL_AUTH_REQUIRED=true exige OTEL_EXPORTER_OTLP_HEADERS" 1 "OTEL_EXPORTER_OTLP_HEADERS" \
  IWRITE_OTEL_ENABLED=true \
  IWRITE_OTEL_AUTH_REQUIRED=true \
  OTEL_SERVICE_NAME=iwrite-backend \
  OTEL_EXPORTER_OTLP_ENDPOINT=https://otlp.example.invalid \
  "IWRITE_OTEL_AGENT_PATH=$fake_agent"

# 6. OTel ligado, auth ligada e com headers -> passa
check "ligado com IWRITE_OTEL_AUTH_REQUIRED=true e headers presentes passa" 0 "IWRITE_OTEL_ENABLED=true" \
  IWRITE_OTEL_ENABLED=true \
  IWRITE_OTEL_AUTH_REQUIRED=true \
  OTEL_SERVICE_NAME=iwrite-backend \
  OTEL_EXPORTER_OTLP_ENDPOINT=https://otlp.example.invalid \
  "OTEL_EXPORTER_OTLP_HEADERS=Authorization=Bearer $SECRET" \
  "IWRITE_OTEL_AGENT_PATH=$fake_agent"

# 7. Valores booleanos inválidos
check "IWRITE_OTEL_ENABLED inválido falha com mensagem segura" 1 "IWRITE_OTEL_ENABLED deve ser" \
  IWRITE_OTEL_ENABLED=yes

check "IWRITE_OTEL_AUTH_REQUIRED inválido falha com mensagem segura" 1 "IWRITE_OTEL_AUTH_REQUIRED deve ser" \
  IWRITE_OTEL_ENABLED=true \
  IWRITE_OTEL_AUTH_REQUIRED=maybe \
  OTEL_SERVICE_NAME=iwrite-backend \
  OTEL_EXPORTER_OTLP_ENDPOINT=https://otlp.example.invalid

# 8. Agente ausente
check "agente ausente falha com diagnóstico" 1 "Agente OpenTelemetry ausente" \
  IWRITE_OTEL_ENABLED=true \
  OTEL_SERVICE_NAME=iwrite-backend \
  OTEL_EXPORTER_OTLP_ENDPOINT=https://otlp.example.invalid \
  "OTEL_EXPORTER_OTLP_HEADERS=Authorization=Bearer $SECRET" \
  IWRITE_OTEL_AGENT_PATH=/caminho/inexistente/agent.jar

# 9. já coberto pela checagem de "$SECRET"/"Authorization" em todo `check` acima.

# -javaagent continua um único argumento mesmo com espaço no caminho do agente
# (exercita o start.sh real via --print-java-args, uma linha por argumento).
space_dir=$(mktemp -d)/"pasta com espaco"
mkdir -p "$space_dir"
agent_with_space="$space_dir/agent.jar"
: > "$agent_with_space"
argv_output=$(env -i PATH="$PATH" \
  IWRITE_OTEL_ENABLED=true \
  IWRITE_OTEL_AUTH_REQUIRED=false \
  OTEL_SERVICE_NAME=iwrite-backend \
  OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-lgtm:4318 \
  "IWRITE_OTEL_AGENT_PATH=$agent_with_space" \
  sh ./start.sh --print-java-args)
line_count=$(printf '%s\n' "$argv_output" | wc -l)
if [ "$line_count" -eq 3 ] && [ "$(printf '%s\n' "$argv_output" | sed -n 1p)" = "-javaagent:$agent_with_space" ]; then
  echo "PASS: -javaagent com espaço no caminho permanece um único argumento"
else
  echo "FAIL: -javaagent com espaço no caminho não permaneceu um único argumento (saida=$argv_output)"
  fails=$((fails + 1))
fi

if [ "$fails" -gt 0 ]; then
  echo "$fails teste(s) falharam"
  exit 1
fi
echo "Todos os testes passaram"
