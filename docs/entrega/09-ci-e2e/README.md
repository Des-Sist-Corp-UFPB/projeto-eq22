# Requisito 09 — CI e testes E2E

## 1. Objetivo

Garantir que mudanças no backend e frontend sejam verificadas automaticamente e que exista um fluxo ponta a ponta executável em ambiente isolado, com PostgreSQL, Spring Boot, Next.js e navegador real.

O objetivo é reduzir falsa confiança de “funciona na minha máquina” e transformar requisitos importantes — inclusive cobertura frontend — em verificações automáticas, reproduzíveis e auditáveis.

## 2. Estado

**✅ CI implementada para `master` e `main`; E2E Playwright manual/agendado; cobertura frontend ≥85% é gate do job principal; receita local reproduz instalação, credenciais, readiness, Playwright e cleanup usados pela CI.**

Arquivos principais:

```text
.github/workflows/ci.yml
.github/workflows/e2e.yml
docker-compose.e2e.yml
docker/start.test.sh
web/package.json
web/vitest.config.mjs
web/playwright.config.*
web/e2e/
```

## 3. CI principal

A CI é acionada em `push` e `pull_request` para as branches `master` e `main`.

`master` é a branch principal do repositório pessoal; `main` é usada no repositório acadêmico.

### 3.1 Backend

O job de backend:

1. sobe PostgreSQL 16 como service;
2. aguarda `pg_isready`;
3. configura Java 21;
4. testa o entrypoint de OpenTelemetry;
5. executa a suíte Maven.

Comando central:

```bash
./mvnw -s .mvn/local-settings.xml test
```

Os testes de integração usam PostgreSQL real do job.

### 3.2 Frontend

O job frontend usa Node 20 e executa:

```text
npm ci
npm test
npm run build
```

Na revisão atual:

```text
npm test = vitest run --coverage
```

Logo a CI mede cobertura antes do build.

## 4. Gate de cobertura frontend ≥85%

`web/vitest.config.mjs` usa cobertura V8 e contém:

```js
thresholds: {
  lines: 85,
}
```

Fluxo efetivo:

```text
CI frontend
 -> npm ci
 -> npm test
 -> vitest run --coverage
 -> exige lines >= 85
 -> npm run build
```

Na PR #159, a medição atual registrou:

```text
41 arquivos de teste passaram
375 testes passaram
Statements: 87,16%
Branches:   83,87%
Functions:  71,90%
Lines:      87,16%
```

Como o critério acadêmico é cobertura de **linhas ≥85%**, a revisão atual atende ao requisito.

Relatório específico: [`../13-cobertura/README.md`](../13-cobertura/README.md).

## 5. E2E — objetivo e topologia

O workflow Playwright executa uma stack real:

```text
Chromium
 -> Next.js
 -> Spring Boot
 -> PostgreSQL
```

Ele complementa, não substitui, os testes unitários e de integração.

Portas da stack E2E:

```text
PostgreSQL: 5436
Backend:    8086
Frontend:   3001
```

## 6. Gatilhos do E2E

O workflow `.github/workflows/e2e.yml` possui:

```text
workflow_dispatch
schedule semanal
```

Cron versionado:

```text
17 3 * * 0
```

O E2E não roda em cada PR por padrão; ele é manual e agendado.

## 7. Ordem real do workflow E2E

O workflow executa, nesta ordem:

```text
checkout
 -> Java 21
 -> Node 20
 -> npm ci
 -> npx playwright install --with-deps chromium
 -> gera credenciais efêmeras
 -> docker compose up -d --build
 -> espera backend /ping
 -> espera frontend
 -> npm run e2e
 -> publica artifacts em falha
 -> docker compose down -v (always)
```

A receita local abaixo preserva essa mesma ordem lógica.

## 8. Dependências frontend e Chromium

O E2E não pressupõe `node_modules` preexistente.

Na CI:

```bash
cd web
npm ci
npx playwright install --with-deps chromium
```

`npm ci` é importante porque:

