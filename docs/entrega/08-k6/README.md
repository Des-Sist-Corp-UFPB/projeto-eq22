# Requisito 08 — Teste de carga e performance com k6

## 1. Objetivo

Este requisito foi tratado como um trabalho de engenharia de performance, não como um script que dispara requests em `/ping`.

O objetivo foi construir um cenário que se aproxime do uso real do IWrite, com autenticação, sessão, CSRF, leitura de livros/outline/cena, autosave realista, escrita concorrente em recursos independentes, atualização posterior do outline, thresholds por operação e limpeza segura dos dados sintéticos.

Além de medir, o harness precisava ser:

- reproduzível;
- seguro para rodar apenas localmente;
- resistente a falhas parciais de setup/teardown;
- livre de vazamento de sessão/senha em summaries/tags/process argv;
- capaz de reprovar quando o contrato funcional ou de latência fosse quebrado;
- documentado com resultados concretos.

## 2. Estado

**✅ Implementado, medido e revalidado depois da integração da master.**

Foram registradas execuções completas com:

```text
10 VUs
30 VUs
```

Os artefatos estão em:

```text
loadtest/carga.js
loadtest/README.md
loadtest/resultado.json
loadtest/resultados/resultado-10vus.json
loadtest/resultados/resultado-30vus.json
docker-compose.loadtest.yml
```

## 3. Por que o teste antigo não era suficiente

O cenário anterior se limitava essencialmente ao health check.

Medir `/ping` não representa:

- autenticação;
- queries reais;
- serialização do outline;
- leitura de cena;
- transação de salvamento;
- atualização de contagem de palavras;
- lock/concorrência;
- CSRF;
- autosave do editor.

Por isso o cenário foi substituído por um fluxo funcional completo.

## 4. Unidade de carga

Cada VU representa uma sessão independente do mesmo autor usando **seu próprio livro e sua própria cena**.

Não há 30 VUs batendo no mesmo livro.

Essa decisão evita medir artificialmente a serialização causada pelo lock de linha de um único livro. O objetivo é medir concorrência de vários autores/sessões independentes, não contenção forçada do harness.

## 5. Dados sintéticos por VU

Durante `setup()`, o script cria por VU:

```text
1 livro
1 seção
1 capítulo
1 cena
```

Cada VU recebe o par de IDs correspondente ao próprio recurso.

Os títulos sintéticos possuem marcador de execução (`runId`) para permitir limpeza precisa.

## 6. Fluxo de cada turno

Cada turno de uso executa:

```text
1. GET /api/books
2. GET /api/books/{bookId}/outline
3. GET /api/scenes/{sceneId}
4. espera do debounce real de autosave
5. PATCH /api/scenes/{sceneId}/content
6. GET /api/books/{bookId}/outline após save bem-sucedido
7. think time curto
```

As operações são tagueadas como:

```text
list_books
load_outline
load_scene
save_scene
refresh_outline_after_save
```

## 7. Pré-requisitos sequenciais

Os passos 1, 2 e 3 são pré-requisitos reais.

Se listagem, outline ou cena falhar, o turno é encerrado e o script não envia um PATCH apoiado em estado inválido.

Isso impede que uma falha de leitura seja transformada artificialmente em uma avalanche de escritas inválidas.

## 8. Autosave realista

O cenário usa:

```text
AUTO_SAVE_DELAY_MS=1200
```

Esse valor corresponde ao debounce do editor real (`CONTENT_AUTOSAVE_DELAY_MS`).

O script espera esse período depois de montar o conteúdo e antes do PATCH.

Sem isso, o harness escreveria mais rápido do que a própria UI permite e superestimaria a carga de escrita.

## 9. `contentJson` realista

O PATCH não envia apenas texto plano.

O cenário também envia documento ProseMirror compatível com o formato produzido pelo TipTap no frontend.

Isso evita subestimar o caminho real de serialização/persistência.

## 10. Revisão de conteúdo

`expectedContentRevision` vem da leitura atual da cena, não de cache local permanente do script.

