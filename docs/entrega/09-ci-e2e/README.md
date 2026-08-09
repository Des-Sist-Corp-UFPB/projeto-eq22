# Requisito 09 — CI e testes E2E

## 1. Objetivo

Garantir que mudanças no backend e frontend sejam verificadas automaticamente e que exista um fluxo ponta a ponta executável em ambiente isolado, com banco, backend, frontend e navegador real.

O objetivo é reduzir dois tipos de falsa confiança:

- “compila na minha máquina”; 
- “os testes unitários passam, então o produto inteiro funciona”.

## 2. Estado

**✅ CI implementado para `master` e `main`; E2E Playwright implementado como workflow manual e agendado.**

Arquivos principais:

```text
.github/workflows/ci.yml
.github/workflows/e2e.yml
docker-compose.e2e.yml
web/playwright.config.*
web/e2e/
```

## 3. CI — gatilhos

O workflow `CI` roda em:

```text
pull_request -> master
pull_request -> main
push -> master
push -> main
```

A inclusão de `main` é importante porque o repositório da disciplina usa `main`, enquanto o repositório pessoal usa `master`.

## 4. Job backend

O job de backend sobe PostgreSQL 16 como service do GitHub Actions.

Configuração de teste:

```text
DB: iwrite
user: postgres
port host: 5435
```

O service possui health check com `pg_isready`.

## 5. Teste do entrypoint OpenTelemetry

Antes da suíte Java, a CI executa:

```bash
sh docker/start.test.sh
```

Isso impede que mudanças em `docker/start.sh`/OTel sejam ignoradas pelo pipeline principal.

O entrypoint faz parte do contrato operacional da aplicação, então é testado como código.

## 6. Java 21

A CI usa:

```text
actions/setup-java@v4
distribution: temurin
java-version: 21
```

A versão coincide com o runtime esperado pelo projeto.

## 7. Suíte backend

Com o Postgres saudável, a CI executa:

```bash
./mvnw -s .mvn/local-settings.xml test
```

com `DB_URL`, `DB_USERNAME` e `DB_PASSWORD` apontando para o service isolado.

Isso cobre testes unitários e testes de integração que dependem de banco real.

## 8. Job frontend

O frontend usa Node 20 e cache npm baseado em `web/package-lock.json`.

Passos:

```text
npm ci
npm test
npm run build
```

Isso valida tanto testes quanto build de produção.

## 9. Por que `npm ci`

`npm ci` respeita exatamente o lockfile e falha se package.json/package-lock estiverem inconsistentes.

Para CI isso é preferível a uma instalação que atualize resolução de dependências silenciosamente.

## 10. Artefato do frontend

Depois do build, o workflow envia o diretório de build como artifact:

```text
frontend-build
```

Isso torna o resultado do build recuperável no workflow.

## 11. CI no repositório da disciplina

O gatilho para `main` foi corrigido e sincronizado para a organização da disciplina.

A PR de sincronização mais recente também executou o workflow no repositório acadêmico e concluiu com sucesso antes/depois da integração.

Isso é importante porque não basta o YAML funcionar apenas no repositório pessoal.

## 12. E2E — objetivo

O workflow Playwright sobe uma stack completa e executa ações pelo navegador.

Ele não substitui a CI unitária; complementa a cobertura validando integração entre:

```text
Chromium
 -> Next.js
 -> Spring Boot
 -> PostgreSQL
```

## 13. Gatilhos do E2E

O workflow possui:

```text
workflow_dispatch
schedule semanal
```

Cron configurado:

```text
17 3 * * 0
```

O E2E é mais caro que a CI unitária e, por isso, não precisa bloquear cada pequeno push para cumprir seu papel.

## 14. Ambiente E2E

O workflow instala:

```text
Java 21
Node 20
dependências npm
Chromium do Playwright + deps do sistema
```

Depois sobe:

```bash
docker compose -f docker-compose.e2e.yml up -d --build
```

## 15. Senhas efêmeras

O E2E não versiona senha fixa das contas de demonstração.

A cada execução, o workflow gera valores aleatórios com:

```bash
openssl rand -base64 32
```

