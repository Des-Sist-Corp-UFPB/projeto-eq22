import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';
import exec from 'k6/execution';

// Nenhuma falha de autenticação de VU é tolerada: rate==1 reprova o run
// inteiro mesmo com apenas uma VU falhando (ver authenticateVuOnce()) — o
// teste não pode "passar" silenciosamente operando com menos sessões que o
// VUS pretendido.
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
// Cada VU roda um ÚNICO "turno" k6 (options.scenarios.default,
// executor per-vu-iterations, iterations:1) contendo um laço interno manual
// de "turnos" lógicos (list_books → load_outline → load_scene → debounce →
// save_scene → refresh_outline_after_save), em vez de várias iterações k6
// separadas — ver o comentário de MAIN_LOOP_MAX_DURATION abaixo para o motivo:
// o runtime do k6 v2.1.0 sempre drena TODAS as Promises pendentes de uma
// iteração (mesmo as nunca aguardadas) antes de considerá-la concluída, então
// qualquer refresh_outline_after_save disparado dentro de uma iteração k6
// tradicional vira barreira da iteração seguinte sempre que for mais lento
// que o think time — contrariando o fire-and-forget real do frontend
// (confirmado empiricamente, não por leitura da documentação: ver PR #141).
//
// IMPORTANTE: NUNCA aponte para Render, produção ou o servidor acadêmico
// compartilhado (ex.: https://eqNN.dsc.rodrigor.com) — o guard de host abaixo
// recusa qualquer destino fora da lista local por padrão. Veja loadtest/README.md.
// ─────────────────────────────────────────────────────────────────────────────

const BASE = (__ENV.BASE_URL || 'http://localhost:8085').replace(/\/+$/, '');

// Hosts controlados aceitos sem override. Qualquer outro — incluindo o
// hostname genérico `backend` do Compose — exige
// ALLOW_UNSAFE_TARGET=eu-autorizo-um-destino-externo explicitamente:
// `parseSafeHost()` só resolve a autoridade léxica da URL, nunca o endereço
// para o qual `backend` de fato resolve; numa máquina com domain search DNS
// fora da rede do Compose pretendida, `backend` pode apontar para um serviço
// remoto não relacionado, e essa allowlist não teria como diferenciar.
const SAFE_HOSTS = ['localhost', '127.0.0.1', '::1', 'host.docker.internal'];
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

const LOGIN_EMAIL = __ENV.LOAD_TEST_EMAIL || 'autor-a@iwrite.local';
const LOGIN_PASSWORD = __ENV.LOAD_TEST_PASSWORD;

/**
 * Valida um valor numérico (THINK_TIME_MIN_S/THINK_TIME_MAX_S/
 * AUTO_SAVE_DELAY_MS) antes de montar `options` ou disparar qualquer
 * requisição: um valor não numérico ou negativo faria o delay
 * correspondente sortear/aplicar um valor inválido/zero silenciosamente,
 * inflando a taxa de requisições da VU sem que nada nas métricas explicasse
 * por quê.
 */
function validateNonNegativeNumber(raw, value, name) {
  if (!Number.isFinite(value)) {
    throw new Error(`${name} inválido: "${raw}" não é um número finito.`);
  }
  if (value < 0) {
    throw new Error(`${name} inválido: "${raw}" é negativo.`);
  }
  return value;
}

const THINK_TIME_MIN_S_RAW = __ENV.THINK_TIME_MIN_S || '0.3';
const THINK_TIME_MAX_S_RAW = __ENV.THINK_TIME_MAX_S || '1';
const THINK_TIME_MIN_S = validateNonNegativeNumber(THINK_TIME_MIN_S_RAW, Number(THINK_TIME_MIN_S_RAW), 'THINK_TIME_MIN_S');
const THINK_TIME_MAX_S = validateNonNegativeNumber(THINK_TIME_MAX_S_RAW, Number(THINK_TIME_MAX_S_RAW), 'THINK_TIME_MAX_S');
if (THINK_TIME_MIN_S > THINK_TIME_MAX_S) {
  throw new Error(`THINK_TIME_MIN_S (${THINK_TIME_MIN_S}s) não pode ser maior que THINK_TIME_MAX_S (${THINK_TIME_MAX_S}s).`);
}

// Debounce do AUTO_SAVE: o editor real só dispara o PATCH depois de
// AUTO_SAVE_DELAY_MS sem nova alteração — mesmo debounce que
// CONTENT_AUTOSAVE_DELAY_MS em
// web/src/features/scenes/components/scene-editor.tsx. Não é think time (que
// modela o intervalo de atividade do usuário DEPOIS do save concluído): é o
// atraso entre a última tecla digitada e o envio do PATCH. Sem modelar isso,
// a VU dispara AUTO_SAVE mais rápido do que qualquer editor real permite.
// Valores menores que 1200 são modo de estresse deliberado, não baseline
// realista.
const AUTO_SAVE_DELAY_MS_RAW = __ENV.AUTO_SAVE_DELAY_MS || '1200';
const AUTO_SAVE_DELAY_MS = validateNonNegativeNumber(AUTO_SAVE_DELAY_MS_RAW, Number(AUTO_SAVE_DELAY_MS_RAW), 'AUTO_SAVE_DELAY_MS');

