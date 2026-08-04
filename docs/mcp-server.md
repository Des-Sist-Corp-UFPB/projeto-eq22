# Servidor MCP do IWrite (issue #128)

## Arquitetura

O servidor MCP é uma **camada fina** dentro do próprio backend Spring Boot (`com.iwrite.mcp`), registrada pelo starter `spring-ai-starter-mcp-server-webmvc` (Spring AI 1.0.9, MCP Java SDK 0.18.3). Nenhuma regra de negócio ou autorização foi duplicada: cada tool chama os services existentes (`BookService`, `OutlineService`, `SceneAnalysisService`), que já aplicam autorização por livro/cena e isolamento por tenant.

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
- **Desabilitado por padrão:** `IWRITE_MCP_ENABLED=false` (`spring.ai.mcp.server.enabled`). Em ambiente remoto o servidor não fica exposto anonimamente por padrão.
- **O transporte MCP não tem autenticação própria.** Nenhum cliente MCP atual realiza `POST /api/auth/login`; `/sse` e `/mcp/message` só podem existir como `permitAll` no `SecurityConfig`. Por isso há exatamente **uma configuração suportada**: identidade fixa de desenvolvimento (`DevelopmentCurrentUserProvider`, `iwrite.current-user.development.enabled=true`) com o processo limitado a loopback (`server.address` resolvendo para um endereço de loopback, ex. `SERVER_ADDRESS=127.0.0.1`). Toda invocação herda essa mesma identidade fixa — não há por-cliente.
- **Fail-fast no startup, de propósito.** `McpLoopbackGuard` recusa a inicialização (`IllegalStateException`, mensagem sem detalhes sensíveis) em qualquer configuração fora da suportada:
  - MCP habilitado com o `CurrentUserProvider` autenticado (`AuthenticatedCurrentUserProvider`) em vez do de desenvolvimento — sem isso, o servidor subiria descobrível mas toda tool/resource falharia com `SessionAuthenticationException`, já que nenhuma requisição MCP popula um `IWriteUserDetails` real;
  - MCP habilitado com identidade de desenvolvimento mas `server.address` ausente ou não resolvendo para loopback.

  A aplicação não sobe fora desses dois casos — o MCP nunca fica exposto remotamente nem com uma identidade que não consegue autenticar nada.
- **Autenticação por cliente MCP é trabalho futuro**, não algo que passa a funcionar sozinho quando a aplicação ganha autenticação real: o `AuthenticatedCurrentUserProvider` já existe hoje para a API HTTP normal, e o guard acima recusa deliberadamente combiná-lo com o MCP até que o transporte MCP tenha seu próprio fluxo de autenticação.
- **Atenção a reverse proxy:** o loopback protege a configuração atual do processo, mas não impede que um reverse proxy publique os endpoints. Enquanto o transporte MCP não tiver autenticação própria, `/sse` e `/mcp/message` **não devem** ser expostos por reverse proxy.

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

`analisar_cena` reutiliza integralmente o fluxo existente: autorização da cena, truncamento de 12k caracteres, gateway de auditoria LLM (registro persistido com trace ID, tokens e custo) e tratamento de indisponibilidade (IA desabilitada → erro `unavailable` previsível, sem custo externo). Nenhuma tool altera manuscritos.

### Limites do `analisar_cena`

Proteção em memória contra abuso e custo (`McpSceneAnalysisLimiter`), por identidade (tenant + usuário):

- no máximo **uma análise concorrente**;
- janela fixa configurável: `IWRITE_MCP_SCENE_ANALYSIS_MAX_PER_WINDOW` (padrão 3) tentativas por `IWRITE_MCP_SCENE_ANALYSIS_WINDOW` (padrão `1m`). Tentativas com falha também contam;
- excedeu → erro sanitizado com categoria `rate_limited`; o bloqueio de concorrência é liberado após sucesso ou falha.

## Resource

`iwrite://books/{bookId}/outline` (template, `application/json`) — retorna apenas metadados autorizados e a estrutura de partes/capítulos/cenas do livro, sem conteúdo integral de cenas. Mesma autorização e mesma semântica não enumerável das tools.

## Erros

Estruturados e sanitizados: `{"error":{"category":"...","message":"..."}}` com categorias enumeradas `not_found`, `invalid_request`, `unavailable`, `rate_limited`, `internal`. Stack traces, classes internas e conteúdo nunca chegam ao cliente. Parâmetros são validados (UUID, formato, limite de 300 chars no foco).