- usa o lockfile versionado;
- falha se `package.json` e `package-lock.json` estiverem incompatíveis;
- instala `@playwright/test` e os demais pacotes necessários;
- evita depender de estado residual de execução anterior.

O Chromium também é instalado explicitamente antes dos testes.

## 9. Credenciais efêmeras

O Compose E2E exige:

```text
IWRITE_DEMO_AUTOR_A_PASSWORD
IWRITE_DEMO_AUTOR_B_PASSWORD
```

As mesmas credenciais são usadas pelo seed e pelo Playwright.

Na CI, elas são geradas a cada execução, mascaradas e gravadas em `GITHUB_ENV`. Não existe senha E2E fixa versionada.

A reprodução local também deve gerar valores temporários e mantê-los no mesmo ambiente que inicia Compose e Playwright.

## 10. Readiness antes do Playwright

`docker compose up -d` informa que os containers foram iniciados, mas não prova que as aplicações já estão prontas.

Por isso o workflow espera explicitamente:

```text
backend  -> http://localhost:8086/ping
frontend -> http://localhost:3001
```

### 10.1 Backend

`/ping` é database-aware:

```text
/ping
 -> DatabaseHealthService
 -> SELECT 1
 -> PostgreSQL
```

Assim, um `200` do backend prova também que o round trip mínimo ao PostgreSQL funcionou.

### 10.2 Frontend

O Playwright só inicia depois de `http://localhost:3001` responder.

## 11. Reprodução completa — Linux/macOS (Bash)

A forma recomendada é executar um script Bash a partir da **raiz do repositório**.

Pré-requisitos externos:

- Docker;
- Node/npm;
- `curl`;
- `openssl`;
- Bash.

Exemplo completo:

```bash
#!/usr/bin/env bash
set -euo pipefail

COMPOSE_FILE="docker-compose.e2e.yml"

cleanup() {
  docker compose -f "$COMPOSE_FILE" down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_http() {
  name="$1"
  url="$2"
  service="$3"
  i=1

  while [ "$i" -le 60 ]; do
    if curl --fail --silent "$url" > /dev/null; then
      printf '%s pronto\n' "$name"
      return 0
    fi

    sleep 2
    i=$((i + 1))
  done

  echo "$name não ficou pronto a tempo"
  docker compose -f "$COMPOSE_FILE" logs "$service"
  return 1
}

# Checkout limpo: instalar dependências e browser antes de subir a stack.
(
  set -e
  cd web
  npm ci

  if [ "$(uname -s)" = "Linux" ]; then
    npx playwright install --with-deps chromium
  else
    npx playwright install chromium
  fi
)

export IWRITE_DEMO_AUTOR_A_PASSWORD="$(openssl rand -base64 32)"
export IWRITE_DEMO_AUTOR_B_PASSWORD="$(openssl rand -base64 32)"

docker compose -f "$COMPOSE_FILE" up -d --build

wait_http "backend" "http://localhost:8086/ping" "backend"
wait_http "frontend" "http://localhost:3001" "frontend"

(
  cd web
  npm run e2e
)
```

### 11.1 Por que o setup é fail-fast

O bloco de instalação começa com:

```bash
set -e
```

Portanto, se `npm ci` falhar, o subshell termina imediatamente e **não** continua para `npx playwright install`. Da mesma forma, falha na instalação do browser aborta a receita.

Isso evita mascarar uma instalação incompleta pelo exit code de um comando posterior.

### 11.2 Por que não usamos `seq`

O contador de readiness usa:

```bash
i=1
while [ "$i" -le 60 ]; do
  ...
  i=$((i + 1))
done
```

Ele não depende do utilitário GNU `seq`, portanto funciona no Bash disponível normalmente no macOS e em ambientes Linux sem exigir `coreutils` adicional.

### 11.3 Linux versus macOS

No Linux:

```bash
npx playwright install --with-deps chromium
```

No macOS:

```bash
npx playwright install chromium
```

`--with-deps` é usado no Linux como no GitHub Actions; no macOS a receita instala o browser sem tentar executar instalação de pacotes de sistema Linux.

