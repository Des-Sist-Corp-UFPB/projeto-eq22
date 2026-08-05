# IWrite

IWrite é uma aplicação web para escrita e organização narrativa. O modelo principal é `Livro -> Seção -> Capítulo -> Cena`; a cena concentra o texto TipTap, o autosave, o planejamento, o histórico de versões e a análise opcional com OpenAI.

## Tecnologias

- Backend: Java 21, Spring Boot 3.4.1, Spring Data JPA, Flyway e PostgreSQL 16.
- Frontend: Next.js 15, React 19, TypeScript, Tailwind CSS, TanStack Query e TipTap.
- Qualidade: JUnit/Spring Boot Test, JaCoCo, Vitest, Testing Library e V8 Coverage.
- Infraestrutura local: Docker Compose com `db`, `backend` e `frontend`.

## Estrutura do projeto

- `src/main/java/com/iwrite/`: controllers, services, repositories, entidades e DTOs do backend.
- `src/main/resources/db/migration/`: migrations Flyway.
- `src/test/java/com/iwrite/`: testes unitários e de integração do backend.
- `web/src/app/`: rotas Next.js.
- `web/src/features/`: funcionalidades e testes do frontend.
- `cobertura/`: relatórios HTML de cobertura versionados.

## Execução local com Docker Compose

Suba o projeto inteiro:

```bash
docker compose up -d --build
```

Serviços:

- Frontend: `http://localhost:3000`
- Backend: `http://localhost:8085`
- PostgreSQL host local: `localhost:5435`
- PostgreSQL host interno Docker: `db:5432`
- Database: `iwrite`
- User: `postgres`
- Password local: `postgres`

Para parar:

```bash
docker compose down
```

## Execução local sem Docker

Suba apenas o PostgreSQL local:

```bash
docker compose up -d db
```

Configuração padrão:

- API: `http://localhost:8085`
- PostgreSQL host: `localhost:5435`
- Database: `iwrite`
- User: `postgres`
- Password local: `postgres`

Compile o backend no Windows:

```powershell
.\mvnw.cmd -s .mvn/local-settings.xml -DskipTests compile
```

O perfil de desenvolvimento habilita a identidade temporária local (`DevelopmentCurrentUserProvider`)
para resolução de tenant/usuário nos services, mas o Spring Security continua exigindo uma sessão
autenticada em toda API — a identidade temporária não dispensa o login. Antes do primeiro uso,
provisione a credencial do usuário legado determinístico (criado pela V20, id
`00000000-0000-0000-0000-000000000002`, o mesmo da identidade temporária por padrão), com o backend
já compilado e o banco no ar:

```powershell
$env:IWRITE_CREDENTIAL_PROVISIONING_ENABLED = "true"
$env:IWRITE_CREDENTIAL_PROVISIONING_EMAIL = "carlos.legacy@iwrite.local"
$env:IWRITE_CREDENTIAL_PROVISIONING_PASSWORD = "<escolha uma senha local>"
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=development
```

O provisionamento roda uma vez no boot e é idempotente (não sobrescreve uma credencial já existente).
Depois do primeiro boot, remova as três variáveis do ambiente — a senha não deve permanecer
configurada — e reinicie normalmente:

```powershell
Remove-Item Env:\IWRITE_CREDENTIAL_PROVISIONING_ENABLED, Env:\IWRITE_CREDENTIAL_PROVISIONING_EMAIL, Env:\IWRITE_CREDENTIAL_PROVISIONING_PASSWORD
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=development
```

Autentique com `POST /api/auth/login` usando o email e a senha provisionados; o cookie de sessão
resultante já é suficiente para chamar qualquer API protegida.