// Um único par (número, unidade) do formato de duração do k6 (Go duration:
// ms/s/m/h, parte fracionária opcional) — fonte única reaproveitada tanto
// pela validação de formato completo (DURATION_FULL_RE) quanto pela extração
// de cada token em k6DurationToMs(), para não duplicar a mesma regra em dois
// regexes que poderiam divergir.
const DURATION_TOKEN_SOURCE = '([0-9]+(?:\\.[0-9]+)?)(ms|s|m|h)';
const DURATION_FULL_RE = new RegExp(`^(?:${DURATION_TOKEN_SOURCE})+$`);
const DURATION_TOKEN_RE = new RegExp(DURATION_TOKEN_SOURCE, 'g');
const DURATION_UNIT_MS = { ms: 1, s: 1000, m: 60 * 1000, h: 60 * 60 * 1000 };

/**
 * Valida um valor de duração no formato do k6 (Go duration: unidades
 * ms/s/m/h encadeadas, ex. "10m", "90s", "1h30m") antes de aceitar
 * WARMUP_DURATION/STEADY_DURATION/RAMPDOWN_DURATION/SETUP_TIMEOUT/TEARDOWN_TIMEOUT
 * — um valor malformado deve falhar cedo e de forma clara, não virar
 * silenciosamente o timeout padrão de 60s do k6 nem um cálculo de fase
 * incorreto em k6DurationToMs().
 */
function validateK6Duration(raw, name) {
  if (!DURATION_FULL_RE.test(raw)) {
    throw new Error(`${name} inválido: "${raw}". Use um formato de duração do k6, unidades ms/s/m/h encadeadas, com parte fracionária opcional (ex.: "10m", "90s", "1h30m", "0.5m", "1.5s").`);
  }
  return raw;
}

/**
 * Converte uma duração já validada por validateK6Duration() para
 * milissegundos, somando cada token (número, unidade) capturado por
 * DURATION_TOKEN_RE. Nunca chamada sobre uma duração não validada — um valor
 * malformado deve falhar em validateK6Duration(), não virar 0/NaN aqui.
 */
function k6DurationToMs(raw) {
  let totalMs = 0;
  let match;
  DURATION_TOKEN_RE.lastIndex = 0;
  while ((match = DURATION_TOKEN_RE.exec(raw)) !== null) {
    totalMs += parseFloat(match[1]) * DURATION_UNIT_MS[match[2]];
  }
  return totalMs;
}

const WARMUP_DURATION = validateK6Duration(__ENV.WARMUP_DURATION || '30s', 'WARMUP_DURATION');
const STEADY_DURATION = validateK6Duration(__ENV.STEADY_DURATION || '2m', 'STEADY_DURATION');
const RAMPDOWN_DURATION = validateK6Duration(__ENV.RAMPDOWN_DURATION || '30s', 'RAMPDOWN_DURATION');
// Só warmup/steady são necessários para classificar a fase (ver currentPhase()
// abaixo) — o que sobra depois de warmup+steady já é ramp_down por exclusão.
const WARMUP_MS = k6DurationToMs(WARMUP_DURATION);
const STEADY_MS = k6DurationToMs(STEADY_DURATION);
const RAMPDOWN_MS = k6DurationToMs(RAMPDOWN_DURATION);

// Timeout explícito por requisição das 5 operações principais do laço
// (list_books/load_outline/load_scene/save_scene/refresh_outline_after_save):
// sem isso, cada requisição usaria o timeout padrão do k6 (60s), que sozinho
// já dominaria o orçamento de drenagem do último turno calculado abaixo
// (MAIN_LOOP_GRACE_MS) e o infla muito além do que um ambiente local saudável
// precisa. Formato de duração do k6 (validateK6Duration) — o default é
// pensado para localhost, não para uma rede real de alta latência.
const HTTP_REQUEST_TIMEOUT = validateK6Duration(__ENV.HTTP_REQUEST_TIMEOUT || '10s', 'HTTP_REQUEST_TIMEOUT');
const HTTP_REQUEST_TIMEOUT_MS = k6DurationToMs(HTTP_REQUEST_TIMEOUT);

