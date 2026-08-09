# Requisito 09 — CI e testes E2E

## 1. Objetivo

Garantir que mudanças no backend e frontend sejam verificadas automaticamente e que exista um fluxo ponta a ponta executável em ambiente isolado, com banco, backend, frontend e navegador real.

O objetivo é reduzir falsa confiança de “funciona na minha máquina” e transformar requisitos importantes — inclusive cobertura frontend — em verificações automáticas.

## 2. Estado

**✅ CI implementada para `master` e `main`; E2E Playwright manual/agendado; cobertura frontend ≥85% agora é gate do job principal.**

Arquivos principais:

```text
.github/workflows/ci.yml
.github/workflows/e2e.yml
docker-compose.e2e.yml
web/package.json
web/vitest.config.mjs
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

## 4. Backend

O job sobe PostgreSQL 16 como service do GitHub Actions com `pg_isready`, configura Java 21 e executa o entrypoint OTel antes da suíte Java.

```bash
sh docker/start.test.sh
./mvnw -s .mvn/local-settings.xml test
```

Os testes de integração usam PostgreSQL real do job.

## 5. Frontend

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

## 7. Evidência da CI #253

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

O finding do Codex sobre usar apenas o snapshot histórico de 01/07 foi, portanto, corrigido com uma medição atual na própria CI.

Relatório específico: [`../13-cobertura/README.md`](../13-cobertura/README.md).

## 8. `npm ci`

`npm ci` respeita o lockfile e falha quando `package.json` e `package-lock.json` são incompatíveis. Para CI isso evita resolução silenciosa diferente da versionada.

## 9. Build de produção

Só depois dos testes e do gate de cobertura o job executa:

```bash
npm run build
```

Isso valida TypeScript/Next e o build otimizado do frontend.

## 10. Observação sobre artifact de build

O workflow possui etapa de upload do build frontend. A saída padrão atual do Next.js é `.next/`; a etapa histórica aponta para `web/build` e pode emitir warning de ausência de artifact sem falhar o job.

Esse detalhe não invalida testes/build/cobertura, mas deve ser tratado separadamente caso o artifact de build seja necessário como requisito operacional.

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

## 13. Ambiente E2E

O workflow instala Java, Node, dependências npm e Chromium, depois sobe:

```bash
docker compose -f docker-compose.e2e.yml up -d --build
```

## 14. Credenciais efêmeras

As senhas de demonstração são obrigatórias tanto para o seed do backend quanto para o login do Playwright. `docker-compose.e2e.yml` rejeita valores ausentes usando `${VAR:?...}`.

Na CI, as duas senhas são geradas a cada execução com `openssl rand -base64 32`, mascaradas com `::add-mask::` e gravadas em `GITHUB_ENV`. Não existe senha fixa versionada para login E2E.

Para reprodução local, o shell que inicia o Compose e o Playwright também precisa exportar/definir **as mesmas duas variáveis** antes de subir a stack.

### Linux/macOS — gerar valores efêmeros

```bash
export IWRITE_DEMO_AUTOR_A_PASSWORD="$(openssl rand -base64 32)"
export IWRITE_DEMO_AUTOR_B_PASSWORD="$(openssl rand -base64 32)"

docker compose -f docker-compose.e2e.yml up -d --build
cd web
npm run e2e
```

### Windows CMD — gerar valores efêmeros

```cmd
for /f %A in ('powershell -NoProfile -Command "[guid]::NewGuid().ToString('N')"') do set "IWRITE_DEMO_AUTOR_A_PASSWORD=%A"
for /f %B in ('powershell -NoProfile -Command "[guid]::NewGuid().ToString('N')"') do set "IWRITE_DEMO_AUTOR_B_PASSWORD=%B"

docker compose -f docker-compose.e2e.yml up -d --build
cd web
npm run e2e
```

> Os comandos acima são para um **CMD interativo**. Em arquivo `.bat`, use `%%A` e `%%B`. Os valores são efêmeros para aquela execução e não devem ser commitados.

Depois da execução local, faça o cleanup da stack:

```bash
docker compose -f docker-compose.e2e.yml down -v
```

## 15. Readiness

O workflow espera backend e frontend antes do Playwright.

O backend usa `/ping`, que agora é database-aware:

```text
/ping
 -> SELECT 1
 -> PostgreSQL
