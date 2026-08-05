import http from 'k6/http';
import { check, sleep } from 'k6';

// ─────────────────────────────────────────────────────────────────────────────
// Teste de carga realista — k6 (issue #129)
//
// Cenário: GET /api/books → GET /api/books/{bookId}/outline →
// GET /api/scenes/{sceneId} → PATCH /api/scenes/{sceneId}/content, autenticado
// com sessão real (cookie JSESSIONID + CSRF de duplo envio), contra o SEU
// AMBIENTE LOCAL.
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
    // Operações principais do cenário medido, sob carga (loop de VUs):
    'http_req_duration{operation:list_books}': ['p(95)<500'],
    'http_req_duration{operation:load_outline}': ['p(95)<500'],
    'http_req_duration{operation:load_scene}': ['p(95)<500'],
    'http_req_duration{operation:save_scene}': ['p(95)<500'],
    // Autenticação/setup/teardown: fora do loop medido (rodam 1x ou VUS
    // vezes, nunca sob a carga em regime), orçamento mais folgado só para
    // pegar uma chamada realmente travada — e, por terem threshold, o k6
    // passa a reportar as métricas dessas tags separadas no summary.
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

function jsonHeaders(authHeaders, extra) {
  return Object.assign({ 'Content-Type': 'application/json' }, authHeaders, extra || {});
}

/**
 * Tenta remover o livro sintético órfão antes de relançar a falha original.
 * Chamado quando seção/capítulo/cena falham depois que o livro já existe:
 * `teardown()` não roda se `setup()` lança, então sem isso o livro (e o que
 * já tiver sido criado sob ele) fica para trás.
 */
function cleanupOrphanedBook(authHeaders, bookId, marker, originalError) {
  console.error(`setup() falhou após criar o livro sintético ${marker} (${bookId}): ${originalError.message}`);
  const cleanupRes = http.del(`${BASE}/api/books/${bookId}`, null, {
    headers: jsonHeaders(authHeaders),
    tags: { operation: 'setup_cleanup_book', name: 'DELETE /api/books/{bookId}' },
  });
  if (cleanupRes.status === 204) {
    console.error(`Limpeza automática removeu o livro órfão ${marker} (${bookId}).`);
  } else {
    console.error(
      `Limpeza automática NÃO removeu o livro órfão ${marker} (${bookId}) — status ${cleanupRes.status}. ` +
      `Limpeza manual necessária, veja loadtest/README.md.`
    );
  }
  throw originalError;
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

  const ping = http.get(`${BASE}/ping`, { tags: { operation: 'smoke', name: 'GET /ping' } });
  if (ping.status !== 200) {
    throw new Error(`Smoke check falhou: GET /ping retornou ${ping.status}. Suba o ambiente antes de rodar a carga.`);
  }

  const csrfRes = http.get(`${BASE}/api/auth/csrf`, { tags: { operation: 'auth_csrf', name: 'GET /api/auth/csrf' } });
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
      tags: { operation: 'auth_login', name: 'POST /api/auth/login' },
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
    { headers: jsonHeaders(authHeaders), tags: { operation: 'setup_create_book', name: 'POST /api/books' } }
  );
  if (bookRes.status !== 201) {
    throw new Error(`Criação do livro sintético falhou com status ${bookRes.status}.`);
  }
  const bookId = bookRes.json('id');

  // A partir daqui o livro já existe: qualquer falha abaixo precisa limpar
  // esse livro órfão antes de reprovar o teste, porque teardown() nunca roda
  // quando setup() lança.
  let sceneIds;
  try {
    const sectionRes = http.post(
      `${BASE}/api/books/${bookId}/sections`,
      JSON.stringify({ title: `${marker}-section`, type: 'PART', sortOrder: 0 }),
      { headers: jsonHeaders(authHeaders), tags: { operation: 'setup_create_section', name: 'POST /api/books/{bookId}/sections' } }
    );
    if (sectionRes.status !== 201) {
      throw new Error(`Criação da seção sintética falhou com status ${sectionRes.status}.`);
    }
    const sectionId = sectionRes.json('id');

    const chapterRes = http.post(
      `${BASE}/api/sections/${sectionId}/chapters`,
      JSON.stringify({ title: `${marker}-chapter`, sortOrder: 0 }),
      { headers: jsonHeaders(authHeaders), tags: { operation: 'setup_create_chapter', name: 'POST /api/sections/{sectionId}/chapters' } }
    );
    if (chapterRes.status !== 201) {
      throw new Error(`Criação do capítulo sintético falhou com status ${chapterRes.status}.`);
    }
    const chapterId = chapterRes.json('id');

    // Uma cena por VU máximo, para que cada VU escreva sempre na própria cena.
    sceneIds = [];
    for (let i = 0; i < VUS; i++) {
      const sceneRes = http.post(
        `${BASE}/api/chapters/${chapterId}/scenes`,
        JSON.stringify({ title: `${marker}-scene-${i + 1}`, sortOrder: i }),
        { headers: jsonHeaders(authHeaders), tags: { operation: 'setup_create_scene', name: 'POST /api/chapters/{chapterId}/scenes' } }
      );
      if (sceneRes.status !== 201) {
        throw new Error(`Criação da cena sintética ${i + 1}/${VUS} falhou com status ${sceneRes.status}.`);
      }
      sceneIds.push(sceneRes.json('id'));
    }
  } catch (err) {
    cleanupOrphanedBook(authHeaders, bookId, marker, err);
  }

  console.log(`Setup concluído: livro ${marker} (${bookId}) com ${sceneIds.length} cena(s).`);

  return { authHeaders, bookId, runId, marker, sceneIds };
}

