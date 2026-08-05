import http from 'k6/http';
import { check, sleep } from 'k6';

// ─────────────────────────────────────────────────────────────────────────────
// Teste de carga realista — k6 (issue #129)
//
// Cenário: GET /api/books → GET /api/books/{bookId}/outline →
// PATCH /api/scenes/{sceneId}/content, autenticado com sessão real (cookie
// JSESSIONID + CSRF de duplo envio), contra o SEU AMBIENTE LOCAL.
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

function extractHost(url) {
  const match = /^https?:\/\/(?:\[)?([^\/:\]]+)(?:\])?/i.exec(url);
  return match ? match[1].toLowerCase() : '';
}

(function enforceSafeTarget() {
  const host = extractHost(BASE);
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

// Nº máximo de VUs simultâneos (também define quantas cenas o setup() cria —
// uma por VU, para que cada VU escreva sempre na sua própria cena).
const VUS = Number(__ENV.VUS || 10);

const WARMUP_DURATION = __ENV.WARMUP_DURATION || '30s';
const STEADY_DURATION = __ENV.STEADY_DURATION || '2m';
const RAMPDOWN_DURATION = __ENV.RAMPDOWN_DURATION || '30s';

const LOGIN_EMAIL = __ENV.LOAD_TEST_EMAIL || 'autor-a@iwrite.local';
const LOGIN_PASSWORD = __ENV.LOAD_TEST_PASSWORD;

const THINK_TIME_MIN_S = Number(__ENV.THINK_TIME_MIN_S || 0.3);
const THINK_TIME_MAX_S = Number(__ENV.THINK_TIME_MAX_S || 1);

export const options = {
  stages: [
    { duration: WARMUP_DURATION, target: VUS },
    { duration: STEADY_DURATION, target: VUS },
    { duration: RAMPDOWN_DURATION, target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
    http_req_duration: ['p(95)<500'],
    'http_req_duration{operation:list_books}': ['p(95)<500'],
    'http_req_duration{operation:load_outline}': ['p(95)<500'],
    'http_req_duration{operation:save_scene}': ['p(95)<500'],
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

function jarCookie(jar, url, name) {
  const values = jar.cookiesForURL(url + '/')[name];
  return values && values[0];
}

function jsonHeaders(authHeaders, extra) {
  return Object.assign({ 'Content-Type': 'application/json' }, authHeaders, extra || {});
}

/**
 * Login único, fora do loop de VUs: evita contaminar o rate limiter de login
 * (máx. configurado em IWRITE_LOGIN_RATE_LIMIT_MAX_PER_ACCOUNT/ORIGIN, padrão
 * 8/20 por janela de 1m — ver .env.example) e reflete o uso real do produto,
 * onde a sessão do servidor dura a visita inteira, não uma por requisição.
 */
export function setup() {
  if (!LOGIN_PASSWORD) {
    throw new Error('LOAD_TEST_PASSWORD não definida. Nunca versione a senha; exporte a variável de ambiente antes de rodar (ver loadtest/README.md).');
  }

  const ping = http.get(`${BASE}/ping`, { tags: { operation: 'smoke' } });
  if (ping.status !== 200) {
    throw new Error(`Smoke check falhou: GET /ping retornou ${ping.status}. Suba o ambiente antes de rodar a carga.`);
  }

  const csrfRes = http.get(`${BASE}/api/auth/csrf`, { tags: { operation: 'auth_csrf' } });
  if (csrfRes.status !== 204) {
    throw new Error(`GET /api/auth/csrf retornou ${csrfRes.status}, esperado 204.`);
  }
  // Lido do cookie jar em vez do Set-Cookie desta resposta específica: o
  // CsrfFilter do Spring Security já resolve/emite o XSRF-TOKEN em qualquer
  // requisição (inclusive o /ping acima), então uma chamada já pode ter
  // fixado o cookie e esta responder sem repetir o Set-Cookie.
  const jar = http.cookieJar();
  const csrfToken = jarCookie(jar, BASE, 'XSRF-TOKEN');
  if (!csrfToken) {
    throw new Error('Backend não emitiu o cookie XSRF-TOKEN (nem em /ping nem em /api/auth/csrf).');
  }

  const loginRes = http.post(
    `${BASE}/api/auth/login`,
    JSON.stringify({ email: LOGIN_EMAIL, password: LOGIN_PASSWORD }),
    {
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken },
      tags: { operation: 'auth_login' },
    }
  );
  if (loginRes.status !== 200) {
    throw new Error(`Login falhou com status ${loginRes.status}. Confira LOAD_TEST_EMAIL/LOAD_TEST_PASSWORD contra o seed demo (docker-compose.demo.yml).`);
  }
  const sessionId = jarCookie(jar, BASE, 'JSESSIONID');
  if (!sessionId) {
    throw new Error('Backend não emitiu o cookie JSESSIONID em /api/auth/login.');
  }

  const authHeaders = {
    Cookie: `JSESSIONID=${sessionId}; XSRF-TOKEN=${csrfToken}`,
    'X-XSRF-TOKEN': csrfToken,
  };

  const runId = `${Date.now().toString(36)}${Math.floor(Math.random() * 1e6).toString(36)}`;
  const marker = `LOADTEST-${runId}`;

  const bookRes = http.post(
    `${BASE}/api/books`,
    JSON.stringify({ title: marker, status: 'WRITING', targetWordCount: 1000 }),
    { headers: jsonHeaders(authHeaders), tags: { operation: 'setup_create_book' } }
  );
  if (bookRes.status !== 201) {
    throw new Error(`Criação do livro sintético falhou com status ${bookRes.status}: ${bookRes.body}`);
  }
  const bookId = bookRes.json('id');

  const sectionRes = http.post(
    `${BASE}/api/books/${bookId}/sections`,
    JSON.stringify({ title: `${marker}-section`, type: 'PART', sortOrder: 0 }),
    { headers: jsonHeaders(authHeaders), tags: { operation: 'setup_create_section' } }
  );
  if (sectionRes.status !== 201) {
    throw new Error(`Criação da seção sintética falhou com status ${sectionRes.status}: ${sectionRes.body}`);
  }
  const sectionId = sectionRes.json('id');

  const chapterRes = http.post(
    `${BASE}/api/sections/${sectionId}/chapters`,
    JSON.stringify({ title: `${marker}-chapter`, sortOrder: 0 }),
    { headers: jsonHeaders(authHeaders), tags: { operation: 'setup_create_chapter' } }
  );
  if (chapterRes.status !== 201) {
    throw new Error(`Criação do capítulo sintético falhou com status ${chapterRes.status}: ${chapterRes.body}`);
  }
  const chapterId = chapterRes.json('id');

  // Uma cena por VU máximo, para que cada VU escreva sempre na própria cena.
  const sceneIds = [];
  for (let i = 0; i < VUS; i++) {
    const sceneRes = http.post(
      `${BASE}/api/chapters/${chapterId}/scenes`,
      JSON.stringify({ title: `${marker}-scene-${i + 1}`, sortOrder: i }),
      { headers: jsonHeaders(authHeaders), tags: { operation: 'setup_create_scene' } }
    );
    if (sceneRes.status !== 201) {
      throw new Error(`Criação da cena sintética ${i + 1}/${VUS} falhou com status ${sceneRes.status}: ${sceneRes.body}`);
    }
    sceneIds.push(sceneRes.json('id'));
  }

  console.log(`Setup concluído: livro ${marker} (${bookId}) com ${sceneIds.length} cena(s).`);

  return { authHeaders, bookId, runId, marker, sceneIds };
}

// Escopo de módulo = escopo por VU em k6 (cada VU roda sua própria instância
// do script), então isto guarda a revisão da cena entre iterações do mesmo VU
// sem precisar de um recurso externo.
const sceneRevisions = {};

export default function (data) {
  const { authHeaders, bookId, marker, sceneIds } = data;

  const listRes = http.get(`${BASE}/api/books`, {
    headers: jsonHeaders(authHeaders),
    tags: { operation: 'list_books' },
  });
  check(listRes, { 'list_books status 200': (r) => r.status === 200 });

  const outlineRes = http.get(`${BASE}/api/books/${bookId}/outline`, {
    headers: jsonHeaders(authHeaders),
    tags: { operation: 'load_outline' },
  });
  check(outlineRes, { 'load_outline status 200': (r) => r.status === 200 });

  // Uma cena fixa por VU (índice estável em __VU), nunca compartilhada entre VUs.
  const sceneId = sceneIds[(__VU - 1) % sceneIds.length];
  const revision = sceneRevisions[sceneId] !== undefined ? sceneRevisions[sceneId] : 0;

  const saveRes = http.patch(
    `${BASE}/api/scenes/${sceneId}/content`,
    JSON.stringify({
      contentText: `${marker} conteúdo sintético VU${__VU} iter${__ITER} ${Date.now()}`,
      source: 'AUTO_SAVE',
      expectedContentRevision: revision,
      operationId: uuidv4(),
    }),
    { headers: jsonHeaders(authHeaders), tags: { operation: 'save_scene' } }
  );
  const saveOk = check(saveRes, { 'save_scene status 200': (r) => r.status === 200 });
  if (saveOk) {
    sceneRevisions[sceneId] = saveRes.json('contentRevision');
  }

  sleep(THINK_TIME_MIN_S + Math.random() * (THINK_TIME_MAX_S - THINK_TIME_MIN_S));
}

export function teardown(data) {
  const res = http.del(`${BASE}/api/books/${data.bookId}`, null, {
    headers: jsonHeaders(data.authHeaders),
    tags: { operation: 'teardown_delete_book' },
  });
  if (res.status !== 204) {
    console.error(
      `Teardown NÃO removeu o livro sintético ${data.marker} (${data.bookId}) — status ${res.status}. ` +
      `Limpeza manual necessária, veja loadtest/README.md.`
    );
    return;
  }
  console.log(`Teardown concluído: livro ${data.marker} (${data.bookId}) removido.`);
}
