# IWrite

IWrite é uma aplicação web para escrita e organização narrativa. O modelo principal é `Livro -> Seção -> Capítulo -> Cena`; a cena concentra o texto TipTap, autosave, planejamento, histórico de versões e análise opcional com OpenAI.

Este repositório também é a implementação da equipe **eq22** na disciplina **Desenvolvimento de Sistemas Corporativos (DSC/UFPB)**. Para facilitar a avaliação humana e automatizada, os requisitos acadêmicos estão mapeados abaixo para a implementação e para as evidências versionadas no próprio repositório.

## Entrega acadêmica — mapa de requisitos e evidências

| Requisito | Estado no repositório | Implementação / evidência principal |
|---|---|---|
| Autenticação e multi-tenancy | ✅ Implementado e testado | [`docs/authentication-multitenancy.md`](docs/authentication-multitenancy.md), `com.iwrite.auth`, `CurrentUserProvider`, `tenant_memberships` |
| Isolamento multi-tenant | ✅ Implementado e testado | filtros por tenant nos services/repositories, testes de integração e [`docs/demonstracao-multi-tenant.md`](docs/demonstracao-multi-tenant.md) |
| OpenTelemetry — traces e métricas automáticas | ✅ Implementado | guia oficial em [`docs/opentelemetry.md`](docs/opentelemetry.md) e implementação do IWrite em [`docs/opentelemetry-implementation.md`](docs/opentelemetry-implementation.md) |
| OpenTelemetry — instrumentação manual de negócio | ✅ Implementado e testado | [`docs/otel-business-signals.md`](docs/otel-business-signals.md), `BusinessTelemetry`, spans e métricas dos fluxos críticos |
| Logs estruturados + Loki + correlação com traces | ✅ Implementado e testado | guia oficial em [`docs/opentelemetry-logs.md`](docs/opentelemetry-logs.md) e implementação em [`docs/otel-correlated-logs.md`](docs/otel-correlated-logs.md) |
| Grafana / Tempo / Loki / Prometheus-Mimir | ✅ Stack e exportação configuradas | `docker-compose.observability.yml` + [`docs/opentelemetry-implementation.md`](docs/opentelemetry-implementation.md) |
| Analytics de produto com Umami | ✅ Código e testes implementados; 🟡 validação externa depende do Website ID oficial | [`docs/analytics-umami.md`](docs/analytics-umami.md), `web/src/lib/analytics/` |
| Servidor MCP | ✅ Implementado e testado; 🟡 evidência visual depende de execução humana no Inspector | [`docs/mcp-server.md`](docs/mcp-server.md), `com.iwrite.mcp` |
| Teste de carga | ✅ Implementado com k6 | [`loadtest/README.md`](loadtest/README.md), `loadtest/carga.js`, `docker-compose.loadtest.yml` |
| CI e E2E | ✅ Implementado | [`.github/workflows/ci.yml`](.github/workflows/ci.yml), [`.github/workflows/e2e.yml`](.github/workflows/e2e.yml) |
| Health check / deploy | ✅ Artefatos e probe implementados | `Dockerfile`, `web/Dockerfile`, `GET /ping`, rewrite `/api/ping` no Next.js |

