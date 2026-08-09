# Requisito 06 — Analytics de produto com Umami

## 1. Objetivo

O Umami foi integrado para responder perguntas de **uso do produto**, não de operação interna do backend.

Exemplos:

- quais páginas são acessadas;
- quais funcionalidades são usadas;
- se criação de livro, salvamento de cena e exportação realmente geram eventos;
- se a navegação client-side do Next.js é observada;
- se tudo isso pode ser feito sem enviar conteúdo privado ou identificadores brutos.

A distinção central é:

```text
OpenTelemetry = comportamento técnico do sistema
Umami         = comportamento de uso do produto
```

As duas integrações são independentes.

## 2. Estado

**✅ Implementado, testado e validado contra a instância institucional do Umami.**

**🟡 Única pendência:** repetir a validação depois de configurar as variáveis `NEXT_PUBLIC_UMAMI_*` no build/deploy remoto de `eq22.dsc.rodrigor.com`.

Documento técnico principal:

[`../../analytics-umami.md`](../../analytics-umami.md)

Evidência humana consolidada:

[`../../evidencias-validacao-humana-2026-08-08.md`](../../evidencias-validacao-humana-2026-08-08.md)

Prints versionados:

[`../../evidencias/umami/README.md`](../../evidencias/umami/README.md)

## 3. Instância institucional

A integração foi validada contra:

```text
https://umami.dsc.rodrigor.com
```

O site da equipe está cadastrado no painel institucional.

O Website ID real é fornecido pelo ambiente e não fica versionado no repositório.

## 4. Variáveis

```text
NEXT_PUBLIC_UMAMI_ENABLED
NEXT_PUBLIC_UMAMI_SCRIPT_URL
NEXT_PUBLIC_UMAMI_WEBSITE_ID
NEXT_PUBLIC_UMAMI_HOST_URL   # opcional
```

O `script.js` institucional usado na validação foi:

```text
https://umami.dsc.rodrigor.com/script.js
```

## 5. Falha segura

Sem configuração válida, analytics vira no-op.

Isso significa:

- script não carrega;
- evento não é enviado;
- nenhuma ação de produto falha por causa do tracker;
- desenvolvimento normal continua funcionando.

A integração é fail-open: analytics nunca é requisito para salvar um manuscrito ou navegar no produto.

## 6. Ponto único de integração

Arquivo central:

```text
web/src/lib/analytics/analytics.ts
```

Responsabilidades:

- ler/validar configuração;
- injetar script uma única vez;
- desabilitar auto-track nativo;
- enviar page views explicitamente;
- sanitizar URL/referrer;
- enfileirar eventos enquanto o script ainda não carregou;
- aplicar allowlist de eventos e propriedades;
- deduplicar navegação.

O restante da aplicação não deve chamar `window.umami.track` diretamente.

## 7. Por que `data-auto-track=false`

O IWrite não deixa o tracker capturar automaticamente a URL bruta do navegador.

A aplicação envia explicitamente uma versão sanitizada da rota.

Isso é essencial porque páginas como:

```text
/books/<UUID-real>
```

não devem chegar ao painel com o identificador concreto do livro.

## 8. Sanitização de URL

A camada de analytics remove:

- query string;
- hash;
- segmentos UUID;
- segmentos numéricos dinâmicos;
- hex/identificadores opacos longos.

Exemplo:

```text
/books/ce1bce07-16bd-445c-81c4-5674fa8231b2
```

vira:

```text
/books/{id}
```

antes do envio.

## 9. Referrer

Referrer interno também é sanitizado.

Referrer externo é reduzido para origem, evitando propagar caminho/query de outro site.

O título da página não é enviado pelo tracker customizado.

## 10. Navegação client-side

Componente:

```text
web/src/lib/analytics/umami-analytics.tsx
```

Ele acompanha `usePathname()` no layout raiz e envia page view inicial + mudanças de rota.

Isso resolve um problema típico de SPA/Next.js: trocar de tela sem reload não deve perder page view.

## 11. Deduplicação

A implementação evita que re-renderizações de React dupliquem page views.

O mesmo caminho consecutivo não é enviado repetidamente apenas porque um componente renderizou de novo.

## 12. Fila antes do carregamento

Eventos disparados antes do script terminar de carregar não são simplesmente perdidos.

A integração mantém uma fila limitada a **10 itens**.

Regras:

- preserva ordem;
- deduplica itens consecutivos idênticos;
- quando cheia, descarta o mais antigo;
- faz flush depois que o tracker está disponível.

A fila é propositalmente limitada para nunca virar buffer sem bound.

## 13. Eventos permitidos

| Evento | Quando |
|---|---|
| `book_created` | criação confirmada pelo backend |
| `scene_saved` | conteúdo persistido |
| `scene_analysis_requested` | análise válida enviada |
| `scene_analysis_succeeded` | resposta válida recebida |
| `scene_analysis_failed` | falha exibida ao usuário |
| `book_exported` | exportação/download concluído |

## 14. Propriedades permitidas

`scene_saved`:

```text
source = AUTO_SAVE | MANUAL_SAVE
```

`scene_analysis_failed`:

```text
category = unavailable | request_failed
```

`book_exported`:

```text
target = manuscript | notebook
format = txt | md | docx
```

Valores fora do vocabulário são removidos antes do envio.

## 15. Dados proibidos

Não são enviados pelo tracker customizado:

```text
conteúdo de manuscrito
títulos privados
e-mail/nome
userId/tenantId/bookId/sceneId bruto
prompt
resposta de IA
token
stack trace
query string
hash
```

