# Autenticação e resolução de tenant (issues #135 e #136)

Notas técnicas de `com.iwrite.auth` e `com.iwrite.user.context`.

## Fluxo

```
cookie de sessão (HttpOnly, SameSite=Lax)
        │
        ▼
SecurityContext restaurado por HttpSessionSecurityContextRepository
        │
        ▼
IWriteUserDetails  (userId, tenantId, role — capturados no login)
        │
        ▼
AuthenticatedCurrentUserProvider  ──►  TenantMembership relida no banco a cada requisição
        │
        ▼
CurrentUserProvider.userId() / tenantId() / effectiveZoneId()
        │
        ▼
services e repositories já existentes, com o filtro por tenant que sempre tiveram
        │
        ▼
recurso de outro tenant → 404
```

Nenhuma etapa consulta o cliente. O usuário vem do `Authentication` guardado na sessão e o tenant
vem da linha de `tenant_memberships` que o sustenta.

## Invariantes

### O `tenantId` do navegador nunca é autoritativo

`AuthenticatedCurrentUserProvider` lê o principal do `SecurityContextHolder` e resolve o tenant pela
membership persistida. Corpo, query string e cabeçalhos não participam da decisão: uma requisição
que envie o `tenantId` de outro workspace continua resolvendo o tenant do próprio autor. Aceitar o
valor do cliente transformaria o isolamento multi-tenant em uma convenção do frontend — quem
controla o navegador controlaria o tenant.

Coberto por `AuthenticatedTenantResolutionIntegrationTest`, nos três vetores (corpo, query param e
cabeçalho), mais o caso em que `userId` e `role` são enviados na requisição.

### Recurso de outro tenant responde 404

A autorização centralizada (`BookAccessService`, `CurrentUserMembershipService`) já filtrava por
tenant; esta fatia apenas passou a alimentá-la com a identidade autenticada. Um livro de outro
tenant e um UUID que nunca existiu produzem a mesma resposta — mesmo status e mesmo corpo, exceto
pelo id que o próprio cliente enviou de volta. Um `403` distinguiria "existe, mas não é seu" de
"não existe" e permitiria enumerar recursos alheios.

### Exatamente uma membership por usuário

`AuthSessionService.loadUserByUsername` recusa o login quando o usuário não tem exatamente uma
membership ativa. Zero e várias falham, ambas com a mesma mensagem genérica de credencial inválida.

Escolher silenciosamente um workspace entre vários tornaria a seleção de tenant implícita e
dependente de ordenação de linha. Enquanto não existe seletor de workspace, recusar é a única opção
que não inventa um contexto pelo usuário.

### Revogação de membership

O principal carrega `userId` e `tenantId`, mas eles são chave de busca, não autorização permanente.
Toda requisição relê a membership:

- `GET /api/auth/me` responde `401` e **invalida a sessão**;
- requisições protegidas respondem `401` antes de qualquer consulta com escopo de tenant;
- o usuário precisa autenticar novamente.

Excluir o usuário ou o tenant tem o mesmo efeito: ambos cascateiam para `tenant_memberships`, então
uma única leitura cobre os três casos.

Fora de `/api/auth/me` a sessão é apenas recusada, não destruída — ela passa a responder `401` em
todas as chamadas seguintes e expira sozinha. Destruir a sessão dentro de um caminho de leitura de
domínio seria um efeito colateral em lugar impróprio.

Observação de esquema: `books.owner` é chave estrangeira para `tenant_memberships`
(`fk_books_owner_tenant_membership`). A membership de quem possui manuscritos não pode ser
removida sem antes tratar os livros — o banco impede.

### Identidade de desenvolvimento e identidade autenticada

| | `DevelopmentCurrentUserProvider` | `AuthenticatedCurrentUserProvider` |
|---|---|---|
| Origem da identidade | ids fixos de configuração | sessão autenticada |
| Registrado quando | `iwrite.current-user.development.enabled=true` | a mesma propriedade é `false` ou ausente |
| Escopo do bean | singleton | por requisição |
| Valida membership | não | sim, a cada requisição |

As duas condições são complementares, então existe **exatamente um** `CurrentUserProvider` em
qualquer perfil. A escolha não depende de `@Primary` nem de ordem de registro de beans — depende de
uma única chave de configuração, e o padrão (propriedade ausente) é o provedor autenticado.

O perfil de demonstração mantém `IWRITE_DEVELOPMENT_CURRENT_USER_ENABLED=false`, portanto o provedor
de desenvolvimento não é sequer instanciado.

## Limitações desta fatia acadêmica

- **Seleção de workspace adiada.** Usuários com mais de uma membership não conseguem entrar. O
  seletor de tenant está fora do escopo de #136 e permanece na visão completa da #63.
- **Sessões em memória.** A sessão vive no servlet container; reiniciar o backend derruba todas as
  sessões e exige novo login. Não há armazenamento externo de sessão.
- **Sem cadastro público**, recuperação de senha, verificação de email ou SSO — ver #63.
- **Sem seed de demonstração** nesta fatia; é a #137.

## Isolamento do banco de testes entre worktrees

`TestDatabaseInitializer` executa `DROP DATABASE ... WITH (FORCE)` no início da suíte. Worktrees
paralelos que apontem para o mesmo Postgres destroem a base de teste ativa uns dos outros no meio da
execução, produzindo falhas que parecem aleatórias.

Use uma instância dedicada por worktree e passe `TEST_DB_URL` explicitamente:

```bash
docker run -d --name iwrite-auth-testdb -p 5439:5432 \
  -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres postgres:16

TEST_DB_URL=jdbc:postgresql://localhost:5439/iwrite_auth_test ./mvnw test
```

Sem `TEST_DB_URL` a suíte cai silenciosamente no padrão `localhost:5435/iwrite_test`, que é
compartilhado. O container temporário não está no `docker-compose.yml`: é ferramenta de
desenvolvimento local, não configuração do projeto.
