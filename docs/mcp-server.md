# Servidor MCP do IWrite (issue #128)

## Arquitetura

O servidor MCP é uma **camada fina** dentro do próprio backend Spring Boot (`com.iwrite.mcp`), registrada pelo starter `spring-ai-starter-mcp-server-webmvc` (Spring AI 1.0.9, MCP Java SDK 0.18.3). Nenhuma regra de negócio ou autorização foi duplicada: cada tool chama os services existentes (`BookService`, `OutlineService`, `SceneAnalysisService`, `BookExportService`), que já aplicam autorização por livro/cena e isolamento por tenant.

```
Cliente MCP (Inspector, Claude Desktop, ...)
        │  SSE: GET /sse   +   POST /mcp/message
        ▼
Backend IWrite (porta 8085) ── com.iwrite.mcp ──► services existentes ──► PostgreSQL
                                     │
                                     └─► AuditLogService + log estruturado + gateway de auditoria LLM
```

## Transporte e exposição

- **Transporte:** HTTP + SSE na mesma porta da API (`/sse` e `/mcp/message`, padrões do starter WebMVC). Escolhido por ser o transporte mínimo compatível com o runtime atual (aplicação web contínua; `stdio` não se aplica). O SDK 0.18 também suporta streamable HTTP; o SSE é o padrão do starter e o suficiente aqui.
- **Desabilitado por padrão:** `IWRITE_MCP_ENABLED=false` (`spring.ai.mcp.server.enabled`). Em ambiente remoto o servidor não fica exposto anonimamente por padrão; habilite apenas em ambiente local/controlado, na mesma postura de segurança da API REST atual (que ainda não tem autenticação própria — o provedor de identidade de desenvolvimento define usuário/tenant).

## Autenticação e autorização

- A identidade de cada invocação vem **exclusivamente** do `CurrentUserProvider` da aplicação (hoje, o provedor de desenvolvimento configurado por `iwrite.current-user.development.*`).
- `tenantId`/`userId` **nunca** são parâmetros das tools. IDs enviados pelo cliente são apenas referências de recurso e passam pelas mesmas consultas com escopo de tenant do produto.
- Recurso de outro tenant, recurso inexistente e acesso revogado produzem exatamente o mesmo erro (`not_found`), sem enumerar a existência do recurso.

## Tools

| Tool | Parâmetros | Retorno |
|---|---|---|
| `listar_livros_acessiveis` | — | id, título, status e nível de acesso dos livros acessíveis |
| `obter_outline_livro` | `bookId` (UUID) | outline: partes → capítulos → cenas (títulos, status, contagem de palavras); sem conteúdo |
| `analisar_cena` | `sceneId` (UUID), `focus` (opcional, ≤ 300 chars) | análise da IA (resumo, tom, ritmo, pontos fortes, problemas, sugestões) |
| `exportar_livro` | `bookId` (UUID), `format` (`txt`\|`md`\|`docx`, padrão `md`) | metadados do arquivo + caminho de download da API (`/api/books/{id}/exports/manuscript?...`); nunca caminho interno do servidor nem o conteúdo |

`analisar_cena` reutiliza integralmente o fluxo existente: autorização da cena, truncamento de 12k caracteres, gateway de auditoria LLM (registro persistido com trace ID, tokens e custo) e tratamento de indisponibilidade (IA desabilitada → erro `unavailable` previsível, sem custo externo). Nenhuma tool altera manuscritos.

## Resource

`iwrite://books/{bookId}/outline` (template, `application/json`) — retorna apenas metadados autorizados e a estrutura de partes/capítulos/cenas do livro, sem conteúdo integral de cenas. Mesma autorização e mesma semântica não enumerável das tools.

## Erros

Estruturados e sanitizados: `{"error":{"category":"...","message":"..."}}` com categorias enumeradas `not_found`, `invalid_request`, `unavailable`, `internal`. Stack traces, classes internas e conteúdo nunca chegam ao cliente. Parâmetros são validados (UUID, formato, limite de 300 chars no foco).

## Auditoria e correlação

Cada invocação (tool ou resource) gera:

- um registro de domínio em `audit_logs` (`MCP_BOOKS_LISTED`, `MCP_BOOK_OUTLINE_VIEWED`, `MCP_SCENE_ANALYZED`, `MCP_BOOK_EXPORTED`) com tenant, usuário, recurso e resultado;
- um log estruturado `mcp_invocation tool=... outcome=... durationMs=... errorCategory=...` com metadados apenas — argumentos livres e respostas **não** são registrados. Quando a correlação de logs com traces (OTel, branch de observabilidade) estiver integrada, essas linhas herdam o trace ID automaticamente via MDC;
- para `analisar_cena`, adicionalmente o registro do gateway de auditoria LLM já existente.

## Como conectar um cliente (MCP Inspector)

```bash
# 1. Suba o banco e o backend com MCP habilitado
docker compose up -d db
IWRITE_MCP_ENABLED=true IWRITE_DEVELOPMENT_CURRENT_USER_ENABLED=true ./mvnw spring-boot:run

# 2. Rode o Inspector e conecte por SSE
npx @modelcontextprotocol/inspector
# Transport: SSE — URL: http://localhost:8085/sse
```

No Inspector: **Tools → List Tools** deve mostrar as quatro tools; **Resources → Resource Templates** deve mostrar `iwrite://books/{bookId}/outline`. Exemplos seguros: chamar `listar_livros_acessiveis` (sem argumentos) e ler o resource com o id de um livro seu.

## Limitações e escopo

- Sem operações de escrita/destrutivas (nenhuma tool altera ou apaga manuscritos).
- Sem autenticação por cliente MCP: a identidade é a do processo (provedor de desenvolvimento). Quando a aplicação ganhar autenticação real, o mesmo `CurrentUserProvider` passa a valer para o MCP sem mudar as tools.
- `exportar_livro` retorna referência segura (metadados + caminho da API), não bytes.
- Catálogo mínimo proposital: 4 tools + 1 resource (expansão é P2 na issue).

## Testes multi-tenant (issue #130)

`McpToolsTenantIsolationIntegrationTest` cobre, contra o schema real: proprietário, colaborador autorizado, usuário do mesmo tenant sem acesso, tenant distinto, recurso inexistente e acesso revogado — para tools e resource — provando que cross-tenant e inexistente têm mensagens idênticas, que IDs do cliente não mudam o tenant atual e que auditoria/erros não carregam conteúdo. `McpServerDiscoveryIntegrationTest` sobe o servidor HTTP real e usa um cliente MCP SSE para provar descoberta, execução autorizada, leitura do resource e o erro sanitizado de análise indisponível.

## Evidências que dependem de ação humana (checklist)

- [ ] Website ID oficial do Umami informado e configurado no ambiente (nunca versionado).
- [ ] Acesso ao painel Umami confirmado.
- [ ] Page views visíveis no painel após navegação real.
- [ ] Três eventos (`book_created`, `scene_saved`, `book_exported`) visíveis no painel após ações reais.
- [ ] Screenshot do cliente MCP (Inspector) conectado.
- [ ] Screenshot da listagem de tools e do resource template no Inspector.
- [ ] Validação pós-deploy (page views + eventos + MCP) registrada com data e ambiente.