## Auditoria e correlação

Cada invocação (tool ou resource) gera:

- um registro de domínio em `audit_logs` (`MCP_BOOKS_LISTED`, `MCP_BOOK_OUTLINE_VIEWED`, `MCP_SCENE_ANALYZED`) com tenant, usuário, recurso e resultado;
- um log estruturado `mcp_invocation tool=... outcome=... durationMs=... errorCategory=...` com metadados apenas — argumentos livres e respostas **não** são registrados. Quando a correlação de logs com traces (OTel, branch de observabilidade) estiver integrada, essas linhas herdam o trace ID automaticamente via MDC;
- para `analisar_cena`, adicionalmente o registro do gateway de auditoria LLM já existente.

## Como conectar um cliente (MCP Inspector)

```bash
# 1. Suba o banco e o backend com MCP habilitado, limitado a loopback
docker compose up -d db
IWRITE_MCP_ENABLED=true IWRITE_DEVELOPMENT_CURRENT_USER_ENABLED=true SERVER_ADDRESS=127.0.0.1 ./mvnw spring-boot:run

# 2. Rode o Inspector e conecte por SSE
npx @modelcontextprotocol/inspector
# Transport: SSE — URL: http://localhost:8085/sse
```

`SERVER_ADDRESS=127.0.0.1` é obrigatório com o provedor de desenvolvimento: sem ele o guard de loopback impede o startup (ver "Transporte e exposição").

No Inspector: **Tools → List Tools** deve mostrar as três tools; **Resources → Resource Templates** deve mostrar `iwrite://books/{bookId}/outline`. Exemplos seguros: chamar `listar_livros_acessiveis` (sem argumentos) e ler o resource com o id de um livro seu.

## Limitações e escopo

- Sem operações de escrita/destrutivas (nenhuma tool altera ou apaga manuscritos).
- Sem autenticação por cliente MCP: a identidade é a do processo (provedor de desenvolvimento fixo), e por isso o servidor só sobe limitado a loopback — ver "Transporte e exposição" para o contrato exato e o que o `McpLoopbackGuard` recusa. Autenticar cada cliente MCP individualmente é trabalho futuro; as tools em si não mudam quando isso existir, mas o guard atual precisa ser revisto junto.
- Catálogo mínimo proposital: 3 tools + 1 resource (expansão é P2 na issue). `exportar_livro` foi removida: gerava o arquivo inteiro apenas para devolver metadados e uma URL — exportação continua disponível pela API REST.

## Testes multi-tenant (issue #130)

`McpToolsTenantIsolationIntegrationTest` cobre, contra o schema real: proprietário, colaborador autorizado, usuário do mesmo tenant sem acesso, tenant distinto, recurso inexistente e acesso revogado — para tools e resource — provando que cross-tenant e inexistente têm mensagens idênticas, que IDs do cliente não mudam o tenant atual e que auditoria/erros não carregam conteúdo. `McpServerDiscoveryIntegrationTest` sobe o servidor HTTP real na configuração local suportada (identidade fixa + loopback) e usa um cliente MCP SSE para provar descoberta, execução autorizada, leitura do resource e o erro sanitizado de análise indisponível. `McpLoopbackGuardTest` prova que toda configuração fora da suportada recusa o startup — identidade autenticada em vez de desenvolvimento, identidade fixa sem loopback, endereço remoto — e que a configuração suportada (identidade fixa + loopback) sobe normalmente; `SecurityConfigMcpIntegrationTest` prova que `/sse` e `/mcp/message` só ficam sem exigência de sessão/CSRF quando o MCP está habilitado, nunca por padrão, e que os demais endpoints continuam autenticados nessa mesma configuração. `McpSceneAnalysisLimiterTest` cobre concorrência, janela e liberação do bloqueio do `analisar_cena`.

## Evidências que dependem de ação humana (checklist)

- [ ] Website ID oficial do Umami informado e configurado no ambiente (nunca versionado).
- [ ] Acesso ao painel Umami confirmado.
- [ ] Page views visíveis no painel após navegação real.
- [ ] Três eventos (`book_created`, `scene_saved`, `book_exported`) visíveis no painel após ações reais.
- [ ] Screenshot do cliente MCP (Inspector) conectado.
- [ ] Screenshot da listagem de tools e do resource template no Inspector.
- [ ] Validação pós-deploy (page views + eventos + MCP) registrada com data e ambiente.