Alternativamente, qualquer pessoa pode criar a própria conta pela interface (`/register`) ou via
`POST /api/auth/register`: cria usuário, credencial, workspace pessoal, membership `OWNER`, persona
principal e sessão autenticada em uma única transação. Política de senha: no mínimo 10 caracteres,
com ao menos uma letra e um dígito, validada no backend (`PasswordPolicy`) e apenas replicada no
frontend como conveniência. Detalhes de contrato, migrations, segurança e limitação de tentativas em
[docs/authentication-multitenancy.md](docs/authentication-multitenancy.md#cadastro-público-143).

Compile o backend no Linux/macOS:

```bash
./mvnw -s .mvn/local-settings.xml -DskipTests compile
```

Execute o backend no Linux/macOS com o perfil de desenvolvimento (mesmo provisionamento acima, com
sintaxe de shell):

```bash
IWRITE_CREDENTIAL_PROVISIONING_ENABLED=true \
IWRITE_CREDENTIAL_PROVISIONING_EMAIL=carlos.legacy@iwrite.local \
IWRITE_CREDENTIAL_PROVISIONING_PASSWORD='<escolha uma senha local>' \
./mvnw spring-boot:run -Dspring-boot.run.profiles=development

# Depois do primeiro boot, sem as três variáveis:
./mvnw spring-boot:run -Dspring-boot.run.profiles=development
```

## Frontend local

O app Next.js fica em `web/` e usa `BACKEND_ORIGIN=http://localhost:8085` por padrão para o rewrite
de `/api/*` (`web/next.config.ts`). `NEXT_PUBLIC_API_URL` ainda é aceita como compatibilidade legada
quando `BACKEND_ORIGIN` não está definida, mas está depreciada — configure `BACKEND_ORIGIN` em
implantações novas. Um valor definido (em qualquer uma das duas) que não seja uma URL absoluta válida
falha o build em vez de cair silenciosamente no padrão local.

```bash
cd web
npm ci
npm run dev
```

## Variáveis de ambiente

Banco e runtime:

- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`: conexão PostgreSQL.
- `SERVER_PORT`: porta do backend; padrão `8085`.
- `BACKEND_ORIGIN`: origem do backend usada pelo rewrite de `/api/*` do frontend (server-side; nunca chega ao navegador).
- `NEXT_PUBLIC_API_URL`: **legada/depreciada** — usada apenas quando `BACKEND_ORIGIN` não está definida; mantida por compatibilidade com implantações existentes.
- `IWRITE_DEVELOPMENT_CURRENT_USER_ENABLED`: habilita somente a identidade temporária de desenvolvimento.
- `IWRITE_DEVELOPMENT_CURRENT_USER_ID`, `IWRITE_DEVELOPMENT_TENANT_ID`, `IWRITE_DEVELOPMENT_TIME_ZONE_ID`: identidade temporária local.
- `IWRITE_CREDENTIAL_PROVISIONING_ENABLED`, `IWRITE_CREDENTIAL_PROVISIONING_EMAIL`, `IWRITE_CREDENTIAL_PROVISIONING_PASSWORD`: provisiona a credencial de login de um usuário já existente (nunca cria um usuário); sem padrão de senha, remova as três depois de usar. Ver [docs/authentication-multitenancy.md](docs/authentication-multitenancy.md).
- `IWRITE_REGISTRATION_RATE_LIMIT_MAX_PER_ORIGIN` (padrão 10), `IWRITE_REGISTRATION_RATE_LIMIT_MAX_TRACKED_KEYS` (padrão 10000), `IWRITE_REGISTRATION_RATE_LIMIT_WINDOW` (padrão `1m`): orçamento próprio de `POST /api/auth/register`, independente do de login. Ver [docs/authentication-multitenancy.md](docs/authentication-multitenancy.md#cadastro-público-143).

Observabilidade (opcional):

- `IWRITE_OTEL_ENABLED`: habilita o OpenTelemetry Java Agent no container; padrão `false` (comportamento atual, sem variáveis extras).
- `IWRITE_OTEL_AUTH_REQUIRED`: com `true`, exige `OTEL_EXPORTER_OTLP_HEADERS` (backend institucional); com `false`, dispensa headers (LGTM local sem autenticação). Padrão `true` quando `IWRITE_OTEL_ENABLED=true` (seguro por padrão); sem efeito quando OTel está desabilitado.
- `OTEL_SERVICE_NAME`, `OTEL_EXPORTER_OTLP_ENDPOINT`: obrigatórias sempre que `IWRITE_OTEL_ENABLED=true`.
- `OTEL_EXPORTER_OTLP_HEADERS`: obrigatória apenas com `IWRITE_OTEL_AUTH_REQUIRED=true`.

Detalhes, exemplos para os dois ambientes (LGTM local e servidor institucional) e diagnóstico em [docs/opentelemetry.md](docs/opentelemetry.md). Para subir um LGTM local:

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml up --build
```

Use `.env.example` como modelo. As variáveis da integração OpenAI estão descritas na seção de serviço externo. Não versione valores secretos.

## Validação local

Backend no Windows:

```powershell
.\mvnw.cmd -s .mvn/local-settings.xml clean test jacoco:report
```

Frontend:

```bash
cd web
npm ci
npm run lint
npm run test
npm run test:coverage
npm run build
```

Os testes de integração do backend precisam do PostgreSQL em `localhost:5435`; use `docker compose up -d db` antes da suíte.

## Cobertura de Testes

Os números abaixo foram gerados em 1º de julho de 2026 sem excluir classes de produção do backend e incluindo todos os arquivos de produção `web/src/**/*.{ts,tsx}` do frontend, exceto arquivos de teste e suporte de teste.

| Camada | Testes | Linhas | Branches | Métodos/Funções | Classes |
|---|---:|---:|---:|---:|---:|
| Backend | 362 | **90,33%** | 74,43% | 91,76% | 99,47% |
| Frontend | 211 | **85,90%** | 82,33% | 68,81% | — |

- Backend: JaCoCo 0.8.12, relatório em `cobertura/backend/index.html`.
- Frontend: Vitest 3.2.6 com V8 Coverage, relatório em `cobertura/frontend/index.html`.
- A configuração do backend está em `pom.xml`; a do frontend está em `web/vitest.config.mjs` e `web/package.json`.

## Log de Auditoria

O módulo persiste eventos na tabela `audit_logs`. Cada registro contém `id`, `tenant_id`, `user_id`, `action`, `resource_type`, `resource_id`, `occurred_at` e `result` (`SUCCEEDED` ou `FAILED`). IDs de usuário e tenant vêm de `CurrentUserProvider`. Corpos de requisição, conteúdo das cenas, prompts, senhas, tokens e chaves de API não são armazenados.

Operações auditadas:

- criação, atualização e exclusão de livros;
- criação, atualização de metadados, conteúdo e planejamento, e exclusão de cenas;
- adição e remoção de colaboradores;
- restauração de versão de cena;
- análise de cena com OpenAI, incluindo resultado de sucesso ou falha.

A estratégia usa uma annotation nos endpoints relevantes e um aspecto Spring AOP. O aspecto registra o resultado por meio de um serviço com transação `REQUIRES_NEW`, preservando eventos de falha mesmo quando a operação de negócio é revertida.

Evidências no código:

- Migration: `src/main/resources/db/migration/V27__create_audit_logs.sql`.
- Entidade e enums: `src/main/java/com/iwrite/audit/entity/`.
- Repository: `src/main/java/com/iwrite/audit/repository/AuditLogRepository.java`.
- Serviço: `src/main/java/com/iwrite/audit/service/AuditLogService.java`.
- Annotation: `src/main/java/com/iwrite/audit/annotation/AuditedOperation.java`.
- Aspecto: `src/main/java/com/iwrite/audit/aspect/AuditLogAspect.java`.
- Integração: `BookController`, `BookCollaboratorController`, `SceneController` e `SceneVersionController` em `src/main/java/com/iwrite/`.
- Testes: `src/test/java/com/iwrite/audit/AuditLogIntegrationTest.java`.

## Integração com Serviço Externo

O serviço externo é a API da OpenAI. No IWrite ela produz uma análise estruturada da cena salva — resumo, tom, ritmo, pontos fortes, problemas e sugestões — pelo endpoint `POST /api/scenes/{sceneId}/ai-analysis`. A cena não é alterada pela análise.

Arquivos participantes no backend:

- `src/main/java/com/iwrite/scene/controller/SceneController.java`.
- `src/main/java/com/iwrite/scene/service/SceneAnalysisService.java`.
- `src/main/java/com/iwrite/scene/ai/WritingAssistant.java`.
- `src/main/java/com/iwrite/scene/ai/OpenAiWritingAssistant.java`.
- `src/main/java/com/iwrite/scene/ai/DisabledWritingAssistant.java`.
- `src/main/java/com/iwrite/scene/ai/OpenAiChatGenerationProperties.java`.
- `src/main/java/com/iwrite/scene/ai/OpenAiChatOptionsConfiguration.java`.
- `src/main/resources/application.yml`.

Arquivos participantes no frontend:

- `web/src/features/scenes/api/analyze-scene.ts`.
- `web/src/features/scenes/components/scene-ai-analysis-panel.tsx`.
- `web/src/features/scenes/components/scene-editor.tsx`.

Configuração:

- `SPRING_AI_MODEL_CHAT=openai`: habilita o provider; o padrão é `none`.
- `OPENAI_API_KEY`: chave secreta obrigatória quando habilitado.
- `OPENAI_BASE_URL`: base da API; padrão `https://api.openai.com`.
- `OPENAI_COMPLETIONS_PATH`: caminho de completions; padrão `/v1/chat/completions`.
- `OPENAI_MODEL`: modelo; padrão `gpt-4o-mini`.
- `OPENAI_TEMPERATURE`, `OPENAI_MAX_TOKENS`, `OPENAI_MAX_COMPLETION_TOKENS`, `OPENAI_REASONING_EFFORT`: opções opcionais de geração. Configure somente um dos dois limites de tokens.
- `OPENAI_CONNECT_TIMEOUT`: timeout de conexão; padrão `5s`.
- `OPENAI_READ_TIMEOUT`: timeout de leitura; padrão `60s`.

Sem `SPRING_AI_MODEL_CHAT=openai`, `DisabledWritingAssistant` mantém a aplicação inicializável e a rota responde `503 Service Unavailable`. O backend limita a entrada a 12.000 caracteres, higieniza a resposta estruturada, converte falhas do provider em erro seguro, faz no máximo duas tentativas e não registra conteúdo da cena. Os testes em `src/test/java/com/iwrite/scene/ai/`, `src/test/java/com/iwrite/scene/service/SceneAnalysisServiceTest.java`, `src/test/java/com/iwrite/scene/controller/` e `web/src/features/scenes/` usam mocks/stubs; nenhuma suíte chama a API paga.

Nenhuma chave ou valor secreto é versionado. Use variáveis de ambiente locais ou um gerenciador de segredos no ambiente de implantação.