```

Logo o E2E não inicia se o backend HTTP responder mas o banco estiver inacessível.

## 16. Execução Playwright

```bash
cd web
npm run e2e
```

O navegador usa a stack real levantada para a execução e lê as mesmas variáveis `IWRITE_DEMO_AUTOR_A_PASSWORD` e `IWRITE_DEMO_AUTOR_B_PASSWORD` usadas pelo seed da stack.

## 17. Evidências em falha

Em falha, o workflow publica:

```text
web/playwright-report/
web/test-results/
```

com retenção limitada.

## 18. Cleanup

O compose E2E é derrubado com `if: always()`, reduzindo vazamento de containers e volumes mesmo quando o teste falha.

## 19. Relação com autenticação e multi-tenancy

O E2E valida sessão server-side, rewrite same-origin e fluxo real do navegador. Os testes Java continuam responsáveis pelos casos adversariais detalhados de isolamento multi-tenant.

## 20. Relação com OTel

`docker/start.test.sh` garante que o entrypoint de observabilidade participe do pipeline e não exista apenas como documentação.

## 21. Relação com Cob

A cobertura frontend deixou de ser uma execução manual opcional:

```text
.github/workflows/ci.yml chama npm test
web/package.json transforma npm test em vitest run --coverage
web/vitest.config.mjs exige lines >= 85
```

Uma regressão suficiente para cair abaixo de 85% faz a CI falhar antes do build.

## 22. Comandos equivalentes locais

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

### E2E Linux/macOS

```bash
export IWRITE_DEMO_AUTOR_A_PASSWORD="$(openssl rand -base64 32)"
export IWRITE_DEMO_AUTOR_B_PASSWORD="$(openssl rand -base64 32)"
docker compose -f docker-compose.e2e.yml up -d --build
cd web
npm run e2e
```

### E2E Windows CMD

```cmd
for /f %A in ('powershell -NoProfile -Command "[guid]::NewGuid().ToString('N')"') do set "IWRITE_DEMO_AUTOR_A_PASSWORD=%A"
for /f %B in ('powershell -NoProfile -Command "[guid]::NewGuid().ToString('N')"') do set "IWRITE_DEMO_AUTOR_B_PASSWORD=%B"
docker compose -f docker-compose.e2e.yml up -d --build
cd web
npm run e2e
```

## 23. O que uma IA avaliadora deve verificar

1. `master` e `main` aparecem nos gatilhos da CI.
2. PostgreSQL real é service do backend job.
3. `docker/start.test.sh` é executado.
4. Java 21 executa Maven tests.
5. `.github/workflows/ci.yml` executa `npm test`.
6. `web/package.json` define `npm test = vitest run --coverage`.
7. `web/vitest.config.mjs` exige `thresholds.lines = 85`.
8. A CI #253 registrou 87,16% de linhas e 375 testes frontend.
9. O frontend é buildado depois do gate.
10. O E2E possui dispatch, schedule, credenciais efêmeras, wait loops, Playwright e cleanup.
11. A receita local define `IWRITE_DEMO_AUTOR_A_PASSWORD` e `IWRITE_DEMO_AUTOR_B_PASSWORD` antes do Compose e mantém os valores no ambiente para o Playwright.

## 24. Arquivos para auditoria

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

## 25. Limitações

- E2E não roda em cada PR por padrão; é manual/agendado.
- O gate frontend é de **linhas ≥85%**, não de branches/funções.
- A etapa histórica de upload de `web/build` não corresponde à saída `.next/` do Next.js e pode apenas gerar warning; testes, cobertura e build continuam independentes disso.
- CI depende da infraestrutura do GitHub Actions.

## 26. Conclusão

A entrega possui CI de backend/frontend, cobertura frontend continuamente verificada e E2E com stack completa. O finding de cobertura da PR #159 fortaleceu o pipeline: em vez de depender apenas de snapshot histórico, a revisão atual mede o código corrente e exige ≥85% de linhas antes do build.
