# Requisito 01 — Autenticação e multi-tenancy

## 1. Objetivo do requisito

O IWrite não trata multi-tenancy como um filtro decorativo no frontend. O requisito é garantir que a identidade do usuário e o tenant efetivo sejam resolvidos no servidor, que recursos sejam sempre consultados dentro desse escopo e que um cliente malicioso não consiga trocar de tenant enviando IDs próprios no corpo, query string ou headers.

O resultado esperado é uma propriedade de segurança: **o navegador não escolhe quem o servidor acredita que o usuário é nem em qual tenant ele está operando**.

## 2. Estado

**✅ Implementado, integrado ao fluxo real de sessão e coberto por testes de integração.**

Documentação técnica principal: [`../../authentication-multitenancy.md`](../../authentication-multitenancy.md).

Demonstração específica: [`../../demonstracao-multi-tenant.md`](../../demonstracao-multi-tenant.md).

## 3. Arquitetura adotada

```text
Navegador
  |
  | cookie JSESSIONID HttpOnly + XSRF-TOKEN para mutações
  v
Spring Security
  |
  v
SecurityContext / IWriteUserDetails
  |
  v
AuthenticatedCurrentUserProvider
  |
  | relê tenant_memberships no banco
  v
CurrentUserProvider
  |
  v
Services / repositories escopados por tenant
  |
  v
PostgreSQL
```

A decisão central é que `tenantId`, `userId` e `role` enviados pelo cliente nunca são fonte de autoridade. O principal autenticado fornece a identidade, e a membership persistida é relida para resolver o tenant efetivo.

## 4. Fluxo de autenticação

A aplicação usa sessão de servidor. O backend autentica credenciais e persiste o contexto de segurança na `HttpSession`; o navegador recebe `JSESSIONID` como cookie `HttpOnly`, inacessível ao JavaScript.

O frontend não persiste identidade em `localStorage` ou `sessionStorage`. O estado de sessão é obtido por `/api/auth/me` e compartilhado por TanStack Query.

Mutações usam proteção CSRF de duplo envio: o cliente busca o token CSRF e envia `X-XSRF-TOKEN` junto do cookie correspondente.

## 5. Resolução do tenant

`AuthenticatedCurrentUserProvider` lê o `Authentication` do `SecurityContextHolder` e usa os identificadores do principal apenas como chave para consultar a membership vigente no banco.

Isso produz duas garantias importantes:

1. uma membership revogada deixa de autorizar imediatamente novas requisições;
2. o cliente não consegue trocar de tenant enviando outro valor em payload, query ou header.

A resolução é server-authoritative.

## 6. Semântica anti-enumeração

Um recurso de outro tenant e um recurso inexistente devem ser indistinguíveis para o cliente.

O IWrite usa a mesma semântica de `404` nos dois casos, em vez de responder `403` para um UUID que existe em outro tenant. Isso impede o atacante de usar o status HTTP como oráculo de existência de recursos alheios.

## 7. Membership e revogação

O login exige exatamente uma membership ativa para o usuário. Zero ou múltiplas memberships falham de maneira genérica, porque o produto ainda não possui seletor explícito de workspace.

Depois do login, a membership continua sendo relida. Se ela for removida:

- `/api/auth/me` responde `401` e invalida a sessão;
- endpoints protegidos recusam o contexto antes de executar consultas de domínio;
- o usuário precisa autenticar novamente.

## 8. Frontend e mesma origem

O Next.js recebe as chamadas `/api/*` e faz rewrite server-side para o backend configurado em `BACKEND_ORIGIN`.

```text
Browser -> /api/... -> Next.js -> Spring Boot
```

Essa arquitetura evita depender de XHR cross-site para cookies `SameSite=Lax` e simplifica o ciclo de sessão/CSRF.

`NEXT_PUBLIC_API_URL` existe apenas como compatibilidade legada; configuração nova deve usar `BACKEND_ORIGIN`.

## 9. Proteção CSRF

O frontend centraliza o envio das requisições. Para métodos mutáveis, obtém o token CSRF quando necessário e envia o header correspondente.

Isso evita que cada tela implemente CSRF por conta própria e reduz a chance de uma nova mutação nascer sem proteção.

## 10. Rate limiting do login

`POST /api/auth/login` possui limitação própria em duas dimensões:

- origem da requisição;
- conta normalizada.

As respostas de excesso são genéricas e não revelam se a conta existe nem qual dimensão atingiu o limite.