// O executor per-vu-iterations (ver options.scenarios abaixo) inicia as VUS
// VUs simultaneamente em exec.scenario.startTime — sem o escalonamento
// automático de VU que options.stages/ramping-vus fazia. default() escalona
// manualmente sua própria ativação (rampa de subida) e desativação (rampa de
// descida) por VU, proporcional ao índice __VU (1..VUS): a k-ésima VU (por
// índice, 1..VUS) entra em t=(k/VUS)×WARMUP_MS e sai em
// t=WARMUP_MS+STEADY_MS+(k/VUS)×RAMPDOWN_MS.
//
// Essa é a discretização exata do alvo contínuo VUs_ativas(t)=VUS×t/WARMUP_MS
// (rampa linear 0→VUS): o alvo contínuo cruza o inteiro k exatamente em
// t=(k/VUS)×WARMUP_MS, então a k-ésima VU só "conta" a partir desse instante.
// Usar k/VUS (em vez de (k-1)/VUS, usado antes) garante que a última VU
// (k=VUS) entre exatamente em WARMUP_MS — não antes — e saia exatamente no
// fim de RAMPDOWN_MS — não antes —, cobrindo os dois extremos do intervalo
// configurado sem cauda ociosa. (k-1)/VUS varia só de 0 a (VUS-1)/VUS, nunca
// alcança 1, e por isso deixava a última VU desativar RAMPDOWN_MS/VUS antes
// do fim nominal da rampa (ex.: VUS=2/ramp=5s desativava aos 2.5s de um
// ramp-down de 5s; achado do Codex, PR #141).
//
// Tabela de verificação (offset da última VU, k=VUS, em fração da duração
// configurada — sempre 1, ou seja, sempre no limite exato):
//   VUS=1:  ativa em 1×WARMUP_MS/1  = WARMUP_MS  | desativa em RAMPDOWN_MS/1  = RAMPDOWN_MS
//   VUS=2:  ativa em 2×WARMUP_MS/2  = WARMUP_MS  | desativa em RAMPDOWN_MS
//   VUS=10: ativa em 10×WARMUP_MS/10 = WARMUP_MS | desativa em RAMPDOWN_MS
//   VUS=30: ativa em 30×WARMUP_MS/30 = WARMUP_MS | desativa em RAMPDOWN_MS
// (confirmado também por instrumentação temporária não versionada — ver PR
// #141 — logando activation/deactivation time real para VUS=1/2/10.)
//
// É uma aproximação discreta EQUIVALENTE ao ramping-vus só nesses pontos de
// ativação/desativação — não uma cópia bit-a-bit do algoritmo interno do
// executor; a ordem exata de qual VU entra/sai primeiro pode diferir.
function activationOffsetMs(vuIndex) {
  return (vuIndex / VUS) * WARMUP_MS;
}
function deactivationOffsetMs(vuIndex) {
  return WARMUP_MS + STEADY_MS + (vuIndex / VUS) * RAMPDOWN_MS;
}

// Folga além de warmup+steady+rampdown antes do k6 interromper à força a
// iteração única de uma VU — nunca usada para estender as fases medidas em si
// (currentPhase() só olha para WARMUP_MS/STEADY_MS, nunca para este valor).
//
// O laço de default() só verifica o limite ANTES de iniciar um turno (nunca
// interrompe um turno em andamento), então o pior caso é um turno iniciado a
// poucos milissegundos de deactivateAt, que ainda precisa completar, em
// sequência: list_books + load_outline + load_scene (3 leituras síncronas,
// cada uma limitada a HTTP_REQUEST_TIMEOUT_MS), o debounce do AUTO_SAVE
// (AUTO_SAVE_DELAY_MS), o PATCH save_scene (mais um HTTP_REQUEST_TIMEOUT_MS)
// e o think time do próprio turno (até THINK_TIME_MAX_S) — mais a drenagem
// do refresh_outline_after_save fire-and-forget que esse turno disparou.
//
// O refresh NÃO soma um HTTP_REQUEST_TIMEOUT_MS por turno pendente: não
// importa quantos refreshes de turnos anteriores ainda estejam em voo quando
// a VU retorna, todos — inclusive o do último turno — já estão limitados ao
// mesmo deadline individual (o instante em que cada um foi disparado +
// HTTP_REQUEST_TIMEOUT_MS), então o pior caso de drenagem final é UM
// HTTP_REQUEST_TIMEOUT_MS a partir do último refresh disparado, nunca a soma
// de vários.
const MAIN_LOOP_MARGIN_MS = 5000; // margem técnica: agendamento de VU, parsing de JSON, GC do k6 — não modelado nos termos acima
const MAIN_LOOP_GRACE_MS =
  4 * HTTP_REQUEST_TIMEOUT_MS + // list_books + load_outline + load_scene + save_scene, síncronos e sequenciais
  AUTO_SAVE_DELAY_MS +
  THINK_TIME_MAX_S * 1000 +
  HTTP_REQUEST_TIMEOUT_MS + // drenagem do refresh_outline_after_save pendente
  MAIN_LOOP_MARGIN_MS;
const MAIN_LOOP_MAX_DURATION = `${WARMUP_MS + STEADY_MS + RAMPDOWN_MS + MAIN_LOOP_GRACE_MS}ms`;