> **Importante:** evidências que dependem de serviços externos ou ação humana não são marcadas como concluídas apenas porque o código existe. O Website ID/painel oficial do Umami, visualização no Grafana institucional e screenshots do MCP Inspector devem ser validados no ambiente da disciplina. O checklist correspondente está em [`docs/mcp-server.md`](docs/mcp-server.md#evidências-que-dependem-de-ação-humana-checklist).

## Arquitetura

```text
Navegador
   │
   │ mesma origem (/api/*)
   ▼
Next.js 15 / React 19
   │
   │ rewrite server-side
   ▼
Spring Boot 3.4.1 / Java 21
   │
   ├──────────────► PostgreSQL 16
   │
   ├── OpenTelemetry Java Agent ──OTLP──► Grafana / Tempo / Loki / Mimir
   │
   ├── OpenAI (opcional) ───────────────► análise de cenas
   │
   └── MCP (opcional, somente loopback) ► tools/resources do IWrite

Next.js ──► Umami (opcional, analytics de produto)
```

A identidade e o tenant são resolvidos no backend. O navegador não escolhe `tenantId`, e recursos de outro tenant são tratados como não encontrados para evitar enumeração.

## Tecnologias

- **Backend:** Java 21, Spring Boot 3.4.1, Spring Security, Spring Data JPA, Flyway e PostgreSQL 16.
- **Frontend:** Next.js 15, React 19, TypeScript, Tailwind CSS, TanStack Query e TipTap.
- **Observabilidade:** OpenTelemetry Java Agent, OTLP, Grafana, Tempo, Loki e Prometheus/Mimir.
- **Analytics:** Umami.
- **MCP:** Spring AI MCP Server WebMVC.
- **Qualidade:** JUnit/Spring Boot Test, JaCoCo, Vitest, Testing Library, V8 Coverage e Playwright.
- **Carga:** k6.
- **Infraestrutura local:** Docker Compose.

## Estrutura do projeto

- `src/main/java/com/iwrite/`: controllers, services, repositories, entidades, DTOs, autenticação, auditoria, observabilidade e MCP.
- `src/main/resources/db/migration/`: migrations Flyway.
- `src/test/java/com/iwrite/`: testes unitários e de integração do backend.
- `web/src/app/`: rotas Next.js.
- `web/src/features/`: funcionalidades e testes do frontend.
- `web/src/lib/analytics/`: integração tipada e sanitizada com Umami.
- `docs/`: documentação técnica e guias da disciplina.
- `loadtest/`: cenário realista de carga com k6 e resultados versionados.
- `cobertura/`: snapshots HTML de cobertura versionados.
- `.github/workflows/`: CI e E2E.

## Execução local com Docker Compose

Suba o projeto inteiro:

```bash
docker compose up -d --build
```

Serviços padrão:

- Frontend: `http://localhost:3000`
- Backend: `http://localhost:8085`
- Probe do backend: `http://localhost:8085/ping`
- PostgreSQL no host: `localhost:5435`
- PostgreSQL na rede Docker: `db:5432`
- Database: `iwrite`
- Usuário local: `postgres`
- Senha local padrão: `postgres`

Para parar:

```bash
docker compose down
```

## Execução local sem Docker para a aplicação

Suba apenas o PostgreSQL:

```bash
docker compose up -d db
```

Compile o backend no Windows:

```powershell
.\mvnw.cmd -s .mvn/local-settings.xml -DskipTests compile
```

Execute no Linux/macOS:

```bash
./mvnw -s .mvn/local-settings.xml -DskipTests compile
./mvnw spring-boot:run -Dspring-boot.run.profiles=development
```

O app Next.js fica em `web/`:

```bash
cd web
npm ci
npm run dev
```

O frontend usa `BACKEND_ORIGIN=http://localhost:8085` por padrão para o rewrite server-side de `/api/*`. `NEXT_PUBLIC_API_URL` permanece apenas como compatibilidade legada e está depreciada.

## Autenticação e multi-tenancy

A API normal usa sessão de servidor. O fluxo principal é:

```text
JSESSIONID (HttpOnly, SameSite=Lax)
        │
        ▼
Spring Security / SecurityContext
        │
        ▼
IWriteUserDetails
        │
        ▼
AuthenticatedCurrentUserProvider
        │
        ▼
tenant_memberships relida a cada requisição
        │
        ▼
services/repositories com escopo de tenant
```

Pontos importantes:

- `tenantId`, `userId` e `role` enviados pelo cliente nunca são fonte de autoridade;
- a membership persistida define o tenant efetivo;
- recurso de outro tenant e recurso inexistente produzem a mesma semântica de `404`;
- revogar a membership invalida o contexto autenticado;
- o navegador não guarda identidade em `localStorage`/`sessionStorage`;
- mutações usam proteção CSRF de duplo envio;
- cadastro público cria usuário, credencial, workspace pessoal, membership `OWNER`, persona principal e sessão em uma única transação;
- login e cadastro possuem rate limiting próprio.

Documentação completa: [`docs/authentication-multitenancy.md`](docs/authentication-multitenancy.md).

### Credencial do usuário legado em desenvolvimento

Instalações antigas podem provisionar uma credencial para um usuário que já existe. O mecanismo é desligado por padrão e nunca cria usuário.

```powershell
$env:IWRITE_CREDENTIAL_PROVISIONING_ENABLED = "true"
$env:IWRITE_CREDENTIAL_PROVISIONING_EMAIL = "carlos.legacy@iwrite.local"
$env:IWRITE_CREDENTIAL_PROVISIONING_PASSWORD = "<escolha uma senha local>"
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=development
```

Depois do primeiro boot, remova as variáveis de provisionamento. Detalhes de rollout, rotação e limites do bcrypt estão em [`docs/authentication-multitenancy.md`](docs/authentication-multitenancy.md).

## OpenTelemetry, Grafana, Tempo, Loki e métricas

Há dois conjuntos de documentação propositalmente separados.

### Guias oficiais sincronizados da disciplina

- [`docs/opentelemetry.md`](docs/opentelemetry.md) — telemetria, OpenTelemetry e tutorial geral da disciplina.
- [`docs/opentelemetry-logs.md`](docs/opentelemetry-logs.md) — guia complementar de logs/Loki da disciplina.

### Implementação e evidências específicas do IWrite

- [`docs/opentelemetry-implementation.md`](docs/opentelemetry-implementation.md) — Java Agent, configuração OTLP, segurança, diagnóstico e consultas de evidência.
- [`docs/otel-business-signals.md`](docs/otel-business-signals.md) — spans e métricas manuais de negócio.
- [`docs/otel-correlated-logs.md`](docs/otel-correlated-logs.md) — eventos estruturados, correlação log → trace e LogQL.

O container do backend inclui o OpenTelemetry Java Agent, mas a telemetria fica **desabilitada por padrão**. Com o agente desligado, a aplicação funciona sem exigir nenhuma variável `OTEL_*`.

Variáveis principais:

- `IWRITE_OTEL_ENABLED`
- `IWRITE_OTEL_AUTH_REQUIRED`
- `OTEL_SERVICE_NAME`
- `OTEL_EXPORTER_OTLP_ENDPOINT`
- `OTEL_EXPORTER_OTLP_HEADERS` quando o backend exige autenticação
- `OTEL_TRACES_EXPORTER`
- `OTEL_METRICS_EXPORTER`
- `OTEL_LOGS_EXPORTER`

Para subir o ambiente local LGTM, sem autenticação:

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d --build
```

O Grafana local fica em `http://localhost:3001`. O override de observabilidade é somente para desenvolvimento/evidências e não transforma Grafana, Tempo, Loki ou Mimir em componentes do deploy normal do IWrite.

### Instrumentação manual de negócio

O IWrite instrumenta explicitamente dois fluxos críticos:

- `PATCH /api/scenes/{sceneId}/content` → span `iwrite.scene.content.save`;
- `POST /api/scenes/{sceneId}/ai-analysis` → span `iwrite.scene.analysis`.

As métricas manuais são `iwrite.business.operation.count` e `iwrite.business.operation.duration`. Labels e atributos são limitados a uma allowlist; conteúdo de manuscrito, IDs, prompts, tokens e strings livres não são usados como labels.

## Analytics de produto com Umami

Umami mede **uso do produto**, enquanto OpenTelemetry mede **comportamento técnico do sistema**. As integrações são independentes.

Implementação: [`docs/analytics-umami.md`](docs/analytics-umami.md) e `web/src/lib/analytics/`.

Variáveis de build do frontend:

- `NEXT_PUBLIC_UMAMI_ENABLED`
- `NEXT_PUBLIC_UMAMI_SCRIPT_URL`
- `NEXT_PUBLIC_UMAMI_WEBSITE_ID`
- `NEXT_PUBLIC_UMAMI_HOST_URL` (opcional)

Eventos tipados atualmente suportados:

- `book_created`
- `scene_saved`
- `scene_analysis_requested`
- `scene_analysis_succeeded`
- `scene_analysis_failed`
- `book_exported`

A integração remove query string/hash, normaliza segmentos dinâmicos de rota e aplica allowlist de propriedades e valores. Não envia conteúdo do manuscrito, títulos, emails, nomes, IDs brutos, prompts, respostas de IA, tokens ou stack traces.

Sem configuração válida, a integração é no-op e não bloqueia o produto. O Website ID oficial não é versionado; sua presença e os eventos no painel precisam ser validados no ambiente da disciplina.

## Servidor MCP

O servidor MCP é uma camada fina sobre os services existentes do IWrite. Ele está **desabilitado por padrão** e, na configuração atual, só é suportado com identidade fixa de desenvolvimento e processo limitado a loopback.

Documentação: [`docs/mcp-server.md`](docs/mcp-server.md).

Tools:

| Tool | Objetivo |
|---|---|
| `listar_livros_acessiveis` | lista livros que a identidade atual pode acessar |
| `obter_outline_livro` | retorna outline autorizado sem conteúdo integral das cenas |
| `analisar_cena` | reutiliza o fluxo existente de análise assistida, auditoria e limites |

Resource template:

```text
iwrite://books/{bookId}/outline
```

Para teste local com MCP Inspector:

```bash
docker compose up -d db
IWRITE_MCP_ENABLED=true IWRITE_DEVELOPMENT_CURRENT_USER_ENABLED=true SERVER_ADDRESS=127.0.0.1 ./mvnw spring-boot:run
npx @modelcontextprotocol/inspector
```

Conecte por SSE em `http://localhost:8085/sse`.

O `McpLoopbackGuard` impede o startup em configurações não suportadas. Enquanto o transporte não tiver autenticação própria por cliente, os endpoints MCP não devem ser publicados por reverse proxy.

## Teste de carga com k6

O cenário de carga está em [`loadtest/README.md`](loadtest/README.md) e [`loadtest/carga.js`](loadtest/carga.js).

Ele exercita a API real com sessão e CSRF, em vez de medir apenas `/ping`. Cada VU usa sessão independente e seu próprio livro/cena, evitando contenção artificial em um único recurso compartilhado. O fluxo cobre listagem de livros, carregamento de outline, carregamento de cena, autosave e refresh de outline após salvamento.

Por segurança, o script **recusa destino remoto** e deve rodar apenas contra loopback/ambiente local. Não use o teste de carga contra produção nem contra infraestrutura acadêmica compartilhada.

A stack recomendada para carga local usa os overlays de demonstração e de carga:

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.demo.yml \
  -f docker-compose.loadtest.yml \
  up -d --build
```

Thresholds, preparação, limpeza, autenticação, resultados medidos e comandos completos estão documentados em [`loadtest/README.md`](loadtest/README.md).

## CI, E2E e validação local

### Backend

Windows:

```powershell
.\mvnw.cmd -s .mvn/local-settings.xml clean test jacoco:report
```

Linux/macOS:

```bash
./mvnw -s .mvn/local-settings.xml clean test jacoco:report
```

Os testes de integração precisam do PostgreSQL local em `localhost:5435`:

```bash
docker compose up -d db
```

### Frontend

```bash
cd web
npm ci
npm run lint
npm run test
npm run test:coverage
npm run build
```

### GitHub Actions

- [`.github/workflows/ci.yml`](.github/workflows/ci.yml): testes do backend, validação do entrypoint OpenTelemetry, testes/build do frontend.
- [`.github/workflows/e2e.yml`](.github/workflows/e2e.yml): stack E2E e Playwright com credenciais efêmeras por execução.

## Cobertura de testes — snapshot versionado

Os números abaixo são um **snapshot de 1º de julho de 2026**, não uma afirmação de cobertura calculada a cada commit posterior.

| Camada | Testes | Linhas | Branches | Métodos/Funções | Classes |
|---|---:|---:|---:|---:|---:|
| Backend | 362 | **90,33%** | 74,43% | 91,76% | 99,47% |
| Frontend | 211 | **85,90%** | 82,33% | 68,81% | — |

- Backend: JaCoCo 0.8.12, snapshot em `cobertura/backend/index.html`.
- Frontend: Vitest 3.2.6 + V8 Coverage, snapshot em `cobertura/frontend/index.html`.

## Health check e deploy

O backend expõe `GET /ping` como probe público de liveness. O frontend possui regra explícita `/api/ping -> BACKEND_ORIGIN/ping`, permitindo verificar o backend sem depender de sessão autenticada.

Artefatos principais de deploy:

- `Dockerfile` — backend/runtime do IWrite;
- `web/Dockerfile` — frontend Next.js;
- `docker/start.sh` — inicialização do backend e ativação opcional do Java Agent;
- `web/next.config.ts` — rewrite da API e validação de `BACKEND_ORIGIN`;
- `.env.example` — modelo sem segredos.

A configuração do ambiente implantado deve fornecer banco, origens permitidas e segredos por variáveis/secret manager. O repositório não deve conter tokens institucionais, chave OpenAI, senhas reais ou credenciais administrativas.

## Integração com OpenAI e auditoria LLM

A integração externa opcional produz análise estruturada da cena salva pelo endpoint:

```text
POST /api/scenes/{sceneId}/ai-analysis
```

A análise não altera a cena. Quando o provider não está habilitado, a aplicação continua inicializável e a rota retorna indisponibilidade controlada.

Variáveis principais:

- `SPRING_AI_MODEL_CHAT=openai`
- `OPENAI_API_KEY`
- `OPENAI_BASE_URL`
- `OPENAI_COMPLETIONS_PATH`
- `OPENAI_MODEL`
- `OPENAI_TEMPERATURE`
- `OPENAI_MAX_TOKENS` / `OPENAI_MAX_COMPLETION_TOKENS`
- `OPENAI_REASONING_EFFORT`
- `OPENAI_CONNECT_TIMEOUT`
- `OPENAI_READ_TIMEOUT`

O fluxo passa pelo gateway de auditoria LLM e também participa da auditoria de domínio. Testes usam mocks/stubs e não chamam a API paga.

## Log de auditoria

Eventos relevantes são persistidos em `audit_logs`, com tenant, usuário, ação, recurso, instante e resultado. Conteúdo de cenas, prompts, senhas, tokens e chaves de API não são armazenados no log de domínio.

Operações auditadas incluem livros, cenas, colaboração, restauração de versão, análise com IA e invocações MCP.

Evidências principais:

- `src/main/resources/db/migration/V27__create_audit_logs.sql`
- `src/main/java/com/iwrite/audit/`
- `src/test/java/com/iwrite/audit/AuditLogIntegrationTest.java`

## Variáveis de ambiente — referência rápida

### Banco e runtime

- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
- `SERVER_PORT`
- `SERVER_ADDRESS`
- `BACKEND_ORIGIN`
- `APP_CORS_ALLOWED_ORIGINS`
- `NEXT_PUBLIC_API_URL` — compatibilidade legada/depreciada

### Autenticação / desenvolvimento

- `IWRITE_DEVELOPMENT_CURRENT_USER_ENABLED`
- `IWRITE_DEVELOPMENT_CURRENT_USER_ID`
- `IWRITE_DEVELOPMENT_TENANT_ID`
- `IWRITE_DEVELOPMENT_TIME_ZONE_ID`
- `IWRITE_CREDENTIAL_PROVISIONING_ENABLED`
- `IWRITE_CREDENTIAL_PROVISIONING_EMAIL`
- `IWRITE_CREDENTIAL_PROVISIONING_PASSWORD`
- variáveis de rate limiting documentadas em [`docs/authentication-multitenancy.md`](docs/authentication-multitenancy.md)

### OpenTelemetry

- `IWRITE_OTEL_ENABLED`
- `IWRITE_OTEL_AUTH_REQUIRED`
- `OTEL_SERVICE_NAME`
- `OTEL_EXPORTER_OTLP_ENDPOINT`
- `OTEL_EXPORTER_OTLP_HEADERS`

### Umami

- `NEXT_PUBLIC_UMAMI_ENABLED`
- `NEXT_PUBLIC_UMAMI_SCRIPT_URL`
- `NEXT_PUBLIC_UMAMI_WEBSITE_ID`
- `NEXT_PUBLIC_UMAMI_HOST_URL`

### MCP

- `IWRITE_MCP_ENABLED`
- `IWRITE_MCP_SCENE_ANALYSIS_MAX_PER_WINDOW`
- `IWRITE_MCP_SCENE_ANALYSIS_WINDOW`

### OpenAI

- `SPRING_AI_MODEL_CHAT`
- `OPENAI_API_KEY`
- demais opções em `src/main/resources/application.yml` e `.env.example`

## Princípios de segurança relevantes à entrega

- tenant e usuário são determinados no servidor, nunca confiados ao cliente;
- recursos cross-tenant não são enumeráveis;
- segredos ficam fora do Git;
- telemetria, analytics e logs evitam conteúdo de manuscrito e identificadores brutos;
- Umami usa allowlist de eventos/propriedades;
- OpenTelemetry não registra exception stack/message nos spans manuais de negócio;
- MCP é off por padrão e limitado a loopback na configuração suportada;
- k6 recusa destinos remotos;
- testes de integração com OpenAI usam mocks/stubs.

## Índice de documentação acadêmica e técnica

| Tema | Documento |
|---|---|
| Autenticação e multi-tenancy | [`docs/authentication-multitenancy.md`](docs/authentication-multitenancy.md) |
| Demonstração multi-tenant | [`docs/demonstracao-multi-tenant.md`](docs/demonstracao-multi-tenant.md) |
| OpenTelemetry — guia oficial da disciplina | [`docs/opentelemetry.md`](docs/opentelemetry.md) |
| Logs/Loki — guia oficial da disciplina | [`docs/opentelemetry-logs.md`](docs/opentelemetry-logs.md) |
| OpenTelemetry — implementação do IWrite | [`docs/opentelemetry-implementation.md`](docs/opentelemetry-implementation.md) |
| Sinais manuais de negócio | [`docs/otel-business-signals.md`](docs/otel-business-signals.md) |
| Logs correlacionados | [`docs/otel-correlated-logs.md`](docs/otel-correlated-logs.md) |
| Umami | [`docs/analytics-umami.md`](docs/analytics-umami.md) |
| MCP | [`docs/mcp-server.md`](docs/mcp-server.md) |
| Teste de carga | [`loadtest/README.md`](loadtest/README.md) |

Use `.env.example` e `web/.env.local.example` como modelos. **Não versione valores secretos.**