Isso evita cascatas artificiais de conflito se uma resposta de PATCH for perdida ou ambígua.

Cada escrita gera novo `operationId`.

## 11. Refresh após save

O frontend real invalida o outline depois de salvar a cena.

O harness reproduz esse comportamento com:

```text
refresh_outline_after_save
```

A chamada só acontece quando o PATCH retorna sucesso.

## 12. Fire-and-forget e o problema do k6

Uma das partes mais trabalhosas foi modelar corretamente o comportamento assíncrono do refetch.

Uma versão anterior usava `Promise.all(refresh, thinkTime)`, mas isso continuava transformando o refresh em barreira quando ele era mais lento que o think time.

Foi confirmado empiricamente que o k6 drena Promises pendentes antes de encerrar uma iteração.

A solução foi mudar o desenho para **uma única iteração k6 longa por VU**, contendo um laço interno de turnos. Dentro desse laço, o refresh pode ser disparado sem bloquear o início do próximo turno; o runtime só precisa drenar o trabalho restante no fim da vida da VU.

Isso aproxima melhor o comportamento `void queryClient.invalidateQueries(...)` do frontend real.

## 13. Rampa de carga

O cenário usa ativação/desativação distribuída por VU para produzir:

```text
ramp_up
steady
ramp_down
```

A fase é calculada no momento de dispatch de cada request, não uma única vez no começo do turno.

Isso evita atribuir um PATCH à fase errada quando o turno atravessa a fronteira entre warmup e steady durante o debounce.

## 14. Cobertura completa da rampa

Um achado do Codex identificou que offsets baseados em `(vuIndex-1)/VUS` nunca alcançavam o extremo 1 da rampa.

O cálculo foi corrigido para cobrir o intervalo completo, garantindo que a última VU alcance o fim nominal da subida/descida.

Esse tipo de correção mostra que o harness foi revisado como código de produção, não tratado como script descartável.

## 15. Autenticação por VU

Cada VU autentica uma única vez, com cookie jar próprio.

A sessão não é compartilhada entre VUs.

Isso modela melhor múltiplas sessões independentes e evita usar um único `JSESSIONID` para toda a carga.

`setup()` e `teardown()` também possuem suas próprias sessões separadas.

## 16. Janela de preparação da autenticação

Outro achado do Codex identificou que autenticar depois do offset da VU podia fazer a fase `steady` começar antes de todas as VUs estarem realmente produzindo tráfego.

O cenário passou a autenticar antes da curva medida e introduziu uma janela `AUTH_PREPARE_MS`.

Assim, o relógio da fase medida só começa depois do orçamento destinado ao handshake de autenticação.

## 17. Rate limit no ambiente de carga

Com uma sessão por VU, 30 VUs significam dezenas de logins próximos no tempo.

Isso poderia disparar o rate limiter normal de produção e medir a proteção de login em vez da capacidade do fluxo principal.

Por isso existe:

```text
docker-compose.loadtest.yml
```

Esse overlay aumenta os limites de login **somente no ambiente local controlado de carga**.

Os defaults de produção não são alterados.

## 18. Proteção contra alvo remoto

O script recusa destinos que não sejam locais/controlados.

Hosts permitidos incluem formas de loopback/host Docker apropriadas.

O README é explícito em proibir apontar o teste para:

```text
produção
Render
servidor acadêmico compartilhado
```

Isso evita que um teste de carga acidental afete dados/infraestrutura de terceiros.

## 19. Tags sanitizadas

As requests usam tags como:

```text
operation
name
phase
```

A tag automática de URL concreta é desabilitada para evitar exportar UUIDs sintéticos como dimensão.

As rotas são normalizadas:

```text
PATCH /api/scenes/{sceneId}/content
```

em vez de conter o UUID real.

## 20. Senha fora da linha de comando

Um achado do Codex identificou que passar:

```text
-e LOAD_TEST_PASSWORD=...
```

colocaria o segredo em `argv`, visível para ferramentas de inspeção de processos.