// setup() cria 1 livro/seção/capítulo/cena por VU, em loop serial: o número
// de requisições (e portanto o tempo de setup) cresce com VUS, então o
// timeout de 60s padrão do k6 para setup()/teardown() não escala. Os
// defaults abaixo cobrem folgadamente VUS na casa da centena em ambiente
// local; suba SETUP_TIMEOUT/TEARDOWN_TIMEOUT explicitamente para VUS maior.
const SETUP_TIMEOUT = validateK6Duration(__ENV.SETUP_TIMEOUT || '10m', 'SETUP_TIMEOUT');
const TEARDOWN_TIMEOUT = validateK6Duration(__ENV.TEARDOWN_TIMEOUT || '10m', 'TEARDOWN_TIMEOUT');

// Janela e intervalo de poll da recuperação de livros órfãos (ver
// recoverOrphanedBookIds()): um POST /api/books que deu timeout pode
// persistir no servidor alguns instantes depois da resposta se perder — a
// varredura precisa continuar tentando por essa janela, não parar na
// primeira consulta vazia. Fixo (não configurável por env): é um detalhe
// interno do caminho de erro do setup(), não um parâmetro de carga.
const ORPHAN_RECOVERY_WINDOW_MS = 5000;
const ORPHAN_RECOVERY_POLL_INTERVAL_S = 0.5;

export const options = {
  setupTimeout: SETUP_TIMEOUT,
  teardownTimeout: TEARDOWN_TIMEOUT,
  // per-vu-iterations (não options.stages/ramping-vus): cada VU roda 1 única
  // iteração k6, contendo o laço interno manual de turnos com rampa de
  // subida/descida auto-gerenciada — ver o comentário de
  // activationOffsetMs()/deactivationOffsetMs() acima para o porquê. O nome
  // do cenário continua 'default', então a tag `scenario` nas métricas não muda.
  scenarios: {
    default: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: 1,
      maxDuration: MAIN_LOOP_MAX_DURATION,
    },
  },
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
    // inteiro — o teste não pode passar operando com menos sessões que o
    // VUS pretendido (ver authenticateVuOnce()).
    vu_auth_success: ['rate==1'],
    // Operações principais do cenário medido, restritas à fase estável
    // (phase:steady, ver currentPhase()): os estágios de warmup/rampdown
    // (WARMUP_DURATION/RAMPDOWN_DURATION) operam com menos VUs que o pico e
    // misturariam amostras de latência mais baixa nesses percentis, mascarando
    // se o teto de 500ms se sustenta sob o VUS pretendido de verdade.
    'http_req_duration{operation:list_books,phase:steady}': ['p(95)<500'],
    'http_req_duration{operation:load_outline,phase:steady}': ['p(95)<500'],
    'http_req_duration{operation:load_scene,phase:steady}': ['p(95)<500'],
    'http_req_duration{operation:save_scene,phase:steady}': ['p(95)<500'],
    // Refetch do outline que o frontend real dispara (invalidateQueries) após
    // um save_scene bem-sucedido, em fire-and-forget genuíno — ver runTurn().
    'http_req_duration{operation:refresh_outline_after_save,phase:steady}': ['p(95)<500'],
    // Erro por operação, restrito à fase estável: os thresholds globais
    // (http_req_failed/checks acima) diluem falhas concentradas numa única
    // operação entre as demais requisições da iteração — com 5 operações por
    // iteração, uma falha isolada em save_scene também derruba o
    // refresh_outline_after_save correspondente (ver default()), mas ainda
    // assim fica abaixo de 1% no total das ~5 operações. Cada operação
    // principal precisa da própria taxa de erro para que uma operação
    // degradada não passe escondida atrás das outras quatro saudáveis.
    'http_req_failed{operation:list_books,phase:steady}': ['rate<0.01'],
    'http_req_failed{operation:load_outline,phase:steady}': ['rate<0.01'],
    'http_req_failed{operation:load_scene,phase:steady}': ['rate<0.01'],
    'http_req_failed{operation:save_scene,phase:steady}': ['rate<0.01'],
    'http_req_failed{operation:refresh_outline_after_save,phase:steady}': ['rate<0.01'],
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
  // 'count' habilita ler diretamente de cada Trend com threshold (inclusive
  // http_req_duration{operation:save_scene,phase:steady}) quantas amostras
  // ela recebeu — é a fonte de verdade usada em resultado.json para separar
  // turnos_steady (só phase:steady) de turnos_execucao_total (checks/5, todas
  // as fases, ver loadtest/README.md §8) sem precisar de um Counter dedicado.
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
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
 * Autentica no jar explicitamente passado. k6 recicla o "jar corrente" (o
 * que `http.cookieJar()` devolve) a cada chamada de nível superior — não é o
 * jar persistente "por VU" que a documentação sugere à primeira leitura.
 * Por isso login() nunca usa o jar implícito — sempre recebe e devolve a
 * autenticação no jar que o chamador guardou (setup()/teardown() num
 * `const jar` local; a VU no jar devolvido por `authenticateVuOnce()`,
 * reaproveitado por todos os turnos do laço interno de default() via
 * `{ jar }` explícito em toda requisição). Nunca retorna nem guarda o
 * cookie/token numa variável separada do jar — quem precisa do header CSRF
 * lê de volta do jar, no momento da requisição, via `authHeaders(jar)`.
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

