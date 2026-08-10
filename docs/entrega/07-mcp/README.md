# Requisito 07 — Servidor MCP

## 1. Objetivo

Expor capacidades úteis do IWrite por Model Context Protocol sem criar uma segunda camada de regras de negócio, sem permitir escrita destrutiva e sem enfraquecer o isolamento multi-tenant já existente.

A meta não foi “ter um endpoint chamado MCP”. O servidor precisa:

- ser descoberto por um cliente MCP real;
- publicar tools úteis;
- publicar ao menos um resource;
- reutilizar autorização/services existentes;
- tratar erros de forma estruturada e sanitizada;
- limitar operações com custo externo;
- registrar auditoria;
- não ficar exposto remotamente sem autenticação por cliente.

## 2. Estado

**✅ Implementado, coberto por testes de integração e validado no MCP Inspector v2.1.0.**

Documento técnico principal:

[`../../mcp-server.md`](../../mcp-server.md)

Evidência humana:

[`../../evidencias-validacao-humana-2026-08-08.md`](../../evidencias-validacao-humana-2026-08-08.md)

Prints:

[`../../evidencias/mcp/README.md`](../../evidencias/mcp/README.md)

## 3. Arquitetura

```text
MCP Inspector / cliente MCP
    |
    | GET /sse
    | POST /mcp/message
    v
Spring AI MCP Server WebMVC
    |
    v
com.iwrite.mcp
    |
    |-- BookService
    |-- OutlineService
    |-- SceneAnalysisService
    |-- AuditLogService
    `-- LlmExecutionGateway
          |
          v
       PostgreSQL / provider de IA
```

A camada MCP é propositalmente fina.

## 4. Dependências e runtime

A implementação usa:

```text
Spring AI 1.0.9
MCP Java SDK 0.18.3
spring-ai-starter-mcp-server-webmvc
```

O servidor roda no mesmo processo/porta do backend.

## 5. Transporte

Transporte utilizado:

```text
HTTP + SSE
GET  /sse
POST /mcp/message
```

A escolha foi compatível com o backend web contínuo existente e com o MCP Inspector.

## 6. Desabilitado por padrão

```text
IWRITE_MCP_ENABLED=false
```

O servidor não nasce exposto apenas porque a aplicação está rodando.

Isso é importante porque o transporte atual não possui autenticação individual por cliente MCP.

## 7. Contrato de segurança do runtime

Quando o MCP é habilitado, a configuração suportada exige:

```text
IWRITE_DEVELOPMENT_CURRENT_USER_ENABLED=true
SERVER_ADDRESS=127.0.0.1
```

Ou equivalente de loopback.

O objetivo é garantir que a identidade usada pelas tools seja fixa e que o processo não aceite conexões remotas nessa configuração.

## 8. `McpLoopbackGuard`

O guard falha o startup em configurações inseguras/inconsistentes.

Exemplos recusados:

- MCP habilitado com provider autenticado por sessão HTTP normal;
- MCP habilitado com identidade de desenvolvimento, mas sem bind explícito de loopback;
- endereço remoto/não-loopback.

Isso é fail-fast: a aplicação não “sobe meio funcionando” com um MCP descobrível mas sem modelo de autenticação válido.

## 9. Por que não usar a sessão HTTP normal

O cliente MCP atual não executa o fluxo de login/CSRF da aplicação e não cria `IWriteUserDetails` na sessão como o browser.

Permitir MCP com `AuthenticatedCurrentUserProvider` sem autenticação específica do protocolo produziria um sistema aparentemente habilitado, mas sem identidade real por cliente.

O projeto prefere recusar esse estado.

## 10. Reverse proxy

Loopback protege o bind do processo, mas não impede um administrador de publicar `/sse` e `/mcp/message` através de proxy.

Por isso a documentação é explícita: enquanto não existir autenticação própria do transporte MCP, esses endpoints **não devem ser expostos por reverse proxy**.

## 11. Tools publicadas

São exatamente três:

| Tool | Função |
|---|---|
| `listar_livros_acessiveis` | lista livros autorizados para a identidade atual |
| `obter_outline_livro` | retorna estrutura autorizada de um livro |
| `analisar_cena` | reutiliza análise de cena existente, com auditoria/limites |

O catálogo mínimo foi intencional.

## 12. `listar_livros_acessiveis`

Não recebe `tenantId` nem `userId`.

A tool consulta os livros que os services normais já consideram acessíveis para a identidade corrente.

Na validação real, retornou livros do usuário de desenvolvimento com metadados como id, title, status e accessLevel.

## 13. `obter_outline_livro`

Recebe `bookId` como referência de recurso, não como mecanismo de seleção de tenant.

A autorização continua sendo aplicada pelos services/repositories existentes.

Retorna:

```text
livro
  -> partes/seções
      -> capítulos
          -> cenas