function thinkTime() {
  return THINK_TIME_MIN_S + Math.random() * (THINK_TIME_MAX_S - THINK_TIME_MIN_S);
}

/**
 * list_books, load_outline e load_scene são pré-requisitos sequenciais do
 * fluxo real (não dá para abrir uma cena sem antes navegar até o outline, e
 * não dá para chegar no outline sem antes listar os livros). Uma falha em
 * qualquer um encerra a iteração imediatamente: nenhuma etapa seguinte roda,
 * e em particular nenhum PATCH é enviado usando um estado que o VU nunca
 * confirmou ter lido — uma falha de leitura não pode virar tráfego de
 * escrita nem distorcer a latência/erro de save_scene.
 */
export default function (data) {
  const { authHeaders, bookId, marker, sceneIds } = data;

  const listRes = http.get(`${BASE}/api/books`, {
    headers: jsonHeaders(authHeaders),
    tags: { operation: 'list_books', name: 'GET /api/books' },
  });
  if (!check(listRes, { 'list_books status 200': (r) => r.status === 200 })) {
    sleep(thinkTime());
    return;
  }

  const outlineRes = http.get(`${BASE}/api/books/${bookId}/outline`, {
    headers: jsonHeaders(authHeaders),
    tags: { operation: 'load_outline', name: 'GET /api/books/{bookId}/outline' },
  });
  if (!check(outlineRes, { 'load_outline status 200': (r) => r.status === 200 })) {
    sleep(thinkTime());
    return;
  }

  // Uma cena fixa por VU (índice estável em __VU), nunca compartilhada entre VUs.
  const sceneId = sceneIds[(__VU - 1) % sceneIds.length];

  // A revisão vem sempre desta leitura, nunca de um cache local: assim uma
  // falha ambígua no PATCH anterior (commitou no servidor mas a resposta se
  // perdeu, por exemplo) nunca produz uma sequência de conflitos de revisão
  // artificiais — a próxima escrita sempre parte do estado real do servidor.
  const sceneRes = http.get(`${BASE}/api/scenes/${sceneId}`, {
    headers: jsonHeaders(authHeaders),
    tags: { operation: 'load_scene', name: 'GET /api/scenes/{sceneId}' },
  });
  if (!check(sceneRes, { 'load_scene status 200': (r) => r.status === 200 })) {
    sleep(thinkTime());
    return;
  }
  const revision = sceneRes.json('contentRevision');

  const contentText = `${marker} conteúdo sintético VU${__VU} iter${__ITER} ${Date.now()}`;
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
    { headers: jsonHeaders(authHeaders), tags: { operation: 'save_scene', name: 'PATCH /api/scenes/{sceneId}/content' } }
  );
  check(saveRes, { 'save_scene status 200': (r) => r.status === 200 });

  sleep(thinkTime());
}

export function teardown(data) {
  const res = http.del(`${BASE}/api/books/${data.bookId}`, null, {
    headers: jsonHeaders(data.authHeaders),
    tags: { operation: 'teardown_delete_book', name: 'DELETE /api/books/{bookId}' },
  });
  if (res.status !== 204) {
    // Reprova a execução em vez de só registrar: um teardown que falha
    // silenciosamente deixa o livro LOADTEST- para trás e contamina medições
    // futuras sem que `http_req_failed`/`checks` acusem nada, já que essa
    // chamada acontece fora do loop de VUs medido.
    throw new Error(
      `Teardown falhou: DELETE /api/books/${data.bookId} retornou ${res.status} (esperado 204). ` +
      `Livro sintético ${data.marker} não removido — limpeza manual necessária, veja loadtest/README.md.`
    );
  }
  console.log(`Teardown concluído: livro ${data.marker} (${data.bookId}) removido.`);
}

/**
 * Mantém só o que é seguro versionar/ler no terminal: `setup_data` inclui o
 * retorno inteiro de setup(), então sem isto o cookie de sessão e o token
 * CSRF da execução iriam para qualquer summary (stdout ou arquivo), inclusive
 * quando um threshold falha. Allowlist, não denylist — um campo novo que
 * alguém adicionar ao retorno de setup() no futuro fica de fora por padrão
 * em vez de vazar por omissão.
 */
function redactSummary(data) {
  const clone = JSON.parse(JSON.stringify(data));
  if (clone.setup_data) {
    clone.setup_data = {
      runId: clone.setup_data.runId,
      marker: clone.setup_data.marker,
      bookId: clone.setup_data.bookId,
      sceneCount: Array.isArray(clone.setup_data.sceneIds) ? clone.setup_data.sceneIds.length : undefined,
    };
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
  ['list_books', 'load_outline', 'load_scene', 'save_scene'].forEach((op) => {
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
// assume o controle de toda a saída do k6 (inclusive o texto no terminal),
// então a redação acontece uma vez aqui e vale para qualquer destino.
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