/**
 * Login único por VU, tentado exatamente uma vez, no topo da (única)
 * iteração k6 daquela VU (default() agora roda 1x por VU — ver
 * options.scenarios.default/MAIN_LOOP_MAX_DURATION acima) — não em setup().
 * O jar retornado é passado explicitamente (`{ jar }`) em toda requisição do
 * laço interno principal (ver comentário de `login()`). Cada VU tem sua
 * própria sessão de servidor real, refletindo VUS sessões independentes do
 * mesmo autor editando livros distintos.
 *
 * NUNCA re-tenta. Antes (quando default() era chamada de novo a cada
 * iteração k6), uma falha de autenticação persistente virava uma tempestade
 * de retries: cada iteração subsequente da mesma VU tentava o handshake de
 * duas requisições de novo após só 0.3-1s de think time, consumindo o
 * orçamento de rate-limit de login da conta/origem e podendo impedir até o
 * login de teardown() (achado do Codex, PR #141). Como agora só existe UMA
 * chamada a default() por VU, uma falha aqui já É terminal por construção —
 * não há "próxima iteração" desta VU para retentar, e vu_auth_success só é
 * registrada esta ÚNICA vez, nunca repetidamente. O run inteiro ainda é
 * reprovado (threshold `vu_auth_success: rate==1`, sem tolerância), mas sem
 * gerar tráfego de retry artificial nem competir pelo orçamento de login que
 * teardown() precisa para limpar os livros sintéticos das outras VUs.
 */
function authenticateVuOnce() {
  const jar = http.cookieJar();
  try {
    login(jar);
    vuAuthSuccess.add(true);
    return jar;
  } catch (err) {
    console.error(`VU ${__VU}: falha ao autenticar (sem retry nesta execução): ${err.message}`);
    vuAuthSuccess.add(false);
    return null;
  }
}

/**
 * Recupera livros possivelmente órfãos quando a resposta de um POST
 * /api/books se perdeu (timeout, erro de rede) antes de
 * createdBookIds.push(bookId) — o livro pode ter sido persistido no servidor
 * mesmo assim, e como teardown() nunca roda depois de setup() lançar, esse
 * ID nunca apareceria em lugar nenhum sem esta varredura. Lista os livros da
 * conta e casa só pelo marcador EXATO desta execução (prefixo
 * `LOADTEST-${runId}-vu`) — nunca por `LOADTEST-` genérico, porque outra
 * execução com outro runId pode estar rodando ao mesmo tempo na mesma conta
 * e não pode ser tocada por esta limpeza. Funciona mesmo quando
 * createdBookIds está vazio (a própria primeira criação de livro é que ficou
 * ambígua).
 *
 * Uma única consulta imediata não basta: se o handler Spring do POST que deu
 * timeout ainda estiver terminando sua transação, essa consulta roda ANTES
 * do commit e não vê o livro — ele só aparece no servidor um instante
 * depois, sem teardown() para removê-lo. Por isso a varredura repete a
 * consulta em intervalos curtos (ORPHAN_RECOVERY_POLL_INTERVAL_S) por uma
 * janela limitada (ORPHAN_RECOVERY_WINDOW_MS), acumulando IDs sem duplicar
 * (Set), mesmo que alguma consulta no meio do caminho volte vazia — e faz
 * uma última consulta ao final da janela antes de desistir. A janela é
 * limitada por tempo (Date.now() >= deadline), nunca por uma condição que
 * dependa da resposta do servidor, então o loop sempre termina.
 */
function recoverOrphanedBookIds(jar, runId, createdBookIds) {
  const marker = `LOADTEST-${runId}-vu`;
  const recovered = new Set(createdBookIds);

  function scanOnce() {
    const listRes = http.get(`${BASE}/api/books`, {
      jar,
      headers: jsonHeaders(jar),
      tags: { operation: 'setup_recover_books', name: 'GET /api/books' },
    });
    if (listRes.status !== 200) {
      console.error(`Recuperação de órfãos (runId ${runId}): GET /api/books retornou ${listRes.status}.`);
      return;
    }
    (listRes.json() || []).forEach((book) => {
      if (book && typeof book.title === 'string' && book.title.indexOf(marker) === 0) {
        recovered.add(book.id);
      }
    });
  }

  const deadline = Date.now() + ORPHAN_RECOVERY_WINDOW_MS;
  scanOnce();
  while (Date.now() < deadline) {
    sleep(ORPHAN_RECOVERY_POLL_INTERVAL_S);
    scanOnce();
  }

  return Array.from(recovered);
}