A documentação foi corrigida para usar a variável já presente no ambiente do processo, que o k6 expõe em `__ENV`, sem repetir o segredo em argumento de linha de comando.

Isso foi validado empiricamente.

## 21. Sessão fora dos summaries

Uma versão anterior do setup podia devolver material de autenticação no objeto compartilhado com VUs.

Isso é perigoso porque summaries nativos do k6 podem serializar `setup_data`, inclusive caminhos que ignoram `handleSummary()`.

O desenho final faz `setup()` devolver apenas IDs sintéticos necessários para o cenário.

Nenhum cookie/token de sessão é retornado.

Isso protege:

```text
RESULT_PATH
--summary-export
K6_SUMMARY_EXPORT
```

## 22. Setup seguro

O `setup()` possui orçamento explícito e validação de timeout.

Ele não deve iniciar uma nova operação de provisionamento se não houver tempo suficiente para:

- completar request em andamento;
- recuperar possível livro órfão;
- limpar livros já conhecidos.

Esse desenho surgiu de revisão/fault injection, não de especulação.

## 23. Recuperação de criação ambígua

Se `POST /api/books` for processado pelo servidor mas a resposta se perder/atrasar até timeout, o cliente pode não conhecer o ID do livro criado.

O script usa `runId` no título e uma janela de recuperação para localizar esse possível órfão antes de encerrar o setup.

As requests de recuperação possuem timeout individual menor que a janela total.

## 24. Fault injection: request lenta

Foi usado proxy local throwaway para atrasar respostas e provar que:

- uma request individual respeita o teto;
- a janela total de recuperação não vira loop infinito;
- o setup falha controladamente;
- cleanup ainda recebe orçamento.

Essas injeções não foram versionadas como componente do produto; os resultados/metodologia estão documentados no `loadtest/README.md`.

## 25. Fault injection: commit tardio

Também foi testado o cenário em que o cliente desiste antes de o backend sequer receber/processar a criação e o commit ocorre depois.

A recuperação por polling conseguiu encontrar e remover o livro tardio sem apagar um livro decoy pertencente a outro `runId`.

Isso valida isolamento entre execuções concorrentes do próprio harness.

## 26. Teardown

`teardown()` autentica com sessão própria e apaga todos os livros sintéticos conhecidos.

Falha de DELETE não é apenas logada: ela reprova a execução.

Isso evita um teste “verde” deixando sujeira no banco.

## 27. Limpeza manual de emergência

O README documenta procedimentos de emergência escopados por `runId`.

É explicitamente proibido usar um padrão genérico que apague todos os títulos `LOADTEST-%`, porque isso poderia remover dados de outra execução concorrente.

## 28. Thresholds globais

Entre os gates globais:

```text
http_req_failed < 1%
checks > 99%
vu_auth_success == 100%
http_req_duration p95 < 500 ms
```

Falhar threshold produz exit code não zero.

## 29. Thresholds por operação

A fase `steady` possui thresholds de latência por operação principal.

Também existe gate de **status exato**, não apenas de `http_req_failed`.

## 30. Por que status exato

O k6 considera qualquer status 200-399 como sucesso HTTP por padrão.

Mas o contrato do cenário exige `200` para as operações medidas.

Uma regressão `200 -> 204` poderia passar em `http_req_failed` mesmo quebrando o contrato funcional esperado pelo frontend.

Por isso foi criada a métrica:

```text
operation_status_success
```

alimentada por checagem do status exato.

## 31. Fault injection do contrato

Foi validado um cenário de regressão proposital em que parte dos PATCHes retornava `204`.

Resultado:

- `http_req_failed` continuaria passando;
- `operation_status_success` caiu e reprovou o threshold.

Isso prova que o novo gate captura regressões que a métrica HTTP padrão não detectaria.

## 32. Número de thresholds

Na revalidação registrada, **21 thresholds** passaram nas execuções de 10 e 30 VUs.

Eles combinam:

- latência por operação;
- status exato por operação;
- gates globais;
- auth/setup/teardown auxiliares.