A estrutura é limitada em memória para evitar crescimento sem controle.

## 11. Provisionamento e rotação de credenciais

Instalações antigas podem ter usuários sem linha em `user_credentials`. O `CredentialProvisioningRunner` permite provisionar uma credencial para usuário já existente através de variáveis de ambiente.

Propriedades importantes:

- desabilitado por padrão;
- não cria usuário;
- nunca loga senha/hash;
- idempotente;
- pode substituir credencial existente apenas com flag explícita;
- aplica a mesma política de entrada do bcrypt usada pelo login.

## 12. Multi-tenancy nas superfícies novas

O requisito não foi validado apenas nas rotas antigas. As novas superfícies acadêmicas também foram revisadas:

- MCP não aceita `tenantId` como parâmetro e reutiliza os services existentes;
- eventos Umami não enviam tenant/user/resource IDs brutos;
- logs estruturados não exportam tenant/user IDs;
- spans manuais não usam tenant/user IDs como atributos;
- k6 usa dados controlados e não exporta IDs concretos como tags.

## 13. Testes importantes

A documentação técnica registra testes de integração para vetores de spoofing de identidade e tenant. Entre os cenários cobertos:

- `tenantId` malicioso no corpo;
- `tenantId` em query param;
- `tenantId` em header;
- `userId` e `role` fornecidos pelo cliente;
- recurso de outro tenant;
- UUID inexistente;
- membership revogada;
- identidade de desenvolvimento versus identidade autenticada;
- isolamento das tools/resources MCP.

Para auditoria, procurar especialmente:

```text
src/test/java/com/iwrite/auth/
src/test/java/com/iwrite/mcp/McpToolsTenantIsolationIntegrationTest.java
```

## 14. Demonstração humana

O projeto possui um fluxo de demonstração multi-tenant separado do ambiente normal. O objetivo é permitir reproduzir um cenário com identidades/tenants controlados sem transformar credenciais de demo em configuração de produção.

Arquivo principal:

[`../../demonstracao-multi-tenant.md`](../../demonstracao-multi-tenant.md)

## 15. Invariantes que um avaliador pode verificar no código

- `CurrentUserProvider` é a fonte de identidade para o domínio;
- queries críticas usam tenant como parte do escopo;
- o cliente não envia o tenant como autoridade;
- a sessão é server-side;
- cookies de autenticação não ficam disponíveis ao JavaScript;
- recursos cross-tenant não são enumeráveis;
- novas integrações reutilizam autorização existente.

## 16. Por que isso é mais que “colocar login”

A parte relevante do trabalho não é apenas uma tela de login. O requisito afeta:

- modelo de segurança;
- ciclo da sessão;
- CSRF;
- origem das chamadas frontend/backend;
- revogação;
- semântica de erro;
- queries de domínio;
- compatibilidade com MCP;
- testes de carga autenticados;
- CI/E2E com credenciais efêmeras.

## 17. Limitações conhecidas

O modelo atual exige exatamente uma membership ativa por usuário porque ainda não existe seletor explícito de workspace. Isso é deliberado: escolher silenciosamente um tenant seria menos seguro do que recusar a sessão.

O MCP ainda não possui autenticação por cliente; por isso, quando habilitado, usa somente identidade fixa de desenvolvimento e loopback. Esse limite está documentado no requisito MCP.

## 18. Como reproduzir

Para o produto normal, suba o banco/backend/frontend e use o fluxo de login/cadastro documentado no README principal.

Para demonstração controlada multi-tenant, siga:

[`../../demonstracao-multi-tenant.md`](../../demonstracao-multi-tenant.md)

Para validar a suíte automatizada:

```bash
./mvnw -s .mvn/local-settings.xml test
```

## 19. Arquivos para auditoria

```text
docs/authentication-multitenancy.md
docs/demonstracao-multi-tenant.md
src/main/java/com/iwrite/auth/
src/main/java/com/iwrite/user/context/
src/main/java/com/iwrite/book/
src/test/java/com/iwrite/auth/
web/src/features/auth/
web/next.config.ts
docker-compose.demo.yml
```

## 20. Conclusão

O IWrite implementa multi-tenancy como uma propriedade do backend, não como convenção de UI. A identidade vem da sessão, o tenant é resolvido pela membership persistida, os services consultam dentro do escopo correto e as integrações novas foram desenhadas para não criar atalhos em volta dessa regra.