/**
 * Remove todos os livros já criados nesta execução de setup() antes de
 * relançar o erro original — teardown() nunca roda quando setup() lança, e
 * agora há até VUS livros órfãos possíveis (um por VU já provisionada), não
 * só um. `bookIds` já inclui qualquer órfão recuperado por
 * recoverOrphanedBookIds() antes desta chamada. Loga quais limpezas
 * falharam (só bookId + status HTTP, nunca sessão/CSRF) para permitir
 * limpeza manual pontual em vez de exigir varrer tudo que casa com
 * LOADTEST-.
 */
function cleanupBooks(jar, runId, bookIds, originalError) {
  console.error(`setup() falhou (runId ${runId}) após criar ${bookIds.length} livro(s) sintético(s): ${originalError.message}`);
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
 * autentica de novo, sozinha, no topo da sua própria (única) iteração
 * (authenticateVuOnce()). O retorno de setup() é só metadado não sensível
 * ([{ bookId, sceneId }, ...]): nenhum cookie, header ou CSRF sai daqui,
 * então nenhum caminho de summary do k6 — RESULT_PATH, --summary-export
 * nativo ou K6_SUMMARY_EXPORT — consegue vazar sessão, nem mesmo o caminho
 * nativo que ignora handleSummary() por completo.
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
  // Impresso ANTES do primeiro POST /api/books: se o setup() falhar mais
  // adiante (mesmo antes de criar qualquer livro), o runId já está no log
  // para permitir limpeza manual escopada (ver loadtest/README.md §5).
  console.log(`runId desta execução: ${runId}`);

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
        throw new Error(`Criação do livro sintético ${i + 1}/${VUS} falhou com status ${bookRes.status} (runId ${runId}).`);
      }
      const bookId = bookRes.json('id');
      createdBookIds.push(bookId);

      const sectionRes = http.post(
        `${BASE}/api/books/${bookId}/sections`,
        JSON.stringify({ title: `${marker}-section`, type: 'PART', sortOrder: 0 }),
        { jar, headers: jsonHeaders(jar), tags: { operation: 'setup_create_section', name: 'POST /api/books/{bookId}/sections' } }
      );
      if (sectionRes.status !== 201) {
        throw new Error(`Criação da seção sintética ${i + 1}/${VUS} falhou com status ${sectionRes.status} (runId ${runId}).`);
      }
      const sectionId = sectionRes.json('id');

      const chapterRes = http.post(
        `${BASE}/api/sections/${sectionId}/chapters`,
        JSON.stringify({ title: `${marker}-chapter`, sortOrder: 0 }),
        { jar, headers: jsonHeaders(jar), tags: { operation: 'setup_create_chapter', name: 'POST /api/sections/{sectionId}/chapters' } }
      );
      if (chapterRes.status !== 201) {
        throw new Error(`Criação do capítulo sintético ${i + 1}/${VUS} falhou com status ${chapterRes.status} (runId ${runId}).`);
      }
      const chapterId = chapterRes.json('id');

      const sceneRes = http.post(
        `${BASE}/api/chapters/${chapterId}/scenes`,
        JSON.stringify({ title: `${marker}-scene`, sortOrder: 0 }),
        { jar, headers: jsonHeaders(jar), tags: { operation: 'setup_create_scene', name: 'POST /api/chapters/{chapterId}/scenes' } }
      );
      if (sceneRes.status !== 201) {
        throw new Error(`Criação da cena sintética ${i + 1}/${VUS} falhou com status ${sceneRes.status} (runId ${runId}).`);
      }

      resources.push({ bookId, sceneId: sceneRes.json('id') });
    }
  } catch (err) {
    cleanupBooks(jar, runId, recoverOrphanedBookIds(jar, runId, createdBookIds), err);
  }

  console.log(`Setup concluído: ${resources.length} livro(s) sintético(s) (1 por VU, runId ${runId}).`);
  return resources;
}

function thinkTime() {
  return THINK_TIME_MIN_S + Math.random() * (THINK_TIME_MAX_S - THINK_TIME_MIN_S);
}

// Substitui k6/sleep dentro de default()/runTurn(), que agora são async:
// sleep() bloqueia a thread e nunca deixaria o refresh_outline_after_save
// fire-and-forget de um turno anterior avançar em segundo plano (ver
// runTurn()). exec.scenario.startTime é o timestamp (ms desde epoch) de
// quando o cenário começou — currentPhase() usa isso, não __VU nem contagem
// de VUs ativas, porque o k6 não expõe "VUs atuais" por requisição.
function delay(seconds) {
  return new Promise((resolve) => {
    setTimeout(resolve, seconds * 1000);
  });
}

