# Requisito 10 — Health check e artefatos de deploy

## 1. Objetivo

Garantir que o IWrite possua artefatos reproduzíveis para execução containerizada e um probe simples, público e independente de sessão para verificar se o backend está vivo.

Esse requisito também serve de base para CI/E2E, observabilidade e qualquer plataforma de deploy que precise distinguir “processo iniciou” de “aplicação responde”.

## 2. Estado

**✅ Dockerfiles, Docker Compose e endpoint de health/liveness implementados.**

Pontos principais:

```text
Dockerfile
web/Dockerfile
docker-compose.yml
GET /ping
rewrite /api/ping -> backend /ping
```

## 3. Backend containerizado

O backend é empacotado para Java 21 e executado através do entrypoint versionado do projeto.

O mesmo container é capaz de iniciar:

- sem OpenTelemetry;
- com OpenTelemetry Java Agent anexado;
- com configuração local ou externa por variáveis de ambiente.

A observabilidade é opcional; o container do produto não depende do Grafana para funcionar.

## 4. Frontend containerizado

O frontend possui `web/Dockerfile` separado, adequado ao runtime Next.js.

A comunicação com o backend em produção/container é feita pelo rewrite server-side e `BACKEND_ORIGIN`, não por uma URL pública hardcoded dentro do bundle.

## 5. Docker Compose local

`docker-compose.yml` organiza:

```text
PostgreSQL
backend
frontend
```

Overrides adicionais existem para necessidades específicas:

```text
docker-compose.observability.yml
docker-compose.demo.yml
docker-compose.loadtest.yml
docker-compose.e2e.yml
```

O objetivo dos overlays é não entupir o ambiente normal com configurações que só fazem sentido em evidência, demo, carga ou E2E.

## 6. Endpoint `/ping`

O backend expõe:

```http
GET /ping
```

Esse endpoint é propositalmente simples e não exige sessão.

Ele serve como probe de liveness para:

- Docker healthcheck;
- wait loop do E2E;
- smoke inicial do k6;
- plataformas de deploy;
- diagnóstico básico.

## 7. Por que `/api/books` não serve como health check

Depois que autenticação real foi implementada, `/api/books` exige sessão.

Usar uma rota protegida como probe criaria dependência artificial de:

- credencial;
- seed de usuário;
- cookie;
- CSRF em alguns fluxos;
- estado de autorização.

`/ping` responde apenas à pergunta “o backend HTTP está vivo?”.

## 8. Rewrite `/api/ping`

O browser fala com o Next.js por mesma origem. Para manter esse contrato, o frontend possui regra específica que encaminha:

```text
/api/ping
```

para:

```text
BACKEND_ORIGIN/ping
```

antes da regra genérica `/api/*`.

Isso permite testar a conectividade backend através da mesma origem do frontend sem mudar o endpoint nativo do Spring.

## 9. `BACKEND_ORIGIN`

A configuração moderna do frontend usa:

```text
BACKEND_ORIGIN
```

O valor é usado server-side pelo Next.js.

Benefícios:

- URL interna do backend não precisa ser exposta ao browser;
- sessão/cookies continuam em mesma origem do ponto de vista do navegador;
- ambientes Docker e deploy podem trocar backend sem rebuildar código fonte.

## 10. Validação de configuração

O `next.config.ts` valida que a origem configurada é URL absoluta válida.

Um valor inválido deve falhar claramente, em vez de cair silenciosamente para localhost e gerar deploy aparentemente saudável com proxy quebrado.

## 11. CORS continua relevante

Mesmo com rewrite server-side, o header `Origin` do navegador pode ser encaminhado.

O backend continua aplicando sua allowlist CORS.

Portanto, mudar a origem pública do frontend exige configurar `APP_CORS_ALLOWED_ORIGINS` adequadamente.

Essa nuance está documentada para evitar o clássico cenário “curl funciona, navegador dá 403”.

## 12. Entrypoint do backend

`docker/start.sh` concentra decisões de startup, incluindo a anexação opcional do Java Agent.

