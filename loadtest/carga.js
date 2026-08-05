import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

// Nenhuma falha de autenticação de VU é tolerada: rate==1 reprova o run
// inteiro mesmo que uma VU se autentique com sucesso numa iteração
// posterior (ver ensureVuAuthenticated()) — o teste não pode "passar"
// silenciosamente operando com menos sessões que o VUS pretendido.
const vuAuthSuccess = new Rate('vu_auth_success');

// ─────────────────────────────────────────────────────────────────────────────
// Teste de carga realista — k6 (issue #129)
//
// Cenário: GET /api/books → GET /api/books/{bookId}/outline →
// GET /api/scenes/{sceneId} → PATCH /api/scenes/{sceneId}/content → (se o
// PATCH deu certo) GET /api/books/{bookId}/outline de novo, espelhando o
// invalidateQueries que o frontend real dispara após salvar — autenticado
// com sessão real (cookie JSESSIONID + CSRF de duplo envio), contra o SEU
// AMBIENTE LOCAL. Cada VU representa uma sessão independente do mesmo autor
// editando o SEU PRÓPRIO livro (1 livro/seção/capítulo/cena por VU, criados
// em setup()) — sem contenção artificial no lock pessimista de linha de um
// único livro compartilhado.
//
// IMPORTANTE: NUNCA aponte para Render, produção ou o servidor acadêmico
// compartilhado (ex.: https://eqNN.dsc.rodrigor.com) — o guard de host abaixo
// recusa qualquer destino fora da lista local por padrão. Veja loadtest/README.md.
// ─────────────────────────────────────────────────────────────────────────────

const BASE = (__ENV.BASE_URL || 'http://localhost:8085').replace(/\/+$/, '');

// Hosts controlados aceitos sem override. Qualquer outro exige
// ALLOW_UNSAFE_TARGET=eu-autorizo-um-destino-externo explicitamente.
const SAFE_HOSTS = ['localhost', '127.0.0.1', '::1', 'host.docker.internal', 'backend'];
const DANGEROUS_OVERRIDE_VALUE = 'eu-autorizo-um-destino-externo';

/**
 * Parser de autoridade HTTP com semântica real (sem depender de `URL`, que
 * este runtime k6 não expõe): só http(s)://, rejeita user-info por completo
 * (não tenta "adivinhar" o host real por trás de user@host — uma URL com
 * credenciais é recusada mesmo que o host à direita seja local, porque um
 * parser ingênuo já provou ser a superfície de bypass aqui), resolve
 * `[::1]` como literal IPv6 e valida a porta.
 *
 * Nenhuma mensagem de erro aqui embute `rawUrl`: uma BASE_URL rejeitada por
 * conter user-info pode literalmente conter uma senha (ex.:
 * `http://usuario:senha@host`), e ecoar a URL inteira em stdout/stderr
 * vazaria exatamente o que a rejeição deveria proteger.
 */