/**
 * Fase do estágio no instante da chamada: ramp_up (WARMUP_DURATION),
 * steady (STEADY_DURATION) ou ramp_down (RAMPDOWN_DURATION), pelo tempo
 * decorrido desde o início do cenário. Chamada de novo a cada requisição, no
 * momento exato do dispatch (list_books/load_outline/load_scene/save_scene/
 * refresh_outline_after_save) — NUNCA capturada uma vez só e reutilizada para
 * as demais. Antes (uma única `const phase = currentPhase()` no topo do
 * turno) uma iteração que começasse perto de uma borda de fase carregava essa
 * fase "congelada" em requisições disparadas bem depois — especialmente
 * save_scene, que só sai após o debounce de AUTO_SAVE_DELAY_MS (1200ms por
 * padrão): um turno iniciado no fim de steady podia entregar seu save_scene
 * já em ramp_down, mas ainda marcado phase:steady, contaminando os percentis
 * que os thresholds tratam como exclusivamente de pico (achado do Codex, PR
 * #141). Chamar currentPhase() no instante do dispatch de cada requisição
 * elimina essa contaminação por construção.
 */
function currentPhase() {
  const elapsedMs = Date.now() - exec.scenario.startTime;
  if (elapsedMs < WARMUP_MS) {
    return 'ramp_up';
  }
  if (elapsedMs < WARMUP_MS + STEADY_MS) {
    return 'steady';
  }
  return 'ramp_down';
}

/**
 * Um turno lógico completo: list_books → load_outline → load_scene →
 * debounce do AUTO_SAVE → save_scene → (se bem-sucedido)
 * refresh_outline_after_save disparado em fire-and-forget genuíno — ver
 * comentário grande no topo do arquivo para por que isso exige um laço manual
 * dentro de uma única iteração k6, em vez de uma nova iteração k6 por turno.
 *
 * list_books/load_outline/load_scene são pré-requisitos sequenciais do fluxo
 * real (não dá para abrir uma cena sem antes navegar até o outline, e não dá
 * para chegar no outline sem antes listar os livros). Uma falha em qualquer
 * um encerra o turno imediatamente (retorna sem chamar save_scene): nenhuma
 * etapa seguinte roda, e em particular nenhum PATCH é enviado usando um
 * estado que o VU nunca confirmou ter lido.
 *
 * `turn` substitui `__ITER` (que ficaria fixo em 0 — default() agora só tem
 * uma iteração k6 por VU) para alternar a contagem de palavras entre turnos
 * pares/ímpares: sem isso, todo save após o primeiro repetiria a mesma
 * contagem do anterior, então SceneService.updateContent() sempre calcularia
 * wordCountDelta=0 e WordCountEventService.shouldUpdateDailyRollup() nunca
 * atualizaria o progresso diário.
 *
 * Retorna sempre depois de aguardar o think time — o chamador (default())
 * decide se continua para o próximo turno ou encerra o laço.
 */