O script valida configuração e nunca deve imprimir tokens OTLP.

Ele também é testado automaticamente por:

```bash
sh docker/start.test.sh
```

na CI.

## 13. Health em E2E

O workflow Playwright espera o backend com:

```text
http://localhost:8086/ping
```

usando retries.

Se o probe não responder, o workflow imprime logs do backend e falha antes de rodar browser tests.

Isso torna falhas de startup distinguíveis de falhas funcionais do Playwright.

## 14. Health no k6

O cenário k6 usa `/ping` somente como smoke inicial em `setup()`.

Depois do smoke, a carga principal usa rotas reais.

Essa distinção é importante: `/ping` é bom probe, mas péssimo benchmark representativo.

## 15. Relação com observabilidade

A configuração de OTel vive no mesmo container, mas é opcional.

Quando `IWRITE_OTEL_ENABLED=false`, o backend deve continuar subindo sem endpoint OTLP, token ou collector.

Quando habilitado, `docker/start.sh` valida as variáveis específicas antes de anexar o agente.

## 16. Separação de ambientes por overlay

### Normal

```text
docker-compose.yml
```

### Observabilidade

```text
docker-compose.yml + docker-compose.observability.yml
```

### Demo multi-tenant

```text
docker-compose.yml + docker-compose.demo.yml
```

### Carga

```text
docker-compose.yml + demo + loadtest
```

### E2E

```text
docker-compose.e2e.yml
```

Essa separação reduz risco de carregar configurações especiais em produção por acidente.

## 17. Secrets

Configuração sensível deve vir do ambiente.

O repositório versiona exemplos/placeholders, não:

- token OTLP;
- credenciais administrativas Umami;
- senha de provider;
- senha fixa de E2E.

No E2E, senhas demo são geradas aleatoriamente por workflow.

## 18. Reprodutibilidade

O ambiente local básico pode ser iniciado com:

```bash
docker compose up -d --build
```

E parado com:

```bash
docker compose down
```

Esse é o caminho mínimo, sem exigir ferramentas acadêmicas adicionais.

## 19. Diagnóstico mínimo

Backend:

```bash
curl http://localhost:8085/ping
```

Frontend:

```bash
curl http://localhost:3000/
```

Via mesma origem do frontend:

```bash
curl http://localhost:3000/api/ping
```

As portas podem mudar em overlays específicos.

## 20. O que uma IA avaliadora deve verificar

1. Existe `Dockerfile` de backend.
2. Existe `web/Dockerfile`.
3. `docker-compose.yml` sobe os componentes principais.
4. O endpoint `/ping` existe e é público.
5. O frontend tem rewrite específico para `/api/ping`.
6. `BACKEND_ORIGIN` é server-side e validado.
7. O entrypoint OTel é opcional/testado.
8. Overlays acadêmicos não são acoplados ao deploy normal.
9. E2E e k6 usam `/ping` como probe, não como substituto de testes reais.

## 21. Arquivos para auditoria

```text
Dockerfile
web/Dockerfile
docker/start.sh
docker/start.test.sh
docker-compose.yml
docker-compose.observability.yml
docker-compose.demo.yml
docker-compose.loadtest.yml
docker-compose.e2e.yml
web/next.config.ts
src/main/java/...  # controller/configuração que expõe /ping
.github/workflows/e2e.yml
```

## 22. Limitações

- `/ping` é liveness simples; não substitui um health check profundo de todas as dependências;
- disponibilidade do Postgres e provedores externos deve ser observada por sinais próprios;
- detalhes de infraestrutura do deploy institucional dependem do ambiente externo à aplicação;
- o stack LGTM local não é parte obrigatória do deploy.

## 23. Conclusão

O IWrite possui uma base de execução reproduzível e probes coerentes com a arquitetura autenticada. O mesmo `/ping` sustenta health de containers, E2E e smoke de carga, enquanto os testes reais continuam exercitando endpoints funcionais. A separação por overlays mantém observabilidade, demo e carga como capacidades opcionais e controladas.