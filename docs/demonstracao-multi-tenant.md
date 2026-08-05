# Demonstração de autenticação e isolamento multi-tenant (issue #137)

Roteiro reproduzível para provar, pela interface e pela API, que duas identidades autenticadas
recebem contextos e dados isolados. Detalhes de implementação estão em
[`authentication-multitenancy.md`](authentication-multitenancy.md).

## 1. Variáveis necessárias

Copie `.env.example` para `.env` (o `.env` é ignorado pelo Git) e preencha:

| Variável | Obrigatória | Para quê |
|---|---|---|
| `IWRITE_DEMO_SEED_ENABLED` | sim, `true` | liga o seed. Sem ela o backend sobe normalmente e não cria nada. |
| `IWRITE_DEMO_AUTOR_A_PASSWORD` | sim | senha do Autor A. **Sem valor padrão.** |
| `IWRITE_DEMO_AUTOR_B_PASSWORD` | sim | senha do Autor B. **Sem valor padrão.** |

Gere as senhas localmente, por exemplo:

```bash
node -e "console.log(require('crypto').randomBytes(18).toString('base64url'))"
```

Não versione os valores. Se o seed for ligado sem elas, o compose recusa subir nomeando a variável
que falta, e o backend recusaria iniciar pelo mesmo motivo — nenhuma das duas mensagens contém
senha.

## 2. Subir o ambiente

```bash
docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d --build
```

Sobem banco, backend e frontend. O backend roda com `IWRITE_DEVELOPMENT_CURRENT_USER_ENABLED=false`,
ou seja, a identidade vem da sessão — sem isso todo usuário autenticado cairia no mesmo workspace.

Confira que o seed rodou (as linhas não contêm credenciais):

```bash
docker compose -f docker-compose.yml -f docker-compose.demo.yml logs backend | grep "Demo seed"
```

O seed é idempotente: reiniciar o backend registra `already exists, leaving it untouched` e não
duplica nada.

### O que é criado

| Usuário | Workspace | Memberships | Livro |
|---|---|---|---|
| `autor-a@iwrite.local` | Espaço do Autor A | 1 (OWNER) | *A Cidade de Vidro* |
| `autor-b@iwrite.local` | Espaço do Autor B | 1 (OWNER) | *O Jardim Submerso* |

As senhas ficam apenas como hash bcrypt em `user_credentials`; texto claro não é persistido em
lugar nenhum.

## 3. Roteiro de cinco minutos

Abra `http://localhost:3000`.

| # | Passo | Resultado esperado |
|---|---|---|
| 1 | Acessar `/library` sem sessão | redireciona para `/login` |
| 2 | Entrar como `autor-a@iwrite.local` | vai para `/library` |
| 3 | Olhar a barra superior | **Autor A / Espaço do Autor A** |
| 4 | Ver a biblioteca | somente *A Cidade de Vidro* |
| 5 | Abrir uma janela anônima e entrar como `autor-b@iwrite.local` | **Autor B / Espaço do Autor B** |
| 6 | Ver a biblioteca do B | somente *O Jardim Submerso* |
| 7 | Copiar o ID do livro do B e abrir na sessão do A | `404 Not Found` |
| 8 | Comparar com um UUID inexistente | resposta equivalente — não dá para descobrir se o recurso existe |
| 9 | Criar um livro na sessão do A enviando o `tenantId` do B | criado no **Tenant A**; não aparece na biblioteca do B |
| 10 | Recarregar a página | continua autenticado (sessão restaurada por `/api/auth/me`) |
| 11 | Sair | volta para `/login` |
| 12 | Repetir uma chamada protegida com a sessão antiga | `401` |

Passo 9, para mostrar que nada vindo do cliente é aceito como identidade — corpo, query e cabeçalho
ao mesmo tempo:

```bash
# depois de entrar como Autor A no navegador, com os cookies daquela sessão
curl -b cookies.txt -H "X-XSRF-TOKEN: <token do cookie XSRF-TOKEN>" \
     -H "X-Tenant-Id: <tenant do B>" -H "Content-Type: application/json" \
     -d '{"title":"Teste","tenantId":"<tenant do B>"}' \
     "http://localhost:3000/api/books?tenantId=<tenant do B>"
```

O livro nasce no Tenant A.

## 4. Limpeza

```bash
# derruba os containers e apaga o banco da demonstração
docker compose -f docker-compose.yml -f docker-compose.demo.yml down -v

# apenas os livros criados durante a apresentação, mantendo os dois autores
docker exec iwrite-db psql -U postgres -d iwrite -c "delete from books where title = 'Livro da demonstração';"
```

O `down -v` remove o volume, então a próxima subida recria tudo do zero — inclusive o seed.

## 5. Avisos

**Sessões em memória.** Ficam no servlet container. Reiniciar o backend derruba todas as sessões e
exige novo login. Não faça isso no meio da apresentação.

**Uma membership por usuário.** A fatia acadêmica só resolve o tenant quando não há ambiguidade.
Usuário com zero ou com mais de uma membership não consegue entrar, e a mensagem é a mesma de
credencial inválida — por isso os usuários de demonstração têm exatamente uma cada. O seletor de
workspace ficou fora do escopo (#63).

**`Origin` e `APP_CORS_ALLOWED_ORIGINS`.** O rewrite do Next é server-side, mas ele repassa o header
`Origin` do navegador. O backend continua aplicando CORS e responde `403 Invalid CORS request` se a
origem real do frontend não estiver em `APP_CORS_ALLOWED_ORIGINS`. Servir o frontend em outra porta
exige ajustar a variável. Um teste com `curl` não percebe: `curl` não envia `Origin`.

**Bancos de teste destrutivos.** `TestDatabaseInitializer` executa `DROP DATABASE ... WITH (FORCE)`.
Worktrees paralelos apontando para o mesmo Postgres destroem a base de teste um do outro no meio da
execução. Use uma instância por worktree e passe `TEST_DB_URL` explicitamente:

```bash
TEST_DB_URL=jdbc:postgresql://localhost:5439/iwrite_auth_test ./mvnw test
```

**Nomes de container fixos.** `docker-compose.yml` usa `container_name` fixo (`iwrite-db`,
`iwrite-backend`, `iwrite-frontend`), então apenas um worktree pode ter a stack no ar por vez.
Suba a demonstração com os outros derrubados.

## 6. Suíte e2e

`docker-compose.e2e.yml` sobe uma stack isolada (banco 5436, backend 8086, frontend 3001) e também
semeia os autores, porque a suíte autentica antes de qualquer coisa:

```bash
docker compose -f docker-compose.e2e.yml -p iwrite-e2e up -d --build
cd web && npm run e2e
```

## 7. Evidências

Capturas em `docs/evidencias/` — apenas telas de produto, sem cookies, tokens, hashes ou senhas
(o campo de senha aparece mascarado).

| Arquivo | Mostra |
|---|---|
| `demo-01-login.png` | tela de login |
| `demo-02-biblioteca-a.png` | biblioteca do Autor A |
| `demo-03-biblioteca-b.png` | biblioteca do Autor B |
| `demo-04-mobile.png` | login em 390px |
| `demo-05-zoom200.png` | login com zoom equivalente a 200% |
