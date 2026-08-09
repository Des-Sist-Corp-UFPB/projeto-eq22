# Evidências de validação humana — 08/08/2026

Este documento registra a validação humana realizada em 08/08/2026 para os dois itens acadêmicos que dependiam de infraestrutura externa ou interação manual: **Umami** e **MCP Inspector**.

O objetivo é separar claramente o que foi observado de fato do que ainda depende do deploy remoto. Nenhuma credencial, senha, token ou Website ID real é versionado aqui.

## Ambiente usado

- Aplicação IWrite local: frontend em `http://localhost:3000`, backend em `http://localhost:8085` e PostgreSQL local.
- Umami institucional: `https://umami.dsc.rodrigor.com`, conta da equipe EQ22 e website institucional previamente cadastrado.
- MCP Inspector: v2.1.0, conectado por SSE em `http://localhost:8085/sse`.
- MCP executado apenas em loopback com identidade fixa de desenvolvimento, conforme o contrato documentado em [`mcp-server.md`](mcp-server.md).

## Umami — validação confirmada

### Transporte e ingestão

O frontend foi iniciado com o tracker institucional habilitado e com o Website ID oficial fornecido pelo portal da disciplina apenas via variável de ambiente.

No navegador, a aba Network mostrou requisições `send` iniciadas pelo `script.js` do Umami com resposta HTTP `200`. Em seguida, o painel institucional confirmou a ingestão dos dados.

### Page views observadas

O painel do site da EQ22 exibiu page views para:

- `/`
- `/login`
- `/dashboard`
- `/library`
- `/books/{id}`

A presença de `/books/{id}` em vez do UUID real do livro confirma visualmente o funcionamento da sanitização implementada em `web/src/lib/analytics/analytics.ts`.

Na sessão observada, o painel mostrou 1 visitante, 2 visitas e 9 views, além de identificar o navegador Opera e o país Brasil.

### Eventos de produto observados

A aba **Events** do painel institucional registrou 9 eventos e 3 tipos únicos:

| Evento | Contagem observada |
|---|---:|
| `scene_saved` | 5 |
| `book_exported` | 3 |
| `book_created` | 1 |

Esses eventos foram produzidos por ações reais na aplicação, não por chamadas manuais ao endpoint de coleta.

### Privacidade validada visualmente

A validação confirmou que a navegação de livro chegou ao painel como `/books/{id}`. Não foi observado UUID bruto de livro na lista de páginas. A implementação mantém allowlist de eventos/propriedades e não envia conteúdo de manuscrito, títulos, emails, nomes, prompts, respostas de IA, tokens ou stack traces.

### O que ainda falta no Umami

A sessão acima usou a aplicação em `localhost`. Portanto, ainda falta repetir a mesma validação depois que as variáveis `NEXT_PUBLIC_UMAMI_*` forem configuradas no **build/deploy remoto** de `eq22.dsc.rodrigor.com`.

## MCP Inspector — validação confirmada

### Conexão e descoberta

O backend foi iniciado com MCP habilitado, identidade de desenvolvimento e `SERVER_ADDRESS=127.0.0.1`. O Inspector v2.1.0 conectou com sucesso por SSE.

A aba **Tools** mostrou exatamente as três tools esperadas:

- `listar_livros_acessiveis`
- `obter_outline_livro`
- `analisar_cena`

### Execução real das tools

`listar_livros_acessiveis` retornou livros realmente acessíveis à identidade de desenvolvimento, com `id`, `title`, `status` e `accessLevel`.

`obter_outline_livro` foi executada com um livro real e retornou a estrutura aninhada do manuscrito — livro, parte, capítulo e cenas — com metadados e contagens de palavras, sem expor o conteúdo integral das cenas.

### Resource template

A aba **Resources** mostrou o template:

```text
iwrite://books/{bookId}/outline
```

A leitura do resource com um livro real retornou `application/json` e o outline autorizado. Isso confirmou descoberta do template e leitura efetiva via protocolo MCP, não apenas chamada REST equivalente.

### Caminho de erro sanitizado da IA

`analisar_cena` foi invocada com a IA desabilitada (`SPRING_AI_MODEL_CHAT=none`) e retornou o erro estruturado previsto:

```json
{"error":{"category":"unavailable","message":"A operação está indisponível no momento. Tente novamente mais tarde."}}
```

O cliente não recebeu stack trace, nome de classe Java, configuração interna ou conteúdo sensível.

Uma tentativa posterior de habilitar um provider externo foi interrompida porque as APIs comerciais avaliadas exigiam faturamento separado. Isso **não invalida o MCP**: descoberta, execução autorizada de tools, resource e tratamento de indisponibilidade foram todos demonstrados. Um happy path de IA pode ser reproduzido futuramente com provider real ou stub compatível, mas não é necessário para provar o servidor MCP.

## Checklist consolidado

- [x] Acesso ao painel Umami institucional.
- [x] Website ID oficial configurado em ambiente local sem ser versionado.
- [x] Tracker institucional carregado e coleta HTTP `200` observada.
- [x] Page views reais visíveis no painel.
- [x] Sanitização `/books/{id}` confirmada visualmente.
- [x] `book_created`, `scene_saved` e `book_exported` visíveis no painel.
- [x] MCP Inspector conectado por SSE.
- [x] Três tools MCP descobertas.
- [x] `listar_livros_acessiveis` executada com dados reais.
- [x] `obter_outline_livro` executada com dados reais.
- [x] Resource template descoberto e lido com sucesso.
- [x] Erro `unavailable` de `analisar_cena` demonstrado de forma sanitizada.
- [ ] Repetir page views e eventos Umami no deploy remoto `eq22.dsc.rodrigor.com`.

## Relação com a documentação principal

- Umami: [`analytics-umami.md`](analytics-umami.md)
- MCP: [`mcp-server.md`](mcp-server.md)
- Autenticação/multi-tenancy: [`authentication-multitenancy.md`](authentication-multitenancy.md)