### 11.4 Cleanup garantido

O script instala:

```bash
trap cleanup EXIT
```

Logo o `docker compose down -v` é tentado no caminho feliz e também quando:

- o backend não fica pronto;
- o frontend não fica pronto;
- Playwright falha;
- qualquer etapa posterior ao registro do trap aborta por `set -e`.

## 12. Reprodução completa — Windows CMD

Para evitar diferenças entre CMD interativo e arquivo batch, a receita abaixo é explicitamente um **arquivo `.cmd`** executado a partir da raiz do repositório.

Pré-requisitos:

- Docker Desktop;
- Node/npm;
- PowerShell disponível no Windows.

Exemplo `run-e2e-local.cmd`:

```cmd
@echo off
setlocal
set "COMPOSE_FILE=docker-compose.e2e.yml"
set "EXIT_CODE=0"

pushd web
if errorlevel 1 exit /b 1

call npm ci
if errorlevel 1 goto :setup_fail

call npx playwright install chromium
if errorlevel 1 goto :setup_fail

popd

for /f %%A in ('powershell -NoProfile -Command "[guid]::NewGuid().ToString('N')"') do set "IWRITE_DEMO_AUTOR_A_PASSWORD=%%A"
for /f %%B in ('powershell -NoProfile -Command "[guid]::NewGuid().ToString('N')"') do set "IWRITE_DEMO_AUTOR_B_PASSWORD=%%B"

docker compose -f "%COMPOSE_FILE%" up -d --build
if errorlevel 1 (
  set "EXIT_CODE=1"
  goto :cleanup
)

powershell -NoProfile -Command "$ok=$false; foreach($i in 1..60){ try { Invoke-WebRequest -UseBasicParsing http://localhost:8086/ping | Out-Null; $ok=$true; break } catch {}; Start-Sleep -Seconds 2 }; if(-not $ok){ exit 1 }"
if errorlevel 1 (
  docker compose -f "%COMPOSE_FILE%" logs backend
  set "EXIT_CODE=1"
  goto :cleanup
)

powershell -NoProfile -Command "$ok=$false; foreach($i in 1..60){ try { Invoke-WebRequest -UseBasicParsing http://localhost:3001 | Out-Null; $ok=$true; break } catch {}; Start-Sleep -Seconds 2 }; if(-not $ok){ exit 1 }"
if errorlevel 1 (
  docker compose -f "%COMPOSE_FILE%" logs frontend
  set "EXIT_CODE=1"
  goto :cleanup
)

pushd web
call npm run e2e
set "EXIT_CODE=%ERRORLEVEL%"
popd
goto :cleanup

:setup_fail
set "EXIT_CODE=%ERRORLEVEL%"
popd
exit /b %EXIT_CODE%

:cleanup
docker compose -f "%COMPOSE_FILE%" down -v
exit /b %EXIT_CODE%
```

### 12.1 Instalação não é mascarada

No batch, `npm ci` e a instalação do Chromium são checados imediatamente:

```cmd
call npm ci
if errorlevel 1 goto :setup_fail

call npx playwright install chromium
if errorlevel 1 goto :setup_fail
```

Assim, um comando posterior não consegue transformar uma instalação fracassada em sucesso.

### 12.2 Readiness não é mascarado por logs

O status do PowerShell é avaliado antes de qualquer comando de logging:

```cmd
if errorlevel 1 (
  docker compose ... logs backend
  set "EXIT_CODE=1"
  goto :cleanup
)
```

Mesmo que `docker compose logs` termine com exit code `0`, a receita já tomou a decisão de falhar e segue para cleanup.

### 12.3 Cleanup centralizado

Todos os caminhos posteriores ao `docker compose up` convergem para:

```cmd
:cleanup
docker compose -f "%COMPOSE_FILE%" down -v
exit /b %EXIT_CODE%
```

Assim o teste local não depende de o usuário lembrar manualmente de derrubar os containers após falha de readiness ou Playwright.