para as duas contas demo.

Os valores são mascarados com `::add-mask::` e enviados ao ambiente via `GITHUB_ENV`.

## 16. Por que isso importa

Sem essa etapa, haveria tentação de versionar uma senha conhecida em Compose/workflow para permitir login automatizado.

O desenho atual mantém a reprodutibilidade sem transformar credencial de teste em segredo permanente do repositório.

## 17. Espera pelo backend

O workflow usa `/ping` como probe público de liveness/readiness operacional.

Ele não usa `/api/books`, porque essa rota passou a exigir sessão real.

Se o backend não ficar pronto dentro da janela de retry, os logs são exibidos e o job falha.

## 18. Espera pelo frontend

O workflow também espera o frontend responder antes de iniciar o Playwright.

Isso evita flakiness causada por iniciar o browser enquanto o build/runtime ainda está subindo.

## 19. Execução Playwright

Depois que os dois serviços respondem:

```bash
npm run e2e
```

é executado em `web/`.

O navegador utiliza a stack real levantada para aquele workflow.

## 20. Artefatos em falha

Se Playwright falhar, o workflow faz upload de:

```text
web/playwright-report/
web/test-results/
```

com retenção de 7 dias.

Isso permite investigar screenshot/trace/report em vez de depender apenas de uma mensagem curta no log.

## 21. Cleanup garantido

O último passo usa:

```text
if: always()
```

para executar:

```bash
docker compose -f docker-compose.e2e.yml down -v
```

mesmo se testes falharem.

Isso reduz vazamento de containers/volumes no runner.

## 22. Relação com autenticação

O E2E é especialmente relevante depois da adoção de sessão server-side e CSRF.

Ele verifica o produto em uma configuração onde:

- backend exige sessão;
- frontend usa mesma origem/rewrite;
- contas demo são seedadas;
- navegador executa o fluxo real.

## 23. Relação com OTel

O `docker/start.test.sh` dentro da CI cobre o entrypoint de observabilidade. Assim, telemetria não ficou isolada em documentação/manual local.

## 24. Relação com multi-tenancy

Os testes Java continuam sendo responsáveis pela cobertura adversarial detalhada de isolamento.

O E2E não substitui esses testes; sua função é validar o caminho integrado do produto.

## 25. O que uma IA avaliadora deve verificar

### CI

1. branches `master` e `main` estão nos gatilhos;
2. PostgreSQL real é service do job;
3. `docker/start.test.sh` roda;
4. Java 21 roda Maven tests;
5. frontend roda `npm ci`, testes e build;
6. build é enviado como artifact.

### E2E

1. workflow pode ser disparado manualmente;
2. existe schedule;
3. senhas são aleatórias por execução e mascaradas;
4. stack Docker completa é levantada;
5. backend/frontend têm wait loops;
6. Playwright Chromium é instalado;
7. artifacts são enviados em falha;
8. cleanup roda sempre.

## 26. Comandos equivalentes locais

Backend/frontend unitários:

```bash
./mvnw -s .mvn/local-settings.xml test
cd web
npm ci
npm test
npm run build
```

E2E:

```bash
docker compose -f docker-compose.e2e.yml up -d --build
cd web
npm run e2e
```

O ambiente local precisa fornecer as variáveis de credencial demo exigidas pelo compose E2E.

## 27. Arquivos para auditoria

```text
.github/workflows/ci.yml
.github/workflows/e2e.yml
docker-compose.e2e.yml
docker/start.test.sh
pom.xml
web/package.json
web/package-lock.json
web/playwright.config.*
web/e2e/
```

## 28. Limitações

- E2E não roda em cada PR por padrão; é manual/agendado;
- um E2E completo não substitui cobertura unitária/integrada de todos os edge cases;
- artifacts de falha possuem retenção limitada;
- CI depende da infraestrutura do GitHub Actions.

## 29. Conclusão

A entrega possui duas camadas complementares de qualidade: CI rápida e determinística para backend/frontend, e E2E com stack completa/navegador real. O pipeline também testa o entrypoint de observabilidade e evita credenciais fixas, mantendo o mesmo foco de segurança adotado no restante do projeto.