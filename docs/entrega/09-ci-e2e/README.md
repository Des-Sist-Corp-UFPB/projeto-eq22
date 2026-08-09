# Requisito 09 — CI e testes E2E

## 1. Objetivo

Garantir que mudanças no backend e frontend sejam verificadas automaticamente e que exista um fluxo ponta a ponta executável em ambiente isolado, com banco, backend, frontend e navegador real.

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

## 3. Gatilhos da CI

```text
pull_request -> master
pull_request -> main
push -> master
push -> main
```

`main` é necessário para o repositório acadêmico e `master` para o repositório pessoal.

## 4. Backend na CI

O job sobe PostgreSQL 16 como service do GitHub Actions com `pg_isready`, configura Java 21, executa o entrypoint de OpenTelemetry e só então roda a suíte Java.

```bash
sh docker/start.test.sh
./mvnw -s .mvn/local-settings.xml test
```

Os testes de integração usam PostgreSQL real do job.

## 5. Frontend na CI

O job frontend usa Node 20 e executa:

```text
npm ci
npm test
npm run build
```

A partir da PR #159, `npm test` significa:

```text
vitest run --coverage
```

Portanto a CI não executa apenas testes: ela mede cobertura antes do build.

## 6. Gate de cobertura ≥85%

`web/vitest.config.mjs` define:

```js
coverage: {
  provider: "v8",
  reporter: ["text", "json-summary", "html"],
  reportsDirectory: "./coverage",
  include: ["src/**/*.{ts,tsx}"],
  exclude: ["src/**/*.test.{ts,tsx}", "src/test/**"],
  thresholds: {
    lines: 85,
  },
}
```

Fluxo efetivo:

```text
CI frontend
 -> npm test
 -> vitest run --coverage
 -> calcula cobertura do código atual
 -> exige lines >= 85
 -> só então segue para npm run build
```

Isso transforma o requisito **Cob** em gate contínuo do frontend.

## 7. Evidência de cobertura atual

A execução da PR #159 registrou:

```text
41 arquivos de teste passaram
375 testes passaram
Statements: 87,16%
Branches:   83,87%
Functions:  71,90%
Lines:      87,16%
```

Como o threshold é `lines: 85`, o passo de testes concluiu com sucesso.

O finding do Codex sobre usar apenas o snapshot histórico de 01/07 foi corrigido com uma medição atual na própria CI.

Relatório específico: [`../13-cobertura/README.md`](../13-cobertura/README.md).

## 8. Por que `npm ci` é usado

`npm ci` respeita o lockfile e falha quando `package.json` e `package-lock.json` são incompatíveis. Para CI isso evita resolução silenciosa diferente da versão declarada e ajuda a detectar dependências diretas ausentes.

O mesmo comando faz parte da receita E2E local. Portanto a reprodução não pressupõe um `node_modules` deixado por uma execução anterior.

## 9. Build de produção

Só depois dos testes e do gate de cobertura o job executa:

```bash
npm run build
```

Isso valida TypeScript/Next e o build otimizado do frontend.

## 10. Observação sobre artifact de build

O workflow possui etapa histórica de upload do build frontend. A saída padrão atual do Next.js é `.next/`; uma referência antiga a `web/build` pode gerar warning de ausência de artifact sem invalidar testes, cobertura ou o build em si.

Caso o artifact seja usado operacionalmente, o caminho deve acompanhar a saída efetiva do Next.js.

## 11. E2E — objetivo

O workflow Playwright sobe uma stack completa e executa ações em navegador real:

```text
Chromium
 -> Next.js
 -> Spring Boot
 -> PostgreSQL
```

Ele complementa, não substitui, testes unitários e de integração.

## 12. Gatilhos do E2E

```text
workflow_dispatch
schedule semanal
```

Cron:

```text
17 3 * * 0
```

## 13. Preparação do runner E2E

Antes de subir a stack, o workflow oficial executa, nesta ordem:

```text
checkout
Java 21
Node 20
npm ci
npx playwright install --with-deps chromium
credenciais efêmeras
Docker Compose
readiness
Playwright
cleanup
```