function parseSafeHost(rawUrl) {
  const schemeMatch = /^([a-zA-Z][a-zA-Z0-9+.-]*):\/\//.exec(rawUrl);
  if (!schemeMatch) {
    throw new Error('BASE_URL inválida.');
  }
  const scheme = schemeMatch[1].toLowerCase();
  if (scheme !== 'http' && scheme !== 'https') {
    throw new Error('BASE_URL usa protocolo não permitido.');
  }

  const rest = rawUrl.slice(schemeMatch[0].length);
  const authorityEnd = rest.search(/[\/?#]/);
  const authority = authorityEnd === -1 ? rest : rest.slice(0, authorityEnd);
  if (!authority) {
    throw new Error('BASE_URL inválida.');
  }
  if (authority.indexOf('@') !== -1) {
    throw new Error('BASE_URL rejeitada porque contém user-info.');
  }

  if (authority[0] === '[') {
    const closeIdx = authority.indexOf(']');
    if (closeIdx === -1) {
      throw new Error('BASE_URL possui autoridade IPv6 inválida.');
    }
    const afterBracket = authority.slice(closeIdx + 1);
    if (afterBracket !== '' && !/^:\d+$/.test(afterBracket)) {
      throw new Error('BASE_URL possui autoridade IPv6 inválida.');
    }
    return authority.slice(1, closeIdx).toLowerCase();
  }

  const portIdx = authority.indexOf(':');
  const host = portIdx === -1 ? authority : authority.slice(0, portIdx);
  if (!host || (portIdx !== -1 && !/^\d+$/.test(authority.slice(portIdx + 1)))) {
    throw new Error('BASE_URL inválida.');
  }
  return host.toLowerCase();
}

(function enforceSafeTarget() {
  const host = parseSafeHost(BASE);
  if (SAFE_HOSTS.indexOf(host) !== -1) {
    return;
  }
  if (__ENV.ALLOW_UNSAFE_TARGET === DANGEROUS_OVERRIDE_VALUE) {
    console.warn(`ALLOW_UNSAFE_TARGET setado: rodando carga contra host NÃO local "${host}". Isso pode derrubar um ambiente compartilhado.`);
    return;
  }
  throw new Error(
    `BASE_URL aponta para um host não controlado ("${host}"). Hosts aceitos por padrão: ${SAFE_HOSTS.join(', ')}. ` +
    `Se você tem certeza absoluta que este destino é seu e é seguro martelar, rode de novo com ` +
    `-e ALLOW_UNSAFE_TARGET=${DANGEROUS_OVERRIDE_VALUE}. NUNCA use isso contra Render, produção ou o servidor acadêmico compartilhado.`
  );
})();

// Nº máximo de VUs simultâneos (também define quantos livros o setup() cria —
// um por VU, para que cada VU edite sempre o próprio livro/cena).
const VUS = Number(__ENV.VUS || 10);

const WARMUP_DURATION = __ENV.WARMUP_DURATION || '30s';
const STEADY_DURATION = __ENV.STEADY_DURATION || '2m';
const RAMPDOWN_DURATION = __ENV.RAMPDOWN_DURATION || '30s';

const LOGIN_EMAIL = __ENV.LOAD_TEST_EMAIL || 'autor-a@iwrite.local';
const LOGIN_PASSWORD = __ENV.LOAD_TEST_PASSWORD;

const THINK_TIME_MIN_S = Number(__ENV.THINK_TIME_MIN_S || 0.3);
const THINK_TIME_MAX_S = Number(__ENV.THINK_TIME_MAX_S || 1);

/**
 * Valida um valor de duração no formato do k6 (Go duration: unidades
 * ms/s/m/h encadeadas, ex. "10m", "90s", "1h30m") antes de aceitar
 * SETUP_TIMEOUT/TEARDOWN_TIMEOUT — um valor malformado deve falhar cedo e de
 * forma clara, não virar silenciosamente o timeout padrão de 60s do k6.
 */
function validateK6Duration(raw, name) {
  if (!/^([0-9]+(?:\.[0-9]+)?(?:ms|s|m|h))+$/.test(raw)) {
    throw new Error(`${name} inválido: "${raw}". Use um formato de duração do k6, unidades ms/s/m/h encadeadas, com parte fracionária opcional (ex.: "10m", "90s", "1h30m", "0.5m", "1.5s").`);
  }
  return raw;
}

// setup() cria 1 livro/seção/capítulo/cena por VU, em loop serial: o número
// de requisições (e portanto o tempo de setup) cresce com VUS, então o
// timeout de 60s padrão do k6 para setup()/teardown() não escala. Os
// defaults abaixo cobrem folgadamente VUS na casa da centena em ambiente
// local; suba SETUP_TIMEOUT/TEARDOWN_TIMEOUT explicitamente para VUS maior.
const SETUP_TIMEOUT = validateK6Duration(__ENV.SETUP_TIMEOUT || '10m', 'SETUP_TIMEOUT');
const TEARDOWN_TIMEOUT = validateK6Duration(__ENV.TEARDOWN_TIMEOUT || '10m', 'TEARDOWN_TIMEOUT');

export const options = {
  setupTimeout: SETUP_TIMEOUT,
  teardownTimeout: TEARDOWN_TIMEOUT,
  stages: [
    { duration: WARMUP_DURATION, target: VUS },
    { duration: STEADY_DURATION, target: VUS },
    { duration: RAMPDOWN_DURATION, target: 0 },
  ],
  // Lista padrão do k6 menos 'url': a tag automática 'url' carrega a URL
  // concreta de cada requisição (com o bookId/sceneId sintéticos embutidos),
  // o que criaria uma série por livro/cena caso o resultado seja exportado
  // para um time-series output ou o k6 cloud. Toda requisição abaixo define
  // um `name` fixo (rota normalizada) para navegação/agrupamento no lugar da
  // URL, e `operation` como dimensão funcional — nenhuma tag exportada
  // carrega bookId, sceneId, runId, título, conteúdo, usuário ou tenant.
  systemTags: [
    'proto', 'subproto', 'status', 'method', 'name', 'group', 'check',
    'error', 'error_code', 'tls_version', 'scenario', 'service', 'expected_response',
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
    http_req_duration: ['p(95)<500'],
    // Sem tolerância: uma única falha de autenticação de VU reprova o run
    // inteiro, mesmo que a mesma VU se autentique com sucesso numa iteração
    // posterior — o teste não pode passar operando com menos sessões que o
    // VUS pretendido (ver ensureVuAuthenticated()).
    vu_auth_success: ['rate==1'],
    // Operações principais do cenário medido, sob carga (loop de VUs):
    'http_req_duration{operation:list_books}': ['p(95)<500'],
    'http_req_duration{operation:load_outline}': ['p(95)<500'],
    'http_req_duration{operation:load_scene}': ['p(95)<500'],
    'http_req_duration{operation:save_scene}': ['p(95)<500'],
    // Refetch do outline que o frontend real dispara (invalidateQueries) após
    // um save_scene bem-sucedido — ver ensureVuAuthenticated()/default().
    'http_req_duration{operation:refresh_outline_after_save}': ['p(95)<500'],
    // Autenticação/setup/teardown: fora do loop medido (rodam 1x, ou VUS
    // vezes já que cada VU também autentica sozinho e setup() cria um livro
    // por VU), orçamento mais folgado só para pegar uma chamada realmente
    // travada — e, por terem threshold, o k6 passa a reportar as métricas
    // dessas tags separadas no summary.
    'http_req_duration{operation:auth_csrf}': ['p(95)<2000'],
    // auth_login é a primeira requisição de verdade contra um backend recém
    // subido: bcrypt (deliberadamente lento) + JIT/warmup da JVM no primeiro
    // uso do caminho de autenticação. Orçamento maior de propósito — não é
    // parte do cenário medido sob carga.
    'http_req_duration{operation:auth_login}': ['p(95)<8000'],
    'http_req_duration{operation:setup_create_book}': ['p(95)<2000'],
    'http_req_duration{operation:setup_create_section}': ['p(95)<2000'],
    'http_req_duration{operation:setup_create_chapter}': ['p(95)<2000'],
    'http_req_duration{operation:setup_create_scene}': ['p(95)<2000'],
    'http_req_duration{operation:teardown_delete_book}': ['p(95)<2000'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// UUID v4 local (sem dependência externa): o backend só exige o formato
// válido para desserializar como java.util.UUID, não aleatoriedade
// criptográfica — não é usado para nada sensível a segurança.
function uuidv4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

/**
 * Mesmo formato que `web/src/features/scenes/editor/tiptap-editor.tsx`
 * (`plainTextToDocument`) produz para hidratar o Tiptap a partir de texto
 * puro — reaproveitado aqui para que o `contentJson` do PATCH seja o mesmo
 * documento ProseMirror que o editor real gera e versiona, não uma estrutura
 * inventada.
 */
function plainTextToDocument(text) {
  const paragraphs = text.split(/\r?\n/);
  return {
    type: 'doc',
    content: paragraphs.map((paragraph) => ({
      type: 'paragraph',
      content: paragraph ? [{ type: 'text', text: paragraph }] : undefined,
    })),
  };
}

function jarCookie(jar, url, name) {
  const values = jar.cookiesForURL(url + '/')[name];
  return values && values[0];
}

/**
 * Autentica no jar explicitamente passado. k6 recicla o "jar corrente"
 * (o que `http.cookieJar()` devolve) a cada chamada de nível superior —
 * setup(), teardown() e CADA iteração de default() começam com um jar
 * vazio, mesmo dentro da mesma VU; não é o jar persistente "por VU" que a
 * documentação sugere à primeira leitura (confirmado empiricamente: chamar
 * `http.cookieJar()` de novo numa iteração posterior da mesma VU devolve um
 * jar sem os cookies da iteração anterior). Por isso login() nunca usa o
 * jar implícito — sempre recebe e devolve a autenticação no jar que o
 * chamador guardou (setup()/teardown() num `const jar` local; a VU num
 * `vuJar` de módulo, reaproveitado entre iterações via `{ jar }` explícito
 * em toda requisição). Nunca retorna nem guarda o cookie/token numa
 * variável separada do jar — quem precisa do header CSRF lê de volta do
 * jar, no momento da requisição, via `authHeaders(jar)`.
 */
function login(jar) {
  const csrfRes = http.get(`${BASE}/api/auth/csrf`, { jar, tags: { operation: 'auth_csrf', name: 'GET /api/auth/csrf' } });
  if (csrfRes.status !== 204) {
    throw new Error(`GET /api/auth/csrf retornou ${csrfRes.status}, esperado 204.`);
  }
  const csrfToken = jarCookie(jar, BASE, 'XSRF-TOKEN');
  if (!csrfToken) {
    throw new Error('Backend não emitiu o cookie XSRF-TOKEN.');
  }

  const loginRes = http.post(
    `${BASE}/api/auth/login`,
    JSON.stringify({ email: LOGIN_EMAIL, password: LOGIN_PASSWORD }),
    {
      jar,
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken },
      tags: { operation: 'auth_login', name: 'POST /api/auth/login' },
    }
  );
  if (loginRes.status !== 200) {
    throw new Error(`Login falhou com status ${loginRes.status}. Confira LOAD_TEST_EMAIL/LOAD_TEST_PASSWORD contra o seed demo (docker-compose.demo.yml).`);
  }
  if (!jarCookie(jar, BASE, 'JSESSIONID')) {
    throw new Error('Backend não emitiu o cookie JSESSIONID em /api/auth/login.');
  }
}

function authHeaders(jar) {
  const csrfToken = jarCookie(jar, BASE, 'XSRF-TOKEN');
  return csrfToken ? { 'X-XSRF-TOKEN': csrfToken } : {};
}

function jsonHeaders(jar, extra) {
  return Object.assign({ 'Content-Type': 'application/json' }, authHeaders(jar), extra || {});
}

let vuJar = null;
let vuAuthenticated = false;

/**
 * Login único por VU, feito na primeira iteração daquela VU — não em
 * setup() nem repetido a cada iteração. `vuJar` é guardado em variável de
 * módulo (sobrevive entre iterações da mesma VU) e passado explicitamente
 * (`{ jar: vuJar }`) em toda requisição do loop principal — sem isso, a
 * sessão se perderia a cada nova iteração (ver comentário de `login()`).
 * Cada VU passa a ter sua própria sessão de servidor real, refletindo VUS
 * sessões independentes do mesmo autor editando livros distintos, em vez de
 * uma única sessão de setup() repassada manualmente via headers para todas.
 * Falha ao autenticar não aborta o processo do k6 imediatamente: só a
 * iteração atual desta VU é perdida (a próxima iteração tenta autenticar de
 * novo, já que `vuAuthenticated` continua `false`), mas a métrica
 * `vu_auth_success` registra `false` nesse instante — e como o threshold
 * é `rate==1` sem tolerância, o run inteiro é reprovado ao final mesmo que
 * essa mesma VU consiga autenticar numa iteração posterior. Não existe
 * "reduzir a carga silenciosamente": qualquer falha de login vira falha do
 * `k6 run`.
 */
function ensureVuAuthenticated() {
  if (vuAuthenticated) {
    return vuJar;
  }
  try {
    vuJar = http.cookieJar();
    login(vuJar);
    vuAuthenticated = true;
    vuAuthSuccess.add(true);
    return vuJar;
  } catch (err) {
    console.error(`VU ${__VU}: falha ao autenticar: ${err.message}`);
    vuJar = null;
    vuAuthSuccess.add(false);
    return null;
  }
}

/**
 * Remove todos os livros já criados nesta execução de setup() antes de
 * relançar o erro original — teardown() nunca roda quando setup() lança, e
 * agora há até VUS livros órfãos possíveis (um por VU já provisionada), não
 * só um. Loga quais limpezas falharam (só bookId + status HTTP, nunca
 * sessão/CSRF) para permitir limpeza manual pontual em vez de exigir varrer
 * tudo que casa com LOADTEST-.
 */
function cleanupBooks(jar, bookIds, originalError) {
  console.error(`setup() falhou após criar ${bookIds.length} livro(s) sintético(s): ${originalError.message}`);
  const failedCleanups = [];
  bookIds.forEach((bookId) => {
    const cleanupRes = http.del(`${BASE}/api/books/${bookId}`, null, {
      jar,
      headers: jsonHeaders(jar),
      tags: { operation: 'setup_cleanup_book', name: 'DELETE /api/books/{bookId}' },
    });
    if (cleanupRes.status !== 204) {
      failedCleanups.push(`${bookId} (status ${cleanupRes.status})`);
    }
  });
  if (failedCleanups.length) {
    console.error(
      `Limpeza automática NÃO removeu ${failedCleanups.length}/${bookIds.length} livro(s) órfão(s): ${failedCleanups.join(', ')}. ` +
      `Limpeza manual necessária, veja loadtest/README.md.`
    );
  } else if (bookIds.length) {
    console.error(`Limpeza automática removeu todos os ${bookIds.length} livro(s) órfão(s).`);
  }
  throw originalError;
}

/**
 * setup() autentica só internamente (jar próprio de setup(), nunca
 * devolvido) para provisionar 1 livro/seção/capítulo/cena por VU — cada VU
 * autentica de novo, sozinha, na própria primeira iteração
 * (ensureVuAuthenticated()). O retorno de setup() é só metadado não
 * sensível ([{ bookId, sceneId }, ...]): nenhum cookie, header ou CSRF sai
 * daqui, então nenhum caminho de summary do k6 — RESULT_PATH,
 * --summary-export nativo ou K6_SUMMARY_EXPORT — consegue vazar sessão,
 * nem mesmo o caminho nativo que ignora handleSummary() por completo.
 */
export function setup() {
  if (!LOGIN_PASSWORD) {
    throw new Error('LOAD_TEST_PASSWORD não definida. Nunca versione a senha; exporte a variável de ambiente antes de rodar (ver loadtest/README.md).');
  }

  const ping = http.get(`${BASE}/ping`, { tags: { operation: 'smoke', name: 'GET /ping' } });
  if (ping.status !== 200) {
    throw new Error(`Smoke check falhou: GET /ping retornou ${ping.status}. Suba o ambiente antes de rodar a carga.`);
  }

  const jar = http.cookieJar();
  login(jar);

  const runId = `${Date.now().toString(36)}${Math.floor(Math.random() * 1e6).toString(36)}`;

  // Um livro por VU (nunca compartilhado): cada VU edita seu próprio
  // livro/seção/capítulo/cena, como sessões independentes do mesmo autor.
  // Um livro único faria todo save_scene serializar no lock pessimista de
  // linha do livro (SceneService.updateContent ->
  // BookAccessService.requireBookEditAccessForUpdate ->
  // BookRepository.findByIdAndTenantIdForUpdate), medindo contenção do
  // harness, não latência real de escritas concorrentes de usuários
  // diferentes.
  const createdBookIds = [];
  const resources = [];
  try {
    for (let i = 0; i < VUS; i++) {
      const marker = `LOADTEST-${runId}-vu${i + 1}`;

      const bookRes = http.post(
        `${BASE}/api/books`,
        JSON.stringify({ title: marker, status: 'WRITING', targetWordCount: 1000 }),
        { jar, headers: jsonHeaders(jar), tags: { operation: 'setup_create_book', name: 'POST /api/books' } }
      );
      if (bookRes.status !== 201) {
        throw new Error(`Criação do livro sintético ${i + 1}/${VUS} falhou com status ${bookRes.status}.`);
      }
      const bookId = bookRes.json('id');
      createdBookIds.push(bookId);

      const sectionRes = http.post(
        `${BASE}/api/books/${bookId}/sections`,
        JSON.stringify({ title: `${marker}-section`, type: 'PART', sortOrder: 0 }),
        { jar, headers: jsonHeaders(jar), tags: { operation: 'setup_create_section', name: 'POST /api/books/{bookId}/sections' } }
      );
      if (sectionRes.status !== 201) {
        throw new Error(`Criação da seção sintética ${i + 1}/${VUS} falhou com status ${sectionRes.status}.`);
      }
      const sectionId = sectionRes.json('id');

      const chapterRes = http.post(
        `${BASE}/api/sections/${sectionId}/chapters`,
        JSON.stringify({ title: `${marker}-chapter`, sortOrder: 0 }),
        { jar, headers: jsonHeaders(jar), tags: { operation: 'setup_create_chapter', name: 'POST /api/sections/{sectionId}/chapters' } }
      );
      if (chapterRes.status !== 201) {
        throw new Error(`Criação do capítulo sintético ${i + 1}/${VUS} falhou com status ${chapterRes.status}.`);
      }
      const chapterId = chapterRes.json('id');

      const sceneRes = http.post(
        `${BASE}/api/chapters/${chapterId}/scenes`,
        JSON.stringify({ title: `${marker}-scene`, sortOrder: 0 }),
        { jar, headers: jsonHeaders(jar), tags: { operation: 'setup_create_scene', name: 'POST /api/chapters/{chapterId}/scenes' } }
      );
      if (sceneRes.status !== 201) {
        throw new Error(`Criação da cena sintética ${i + 1}/${VUS} falhou com status ${sceneRes.status}.`);
      }

      resources.push({ bookId, sceneId: sceneRes.json('id') });
    }
  } catch (err) {
    cleanupBooks(jar, createdBookIds, err);
  }

  console.log(`Setup concluído: ${resources.length} livro(s) sintético(s) (1 por VU, runId ${runId}).`);
  return resources;
}

function thinkTime() {
  return THINK_TIME_MIN_S + Math.random() * (THINK_TIME_MAX_S - THINK_TIME_MIN_S);
}

/**
 * Autenticação (uma vez por VU) e depois list_books/load_outline/load_scene
 * são pré-requisitos sequenciais do fluxo real (não dá para abrir uma cena
 * sem antes navegar até o outline, e não dá para chegar no outline sem
 * antes listar os livros, nem fazer nada sem sessão). Uma falha em qualquer
 * um encerra a iteração imediatamente: nenhuma etapa seguinte roda, e em
 * particular nenhum PATCH é enviado usando um estado que o VU nunca
 * confirmou ter lido — uma falha de leitura não pode virar tráfego de
 * escrita nem distorcer a latência/erro de save_scene.
 *
 * Cada VU opera exclusivamente sobre o livro/cena no índice correspondente
 * a __VU (`data[__VU - 1]`), criados 1:1 por setup() — nunca um recurso
 * compartilhado com outra VU.
 */
export default function (data) {
  const jar = ensureVuAuthenticated();
  if (!jar) {
    sleep(thinkTime());
    return;
  }

  const resource = data[__VU - 1];
  if (!resource) {
    console.error(`VU ${__VU}: nenhum livro provisionado por setup() (criou ${data.length}, VUS=${VUS}).`);
    sleep(thinkTime());
    return;
  }
  const { bookId, sceneId } = resource;

  const listRes = http.get(`${BASE}/api/books`, {
    jar,
    headers: jsonHeaders(jar),
    tags: { operation: 'list_books', name: 'GET /api/books' },
  });
  if (!check(listRes, { 'list_books status 200': (r) => r.status === 200 })) {
    sleep(thinkTime());
    return;
  }

  const outlineRes = http.get(`${BASE}/api/books/${bookId}/outline`, {
    jar,
    headers: jsonHeaders(jar),
    tags: { operation: 'load_outline', name: 'GET /api/books/{bookId}/outline' },
  });
  if (!check(outlineRes, { 'load_outline status 200': (r) => r.status === 200 })) {
    sleep(thinkTime());
    return;
  }

  // A revisão vem sempre desta leitura, nunca de um cache local: assim uma
  // falha ambígua no PATCH anterior (commitou no servidor mas a resposta se
  // perdeu, por exemplo) nunca produz uma sequência de conflitos de revisão
  // artificiais — a próxima escrita sempre parte do estado real do servidor.
  const sceneRes = http.get(`${BASE}/api/scenes/${sceneId}`, {
    jar,
    headers: jsonHeaders(jar),
    tags: { operation: 'load_scene', name: 'GET /api/scenes/{sceneId}' },
  });
  if (!check(sceneRes, { 'load_scene status 200': (r) => r.status === 200 })) {
    sleep(thinkTime());
    return;
  }
  const revision = sceneRes.json('contentRevision');

  const contentText = `LOADTEST conteúdo sintético VU${__VU} iter${__ITER} ${Date.now()}`;
  const saveRes = http.patch(
    `${BASE}/api/scenes/${sceneId}/content`,
    JSON.stringify({
      contentText,
      // Mesmo contrato que o editor real envia (ver scene-editor.tsx /
      // scene-content-editor.tsx): o backend versiona o par contentJson +
      // contentText, então medir só contentText subestima o caminho de save.
      contentJson: JSON.stringify(plainTextToDocument(contentText)),
      source: 'AUTO_SAVE',
      expectedContentRevision: revision,
      operationId: uuidv4(),
    }),
    { jar, headers: jsonHeaders(jar), tags: { operation: 'save_scene', name: 'PATCH /api/scenes/{sceneId}/content' } }
  );
  const saveSucceeded = check(saveRes, { 'save_scene status 200': (r) => r.status === 200 });

  // Espelha o frontend real: BookWorkspace mantém a query do outline ativa e
  // contentMutation.mutateAsync() dispara queryClient.invalidateQueries no
  // sucesso do save, o que refaz este GET (ver web/src/features/scenes).
  // Só roda depois de um save bem-sucedido — o frontend também só invalida
  // o outline nesse caso — e fica numa tag própria (não agregada em
  // load_outline) para mostrar separadamente o custo desse refetch.
  if (saveSucceeded) {
    const refreshRes = http.get(`${BASE}/api/books/${bookId}/outline`, {
      jar,
      headers: jsonHeaders(jar),
      tags: { operation: 'refresh_outline_after_save', name: 'GET /api/books/{bookId}/outline' },
    });
    check(refreshRes, { 'refresh_outline_after_save status 200': (r) => r.status === 200 });
  }

  sleep(thinkTime());
}

/**
 * Reautentica no próprio contexto de teardown() — não reaproveita a sessão
 * de nenhuma VU nem a de setup() (que já terminou e cujo jar não existe
 * mais neste ponto) — antes de apagar todos os livros criados. Reprova a
 * execução (lança) se qualquer DELETE não voltar 204: um teardown que só
 * loga deixaria livros LOADTEST- para trás sem que nenhum threshold
 * acusasse nada, já que essas chamadas acontecem fora do loop de VUs
 * medido.
 */
export function teardown(data) {
  const jar = http.cookieJar();
  login(jar);

  const failed = [];
  data.forEach(({ bookId }) => {
    const res = http.del(`${BASE}/api/books/${bookId}`, null, {
      jar,
      headers: jsonHeaders(jar),
      tags: { operation: 'teardown_delete_book', name: 'DELETE /api/books/{bookId}' },
    });
    if (res.status !== 204) {
      failed.push(`${bookId} (status ${res.status})`);
    }
  });

  if (failed.length) {
    throw new Error(
      `Teardown falhou: ${failed.length}/${data.length} livro(s) não removido(s): ${failed.join(', ')} ` +
      `(esperado 204 em cada). Limpeza manual necessária, veja loadtest/README.md.`
    );
  }
  console.log(`Teardown concluído: ${data.length} livro(s) removido(s).`);
}

/**
 * setup_data já não carrega segredo nenhum — é só [{ bookId, sceneId }, ...]
 * — mas o allowlist continua aqui como defesa em profundidade: se um campo
 * sensível for adicionado ao retorno de setup() no futuro por engano, é esta
 * função (não a disciplina de quem editar setup()) que barra o vazamento
 * para qualquer summary, terminal ou arquivo.
 */
function redactSummary(data) {
  const clone = JSON.parse(JSON.stringify(data));
  if (Array.isArray(clone.setup_data)) {
    clone.setup_data = clone.setup_data.map((r) => ({
      bookId: r && r.bookId,
      sceneId: r && r.sceneId,
    }));
  }
  return clone;
}

/**
 * Resumo textual mínimo, sem dependência de rede: nenhum import de CDN (nem
 * o jslib oficial do k6 para o texto colorido padrão), porque o teste não
 * pode falhar em iniciar por falta de internet. O JSON sanitizado
 * (RESULT_PATH) continua sendo a evidência principal; isto aqui é só uma
 * conferência rápida no terminal.
 */
function renderShortSummary(data) {
  const m = data.metrics || {};
  const pct = (rate) => `${(rate * 100).toFixed(2)}%`;
  const lines = ['--- resumo curto (o JSON sanitizado é a evidência completa) ---'];

  if (m.checks) {
    const { passes, fails, rate } = m.checks.values;
    lines.push(`checks: ${pct(rate)} (${passes}/${passes + fails})`);
  }
  if (m.http_req_failed) {
    lines.push(`http_req_failed: ${pct(m.http_req_failed.values.rate)}`);
  }
  if (m.http_reqs) {
    lines.push(`http_reqs: ${m.http_reqs.values.count} (${m.http_reqs.values.rate.toFixed(2)}/s)`);
  }
  ['list_books', 'load_outline', 'load_scene', 'save_scene', 'refresh_outline_after_save'].forEach((op) => {
    const trend = m[`http_req_duration{operation:${op}}`];
    if (trend) {
      lines.push(`${op} p95: ${trend.values['p(95)'].toFixed(1)}ms`);
    }
  });

  const failedThresholds = [];
  Object.keys(m).forEach((key) => {
    const thresholds = m[key].thresholds || {};
    Object.keys(thresholds).forEach((expr) => {
      if (thresholds[expr].ok === false) {
        failedThresholds.push(`${key} ${expr}`);
      }
    });
  });
  lines.push(
    failedThresholds.length
      ? `THRESHOLDS FALHARAM: ${failedThresholds.join(', ')}`
      : 'Todos os thresholds passaram.'
  );

  return lines.join('\n') + '\n';
}

// Substitui `--summary-export` + sanitização manual: definir handleSummary()
// assume o controle da saída do k6 gerada por ELE (terminal e RESULT_PATH),
// então a redação acontece uma vez aqui e vale para os dois. Isso NÃO cobre
// o `--summary-export`/`K6_SUMMARY_EXPORT` nativos do k6 (que ignoram
// handleSummary() e escrevem o resumo bruto por conta própria) — por isso a
// proteção real contra esses dois caminhos é setup() nunca devolver segredo
// nenhum (ver comentário em setup()), não este redactSummary().
export function handleSummary(data) {
  const redacted = redactSummary(data);
  const outputs = {
    stdout: renderShortSummary(redacted),
  };
  if (__ENV.RESULT_PATH) {
    outputs[__ENV.RESULT_PATH] = JSON.stringify(redacted, null, 2) + '\n';
  }
  return outputs;
}