async function runTurn(jar, resource, turn) {
  const { bookId, sceneId } = resource;

  const listRes = http.get(`${BASE}/api/books`, {
    jar,
    headers: jsonHeaders(jar),
    timeout: HTTP_REQUEST_TIMEOUT,
    tags: { operation: 'list_books', name: 'GET /api/books', phase: currentPhase() },
  });
  if (!check(listRes, { 'list_books status 200': (r) => r.status === 200 })) {
    await delay(thinkTime());
    return;
  }

  const outlineRes = http.get(`${BASE}/api/books/${bookId}/outline`, {
    jar,
    headers: jsonHeaders(jar),
    timeout: HTTP_REQUEST_TIMEOUT,
    tags: { operation: 'load_outline', name: 'GET /api/books/{bookId}/outline', phase: currentPhase() },
  });
  if (!check(outlineRes, { 'load_outline status 200': (r) => r.status === 200 })) {
    await delay(thinkTime());
    return;
  }

  // A revisão vem sempre desta leitura, nunca de um cache local: assim uma
  // falha ambígua no PATCH anterior (commitou no servidor mas a resposta se
  // perdeu, por exemplo) nunca produz uma sequência de conflitos de revisão
  // artificiais — a próxima escrita sempre parte do estado real do servidor.
  const sceneRes = http.get(`${BASE}/api/scenes/${sceneId}`, {
    jar,
    headers: jsonHeaders(jar),
    timeout: HTTP_REQUEST_TIMEOUT,
    tags: { operation: 'load_scene', name: 'GET /api/scenes/{sceneId}', phase: currentPhase() },
  });
  if (!check(sceneRes, { 'load_scene status 200': (r) => r.status === 200 })) {
    await delay(thinkTime());
    return;
  }
  const revision = sceneRes.json('contentRevision');

  const extraWord = turn % 2 === 0 ? ' progresso' : '';
  const contentText = `LOADTEST conteúdo sintético VU ${__VU} turno ${turn}${extraWord}`;

  // Debounce do AUTO_SAVE (ver AUTO_SAVE_DELAY_MS acima): aguarda antes de
  // enviar o PATCH, simulando o intervalo que o editor real espera após a
  // última alteração antes de persistir. Nunca sleep() aqui — default() é
  // async e sleep() bloquearia a thread, o que impediria o refresh
  // fire-and-forget de um turno anterior (ainda pendente em segundo plano)
  // de avançar.
  await delay(AUTO_SAVE_DELAY_MS / 1000);

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
    { jar, headers: jsonHeaders(jar), timeout: HTTP_REQUEST_TIMEOUT, tags: { operation: 'save_scene', name: 'PATCH /api/scenes/{sceneId}/content', phase: currentPhase() } }
  );
  const saveSucceeded = check(saveRes, { 'save_scene status 200': (r) => r.status === 200 });

  // Espelha o frontend real: BookWorkspace mantém a query do outline ativa e
  // contentMutation.mutateAsync() dispara `void queryClient.invalidateQueries(...)`
  // (scene-editor.tsx) no sucesso do save — fire-and-forget genuíno, sem
  // bloquear a UI nem o think time seguinte. Só roda depois de um save
  // bem-sucedido — o frontend também só invalida o outline nesse caso.
  //
  // NUNCA `await` aqui, nem via Promise.all com o think time: confirmado
  // empiricamente (PR #141) que o k6 v2.1.0 drena TODAS as Promises de uma
  // iteração — mesmo as nunca aguardadas — antes de considerá-la concluída,
  // então qualquer forma de esperar por esta Promise (explícita ou via
  // Promise.all) dentro da MESMA iteração k6 sempre vira barreira. A correção
  // real não é a Promise em si, é rodá-la dentro do laço manual de
  // runTurn()/default() (uma única iteração k6 por VU): como o `await`
  // seguinte (thinkTime(), e depois o debounce do próximo turno) SÃO
  // aguardados, mas a Promise do refresh NÃO É, o turno seguinte começa
  // assim que o think time atual termina — o event loop só é forçado a
  // drenar a Promise pendente quando toda a VU (não o turno) termina. Isso
  // reproduz o mesmo overlap do frontend: o refresh de um turno pode
  // continuar em voo enquanto o próximo turno já está em andamento. status é
  // checado dentro do `.then()` (roda mesmo sem ninguém aguardar a Promise
  // diretamente) e `.catch()` evita qualquer rejeição não tratada.
  if (saveSucceeded) {
    http.asyncRequest('GET', `${BASE}/api/books/${bookId}/outline`, null, {
      jar,
      headers: jsonHeaders(jar),
      timeout: HTTP_REQUEST_TIMEOUT,
      tags: { operation: 'refresh_outline_after_save', name: 'GET /api/books/{bookId}/outline', phase: currentPhase() },
    }).then((refreshRes) => {
      check(refreshRes, { 'refresh_outline_after_save status 200': (r) => r.status === 200 });
    }).catch((err) => {
      console.error(`VU ${__VU} turno ${turn}: refresh_outline_after_save falhou: ${err.message}`);
    });
  }

  await delay(thinkTime());
}

/**
 * Uma única iteração k6 por VU (options.scenarios.default, executor
 * per-vu-iterations) contendo um laço manual de turnos — ver o comentário
 * grande no topo do arquivo e o de runTurn() para o porquê. Cada VU
 * escalona sua própria ativação (rampa de subida) e desativação (rampa de
 * descida) via activationOffsetMs()/deactivationOffsetMs(), reproduzindo a
 * curva agregada de VUs ativas que options.stages/ramping-vus produzia.
 *
 * Autenticação é tentada exatamente uma vez, sem retry (ver
 * authenticateVuOnce()) — uma falha encerra a VU inteira nesta única
 * iteração, sem gerar tráfego de retry artificial.
 *
 * Cada VU opera exclusivamente sobre o livro/cena no índice correspondente
 * a __VU (`data[__VU - 1]`), criados 1:1 por setup() — nunca um recurso
 * compartilhado com outra VU.
 */
export default async function (data) {
  await delay(activationOffsetMs(__VU) / 1000);

  const jar = authenticateVuOnce();
  if (!jar) {
    return;
  }

  const resource = data[__VU - 1];
  if (!resource) {
    console.error(`VU ${__VU}: nenhum livro provisionado por setup() (criou ${data.length}, VUS=${VUS}).`);
    return;
  }

  const deactivateAt = deactivationOffsetMs(__VU);
  let turn = 0;
  while (Date.now() - exec.scenario.startTime < deactivateAt) {
    await runTurn(jar, resource, turn);
    turn++;
  }
  // default() retorna aqui: o k6 drena qualquer refresh_outline_after_save
  // ainda pendente do último turno (ou dos últimos turnos, sob latência
  // suficientemente alta para empilhar mais de um) antes de considerar esta
  // VU verdadeiramente encerrada — nenhuma requisição é descartada
  // silenciosamente (ver comentário de runTurn()).
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
  // Só as amostras da fase estável (phase:steady) têm threshold, então só
  // essa combinação de tags vira sub-métrica rastreada pelo k6 (ver options.thresholds).
  ['list_books', 'load_outline', 'load_scene', 'save_scene', 'refresh_outline_after_save'].forEach((op) => {
    const trend = m[`http_req_duration{operation:${op},phase:steady}`];
    if (trend) {
      lines.push(`${op} steady p95: ${trend.values['p(95)'].toFixed(1)}ms`);
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