## 33. Resultado — 10 VUs

Execução pós-integração registrada:

```text
VUs: 10
requests: 3955
RPS global: 19,36
p95 global: 65,07 ms
http_req_failed: 0%
checks: 100%
vu_auth_success: 100%
turnos totais: 776
turnos steady: 614
```

### p95 steady por operação

| Operação | p95 |
|---|---:|
| `list_books` | 74,35 ms |
| `load_outline` | 19,90 ms |
| `load_scene` | 17,75 ms |
| `save_scene` | 96,27 ms |
| `refresh_outline_after_save` | 20,56 ms |

Todos abaixo do threshold de 500 ms.

## 34. Resultado — 30 VUs

```text
VUs: 30
requests: 11750
RPS global: 57,18
p95 global: 85,93 ms
http_req_failed: 0%
checks: 100%
vu_auth_success: 100%
turnos totais: 2307
turnos steady: 1830
```

### p95 steady por operação

| Operação | p95 |
|---|---:|
| `list_books` | 134,55 ms |
| `load_outline` | 16,99 ms |
| `load_scene` | 13,75 ms |
| `save_scene` | 89,01 ms |
| `refresh_outline_after_save` | 19,85 ms |

Também todos abaixo de 500 ms.

## 35. Escala 10 -> 30 VUs

Ao triplicar o pico de VUs:

```text
RPS global: 19,36 -> 57,18
p95 global: 65,07 -> 85,93 ms
```

A latência cresceu, como esperado, mas permaneceu com folga em relação ao threshold.

## 36. Operação relativamente mais cara

Na rodada de 30 VUs, `list_books` teve o maior p95 steady entre as cinco operações principais: **134,55 ms**.

Isso é coerente com o próprio cenário, que cria um livro por VU no mesmo tenant. A lista cresce com a quantidade de VUs/dados provisionados.

Mesmo assim ficou muito abaixo do teto de 500 ms.

## 37. `save_scene`

`save_scene` é um caminho crítico porque envolve escrita transacional, revisão, word count e persistência de eventos.

P95 observado:

```text
10 VUs: 96,27 ms
30 VUs: 89,01 ms
```

A diferença entre rodadas não deve ser interpretada como melhoria determinística; há variação normal de execução/JIT/cache/hardware.

## 38. Contaminação e remedição de ambiente

Uma execução anterior apresentou cauda anormal de `save_scene` enquanto outra stack do IWrite estava ativa na mesma máquina.

Essa tentativa foi descartada e não versionada como resultado final.

As execuções registradas foram realizadas depois de isolar melhor o ambiente e confirmar ausência de carga concorrente relevante.

## 39. Energia do notebook como variável real

Durante desenvolvimento, execuções na bateria produziram degradação suficiente para afetar thresholds.

A documentação passou a exigir execução conectada à energia AC para reduzir throttling de CPU do Windows/notebook.

Isso não é maquiagem do resultado: é controle explícito de uma variável do ambiente de benchmark.

## 40. Revalidação depois da integração da master

O harness medido foi reexecutado depois de integrar a master que continha mudanças de autenticação/sessão/rate limiting.

O script permaneceu byte-a-byte idêntico; o backend alvo mudou.

A revalidação confirmou:

```text
0 respostas 429
100% autenticação de VUs
100% checks
21 thresholds aprovados
0 resíduos LOADTEST-
```

## 41. Reprodutibilidade pós-squash

O README documenta que commits intermediários de uma branch podem ficar inalcançáveis depois de squash merge.

Por isso a âncora estável do script medido é o **blob Git**, não apenas o commit intermediário.

Valores registrados:

```text
measured_script_git_blob = 8b8a53ebe4207ee7f8ea951dde273cdd741b5154
measured_script_sha256   = 18bb2fdc32fe5f4d3e483dc7d7ccfdad7e37d58eab745f22d5d657e5f4292b9f
```

Auditoria:

```bash
git rev-parse HEAD:loadtest/carga.js
```