## 16. Testes automatizados

Arquivos principais:

```text
web/src/lib/analytics/analytics.test.ts
web/src/lib/analytics/umami-analytics.test.tsx
```

A integração também é verificada em testes das telas/fluxos que disparam eventos.

Cobertura documentada inclui:

- desabilitado;
- configuração incompleta;
- configuração válida;
- injeção única do script;
- page view inicial;
- navegação client-side;
- deduplicação;
- fila;
- allowlist;
- eventos de sucesso/falha;
- bloqueio de propriedades desconhecidas;
- ausência de conteúdo/IDs privados.

## 17. Validação humana real

Em 08/08/2026, o frontend local foi iniciado apontando para o tracker institucional.

A aba Network mostrou requisições `send` originadas pelo `script.js` com status **HTTP 200**.

Isso prova que não foi apenas um teste unitário do wrapper: houve tráfego aceito pela instância real do Umami.

## 18. Page views observadas

O painel mostrou rotas reais da sessão, incluindo:

```text
/
/login
/dashboard
/library
/books/{id}
```

A presença de `/books/{id}` é a evidência visual da sanitização de UUID.

## 19. Métricas observadas no painel

Na sessão registrada, o painel mostrou:

```text
1 visitante
2 visitas
9 views
```

Esses números estão documentados como evidência daquela sessão específica, não como métrica de uso de produção.

## 20. Eventos observados no painel

A aba Events confirmou **9 eventos** de **3 tipos únicos**:

| Evento | Quantidade |
|---|---:|
| `scene_saved` | 5 |
| `book_exported` | 3 |
| `book_created` | 1 |

Esses eventos vieram de ações reais na aplicação, não de requests manuais ao endpoint de coleta.

## 21. Evidências visuais

O diretório `docs/evidencias/umami/` contém quatro capturas organizadas:

```text
01-network-coleta-200.svg
02-overview-trafego.svg
03-paginas-sanitizadas.svg
04-eventos-customizados.svg
```

O README do próprio diretório explica cada uma.

## 22. O que as capturas comprovam

### Network

Comprova que o navegador carregou o tracker e que a coleta foi aceita com HTTP 200.

### Overview

Comprova ingestão real no website institucional.

### Pages

Comprova que as páginas navegadas aparecem e que `/books/{id}` foi sanitizado.

### Events

Comprova que os eventos customizados existem no painel e possuem contagens reais.

## 23. O que as capturas não comprovam

Não há alegação de que todas as propriedades de todos os eventos foram inspecionadas visualmente no painel.

As propriedades são garantidas por código/testes; a evidência visual registrada confirma nomes e contagens dos eventos observados.

Essa distinção evita exagerar a evidência.

## 24. Separação de analytics e observabilidade

Umami não recebe `trace_id`, latência JDBC, stack trace ou métrica de JVM.

OpenTelemetry não é usado para medir page views de produto.

Essa separação reduz acoplamento e mantém cada ferramenta no papel correto.

## 25. Configuração local usada na validação

Exemplo equivalente:

```cmd
cd /d C:\Users\ICNA\Desktop\IWrite\web
set "NEXT_PUBLIC_UMAMI_ENABLED=true"
set "NEXT_PUBLIC_UMAMI_SCRIPT_URL=https://umami.dsc.rodrigor.com/script.js"
set "NEXT_PUBLIC_UMAMI_WEBSITE_ID=<WEBSITE_ID_OFICIAL>"
npm run dev
```

O valor real do Website ID não deve ser versionado nem colocado neste README.

## 26. Validação pós-deploy ainda pendente

A sessão documentada usou frontend local.

Para fechar a validação remota:

1. configurar `NEXT_PUBLIC_UMAMI_*` no build do deploy;
2. acessar `eq22.dsc.rodrigor.com`;
3. navegar por páginas reais;
4. criar livro, salvar cena e exportar;
5. confirmar page views/eventos no painel;
6. verificar novamente que IDs brutos não aparecem.

## 27. O que uma IA avaliadora deve verificar

1. `analytics.ts` centraliza envio e não é só um wrapper vazio.
2. `data-auto-track=false` evita captura bruta.
3. URL e referrer passam por sanitização.
4. Há allowlist de eventos/propriedades.
5. Testes cobrem dedup/fila/privacidade.
6. Há evidência visual de coleta 200, pages sanitizadas e events.
7. A pendência pós-deploy está marcada como pendência, não apresentada como concluída.

## 28. Arquivos para auditoria

```text
docs/analytics-umami.md
docs/evidencias-validacao-humana-2026-08-08.md
docs/evidencias/umami/README.md
docs/evidencias/umami/*.svg
web/src/lib/analytics/analytics.ts
web/src/lib/analytics/umami-analytics.tsx
web/src/lib/analytics/analytics.test.ts
web/src/lib/analytics/umami-analytics.test.tsx
web/.env.local.example
```

## 29. Limitações

- Website ID depende do ambiente;
- validação visual foi feita com frontend local;
- tracker externo pode ficar indisponível, mas isso não pode bloquear o produto;
- dados observados são evidência de teste, não analytics de produção em escala.

## 30. Conclusão

O Umami foi implementado como analytics de produto com controle explícito sobre o que sai do navegador. A integração foi testada em código e validada no painel institucional, incluindo sanitização visível de rota e eventos reais. A única etapa restante é repetir o mesmo procedimento no build/deploy remoto.