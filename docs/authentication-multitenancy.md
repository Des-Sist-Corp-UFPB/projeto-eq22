# Autenticação e resolução de tenant (issues #135, #136 e #133)

Notas técnicas de `com.iwrite.auth`, `com.iwrite.user.context` e `web/src/features/auth`.

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

## Frontend (#133)

### Proxy de mesma origem

O navegador só conversa com a origem que serviu a página. O Next reescreve `/api/*` para
`BACKEND_ORIGIN` no servidor (`web/next.config.ts`), então nenhuma URL de backend vai para o bundle.

```
navegador ──/api/...──► Next (3000) ──rewrite──► backend (8085)
```

Isso não é cosmético. Um cookie `SameSite=Lax` **não** é enviado em XHR cross-site, e o
double-submit de CSRF precisa ler um cookie que o navegador trataria como de terceiros. Falar com
outra porta funcionava para leitura anônima e não funciona para sessão.

O backend expõe seu probe público em `/ping`, fora de `/api`, então existe uma regra explícita
`/api/ping → BACKEND_ORIGIN/ping`, antes da regra coringa. Ela dá ao proxy uma verificação que não
depende de sessão.

`NEXT_PUBLIC_API_URL` era a variável documentada e implantada antes de `BACKEND_ORIGIN` existir;
`next.config.ts` ainda a lê, mas só como *fallback* quando `BACKEND_ORIGIN` está ausente, e ela é
**legada/depreciada** — configuração nova deve usar `BACKEND_ORIGIN`. Se qualquer uma das duas
estiver definida com um valor que não seja uma URL absoluta válida, o build falha alto em vez de
cair silenciosamente no padrão local (`http://127.0.0.1:8085`): um erro de digitação em produção não
pode virar todo request reescrevendo silenciosamente para o próprio container do frontend.

**Cuidado com CORS.** O rewrite é server-side, mas o Next repassa o header `Origin` do navegador.
O backend continua aplicando `app.cors.allowed-origins` (padrão `http://localhost:3000`) e responde
`403 Invalid CORS request` quando a origem do frontend não está na lista. Um teste com `curl` não
percebe isso, porque `curl` não envia `Origin`. Ao servir o frontend em outra porta, ajuste
`APP_CORS_ALLOWED_ORIGINS`.

### Ciclo de sessão

- `apiRequest` usa caminhos relativos, envia o cookie e anexa `X-XSRF-TOKEN` em todo método que não
  seja `GET`, buscando `/api/auth/csrf` antes quando ainda não há cookie — é isso que torna possível
  a primeira mutação, o próprio login;
- a sessão é **uma** query TanStack (`["auth","session"]`), então `/api/auth/me` é pedido uma vez
  para toda a aplicação;
- não estar logado resolve para `null`, não para erro: manter isso distinto de uma falha é o que
  permite diferenciar "entre novamente" de "não conseguimos acessar o IWrite agora";
- um `401` em qualquer requisição encerra a sessão em um único handler nas caches de query e
  mutation, e o guard redireciona uma vez — nenhuma tela trata 401 por conta própria;
- o logout limpa a cache inteira. Tudo que estava nela foi buscado como aquele usuário naquele
  tenant.

### Rotas

| Rota | Comportamento |
|---|---|
| `/login` | Pública. Quem já tem sessão é levado para `/library`. |
| `/library` | Biblioteca. Antes era `/`. |
| `/` | Redireciona para `/library`. |
| demais | Protegidas pelo `SessionGuard`. |

`SessionGuard` envolve a aplicação a partir do layout raiz, então uma rota nova nasce protegida em
vez de depender de alguém lembrar. **Ele é conveniência, não autorização** — o backend recusa
requisição sem sessão por conta própria; o guard só evita uma tela cheia de falhas. Ter tido sessão
e perdê-la vira `?reason=expired`; nunca ter tido vira a tela de login normal.

### O que o navegador nunca guarda