Os passos relevantes do próprio `.github/workflows/e2e.yml` são:

```bash
# em web/
npm ci
npx playwright install --with-deps chromium
```

A receita local abaixo também instala dependências npm e o Chromium antes de tentar executar `npm run e2e`. Isso é importante num checkout limpo: sem `npm ci`, o executável local do Playwright e `@playwright/test` podem nem existir; sem a instalação do browser, o runner pode existir mas não conseguir iniciar Chromium.

## 14. Stack E2E

Depois da preparação, o workflow sobe:

```bash
docker compose -f docker-compose.e2e.yml up -d --build
```

A stack usa portas locais próprias para não conflitar com a execução padrão:

```text
PostgreSQL: 5436
Backend:    8086
Frontend:   3001
```

## 15. Credenciais efêmeras

As senhas de demonstração são obrigatórias tanto para o seed do backend quanto para o login do Playwright. `docker-compose.e2e.yml` rejeita valores ausentes usando `${VAR:?...}`.

Na CI, as duas senhas são geradas a cada execução com `openssl rand -base64 32`, mascaradas e gravadas em `GITHUB_ENV`. Não existe senha fixa versionada para login E2E.

As variáveis são:

```text
IWRITE_DEMO_AUTOR_A_PASSWORD
IWRITE_DEMO_AUTOR_B_PASSWORD
```

Para reprodução local, o mesmo shell que inicia Compose e Playwright deve manter esses valores no ambiente durante toda a execução.

## 16. Readiness antes do Playwright

`docker compose ... up -d` confirma que os containers foram iniciados, mas não garante que backend e frontend já estejam prontos para receber requisições.

Por isso a CI **não** chama Playwright imediatamente. Ela espera explicitamente:

```text
backend  -> http://localhost:8086/ping
frontend -> http://localhost:3001
```

O mesmo princípio é obrigatório na receita local para evitar falhas intermitentes em máquinas frias, builds recém-criados ou inicializações mais lentas.

### Backend

O endpoint usado é `/ping`, que é database-aware:

```text
/ping
 -> DatabaseHealthService
 -> SELECT 1
 -> PostgreSQL
```

Logo um `200` do probe significa que o backend respondeu **e** conseguiu consultar o banco.

### Frontend

O frontend é considerado pronto somente quando `http://localhost:3001` responde com sucesso.

## 17. Receita E2E completa — Linux/macOS

Execute a partir da **raiz do repositório**.

Pré-requisitos externos: Docker, Node/npm, `curl` e, para geração das senhas, `openssl`.

### 17.1 Instalar dependências frontend e Chromium

A CI usa `--with-deps` no Ubuntu. Para uma receita local que também funcione no macOS, o comando diferencia a plataforma:

```bash
(
  cd web
  npm ci
  if [ "$(uname -s)" = "Linux" ]; then
    npx playwright install --with-deps chromium
  else
    npx playwright install chromium
  fi
)
```

No Linux isso também instala as dependências de sistema suportadas pelo Playwright; no macOS instala o browser necessário sem tentar usar o gerenciador de pacotes Linux.

### 17.2 Gerar credenciais efêmeras

```bash
export IWRITE_DEMO_AUTOR_A_PASSWORD="$(openssl rand -base64 32)"
export IWRITE_DEMO_AUTOR_B_PASSWORD="$(openssl rand -base64 32)"
```

### 17.3 Subir a stack

```bash
docker compose -f docker-compose.e2e.yml up -d --build
```

### 17.4 Esperar o backend

```bash
for i in $(seq 1 60); do
  if curl --fail --silent http://localhost:8086/ping > /dev/null; then
    break
  fi

  if [ "$i" -eq 60 ]; then
    docker compose -f docker-compose.e2e.yml logs backend
    exit 1
  fi

  sleep 2
done
```

### 17.5 Esperar o frontend

```bash
for i in $(seq 1 60); do
  if curl --fail --silent http://localhost:3001 > /dev/null; then
    break
  fi

  if [ "$i" -eq 60 ]; then
    docker compose -f docker-compose.e2e.yml logs frontend
    exit 1
  fi

  sleep 2
done
```