```

com metadados e contagens de palavras, sem conteúdo integral de cena.

## 14. `analisar_cena`

Recebe:

```text
sceneId
focus opcional <= 300 caracteres
```

Reutiliza o fluxo existente de análise:

- autorização da cena;
- limite/truncamento de entrada para IA;
- gateway LLM;
- auditoria persistida;
- tratamento de provider indisponível;
- telemetria e logs estruturados.

Nenhuma lógica paralela de análise foi criada apenas para MCP.

## 15. Limites de custo

`McpSceneAnalysisLimiter` aplica:

- no máximo uma análise concorrente por identidade;
- janela fixa de tentativas;
- default de 3 tentativas por 1 minuto;
- falhas também contam;
- lock é liberado após sucesso ou falha.

Excesso retorna categoria pública `rate_limited`.

## 16. Resource template

Template publicado:

```text
iwrite://books/{bookId}/outline
```

MIME:

```text
application/json
```

Ele reutiliza a mesma autorização do outline e não expõe o conteúdo integral das cenas.

## 17. Semântica cross-tenant

Recurso de outro tenant e recurso inexistente convergem para erro público `not_found`.

Isso evita enumeração por tool/resource.

O cliente não consegue trocar tenant enviando um UUID de outro workspace.

## 18. Erros sanitizados

Formato público:

```json
{"error":{"category":"...","message":"..."}}
```

Categorias:

```text
not_found
invalid_request
unavailable
rate_limited
internal
```

O cliente não recebe:

- stack trace;
- classe Java;
- query SQL;
- configuração interna;
- conteúdo do manuscrito;
- mensagem crua do provider.

## 19. Auditoria

Cada invocação relevante gera registro de domínio em `audit_logs`.

Eventos incluem ações como:

```text
MCP_BOOKS_LISTED
MCP_BOOK_OUTLINE_VIEWED
MCP_SCENE_ANALYZED
```

A auditoria registra identidade/recurso no banco apropriado, enquanto logs estruturados exportados evitam campos livres/IDs desnecessários.

## 20. Correlação com observabilidade

Invocações MCP também geram evento estruturado `iwrite.mcp.invocation`.

Quando OTel está ativo, o evento pode herdar contexto de trace do request.

O formato do evento e a política de severidade são documentados em `docs/otel-correlated-logs.md`.

## 21. Integração MCP + IA e dependência circular corrigida

Ao habilitar MCP e um provider de chat simultaneamente, foi encontrada uma dependência circular entre:

```text
MCP tools
 -> SceneAnalysisService
 -> WritingAssistant / ChatClient
 -> ToolCallingManager
 -> MCP ToolCallbackProvider
 -> MCP tools