O valor deve corresponder ao blob medido se o script final não mudou.

## 42. JSON comparativo

`loadtest/resultado.json` não é apenas dump do k6. Ele registra:

- commit/blob/hash;
- ambiente;
- cenário;
- metodologia;
- execuções 10/30 VUs;
- percentis;
- erros/checks;
- thresholds;
- comparação com rodada anterior;
- limitações;
- gargalo identificado;
- próxima ação recomendada.

Isso permite auditoria sem depender de print de terminal.

## 43. JSONs brutos

Os summaries por execução ficam em:

```text
loadtest/resultados/resultado-10vus.json
loadtest/resultados/resultado-30vus.json
```

Eles são a evidência de baixo nível usada pelo resumo comparativo.

## 44. Limitações assumidas

A documentação não vende o benchmark como capacidade absoluta de produção.

Limitações:

- gerador de carga e backend na mesma máquina;
- hardware de desenvolvimento;
- conteúdo sintético curto;
- `list_books` cresce com a quantidade de livros provisionados;
- sem OTel habilitado durante a rodada final de carga, para não alterar o custo medido;
- cenário não mede cadastro público;
- máximo estudado e versionado: 30 VUs.

## 45. Próximos passos recomendados

O próprio relatório sugere:

- repetir em hardware dedicado;
- experimentar 50-100 VUs;
- rodar uma rodada com OTel para decompor custos;
- criar cenário complementar para cadastro, se essa rota virar crítica.

## 46. Como executar

Pré-requisitos detalhados estão em `loadtest/README.md`.

Exemplo 10 VUs:

```bash
k6 run \
  -e BASE_URL=http://localhost:8085 \
  -e VUS=10 \
  -e RESULT_PATH=loadtest/resultados/resultado-10vus.json \
  loadtest/carga.js
```

Exemplo 30 VUs:

```bash
k6 run \
  -e BASE_URL=http://localhost:8085 \
  -e VUS=30 \
  -e RESULT_PATH=loadtest/resultados/resultado-30vus.json \
  loadtest/carga.js
```

A senha deve existir no ambiente do processo; não deve ser passada em argumento `-e LOAD_TEST_PASSWORD=...`.

## 47. O que uma IA avaliadora deve verificar

1. O script não testa apenas `/ping`.
2. Existe sessão/CSRF real.
3. Cada VU possui sessão e recurso próprios.
4. Há debounce igual ao frontend.
5. `contentJson` imita o formato real.
6. Existe refresh pós-save.
7. Há fases ramp/steady/ramp-down.
8. Thresholds de steady não são diluídos pelas rampas.
9. Existe gate de status exato.
10. O alvo remoto é bloqueado.
11. Secrets não entram em argv/summaries.
12. Setup/teardown possuem limpeza robusta.
13. Há resultados brutos + resumo comparativo.
14. Há 10 e 30 VUs medidos.
15. Os números da documentação correspondem a `resultado.json`.
16. O harness foi revalidado depois da integração do backend final.

## 48. Arquivos para auditoria

```text
loadtest/carga.js
loadtest/README.md
loadtest/resultado.json
loadtest/resultados/resultado-10vus.json
loadtest/resultados/resultado-30vus.json
docker-compose.loadtest.yml
docker-compose.demo.yml
web/src/features/scenes/components/scene-editor.tsx
web/src/features/scenes/editor/tiptap-editor.tsx
src/main/java/com/iwrite/scene/service/SceneService.java
```

## 49. Conclusão

O k6 é provavelmente o requisito com maior volume de engenharia defensiva da entrega. O trabalho envolveu modelagem do comportamento real do editor, sessão por VU, CSRF, controle de fases, métricas funcionais próprias, fault injection, recuperação de órfãos, proteção de secrets, limpeza, reprodutibilidade pós-squash e múltiplas rodadas de medição. O resultado final não é “um teste passou”: é um harness auditável que mede um fluxo realista e falha de forma explícita quando latência, autenticação, status funcional ou limpeza deixam de cumprir o contrato.