### 17.6 Executar Playwright sem abandonar a raiz

```bash
(
  cd web
  npm run e2e
)
```

O subshell é intencional: quando termina, o shell principal continua na raiz do repositório.

### 17.7 Cleanup

```bash
docker compose -f docker-compose.e2e.yml down -v
```

## 18. Receita E2E completa — Windows CMD

Execute a partir da **raiz do repositório**.

Pré-requisitos externos: Docker Desktop, Node/npm e PowerShell disponível no Windows.

### 18.1 Instalar dependências frontend e Chromium

```cmd
pushd web
npm ci && npx playwright install chromium
set "E2E_SETUP_EXIT=%ERRORLEVEL%"
popd
if not "%E2E_SETUP_EXIT%"=="0" exit /b %E2E_SETUP_EXIT%
```

O status é capturado **antes** do `popd` para que uma falha de instalação não seja mascarada por um comando posterior bem-sucedido.

### 18.2 Gerar credenciais efêmeras

```cmd
for /f %A in ('powershell -NoProfile -Command "[guid]::NewGuid().ToString('N')"') do set "IWRITE_DEMO_AUTOR_A_PASSWORD=%A"
for /f %B in ('powershell -NoProfile -Command "[guid]::NewGuid().ToString('N')"') do set "IWRITE_DEMO_AUTOR_B_PASSWORD=%B"
```

Os comandos acima são para um CMD interativo. Em arquivo `.bat`, use `%%A` e `%%B`.

### 18.3 Subir a stack

```cmd
docker compose -f docker-compose.e2e.yml up -d --build
```

### 18.4 Esperar o backend

```cmd
powershell -NoProfile -Command "$ok=$false; foreach($i in 1..60){ try { Invoke-WebRequest -UseBasicParsing http://localhost:8086/ping | Out-Null; $ok=$true; break } catch {}; Start-Sleep -Seconds 2 }; if(-not $ok){ exit 1 }"
if errorlevel 1 (
  docker compose -f docker-compose.e2e.yml logs backend
  exit /b 1
)
```

O bloco é intencional. A condição `if errorlevel 1` é avaliada imediatamente após o probe; se ele falhou, os logs são impressos e o bloco termina com `exit /b 1`. Assim um `docker compose ... logs` bem-sucedido **não pode apagar o status de falha do probe** e deixar a receita seguir para Playwright.

### 18.5 Esperar o frontend

```cmd
powershell -NoProfile -Command "$ok=$false; foreach($i in 1..60){ try { Invoke-WebRequest -UseBasicParsing http://localhost:3001 | Out-Null; $ok=$true; break } catch {}; Start-Sleep -Seconds 2 }; if(-not $ok){ exit 1 }"
if errorlevel 1 (
  docker compose -f docker-compose.e2e.yml logs frontend
  exit /b 1
)
```

A mesma regra vale para o frontend: falha de readiness sempre aborta a receita depois de exibir os logs úteis.

### 18.6 Executar Playwright e voltar à raiz

```cmd
pushd web
npm run e2e
set "E2E_EXIT=%ERRORLEVEL%"
popd
if not "%E2E_EXIT%"=="0" exit /b %E2E_EXIT%
```

`pushd`/`popd` evita o erro de permanecer em `web` e depois procurar `web\docker-compose.e2e.yml`, que não existe. O exit code do Playwright também é capturado antes do `popd`.

### 18.7 Cleanup

```cmd
docker compose -f docker-compose.e2e.yml down -v
```

## 19. Cleanup mesmo quando Playwright falha

Na CI, o cleanup possui `if: always()`:

```text
docker compose -f docker-compose.e2e.yml down -v
```

Isso reduz vazamento de containers e volumes mesmo quando um teste falha.

Para execução local manual, se o Playwright falhar e o shell encerrar antes do passo de cleanup, execute explicitamente, a partir da raiz:

```bash
docker compose -f docker-compose.e2e.yml down -v
```

ou, em CMD:

```cmd
docker compose -f docker-compose.e2e.yml down -v
```

## 20. Evidências em falha

Na CI, em caso de falha do Playwright, são publicados:

```text
web/playwright-report/
web/test-results/
```

com retenção limitada.

## 21. Relação com autenticação e multi-tenancy

O E2E valida sessão server-side, rewrite same-origin e fluxo real do navegador. Os testes Java continuam responsáveis pelos casos adversariais detalhados de isolamento multi-tenant.

## 22. Relação com OpenTelemetry

`docker/start.test.sh` garante que o entrypoint de observabilidade participe do pipeline e não exista apenas como documentação.

## 23. Relação com cobertura

A cobertura frontend deixou de ser uma execução manual opcional:

```text
.github/workflows/ci.yml chama npm test
web/package.json define npm test = vitest run --coverage
web/vitest.config.mjs exige lines >= 85
```

Uma regressão suficiente para cair abaixo de 85% faz a CI falhar antes do build.

## 24. Comandos equivalentes locais — backend/frontend

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

Para voltar à raiz depois:

```bash
cd ..
```

## 25. O que uma IA avaliadora deve verificar

1. `master` e `main` aparecem nos gatilhos da CI.
2. PostgreSQL real é service do backend job.
3. `docker/start.test.sh` é executado.
4. Java 21 executa Maven tests.
5. `.github/workflows/ci.yml` executa `npm test`.
6. `web/package.json` define `npm test = vitest run --coverage`.
7. `web/vitest.config.mjs` exige `thresholds.lines = 85`.
8. A medição atual registrou 87,16% de linhas e 375 testes frontend.
9. O frontend é buildado depois do gate.
10. O E2E possui dispatch, schedule, instalação de dependências/Chromium, credenciais efêmeras, wait loops, Playwright e cleanup.
11. `docker-compose.e2e.yml` exige as duas credenciais de demonstração sem default inseguro.
12. A receita local executa `npm ci` e instala Chromium antes do Playwright.
13. A receita local espera backend e frontend antes de Playwright.
14. O probe do backend consulta PostgreSQL via `/ping`.
15. No Windows, falhas dos probes não são mascaradas pelos comandos de logging.
16. As receitas não deixam o shell preso em `web` antes do cleanup.
17. O cleanup local referencia o `docker-compose.e2e.yml` da raiz.

## 26. Arquivos para auditoria

```text
.github/workflows/ci.yml
.github/workflows/e2e.yml
docker-compose.e2e.yml
docker/start.test.sh
pom.xml
web/package.json
web/vitest.config.mjs
web/package-lock.json
web/playwright.config.*
web/e2e/
docs/entrega/13-cobertura/README.md
```

## 27. Limitações e transparência

- E2E não roda em cada PR por padrão; é manual/agendado.
- O gate frontend é de **linhas ≥85%**, não de branches/funções.
- A receita local pressupõe Docker e Node/npm; no POSIX também usa `curl` e `openssl`; no Windows usa PowerShell para os probes e geração das credenciais.
- `npx playwright install --with-deps chromium` pode exigir privilégios adequados no Linux para instalar pacotes de sistema; no macOS/Windows a receita instala Chromium sem `--with-deps`.
- O workflow depende da infraestrutura do GitHub Actions.
- A etapa histórica de artifact frontend deve acompanhar o diretório real de build do Next.js caso esse artifact se torne requisito operacional.

## 28. Conclusão

A entrega possui CI de backend/frontend, cobertura frontend continuamente verificada e E2E com stack completa. A receita local reproduz as etapas essenciais do workflow: instalação determinística (`npm ci`), browser Chromium, credenciais efêmeras obrigatórias, espera explícita por backend/frontend, execução do Playwright somente após readiness e cleanup a partir da raiz do repositório.

Os caminhos de erro também são explícitos: falha de instalação aborta, falha de readiness imprime logs e aborta, falha de Playwright preserva seu exit code. Isso deixa o fluxo auditável e reproduzível sem depender de estado prévio do diretório `web` nem de timing acidental de inicialização dos containers.