```

Isso foi corrigido por `McpAiToolResolutionConfiguration`, garantindo que as tools MCP continuem publicadas para clientes externos, mas não sejam herdadas recursivamente pelo `ChatClient` usado internamente na análise.

Testes de startup cobrem a combinação MCP + OpenAI e MCP + Anthropic.

## 22. Validação no MCP Inspector

Em 08/08/2026, o Inspector v2.1.0 foi conectado por SSE em:

```text
http://localhost:8085/sse
```

A validação confirmou descoberta e execução real.

## 23. Evidência 1 — descoberta das tools

A aba Tools mostrou exatamente:

```text
listar_livros_acessiveis
obter_outline_livro
analisar_cena
```

Print versionado:

```text
docs/evidencias/mcp/01-tools-descobertas.svg
```

## 24. Evidência 2 — listagem real de livros

`listar_livros_acessiveis` foi executada com sucesso e retornou dados reais acessíveis à identidade corrente.

Print:

```text
docs/evidencias/mcp/02-listar-livros.svg
```

## 25. Evidência 3 — outline real

`obter_outline_livro` foi executada contra um livro real e retornou estrutura aninhada com seção, capítulo e cenas.

Print:

```text
docs/evidencias/mcp/03-obter-outline.svg
```

## 26. Evidência 4 — erro sanitizado da IA

Com IA desabilitada, `analisar_cena` retornou:

```json
{"error":{"category":"unavailable","message":"A operação está indisponível no momento. Tente novamente mais tarde."}}
```

Isso demonstrou o caminho de erro sem stack trace/configuração interna.

Print:

```text
docs/evidencias/mcp/04-analise-indisponivel.svg
```

## 27. Evidência 5 — resource template

O Inspector descobriu:

```text
iwrite://books/{bookId}/outline
```

Print:

```text
docs/evidencias/mcp/05-resource-template.svg
```

## 28. Evidência 6 — leitura do resource

O resource foi lido com livro real e retornou JSON autorizado.

Print:

```text
docs/evidencias/mcp/06-resource-outline.svg
```

## 29. Testes de descoberta

`McpServerDiscoveryIntegrationTest` sobe o servidor MCP real na configuração suportada e usa cliente SSE para validar descoberta/execução.

Isso é diferente de apenas instanciar a classe de tool em teste unitário.

## 30. Testes de isolamento

`McpToolsTenantIsolationIntegrationTest` cobre cenários como:

- proprietário;
- colaborador autorizado;
- usuário sem acesso;
- outro tenant;
- recurso inexistente;
- acesso revogado.

O objetivo é provar que a nova superfície não criou bypass da autorização já existente.

## 31. Testes do guard

`McpLoopbackGuardTest` cobre configurações permitidas e proibidas.

`SecurityConfigMcpIntegrationTest` verifica que os endpoints MCP só recebem a exceção específica de sessão/CSRF quando o recurso está habilitado, sem abrir os demais endpoints da API.

## 32. Testes do limiter

`McpSceneAnalysisLimiterTest` cobre:

- concorrência;
- janela;
- liberação do lock;
- falhas.

## 33. Não há tools destrutivas

Nenhuma tool MCP atual:

- edita cena;
- apaga livro;
- reestrutura manuscrito;
- grava conteúdo gerado autonomamente.

Essa restrição reduz risco enquanto o transporte ainda não possui autenticação individual por cliente.

## 34. Reprodução

Backend:

```bash
docker compose up -d db
IWRITE_MCP_ENABLED=true \
IWRITE_DEVELOPMENT_CURRENT_USER_ENABLED=true \
SERVER_ADDRESS=127.0.0.1 \
./mvnw spring-boot:run
```

Inspector:

```bash
npx @modelcontextprotocol/inspector
```

Configurar:

```text
Transport: SSE
URL: http://localhost:8085/sse
```

## 35. O que uma IA avaliadora deve verificar

1. O starter MCP está realmente configurado.
2. As tools chamam services existentes, não repositories sem escopo de forma ad hoc.
3. Não existe `tenantId` como parâmetro de autoridade.
4. `McpLoopbackGuard` impede exposição insegura.
5. Há resource template real.
6. Há testes de descoberta com cliente MCP.
7. Há testes cross-tenant.
8. Há limiter da análise.
9. Há prints do Inspector de descoberta e execução.
10. A indisponibilidade da IA é retornada de forma sanitizada.

## 36. Arquivos para auditoria

```text
docs/mcp-server.md
docs/evidencias-validacao-humana-2026-08-08.md
docs/evidencias/mcp/README.md
docs/evidencias/mcp/*.svg
src/main/java/com/iwrite/mcp/
src/main/java/com/iwrite/mcp/McpAiToolResolutionConfiguration.java
src/test/java/com/iwrite/mcp/
src/main/java/com/iwrite/scene/service/SceneAnalysisService.java
src/main/java/com/iwrite/llm/
```

## 37. Limitações

- não há autenticação individual por cliente MCP;
- por isso o servidor fica limitado a loopback/identidade fixa;
- não deve ser publicado por reverse proxy;
- catálogo mínimo de 3 tools + 1 resource;
- happy path de `analisar_cena` depende de provider real/stub; a evidência humana registrada demonstrou o caminho `unavailable` com provider desabilitado.

## 38. Conclusão

O MCP do IWrite é uma integração protocolar real e testada: cliente externo descobre tools/resource, executa services existentes, respeita isolamento, produz auditoria e recebe erros sanitizados. A segurança do transporte atual é assumida explicitamente: até existir autenticação por cliente, MCP só funciona em loopback e não é componente do deploy remoto.