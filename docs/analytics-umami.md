# Analytics de produto — Umami (issue #127)

## Finalidade

O Umami mede **uso do produto**: page views e eventos de funcionalidades (criar livro, salvar cena, analisar cena, exportar). Ele responde "o que as pessoas usam", não "como o sistema se comporta".

**Analytics ≠ observabilidade.** A observabilidade técnica (OpenTelemetry: traces, métricas, logs — issues #124–#126) diagnostica latência, erros e gargalos por requisição. O Umami agrega comportamento de uso, sem identificar indivíduos e sem dados técnicos de execução. As duas integrações são independentes: desligar uma não afeta a outra.

## Variáveis de ambiente (frontend)

| Variável | Obrigatória | Descrição |
|---|---|---|
| `NEXT_PUBLIC_UMAMI_ENABLED` | sim | `true` habilita; qualquer outro valor desabilita tudo. |
| `NEXT_PUBLIC_UMAMI_SCRIPT_URL` | sim | URL do `script.js` do Umami (ex.: `https://cloud.umami.is/script.js`). |
| `NEXT_PUBLIC_UMAMI_WEBSITE_ID` | sim | Website ID do cadastro **oficial do IWrite** no painel. Placeholder até ser informado; nunca versionar o valor real nem reutilizar ID de outro projeto. |
| `NEXT_PUBLIC_UMAMI_HOST_URL` | não | Apenas se o endpoint de coleta for diferente da origem do script. |

Sem configuração válida a integração é um **no-op total**: o script não é carregado e nenhuma chamada é feita. Falhas do tracker nunca bloqueiam ações do produto (todas as chamadas são fail-open). Nenhuma credencial administrativa do painel é usada pela aplicação — só o website ID público.

## Implementação

- `web/src/lib/analytics/analytics.ts` — camada única e tipada: configuração, injeção do script (uma vez, `data-auto-track="false"`), page views com deduplicação e `trackEvent` com allowlist. **Não** chame `window.umami.track` diretamente fora deste arquivo.
- `web/src/lib/analytics/umami-analytics.tsx` — componente client no layout raiz; registra page view inicial e a cada mudança de `usePathname()` (navegação client-side), com dedup por caminho e fila de uma page view pendente até o script carregar.

## Eventos

| Evento | Momento de disparo | Propriedades (enumeradas) |
|---|---|---|
| `book_created` | após a criação confirmada pelo backend | — |
| `scene_saved` | após persistência confirmada do conteúdo | `source`: `AUTO_SAVE` \| `MANUAL_SAVE` |
| `scene_analysis_requested` | ao enviar uma análise válida | — |
| `scene_analysis_succeeded` | somente após resposta válida da IA | — |
| `scene_analysis_failed` | falha exibida ao usuário | `category`: `unavailable` \| `request_failed` |
| `book_exported` | após download concluído | `target`: `manuscript` \| `notebook`; `format`: `txt` \| `md` \| `docx` |

## Proteção de dados

A allowlist em `analytics.ts` é a única fonte de eventos/propriedades/valores aceitos: eventos desconhecidos são descartados e propriedades ou valores fora da enumeração são removidos antes do envio. Nunca são enviados: conteúdo de manuscrito, títulos, emails, nomes, IDs brutos (usuário/tenant/livro/cena), prompts, respostas de IA, tokens, stack traces ou URLs com query string sensível (os caminhos rastreados são rotas do app: `/`, `/dashboard`, `/books/{id}` — sem query string).

## Testes

`web/src/lib/analytics/analytics.test.ts`, `web/src/lib/analytics/umami-analytics.test.tsx` e a seção de analytics em `scene-ai-analysis-panel.test.tsx` cobrem: integração desabilitada/ausente/válida, carregamento único do script, page view inicial e navegação client-side, deduplicação, eventos de sucesso e falha, bloqueio de propriedades não permitidas e ausência de conteúdo/IDs privados.

## Validação após deploy

1. Configurar as variáveis com o website ID oficial e fazer o build (`npm run build`) — as variáveis `NEXT_PUBLIC_*` são embutidas no build.
2. Abrir o app, navegar entre `/`, `/dashboard` e um livro; confirmar page views no painel do Umami no website correto.
3. Criar um livro, salvar uma cena e exportar um manuscrito; confirmar `book_created`, `scene_saved` e `book_exported` no painel.
4. Conferir que nenhuma propriedade contém título, conteúdo ou IDs.