Nada de sessão em `localStorage` ou `sessionStorage`. A identidade vive no cookie `JSESSIONID`
(`HttpOnly`, `SameSite=Lax`), inacessível ao JavaScript. O payload de `/api/auth/login` e
`/api/auth/me` não carrega `userId` nem `tenantId`, então o cliente não tem identificador para
devolver como afirmação de identidade — mesmo que a requisição traga um, o servidor resolve tudo
pela membership.

## Provisionamento de credencial (rollout e desenvolvimento)

`V30__create_user_credentials.sql` cria a tabela `user_credentials` vazia: nenhum usuário existente
(incluindo o usuário legado determinístico da V20) ganha uma credencial automaticamente. Sem uma delas,
`/api/auth/login` não tem o que verificar, e como não há cadastro público nem endpoint administrativo,
uma instalação que já existia antes da V30 fica sem nenhuma conta capaz de autenticar.

`CredentialProvisioningRunner` (`com.iwrite.auth`) resolve isso com um mecanismo único, usado tanto no
rollout de uma instalação existente quanto no fluxo local de desenvolvimento:

- desligado por padrão (`iwrite.auth.credential-provisioning.enabled`, `IWRITE_CREDENTIAL_PROVISIONING_ENABLED`);
- exige email e senha do usuário (`IWRITE_CREDENTIAL_PROVISIONING_EMAIL`/`_PASSWORD`), sem padrão — falha
  claramente no boot se o flag estiver ligado e faltar um dos dois;
- só provisiona a senha de um usuário que **já existe**; falha claramente no boot se o email não
  corresponder a nenhum `users.email` — nunca cria um usuário;
- armazena somente o hash (`PasswordEncoder` já configurado, `{bcrypt}`); nunca loga o email, a senha
  ou o hash;
- idempotente: se o usuário já tem credencial, não sobrescreve e não falha;
- não existe endpoint HTTP equivalente — é exclusivamente um `ApplicationRunner` de boot, então só
  quem controla as variáveis de ambiente do processo pode disparar o provisionamento.

Depois de provisionar, remova as três variáveis do ambiente: elas não têm efeito quando a credencial
já existe (o runner é idempotente), mas deixar a senha configurada no processo não tem motivo.

No fluxo local (perfil `development`), o alvo natural é o usuário legado da V20
(`carlos.legacy@iwrite.local`, id `00000000-0000-0000-0000-000000000002`), que também é o id padrão de
`IWRITE_DEVELOPMENT_CURRENT_USER_ID`. Depois de logar com a credencial provisionada, o
`DevelopmentCurrentUserProvider` continua resolvendo o mesmo tenant/usuário fixo para as chamadas de
negócio, independentemente de qual conta autenticou a sessão — só a barreira do Spring Security
precisava de uma sessão real, a identidade de negócio em desenvolvimento já era fixa.

## Limitações desta fatia acadêmica

- **Seleção de workspace adiada.** Usuários com mais de uma membership não conseguem entrar. O
  seletor de tenant está fora do escopo de #136 e permanece na visão completa da #63.
- **Sessões em memória.** A sessão vive no servlet container; reiniciar o backend derruba todas as
  sessões e exige novo login. Não há armazenamento externo de sessão.
- **Sem cadastro público**, recuperação de senha, verificação de email ou SSO — ver #63.
- **Sem seed de demonstração** nesta fatia; é a #137. O `docker-compose.yml` já roda com
  `IWRITE_DEVELOPMENT_CURRENT_USER_ENABLED=false`, porque com a identidade fixa ligada todo usuário
  autenticado cairia no mesmo workspace. A consequência é que, até a #137, subir o compose não dá
  nenhuma conta com a qual entrar — as credenciais de demonstração são criadas lá.
- A tela de login **não** oferece cadastro, recuperação de senha nem login social, porque nada disso
  existe no backend. Botão que não faz nada é pior que ausência.

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