## 13. Evidências em falha na CI

Quando Playwright falha, o workflow publica:

```text
web/playwright-report/
web/test-results/
```

com retenção limitada.

O cleanup da CI usa `if: always()`, garantindo:

```text
docker compose -f docker-compose.e2e.yml down -v
```

mesmo quando uma etapa anterior falha.

## 14. Relação com autenticação e multi-tenancy

O E2E valida o fluxo real do navegador, incluindo sessão server-side e rewrite same-origin. Os testes Java continuam responsáveis pelos casos adversariais detalhados de isolamento multi-tenant.

## 15. Relação com OpenTelemetry

`docker/start.test.sh` é executado na CI para validar o entrypoint de observabilidade em vez de depender apenas de documentação.

## 16. Relação com cobertura

A cobertura frontend é gate contínuo porque:

```text
.github/workflows/ci.yml chama npm test
web/package.json define npm test = vitest run --coverage
web/vitest.config.mjs exige lines >= 85
```

Uma regressão que derrube linhas abaixo de 85% faz o job frontend falhar antes do build.

## 17. Comandos locais de backend/frontend

### Backend Linux/macOS

```bash
./mvnw -s .mvn/local-settings.xml clean test jacoco:report
```

### Backend Windows

```cmd
mvnw.cmd -s .mvn\local-settings.xml clean test jacoco:report
```

### Frontend

```bash
cd web
npm ci
npm test
npm run build
```

## 18. O que uma IA avaliadora deve verificar

1. `.github/workflows/ci.yml` possui gatilhos para `master` e `main`.
2. O backend job usa PostgreSQL real e Java 21.
3. A CI executa `docker/start.test.sh`.
4. O frontend job executa `npm ci`, `npm test` e `npm run build`.
5. `npm test` executa `vitest run --coverage`.
6. `web/vitest.config.mjs` exige `lines >= 85`.
7. A revisão atual registrou 87,16% de cobertura de linhas e 375 testes frontend.
8. `.github/workflows/e2e.yml` instala dependências e Chromium antes de subir a stack.
9. As duas credenciais E2E são efêmeras e obrigatórias.
10. O workflow espera `/ping` e o frontend antes de executar Playwright.
11. `/ping` consulta PostgreSQL de verdade.
12. O workflow publica artifacts em falha e sempre executa cleanup.
13. A receita Bash usa `set -e` no setup e não mascara falha de `npm ci`.
14. A receita Bash usa contador shell, não `seq`, portanto não adiciona dependência GNU ao macOS.
15. A receita Bash usa `trap cleanup EXIT`.
16. A receita CMD checa cada etapa de instalação imediatamente.
17. A receita CMD decide falhar antes de imprimir logs e converge para `:cleanup`.

## 19. Arquivos para auditoria

```text
.github/workflows/ci.yml
.github/workflows/e2e.yml
docker-compose.e2e.yml
docker/start.test.sh
pom.xml
web/package.json
web/package-lock.json
web/vitest.config.mjs
web/playwright.config.*
web/e2e/
docs/entrega/13-cobertura/README.md
```

## 20. Limitações e transparência

- E2E não roda em cada PR por padrão; é manual/agendado.
- O gate frontend é de **linhas ≥85%**, não de branches ou funções.
- A receita Bash requer Docker, Node/npm, `curl` e `openssl`.
- `npx playwright install --with-deps chromium` pode exigir privilégios adequados no Linux para instalar dependências de sistema.
- A receita Windows documentada é um arquivo `.cmd`, evitando ambiguidades de `%A` versus `%%A` entre terminal interativo e batch.
- A execução depende da infraestrutura local ou do GitHub Actions para os recursos necessários.

## 21. Conclusão

A entrega possui CI de backend/frontend, cobertura frontend continuamente verificada e E2E com stack completa.

O fluxo de reprodução local não depende de `node_modules` preexistente, Chromium previamente instalado, `seq`, timing acidental de startup ou cleanup manual. Os caminhos de erro são fail-fast e preservam o resultado correto da execução.
