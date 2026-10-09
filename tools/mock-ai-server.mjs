#!/usr/bin/env node
// Mock AI server for QA of the AI providers (docs/AI-PROVIDERS.md §14). Dependency-free Node 18+.
//
// Serves realistic STREAMING answers in the wire formats of every engine family the app speaks:
//   POST …/responses, …/agent                        OpenAI Responses (OpenAI, xAI by `grok-*` model, Perplexity via /agent)
//   POST …/models/{m}:streamGenerateContent?alt=sse   Gemini (text, groundingMetadata, Search Suggestions)
//   POST …/models/{m}:generateContent                 Gemini, one answer (the key test's "Antworte nur: OK")
//   POST …/chat/completions                           Chat Completions (OpenRouter, Mistral, DeepSeek, own server)
//   POST /v1/messages                                 Anthropic Messages SSE (Claude through the SDK)
//   GET  …/models, …/models/{m}, …/api-key, …/key, …/user/balance   key and connection checks
//        (Gemini: GET /v1beta/models is the key test's model list, with supportedGenerationMethods)
//
// Point the app at it in a debug build: Einstellungen → KI → "Entwickler: Basis-URL", e.g.
//   Claude      http://10.0.2.2:8787           (the SDK adds /v1/messages)
//   Gemini      http://10.0.2.2:8787/v1beta
//   all others  http://10.0.2.2:8787/v1        (own server: as its address)
// 10.0.2.2 is the host machine as seen from the Android emulator.
//
// Switches (combine with commas):
//   errors   400 401 402 403 404 429 500 503 529, and variants 400-workspace 400-model 429-quota 429-short
//            Gemini (GE §12.4): 401-auth-key (ACCESS_TOKEN_TYPE_UNSUPPORTED), 403-blocked (API_KEY_SERVICE_BLOCKED),
//            403-disabled (SERVICE_DISABLED), 403-restricted (API_KEY_ANDROID_APP_BLOCKED), 400-region
//            (FAILED_PRECONDITION: free tier not in your country), 400-key-echo (an invalid key echoed into every
//            field of the error body, GE §12.6); other families answer them with the base status
//   modes    slow, no-search, free-tier-search, free-tier-search-400, refusal, truncated, midstream-error,
//            live-reject, live-empty,
//            Gemini model fallback (GE §12.5): model-gone (gemini-3.8-flash: 404 NOT_FOUND, missing from the list),
//            model-no-quota (gemini-3.8-flash: 429 with a free-tier quota of 0)
// Set them
//   per request  path prefix /mock/<switches>/ (works in the app's base URL: http://10.0.2.2:8787/mock/429/v1beta)
//                or query ?mock=<switches> (curl)
//   globally     env MOCK_ERROR / MOCK_MODE, or at runtime: curl -X POST "http://localhost:8787/__mock?set=429,slow"
//                (curl -X POST http://localhost:8787/__mock/reset clears; GET /__mock shows the state)
// Any non-empty key works; a key containing "reject" gets the provider's refusal (401; xAI answers 400 like the real
// API, OC §4.8). Keys are never printed in full.
//
// Usage: node tools/mock-ai-server.mjs [--port 8787] [--host 127.0.0.1] [--delay 35]

import http from 'node:http';

const args = parseArgs(process.argv.slice(2));
const PORT = Number(args.port ?? process.env.MOCK_PORT ?? process.env.PORT ?? 8787);
const HOST = args.host ?? process.env.MOCK_HOST ?? '127.0.0.1';
const DELAY_MS = Number(args.delay ?? process.env.MOCK_DELAY_MS ?? 35);

const ERROR_SWITCHES = new Set(['400', '401', '402', '403', '404', '429', '500', '503', '529',
  '400-workspace', '400-model', '429-quota', '429-short',
  '401-auth-key', '403-blocked', '403-disabled', '403-restricted', '400-region', '400-key-echo']);
const MODE_SWITCHES = new Set(['slow', 'no-search', 'free-tier-search', 'free-tier-search-400', 'refusal', 'truncated',
  'midstream-error', 'live-reject', 'live-empty', 'model-gone', 'model-no-quota']);

/** The Gemini model the model-gone / model-no-quota modes refuse (the app's default, GE §12.5). */
const GONE_MODEL = 'gemini-3.8-flash';

/** The Gemini models the mock's model list shows (GE §3), each able to generateContent. */
const GEMINI_MODELS = ['gemini-3.8-flash', 'gemini-3.7-flash', 'gemini-3.5-flash-lite', 'gemini-3.1-flash-lite', 'gemini-3.1-pro-preview'];

/** Gemini-only error switches are answered by the other families with their base status ("403-blocked" → "403"). */
const baseSwitch = (error) => error.slice(0, 3);

const shared = { switches: splitSwitches([process.env.MOCK_ERROR, process.env.MOCK_MODE].filter(Boolean).join(',')) };

// ---------------------------------------------------------------------------------------------
// HTTP plumbing
// ---------------------------------------------------------------------------------------------

const server = http.createServer(async (req, res) => {
  let url;
  try {
    url = new URL(req.url, `http://${req.headers.host ?? 'localhost'}`);
  } catch {
    return json(res, 400, { error: 'bad url' });
  }
  const { path, switches } = resolveSwitches(url);
  const body = req.method === 'POST' ? await readJson(req) : null;
  const ctx = { req, res, url, path, switches, body, closed: false };
  req.on('close', () => { ctx.closed = true; });
  try {
    await route(ctx);
  } catch (e) {
    log('!!', `${req.method} ${url.pathname} failed: ${e?.stack ?? e}`);
    if (!res.headersSent) json(res, 500, { error: String(e) });
    else res.end();
  }
});

server.listen(PORT, HOST, () => {
  log('ok', `Mock AI server on http://${HOST}:${PORT} (emulator: http://10.0.2.2:${PORT})`);
  if (shared.switches.size) log('ok', `global switches: ${[...shared.switches].join(', ')}`);
});

async function route(ctx) {
  const { req, path, url } = ctx;
  const m = req.method;
  if (path === '/__mock' || path.startsWith('/__mock/')) return control(ctx);
  if (m === 'GET' && (path === '/' || path === '/health')) return json(ctx.res, 200, { ok: true, see: 'docs/AI-PROVIDERS.md §14' });

  let match;
  if (m === 'POST' && (match = path.match(/\/models\/([^/]+):(streamGenerateContent|generateContent)$/))) {
    return gemini(ctx, decodeURIComponent(match[1]), match[2] === 'streamGenerateContent' && url.searchParams.get('alt') === 'sse');
  }
  if (m === 'GET' && path.match(/\/v1beta\/models$/)) return geminiModelList(ctx);
  if (m === 'GET' && (match = path.match(/\/v1beta\/models\/([^/:]+)$/))) return geminiModel(ctx, decodeURIComponent(match[1]));
  if (m === 'POST' && path.endsWith('/messages/count_tokens')) return json(ctx.res, 200, { input_tokens: tokens(ctx.body) });
  if (m === 'POST' && path.endsWith('/messages')) return anthropic(ctx);
  if (m === 'POST' && path.endsWith('/responses')) return responses(ctx, ctx.body?.model?.startsWith('grok') ? 'xai' : 'openai');
  if (m === 'POST' && path.endsWith('/agent')) return responses(ctx, 'perplexity');
  if (m === 'POST' && path.endsWith('/chat/completions')) return chatCompletions(ctx);
  if (m === 'GET' && path.endsWith('/models')) return modelList(ctx);
  if (m === 'GET' && path.endsWith('/api-key')) return keyInfo(ctx, 'xai');
  if (m === 'GET' && path.endsWith('/key')) return keyInfo(ctx, 'openrouter');
  if (m === 'GET' && path.endsWith('/user/balance')) return keyInfo(ctx, 'deepseek');
  log('??', `${m} ${url.pathname} — no route`);
  return json(ctx.res, 404, { error: { message: `Mock: no route for ${m} ${path}`, type: 'not_found', code: 'not_found' } });
}

/** `/__mock` shows the global switches; POST `?set=429,slow` replaces them, `/__mock/reset` clears them. */
function control(ctx) {
  const { req, url, path, res } = ctx;
  if (req.method === 'POST' && path === '/__mock/reset') shared.switches = new Set();
  else if (req.method === 'POST') shared.switches = splitSwitches(url.searchParams.get('set') ?? '');
  return json(res, 200, {
    switches: [...shared.switches],
    errors: [...ERROR_SWITCHES],
    modes: [...MODE_SWITCHES],
    hint: 'per request: path prefix /mock/<switches>/ or ?mock=<switches>',
  });
}

/** Strips a `/mock/<switches>` prefix; switches = prefix + `?mock=` + the global ones. */
function resolveSwitches(url) {
  let path = url.pathname.replace(/\/+$/, '') || '/';
  const local = [];
  const prefix = path.match(/^\/mock\/([^/]+)(\/.*)?$/);
  if (prefix) {
    local.push(prefix[1]);
    path = prefix[2] || '/';
  }
  const query = url.searchParams.get('mock');
  if (query) local.push(query);
  const switches = new Set([...shared.switches, ...splitSwitches(local.join(','))]);
  return { path, switches };
}

function splitSwitches(text) {
  const out = new Set();
  for (const raw of decodeURIComponent(text).split(',')) {
    const s = raw.trim().toLowerCase();
    if (!s) continue;
    if (ERROR_SWITCHES.has(s) || MODE_SWITCHES.has(s)) out.add(s);
    else log('??', `unknown switch "${s}" ignored`);
  }
  return out;
}

const errorSwitch = (switches) => [...switches].find((s) => ERROR_SWITCHES.has(s)) ?? null;

async function readJson(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  const text = Buffer.concat(chunks).toString('utf8');
  if (!text) return {};
  try {
    return JSON.parse(text);
  } catch {
    return { _unparsable: text.slice(0, 200) };
  }
}

function json(res, status, payload, headers = {}) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', ...headers });
  res.end(JSON.stringify(payload));
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** Streams [events] (strings already framed as SSE) with a small pause, stops when the client left. */
async function stream(ctx, events, contentType = 'text/event-stream; charset=utf-8') {
  const { res } = ctx;
  res.writeHead(200, { 'Content-Type': contentType, 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
  const pause = ctx.switches.has('slow') ? 400 : DELAY_MS;
  for (const event of events) {
    if (ctx.closed) {
      log('..', 'client went away – stream stopped');
      return;
    }
    res.write(event);
    await sleep(pause);
  }
  res.end();
}

const sse = (data, event) => (event ? `event: ${event}\n` : '') + `data: ${typeof data === 'string' ? data : JSON.stringify(data)}\n\n`;

function log(tag, message) {
  const time = new Date().toISOString().slice(11, 19);
  console.log(`${time} [${tag}] ${message}`);
}

/** "AIza…(17)" — enough to tell keys apart, never the key. */
function mask(key) {
  if (!key) return '(none)';
  return `${key.slice(0, 4)}…(${key.length})`;
}

function bearer(req) {
  const value = req.headers.authorization ?? '';
  return value.toLowerCase().startsWith('bearer ') ? value.slice(7).trim() : '';
}

/** Rough token count of a JSON body (≈ 4 characters per token). */
function tokens(body) {
  return Math.max(1, Math.round(JSON.stringify(body ?? {}).length / 4));
}

// ---------------------------------------------------------------------------------------------
// Shared content
// ---------------------------------------------------------------------------------------------

const SOURCES = [
  { title: 'Tagesschau: Die wichtigsten Entwicklungen', url: 'https://www.tagesschau.de/', domain: 'tagesschau.de' },
  { title: 'ZEIT ONLINE: Hintergrund und Analyse', url: 'https://www.zeit.de/', domain: 'zeit.de' },
  { title: 'MDR: Was das für Sachsen bedeutet', url: 'https://www.mdr.de/nachrichten/', domain: 'mdr.de' },
];

const PROVIDER_NAMES = { openai: 'OpenAI', xai: 'Grok', perplexity: 'Perplexity', gemini: 'Gemini', anthropic: 'Claude',
  openrouter: 'OpenRouter', mistral: 'Mistral', deepseek: 'DeepSeek', custom: 'dein Server' };

function questionOf(text) {
  const clean = String(text ?? '').replace(/\s+/g, ' ').trim();
  // The app appends a day note ("Heute ist …") to the first question; the question comes first.
  return clean.split(/\s*\(?Heute ist /)[0].slice(0, 140) || 'deine Frage';
}

/** A German answer with light markdown, split into word-sized chunks for streaming. */
function answerChunks(provider, question, searched) {
  const name = PROVIDER_NAMES[provider] ?? provider;
  const text = [
    `**Kurz gesagt:** Zu „${question}“ gibt es drei Punkte, die du kennen solltest (Mock-Antwort von ${name}).`,
    '',
    '- **Hintergrund:** Die Entscheidung kam nicht überraschend, sie wurde seit Wochen vorbereitet.',
    '- **Folgen:** Kurzfristig ändert sich für die meisten wenig, mittelfristig schon.',
    '- **Offen:** Wie schnell die Umsetzung gelingt, ist noch unklar.',
    '',
    searched
      ? 'Die aktuellen Meldungen von heute bestätigen das; die Quellen findest du unten.'
      : 'Ohne Websuche stütze ich mich nur auf den Artikel und das Briefing – für Neues seitdem kann ich nichts sagen.',
  ].join('\n');
  return text.match(/\S+\s*|\s+/g);
}

function berlinIso(date) {
  // Berlin is UTC+1 in winter, UTC+2 in summer (last Sunday of March … last Sunday of October).
  const y = date.getUTCFullYear();
  const lastSunday = (month) => {
    const d = new Date(Date.UTC(y, month + 1, 0, 1));
    d.setUTCDate(d.getUTCDate() - d.getUTCDay());
    return d;
  };
  const summer = date >= lastSunday(2) && date < lastSunday(9);
  const offset = summer ? 2 : 1;
  const local = new Date(date.getTime() + offset * 3600_000);
  return local.toISOString().slice(0, 19) + `+0${offset}:00`;
}

/** Topic ids allowed in this Live run: the `topicId` enum of the publish_articles schema. */
function topicIdsOf(parameters) {
  return parameters?.properties?.articles?.items?.properties?.topicId?.enum ?? ['ki-tech'];
}

/**
 * The publish_articles arguments: one valid article per topic (at most two), or with
 * [broken] one whose body is far too short (the app's validation must reject it).
 */
function publishArguments(topicIds, { broken = false, empty = false } = {}) {
  if (empty) return JSON.stringify({ articles: [] });
  const stamp = Date.now().toString(36);
  const published = berlinIso(new Date(Date.now() - 40 * 60_000));
  const paragraph = (topic) =>
    `Seit dem Morgen-Briefing hat sich im Bereich ${topic} einiges bewegt. Mehrere Redaktionen berichten übereinstimmend ` +
    'über die neue Entwicklung, die am Vormittag bekannt wurde. Fachleute ordnen sie als wichtigen, aber erwartbaren ' +
    'Schritt ein, der seit einigen Wochen vorbereitet wurde.';
  const body = (topic) => [
    paragraph(topic),
    'Im Detail geht es um drei Punkte: den Zeitplan, die Kosten und die Frage, wer zuständig ist. Beim Zeitplan nennen ' +
      'die Beteiligten das kommende Frühjahr, bei den Kosten gibt es noch keine verlässlichen Zahlen. Zuständig bleibt ' +
      'vorerst die bisherige Stelle, eine neue Behörde ist nicht geplant.',
    'Für Leserinnen und Leser ändert sich kurzfristig wenig. Wer betroffen ist, sollte die nächsten Tage die offiziellen ' +
      'Mitteilungen im Blick behalten, denn Details werden erst nach den Beratungen feststehen. Diese Meldung stammt vom ' +
      'Mock-Server und dient nur dem Test der App.',
  ].join('\n\n');
  const articles = topicIds.slice(0, 2).map((topic, i) => ({
    topicId: topic,
    id: `${topic}-live-mock-${stamp}${i}`,
    tier: i === 0 ? 'top' : 'more',
    headline: `Neue Entwicklung im Bereich ${topic}`,
    summary: 'Seit dem Morgen hat sich etwas getan: Mehrere Quellen berichten übereinstimmend über die neue Entwicklung.',
    body: broken ? 'Zu kurz.' : body(topic),
    source: SOURCES[0].domain,
    url: SOURCES[0].url,
    sources: SOURCES.slice(0, 2).map((s) => ({ name: s.domain, url: s.url })),
    publishedAt: published,
    importance: 7 - i,
    readingMinutes: 1,
  }));
  return JSON.stringify({ articles });
}

/**
 * Where a Live run stands from the conversation so far: null = first request; "ok" = the app
 * accepted the articles; "rejected" = it asked for a correction.
 */
function liveVerdict(outputs) {
  const last = outputs.at(-1);
  if (last === undefined) return null;
  return String(last).trim().startsWith('ok') ? 'ok' : 'rejected';
}

// ---------------------------------------------------------------------------------------------
// Responses API (OpenAI, xAI, Perplexity Agent API)
// ---------------------------------------------------------------------------------------------

async function responses(ctx, dialect) {
  const { req, body, switches } = ctx;
  const key = bearer(req);
  const model = body?.model ?? '(none)';
  const tools = body?.tools ?? [];
  const publish = tools.find((t) => t.type === 'function' && t.name === 'publish_articles');
  const search = tools.some((t) => t.type === 'web_search') && !switches.has('no-search');
  log(dialect, `POST ${ctx.path} model=${model} stream=${!!body?.stream} search=${search} live=${!!publish} key=${mask(key)}`);
  lintResponses(dialect, body);
  const error = authError(key) ?? errorSwitch(switches);
  if (dialect === 'xai' && error === '401' && key) return xaiIncorrectKey(ctx);
  if (error) return responsesError(ctx, error, model);

  if (!body?.stream) {
    // Perplexity's key check: a tiny non-streamed request.
    const text = 'OK';
    return json(ctx.res, 200, {
      id: 'resp_mock', object: 'response', status: 'completed', model,
      output: [{ type: 'message', id: 'msg_mock', role: 'assistant', status: 'completed', content: [{ type: 'output_text', text, annotations: [] }] }],
      usage: responsesUsage(dialect, body, 2, 0),
    });
  }

  const input = Array.isArray(body.input) ? body.input : [];
  const outputs = input.filter((i) => i.type === 'function_call_output').map((i) => i.output);
  const question = questionOf([...input].reverse().find((i) => i.role === 'user')?.content ?? body.input);
  const events = [];
  let seq = 0;
  const ev = (type, payload) => events.push(sse({ type, sequence_number: ++seq, ...payload }, type));
  const responseHead = { id: 'resp_mock', object: 'response', model, output: [], usage: null };
  ev('response.created', { response: { ...responseHead, status: 'in_progress' } });
  ev('response.in_progress', { response: { ...responseHead, status: 'in_progress' } });
  let index = 0;
  let searches = 0;

  if (dialect !== 'perplexity') {
    const reasoning = { type: 'reasoning', id: 'rs_mock', summary: [] };
    ev('response.output_item.added', { output_index: index, item: reasoning });
    ev('response.output_item.done', { output_index: index++, item: { ...reasoning, encrypted_content: 'gAAAAABmockENCRYPTED' } });
  }
  const verdict = publish ? liveVerdict(outputs) : null;
  if (search && verdict === null) {
    searches = 1;
    if (dialect === 'perplexity') {
      const item = { type: 'search_results', id: 'sr_mock', queries: [question],
        results: SOURCES.map((s, i) => ({ id: i + 1, title: s.title, url: s.url, snippet: '…', date: new Date().toISOString().slice(0, 10), source: 'web' })) };
      ev('response.output_item.added', { output_index: index, item: { type: 'search_results', id: 'sr_mock' } });
      ev('response.output_item.done', { output_index: index++, item });
    } else {
      const id = 'ws_mock';
      ev('response.output_item.added', { output_index: index, item: { type: 'web_search_call', id, status: 'in_progress' } });
      ev('response.web_search_call.in_progress', { output_index: index, item_id: id });
      ev('response.web_search_call.searching', { output_index: index, item_id: id });
      ev('response.web_search_call.completed', { output_index: index, item_id: id });
      ev('response.output_item.done', { output_index: index++, item: { type: 'web_search_call', id, status: 'completed',
        action: { type: 'search', query: question, sources: SOURCES.map((s) => ({ type: 'url', url: s.url })) } } });
    }
  }

  if (switches.has('refusal') && !publish) {
    const id = 'msg_refusal';
    const text = 'Dabei kann ich nicht helfen.';
    ev('response.output_item.added', { output_index: index, item: { type: 'message', id, status: 'in_progress', role: 'assistant', content: [] } });
    ev('response.refusal.delta', { item_id: id, output_index: index, content_index: 0, delta: text });
    ev('response.refusal.done', { item_id: id, output_index: index, content_index: 0, refusal: text });
    ev('response.completed', { response: { ...responseHead, status: 'completed', usage: responsesUsage(dialect, body, 12, 0) } });
    return stream(ctx, events);
  }

  if (publish && verdict !== 'ok') {
    // Live: one publish_articles call (corrected after a rejection), never with text before it.
    const callId = `call_mock_${seq}`;
    const id = `fc_mock_${seq}`;
    const args = publishArguments(topicIdsOf(publish.parameters), {
      broken: verdict === null && switches.has('live-reject'),
      empty: switches.has('live-empty'),
    });
    if (dialect === 'openai') {
      ev('response.output_item.added', { output_index: index, item: { type: 'function_call', id, call_id: callId, name: 'publish_articles', arguments: '' } });
      for (const part of splitInto(args, 4)) ev('response.function_call_arguments.delta', { item_id: id, output_index: index, delta: part });
      ev('response.function_call_arguments.done', { item_id: id, output_index: index, arguments: args });
    }
    ev('response.output_item.done', { output_index: index++, item: { type: 'function_call', id, call_id: callId, name: 'publish_articles', arguments: args, status: 'completed' } });
    ev('response.completed', { response: { ...responseHead, status: 'completed', usage: responsesUsage(dialect, body, Math.round(args.length / 4), searches) } });
    return stream(ctx, events);
  }

  // The answer (Live: a short closing line after the accepted call).
  const id = 'msg_mock';
  const chunks = publish ? ['Fertig – ', 'die Artikel sind veröffentlicht.'] : answerChunks(dialect, question, search);
  const markers = dialect === 'perplexity' && search;
  ev('response.output_item.added', { output_index: index, item: { type: 'message', id, status: 'in_progress', role: 'assistant',
    ...(dialect === 'openai' ? { phase: 'final_answer' } : {}), content: [] } });
  ev('response.content_part.added', { item_id: id, output_index: index, content_index: 0, part: { type: 'output_text', text: '', annotations: [] } });
  let full = '';
  for (const [i, chunk] of chunks.entries()) {
    // Perplexity writes numbered markers into the text, sometimes split across chunks.
    const piece = markers && i === 8 ? `${chunk}[1` : markers && i === 9 ? `]${chunk}` : chunk;
    full += piece;
    if (switches.has('midstream-error') && i === 6) {
      ev('response.failed', { response: { ...responseHead, status: 'failed', error: { code: 'server_error', message: 'The server had an error while processing your request.' } } });
      return stream(ctx, events);
    }
    ev('response.output_text.delta', { item_id: id, output_index: index, content_index: 0, delta: piece, logprobs: [] });
  }
  const annotations = search && dialect !== 'perplexity'
    ? SOURCES.slice(0, 2).map((s, i) => ({ type: 'url_citation', start_index: 0, end_index: 10 + i, url: s.url, title: dialect === 'xai' ? String(i + 1) : s.title }))
    : [];
  annotations.forEach((annotation, i) => ev('response.output_text.annotation.added', { item_id: id, output_index: index, content_index: 0, annotation_index: i, annotation }));
  ev('response.output_text.done', { item_id: id, output_index: index, content_index: 0, text: full });
  ev('response.output_item.done', { output_index: index, item: { type: 'message', id, status: 'completed', role: 'assistant',
    ...(dialect === 'openai' ? { phase: 'final_answer' } : {}), content: [{ type: 'output_text', text: full, annotations }] } });
  const usage = responsesUsage(dialect, body, Math.round(full.length / 3), searches);
  if (switches.has('truncated')) {
    ev('response.incomplete', { response: { ...responseHead, status: 'incomplete', incomplete_details: { reason: 'max_output_tokens' }, usage } });
  } else {
    ev('response.completed', { response: { ...responseHead, status: 'completed', usage } });
  }
  return stream(ctx, events);
}

function responsesUsage(dialect, body, output, searches) {
  const input = tokens(body);
  const cached = Math.round(input * 0.4);
  const base = {
    input_tokens: input,
    input_tokens_details: { cached_tokens: cached, cache_write_tokens: 0 },
    output_tokens: output + 40,
    output_tokens_details: { reasoning_tokens: 40 },
    total_tokens: input + output + 40,
  };
  if (dialect === 'xai') {
    const usd = (input * 1.25 + (output + 40) * 2.5) / 1e6 + searches * 0.005;
    return { ...base, cost_in_usd_ticks: Math.round(usd * 1e10), server_side_tool_usage_details: { web_search_calls: searches } };
  }
  if (dialect === 'perplexity') {
    const total = (input * 0.75 + (output + 40) * 3.75) / 1e6 + searches * 0.001;
    return {
      ...base,
      input_tokens_details: { cache_read_input_tokens: cached, cache_creation_input_tokens: 0 },
      tool_calls_details: { web_search: { invocation: searches, cost_usd: searches * 0.001 } },
      cost: { currency: 'USD', input_cost: input * 0.75 / 1e6, output_cost: (output + 40) * 3.75 / 1e6, total_cost: Number(total.toFixed(6)) },
    };
  }
  return base;
}

function responsesError(ctx, error, model) {
  const body = (status, type, code, message) => ({ error: { message, type, param: null, code } });
  const table = {
    '400': [400, body(400, 'invalid_request_error', null, 'Mock: invalid request.')],
    '400-model': [400, body(400, 'invalid_request_error', 'model_not_found', `The model \`${model}\` does not exist or you do not have access to it.`)],
    '400-workspace': [400, body(400, 'invalid_request_error', null, 'Mock: invalid request.')],
    '401': [401, body(401, 'invalid_request_error', 'invalid_api_key', 'Incorrect API key provided.')],
    '402': [402, body(402, 'insufficient_balance', 'insufficient_balance', 'Insufficient Balance')],
    '403': [403, body(403, 'permission_error', 'unsupported_country_region_territory', 'Country, region, or territory not supported')],
    '404': [404, body(404, 'invalid_request_error', 'model_not_found', `The model \`${model}\` does not exist.`)],
    '429': [429, body(429, 'requests', 'rate_limit_exceeded', 'Rate limit reached. Please try again in 1s.'), { 'Retry-After': '1' }],
    '429-short': [429, body(429, 'requests', 'rate_limit_exceeded', 'Rate limit reached. Please try again in 1s.'), { 'Retry-After': '1' }],
    '429-quota': [429, body(429, 'insufficient_quota', 'insufficient_quota', 'You exceeded your current quota, please check your plan and billing details.')],
    '500': [500, body(500, 'server_error', 'server_error', 'The server had an error while processing your request.')],
    '503': [503, body(503, 'server_error', 'service_unavailable', 'The engine is currently overloaded, please try again later.')],
    '529': [503, body(503, 'server_error', 'service_unavailable', 'The engine is currently overloaded, please try again later.')],
  };
  const [status, payload, headers] = table[error] ?? table[baseSwitch(error)];
  log('->', `${status} ${error}`);
  return json(ctx.res, status, payload, headers);
}

function lintResponses(dialect, body) {
  if (!body?.stream) return;
  if (dialect !== 'perplexity' && body.store !== false) warn('Responses request without "store": false');
  if ('temperature' in body) warn('Responses request with temperature (reasoning models reject it)');
}

// ---------------------------------------------------------------------------------------------
// Gemini generateContent
// ---------------------------------------------------------------------------------------------

async function gemini(ctx, model, streamed) {
  const { req, url, body, switches } = ctx;
  const key = req.headers['x-goog-api-key'] ?? '';
  const wantsSearch = (body?.tools ?? []).some((t) => 'googleSearch' in t || 'google_search' in t);
  log('gemini', `POST ${ctx.path} model=${model} sse=${streamed} search=${wantsSearch} key=${mask(key)}`);
  if (url.searchParams.has('key')) warn('Gemini key in the URL (?key=) – it belongs in x-goog-api-key only');
  if (req.headers.authorization) warn('Gemini request with an Authorization header – API keys go in x-goog-api-key (GE §2)');
  lintGemini(body);
  const error = (key ? null : '403') ?? authError(key) ?? errorSwitch(switches) ?? modelRefusal(switches, model);
  if (error) return geminiError(ctx, error, model, streamed);
  if (wantsSearch && (switches.has('free-tier-search') || switches.has('free-tier-search-400'))) {
    return geminiError(ctx, switches.has('free-tier-search-400') ? 'search-400' : 'search-429', model, streamed);
  }
  const search = wantsSearch && !switches.has('no-search');
  const contents = body?.contents ?? [];
  const lastUser = [...contents].reverse().find((c) => c.role === 'user');
  const question = questionOf(lastUser?.parts?.at(-1)?.text);
  const chunks = answerChunks('gemini', question, search);
  const usage = {
    promptTokenCount: tokens(body), cachedContentTokenCount: 0,
    candidatesTokenCount: Math.round(chunks.join('').length / 3), thoughtsTokenCount: 64,
    toolUsePromptTokenCount: search ? 180 : 0,
  };
  usage.totalTokenCount = usage.promptTokenCount + usage.candidatesTokenCount + usage.thoughtsTokenCount + usage.toolUsePromptTokenCount;
  const response = (candidate, extra = {}) => ({ candidates: [{ ...candidate, index: 0 }], ...extra, modelVersion: model, responseId: 'mock-resp' });
  const parts = [];
  parts.push(response({ content: { role: 'model', parts: [{ text: 'Ich überlege, welche Quellen passen …', thought: true }] } }));
  // A few words per chunk, like the real stream.
  for (let i = 0; i < chunks.length; i += 6) {
    if (switches.has('midstream-error') && i >= 12) {
      parts.push({ error: { code: 503, message: 'The model is overloaded. Please try again later.', status: 'UNAVAILABLE' } });
      break;
    }
    const chunk = response({ content: { role: 'model', parts: [{ text: chunks.slice(i, i + 6).join('') }] } });
    if (search && i === 0) chunk.candidates[0].groundingMetadata = { webSearchQueries: [question] };
    parts.push(chunk);
  }
  if (!switches.has('midstream-error')) {
    const finish = switches.has('refusal') ? 'SAFETY' : switches.has('truncated') ? 'MAX_TOKENS' : 'STOP';
    const last = { content: { role: 'model', parts: [{ text: '' }] }, finishReason: finish };
    if (search) last.groundingMetadata = groundingMetadata(question);
    parts.push(response(last, { usageMetadata: usage }));
  }
  if (!streamed) {
    // Plain generateContent: one merged response.
    const text = chunks.join('');
    return json(ctx.res, 200, response({ content: { role: 'model', parts: [{ text }] }, finishReason: 'STOP',
      ...(search ? { groundingMetadata: groundingMetadata(question) } : {}) }, { usageMetadata: usage }));
  }
  // No event names and no [DONE]: the stream ends at EOF (GE §5.1).
  return stream(ctx, parts.map((p) => sse(p)));
}

/** groundingMetadata as documented (GE §6.2): redirect URIs, domains as titles, the Search Suggestions. */
function groundingMetadata(question) {
  const queries = [question, `${question} aktuell`];
  return {
    webSearchQueries: queries,
    searchEntryPoint: { renderedContent: suggestionsHtml(queries) },
    groundingChunks: SOURCES.map((s, i) => ({
      web: { uri: `https://vertexaisearch.cloud.google.com/grounding-api-redirect/MOCK${i + 1}${Date.now().toString(36)}`, title: s.domain },
    })),
    groundingSupports: [{ segment: { partIndex: 0, startIndex: 0, endIndex: 40, text: 'Kurz gesagt' }, groundingChunkIndices: [0, 1] }],
  };
}

/**
 * Search Suggestions shaped like Google's widget: own CSS for light and dark through
 * prefers-color-scheme, a horizontal row of chips, each a link to Google's results page.
 */
function suggestionsHtml(queries) {
  const chips = queries
    .map((q) => `<a class="chip" href="https://www.google.com/search?q=${encodeURIComponent(q)}&amp;client=app-vertex-grounding&amp;safesearch=active">${escapeHtml(q)}</a>`)
    .join('');
  return `<style>
.container{align-items:center;border-radius:8px;display:flex;font-family:Roboto,sans-serif;font-size:14px;line-height:20px;padding:8px 12px}
.chip{display:inline-block;border:solid 1px;border-radius:16px;min-width:14px;padding:5px 16px;text-align:center;user-select:none;margin:0 8px}
.carousel{overflow:auto;scrollbar-width:none;white-space:nowrap;margin-right:-12px}
.logo{font-weight:700;font-size:16px;margin-right:4px}
@media (prefers-color-scheme: light){.container{background-color:#fafafa;box-shadow:0 0 0 1px #0000000f}.chip{background-color:#fff;border-color:#d2d2d2;color:#5e5e5e;text-decoration:none}.logo{color:#4285f4}}
@media (prefers-color-scheme: dark){.container{background-color:#1f1f1f;box-shadow:0 0 0 1px #ffffff26}.chip{background-color:#2c2c2c;border-color:#3c4043;color:#fff;text-decoration:none}.logo{color:#8ab4f8}}
</style><div class="container"><span class="logo">G</span><div class="carousel">${chips}</div></div>`;
}

function escapeHtml(text) {
  return text.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);
}

/** model-gone / model-no-quota: the refusal of [model] when it is the one these modes take away; null otherwise. */
function modelRefusal(switches, model) {
  if (model.replace(/^models\//, '') !== GONE_MODEL) return null;
  if (switches.has('model-gone')) return '404';
  if (switches.has('model-no-quota')) return 'model-no-quota';
  return null;
}

function geminiModel(ctx, model) {
  const key = ctx.req.headers['x-goog-api-key'] ?? '';
  log('gemini', `GET ${ctx.path} key=${mask(key)}`);
  const gone = ctx.switches.has('model-gone') ? modelRefusal(ctx.switches, model) : null;
  const error = (key ? null : '403') ?? authError(key) ?? errorSwitch(ctx.switches) ?? gone;
  if (error) return geminiError(ctx, error, model, false);
  const id = model.replace(/^models\//, '');
  return json(ctx.res, 200, {
    name: `models/${id}`, version: '3.8', displayName: id, description: 'Mock model',
    inputTokenLimit: 1048576, outputTokenLimit: 65536,
    supportedGenerationMethods: ['generateContent', 'countTokens'], thinking: true,
  });
}

/**
 * models.list (the key test's first, token-free step, GE §13): the key and project errors apply, the
 * model-specific ones (404, 400-model, the model modes) do not — model-gone leaves its model out instead.
 */
function geminiModelList(ctx) {
  const key = ctx.req.headers['x-goog-api-key'] ?? '';
  log('gemini', `GET ${ctx.path} key=${mask(key)} (key test: model list)`);
  const switched = errorSwitch(ctx.switches);
  const error = (key ? null : '403') ?? authError(key) ?? (['404', '400-model'].includes(switched) ? null : switched);
  if (error) return geminiError(ctx, error, '', false);
  const ids = GEMINI_MODELS.filter((id) => !(ctx.switches.has('model-gone') && id === GONE_MODEL));
  return json(ctx.res, 200, {
    models: ids.map((id) => ({
      name: `models/${id}`, baseModelId: id, displayName: id, inputTokenLimit: 1048576, outputTokenLimit: 65536,
      supportedGenerationMethods: ['generateContent', 'countTokens'], thinking: true,
    })),
  });
}

function geminiError(ctx, error, model, streamed) {
  const key = ctx.req.headers['x-goog-api-key'] ?? '';
  const info =(reason, metadata = {}) => ({ '@type': 'type.googleapis.com/google.rpc.ErrorInfo', reason, domain: 'googleapis.com', metadata: { service: 'generativelanguage.googleapis.com', ...metadata } });
  const help = (url) => ({ '@type': 'type.googleapis.com/google.rpc.Help', links: [{ description: 'Google developers console API activation', url }] });
  const project = '000000000000';
  const activation = `https://console.developers.google.com/apis/api/generativelanguage.googleapis.com/overview?project=${project}`;
  const retry = (delay) => ({ '@type': 'type.googleapis.com/google.rpc.RetryInfo', retryDelay: delay });
  const quota = (metric, limit) => ({ '@type': 'type.googleapis.com/google.rpc.QuotaFailure', violations: [{ quotaMetric: `generativelanguage.googleapis.com/${metric}`, quotaId: 'GenerateRequestsPerDayPerProjectPerModel-FreeTier', quotaDimensions: { location: 'global', model }, quotaValue: String(limit) }] });
  const rpc = (code, status, message, details = []) => [code, { error: { code, message, status, details } }];
  const table = {
    '400': rpc(400, 'INVALID_ARGUMENT', 'API key not valid. Please pass a valid API key.', [info('API_KEY_INVALID')]),
    '400-model': rpc(400, 'INVALID_ARGUMENT', `* GenerateContentRequest.model: unexpected model name format: ${model}`),
    '400-workspace': rpc(400, 'INVALID_ARGUMENT', 'Mock: invalid request.'),
    '401': rpc(401, 'UNAUTHENTICATED', 'Request had invalid authentication credentials. Expected OAuth 2 access token, login cookie or other valid authentication credential.', [info('ACCESS_TOKEN_TYPE_UNSUPPORTED')]),
    // GE §12.4: an AQ.… auth key Google does not take (forum reports since 2026-08, also for keys AI Studio shows as valid).
    '401-auth-key': rpc(401, 'UNAUTHENTICATED', 'Request had invalid authentication credentials. Expected OAuth 2 access token, login cookie or other valid authentication credential.', [info('ACCESS_TOKEN_TYPE_UNSUPPORTED', { method: 'google.ai.generativelanguage.v1beta.GenerativeService.GenerateContent' })]),
    '403-blocked': rpc(403, 'PERMISSION_DENIED', 'Requests to this API generativelanguage.googleapis.com method google.ai.generativelanguage.v1beta.GenerativeService.GenerateContent are blocked.', [info('API_KEY_SERVICE_BLOCKED', { consumer: `projects/${project}`, apiName: 'generativelanguage.googleapis.com' })]),
    '403-disabled': rpc(403, 'PERMISSION_DENIED', `Generative Language API has not been used in project ${project} before or it is disabled. Enable it by visiting ${activation} then retry.`, [info('SERVICE_DISABLED', { consumer: `projects/${project}`, activationUrl: activation }), help(activation)]),
    '403-restricted': rpc(403, 'PERMISSION_DENIED', 'Requests from this Android client application <empty> are blocked.', [info('API_KEY_ANDROID_APP_BLOCKED', { consumer: `projects/${project}` })]),
    '400-region': rpc(400, 'FAILED_PRECONDITION', 'Gemini API free tier is not available in your country. Please enable billing on your project in Google AI Studio.'),
    // GE §12.6: the worst case for the app's "Details" – the key it sent comes back in every field the line could read
    // (code, type, ErrorInfo.reason, the quota id) and in those it never reads (message, metadata, Help link).
    '400-key-echo': [400, { error: {
      code: key, type: key, message: `API key not valid. Please pass a valid API key. Received: ${key}`, status: 'INVALID_ARGUMENT',
      details: [info(key, { key, consumer: `api_key:${key}` }), help(`https://aistudio.google.com/apikey?key=${encodeURIComponent(key)}`),
        { '@type': 'type.googleapis.com/google.rpc.QuotaFailure', violations: [{ quotaMetric: key, quotaId: key }] }],
    } }],
    'model-no-quota': rpc(429, 'RESOURCE_EXHAUSTED', `You exceeded your current quota, please check your plan and billing details. Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 0, model: ${model}`, [quota('generate_content_free_tier_requests', 0), retry('20s')]),
    '402': rpc(402, 'RESOURCE_EXHAUSTED', 'Your prepayment credits are depleted. Please go to AI Studio to manage your billing.'),
    '403': rpc(403, 'PERMISSION_DENIED', "Method doesn't allow unregistered callers (callers without established identity). Please use API Key or other form of API consumer identity to call this API."),
    '404': rpc(404, 'NOT_FOUND', `models/${model} is not found for API version v1beta, or is not supported for generateContent.`),
    // A free key's quota: Google names the wait (37 s: the app does not hammer, it explains – G3).
    '429': rpc(429, 'RESOURCE_EXHAUSTED', 'You exceeded your current quota, please check your plan and billing details. Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 10. Please retry in 37s.', [quota('generate_content_free_tier_requests', 10), retry('37s')]),
    '429-short': rpc(429, 'RESOURCE_EXHAUSTED', 'You exceeded your current quota. Please retry in 3s.', [quota('generate_content_free_tier_requests', 10), retry('3s')]),
    '429-quota': rpc(429, 'RESOURCE_EXHAUSTED', 'You exceeded your current quota, please check your plan and billing details. Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 250 per day.', [quota('generate_content_free_tier_requests', 250)]),
    '500': rpc(500, 'INTERNAL', 'An internal error has occurred. Please retry or report in https://developers.generativeai.google/guide/troubleshooting'),
    '503': rpc(503, 'UNAVAILABLE', 'The model is overloaded. Please try again later.'),
    '529': rpc(503, 'UNAVAILABLE', 'The model is overloaded. Please try again later.'),
    // A free-tier key asking for Google Search (GE §16: the real signal is undocumented).
    'search-429': rpc(429, 'RESOURCE_EXHAUSTED', 'Quota exceeded for metric: generativelanguage.googleapis.com/search_grounding_request_free_tier, limit: 0. Grounding with Google Search is not available on the free tier.', [quota('search_grounding_request_free_tier', 0)]),
    'search-400': rpc(400, 'FAILED_PRECONDITION', 'Grounding with Google Search is not available for this project. Please enable billing.'),
  };
  const [status, payload] = table[error] ?? table[baseSwitch(error)];
  log('->', `${status} ${error}`);
  // Observed: errors of the SSE endpoint are plain JSON labelled text/event-stream (GE §5.1).
  ctx.res.writeHead(status, { 'Content-Type': streamed ? 'text/event-stream' : 'application/json; charset=UTF-8' });
  return ctx.res.end(JSON.stringify(payload));
}

function lintGemini(body) {
  const config = body?.generationConfig ?? {};
  for (const field of ['temperature', 'topP', 'topK', 'candidateCount']) if (field in config) warn(`Gemini 3 request with ${field} (GE §15)`);
  if (config.thinkingConfig?.thinkingBudget !== undefined) warn('Gemini 3 request with thinkingBudget (use thinkingLevel)');
  const contents = body?.contents ?? [];
  if (contents.at(-1)?.role === 'model') warn('Gemini request ends with a model turn (400 on Gemini 3.6+)');
}

// ---------------------------------------------------------------------------------------------
// Chat Completions (OpenRouter, Mistral, DeepSeek, own server)
// ---------------------------------------------------------------------------------------------

async function chatCompletions(ctx) {
  const { req, body, switches } = ctx;
  const key = bearer(req);
  const model = body?.model ?? '(none)';
  const tools = body?.tools ?? [];
  const flavor = tools.some((t) => t.type === 'openrouter:web_search') || model.includes('/') ? 'openrouter'
    : model.startsWith('deepseek') ? 'deepseek' : model.includes('mistral') || model.startsWith('ministral') ? 'mistral' : 'custom';
  const publish = tools.find((t) => t.type === 'function' && t.function?.name === 'publish_articles');
  const search = tools.some((t) => t.type === 'openrouter:web_search') && !switches.has('no-search');
  log(flavor, `POST ${ctx.path} model=${model} stream=${!!body?.stream} search=${search} live=${!!publish} key=${mask(key)}`);
  if (flavor === 'mistral' && body?.stream_options) warn('Mistral request with stream_options (Mistral rejects it, OC §2.5)');
  // The own server works without a key; every hosted provider needs one.
  const error = (flavor !== 'custom' ? authError(key) : key.includes('reject') ? '401' : null) ?? errorSwitch(switches);
  if (error) return responsesError(ctx, error, model);
  if (!model || model === '(none)') {
    return json(ctx.res, 400, { error: { message: 'model is required', type: 'invalid_request_error', code: null } });
  }

  const messages = body?.messages ?? [];
  if (flavor === 'openrouter' && messages.some((m) => m.role === 'assistant' && m.tool_calls && !m.reasoning_details)) {
    warn('OpenRouter correction without reasoning_details (Gemini 3 / Claude upstreams answer 400, OC §2.4)');
  }
  const outputs = messages.filter((m) => m.role === 'tool').map((m) => m.content);
  const question = questionOf([...messages].reverse().find((m) => m.role === 'user')?.content);
  const events = [];
  const base = { id: 'gen-mock', object: 'chat.completion.chunk', created: Math.floor(Date.now() / 1000), model };
  const chunk = (delta, finish = null, extra = {}) => events.push(sse({ ...base, choices: [{ index: 0, delta, finish_reason: finish }], ...extra }));
  if (flavor === 'openrouter') events.push(': OPENROUTER PROCESSING\n\n');
  const promptTokens = tokens(body);
  const usage = (completion, searches = 0) => {
    const u = { prompt_tokens: promptTokens, completion_tokens: completion, total_tokens: promptTokens + completion };
    if (flavor === 'openrouter') {
      Object.assign(u, {
        cost: Number(((promptTokens * 0.75 + completion * 3.75) / 1e6 + searches * 0.014).toFixed(6)), is_byok: false,
        prompt_tokens_details: { cached_tokens: 0, cache_write_tokens: 0 }, completion_tokens_details: { reasoning_tokens: 24 },
        server_tool_use_details: { web_search_requests: searches },
      });
    }
    if (flavor === 'deepseek') Object.assign(u, { prompt_cache_hit_tokens: 0, prompt_cache_miss_tokens: promptTokens });
    return u;
  };

  const verdict = publish ? liveVerdict(outputs) : null;
  if (publish && verdict !== 'ok') {
    const args = publishArguments(topicIdsOf(publish.function.parameters), {
      broken: verdict === null && switches.has('live-reject'),
      empty: switches.has('live-empty'),
    });
    if (flavor === 'openrouter') {
      // A thinking upstream's reasoning (Live: effort medium) and the call's signature, in pieces (OC §2.3).
      chunk({ role: 'assistant', content: null, reasoning_details: [{ type: 'reasoning.text', text: 'Erst suchen, ', format: 'google-gemini-v1', index: 0 }] });
      chunk({ reasoning_details: [{ type: 'reasoning.text', text: 'dann veröffentlichen.', index: 0 }] });
      chunk({ reasoning_details: [{ type: 'reasoning.encrypted', data: `MOCK${Date.now().toString(36)}`, id: 'tool_call_mock', format: 'google-gemini-v1', index: 1 }] });
    } else {
      chunk({ role: 'assistant', content: null });
    }
    const parts = splitInto(args, 4);
    parts.forEach((part, i) => chunk({ tool_calls: [{ index: 0, ...(i === 0 ? { id: `call_mock_${Date.now()}`, type: 'function' } : {}),
      function: { ...(i === 0 ? { name: 'publish_articles' } : {}), arguments: part } }] }));
    chunk({}, 'tool_calls');
    if (flavor === 'openrouter') chunk({}, 'tool_calls', { usage: usage(Math.round(args.length / 4), verdict === null && search ? 1 : 0) });
    events.push(sse('[DONE]'));
    return stream(ctx, events);
  }

  const words = publish ? ['Fertig – ', 'die Artikel sind veröffentlicht.'] : answerChunks(flavor, question, search);
  if (flavor === 'mistral') chunk({ role: 'assistant', content: [{ type: 'thinking', thinking: [{ type: 'text', text: 'Kurz nachdenken …' }] }] });
  else chunk({ role: 'assistant', content: '' });
  let text = '';
  for (const [i, word] of words.entries()) {
    if (switches.has('midstream-error') && i === 6) {
      events.push(sse({ ...base, error: { code: 502, message: 'Provider returned error', metadata: { error_type: 'provider_unavailable' } },
        choices: [{ index: 0, delta: { content: '' }, finish_reason: 'error' }] }));
      events.push(sse('[DONE]'));
      return stream(ctx, events);
    }
    text += word;
    chunk({ content: word });
  }
  if (search && flavor === 'openrouter') {
    chunk({ annotations: SOURCES.slice(0, 2).map((s) => ({ type: 'url_citation', url_citation: { url: s.url, title: s.title, content: '…', start_index: 0, end_index: 10 } })) });
  }
  const finish = switches.has('refusal') ? 'content_filter' : switches.has('truncated') ? 'length' : 'stop';
  const final = usage(Math.round(text.length / 3), search ? 1 : 0);
  if (flavor === 'openrouter') {
    chunk({}, finish);
    chunk({}, finish, { usage: final }); // OpenRouter's extra accounting chunk (OC §7.6)
  } else if (flavor === 'deepseek' && body?.stream_options?.include_usage) {
    chunk({}, finish);
    events.push(sse({ ...base, choices: [], usage: final }));
  } else {
    chunk({}, finish, { usage: final });
  }
  events.push(sse('[DONE]'));
  return stream(ctx, events);
}

// ---------------------------------------------------------------------------------------------
// Model lists and key checks
// ---------------------------------------------------------------------------------------------

function modelList(ctx) {
  const key = bearer(ctx.req);
  log('models', `GET ${ctx.path} key=${mask(key)}`);
  // The own server answers without a key; a switch or a "reject" key still fails it.
  const error = (key.includes('reject') ? '401' : null) ?? errorSwitch(ctx.switches);
  if (error) return responsesError(ctx, error, '');
  const ids = ['gpt-6.1-sol', 'gpt-6-luna', 'gpt-6-astra', 'mistral-medium-3-5', 'mistral-small-2603', 'llama3.3', 'qwen3:8b', 'gemma3:12b'];
  return json(ctx.res, 200, { object: 'list', data: ids.map((id) => ({ id, object: 'model', created: 1760000000, owned_by: 'mock' })) });
}

function keyInfo(ctx, provider) {
  const key = bearer(ctx.req);
  log(provider, `GET ${ctx.path} key=${mask(key)} (key check)`);
  // "402" is no error here: the key works, the account has no credit (the check's OK_NO_CREDIT).
  const noCredit = ctx.switches.has('402');
  const error = authError(key) ?? (noCredit ? null : errorSwitch(ctx.switches));
  if (provider === 'xai' && error === '401' && key) return xaiIncorrectKey(ctx);
  if (error) return responsesError(ctx, error, '');
  if (provider === 'xai') {
    return json(ctx.res, 200, { redacted_api_key: `${key.slice(0, 4)}****`, name: 'mock', api_key_id: 'mock', team_id: 'mock',
      acls: ['api-key:model:*', 'api-key:endpoint:*'], api_key_blocked: false, api_key_disabled: false, team_blocked: noCredit });
  }
  if (provider === 'deepseek') {
    return json(ctx.res, 200, { is_available: !noCredit, balance_infos: [{ currency: 'USD', total_balance: noCredit ? '0.00' : '10.00', granted_balance: '0.00', topped_up_balance: noCredit ? '0.00' : '10.00' }] });
  }
  return json(ctx.res, 200, { data: { label: 'sk-or-v1-mock', limit: null, usage: 0.42, is_free_tier: false, rate_limit: { requests: 20, interval: '10s' } } });
}

/** 401 for a missing key or one that contains "reject"; null otherwise. */
function authError(key) {
  if (!key || key.includes('reject')) return '401';
  return null;
}

/**
 * xAI's real answer to a wrong key on /v1/api-key and /v1/responses: HTTP 400 with a flat body,
 * not 401 (observed 2026-10-09 with a fake key, OC §4.8); only a missing key gets 401.
 */
function xaiIncorrectKey(ctx) {
  log('->', '400 incorrect API key (xAI)');
  return json(ctx.res, 400, { code: 'invalid-argument', error: 'Incorrect API key provided. You can obtain an API key from https://console.x.ai.' });
}

function warn(message) {
  log('WARN', message);
}

function splitInto(text, parts) {
  const size = Math.ceil(text.length / parts);
  const out = [];
  for (let i = 0; i < text.length; i += size) out.push(text.slice(i, i + size));
  return out;
}

// ---------------------------------------------------------------------------------------------
// Anthropic Messages (Claude through the Java SDK)
// ---------------------------------------------------------------------------------------------

async function anthropic(ctx) {
  const { req, body, switches } = ctx;
  const key = req.headers['x-api-key'] ?? '';
  const workspace = req.headers['anthropic-workspace-id'];
  const model = body?.model ?? '(none)';
  const tools = body?.tools ?? [];
  const publish = tools.find((t) => t.name === 'publish_articles');
  const search = tools.some((t) => String(t.type ?? '').startsWith('web_search')) && !switches.has('no-search');
  log('anthropic', `POST ${ctx.path} model=${model} stream=${!!body?.stream} search=${search} live=${!!publish} key=${mask(key)}${workspace ? ` workspace=${workspace}` : ''}`);
  const error = authError(key) ?? errorSwitch(switches);
  if (error) return anthropicError(ctx, error, model);

  const usage = { input_tokens: tokens(body), output_tokens: 1, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 };
  if (!body?.stream) {
    // The key check: a tiny non-streamed request.
    return json(ctx.res, 200, { id: 'msg_mock', type: 'message', role: 'assistant', model, content: [{ type: 'text', text: 'OK' }],
      stop_reason: 'end_turn', stop_sequence: null, usage: { ...usage, output_tokens: 2 } });
  }

  const messages = body.messages ?? [];
  const results = messages.flatMap((m) => (Array.isArray(m.content) ? m.content : []))
    .filter((b) => b.type === 'tool_result')
    .map((b) => (typeof b.content === 'string' ? b.content : (b.content ?? []).map((c) => c.text ?? '').join('')));
  const lastUser = [...messages].reverse().find((m) => m.role === 'user' && (typeof m.content === 'string' || m.content?.some?.((b) => b.type === 'text')));
  const question = questionOf(typeof lastUser?.content === 'string' ? lastUser.content : lastUser?.content?.filter((b) => b.type === 'text').at(-1)?.text);
  const events = [];
  const ev = (type, payload) => events.push(sse({ type, ...payload }, type));
  ev('message_start', { message: { id: 'msg_mock', type: 'message', role: 'assistant', model, content: [], stop_reason: null, stop_sequence: null, usage } });
  ev('ping', {});
  let index = 0;
  let searches = 0;
  const verdict = publish ? liveVerdict(results) : null;
  if (search && verdict === null) {
    searches = 1;
    const toolId = 'srvtoolu_mock';
    ev('content_block_start', { index, content_block: { type: 'server_tool_use', id: toolId, name: 'web_search', input: {} } });
    ev('content_block_delta', { index, delta: { type: 'input_json_delta', partial_json: JSON.stringify({ query: question }) } });
    ev('content_block_stop', { index: index++ });
    ev('content_block_start', { index, content_block: { type: 'web_search_tool_result', tool_use_id: toolId,
      content: SOURCES.map((s) => ({ type: 'web_search_result', url: s.url, title: s.title, encrypted_content: 'EqMockEncrypted', page_age: 'vor 1 Stunde' })) } });
    ev('content_block_stop', { index: index++ });
  }
  let stop = 'end_turn';
  let outputTokens = 0;
  if (publish && verdict !== 'ok') {
    const args = publishArguments(topicIdsOf(publish.input_schema), {
      broken: verdict === null && switches.has('live-reject'),
      empty: switches.has('live-empty'),
    });
    ev('content_block_start', { index, content_block: { type: 'tool_use', id: `toolu_mock_${Date.now()}`, name: 'publish_articles', input: {} } });
    for (const part of splitInto(args, 4)) ev('content_block_delta', { index, delta: { type: 'input_json_delta', partial_json: part } });
    ev('content_block_stop', { index: index++ });
    stop = 'tool_use';
    outputTokens = Math.round(args.length / 4);
  } else {
    const words = publish ? ['Fertig – ', 'die Artikel sind veröffentlicht.'] : answerChunks('anthropic', question, search);
    ev('content_block_start', { index, content_block: { type: 'text', text: '' } });
    for (const [i, word] of words.entries()) {
      if (switches.has('midstream-error') && i === 6) {
        events.push(sse({ type: 'error', error: { type: 'overloaded_error', message: 'Overloaded' } }, 'error'));
        return stream(ctx, events);
      }
      ev('content_block_delta', { index, delta: { type: 'text_delta', text: word } });
      if (search && i === 3) {
        ev('content_block_delta', { index, delta: { type: 'citations_delta', citation: { type: 'web_search_result_location',
          url: SOURCES[0].url, title: SOURCES[0].title, encrypted_index: 'EoMock', cited_text: 'Die wichtigsten Entwicklungen' } } });
      }
      outputTokens += Math.max(1, Math.round(word.length / 3));
    }
    ev('content_block_stop', { index: index++ });
    if (switches.has('refusal')) stop = 'refusal';
    else if (switches.has('truncated')) stop = 'max_tokens';
  }
  ev('message_delta', { delta: { stop_reason: stop, stop_sequence: null },
    usage: { output_tokens: outputTokens + 40, ...(searches ? { server_tool_use: { web_search_requests: searches } } : {}) } });
  ev('message_stop', {});
  return stream(ctx, events);
}

function anthropicError(ctx, error, model) {
  const body = (type, message) => ({ type: 'error', error: { type, message }, request_id: 'req_mock' });
  const table = {
    '400': [400, body('invalid_request_error', 'Mock: invalid request.')],
    '400-model': [400, body('invalid_request_error', `model: ${model}`)],
    '400-workspace': [400, body('invalid_request_error', 'anthropic-workspace-id is required when authenticating with an identity-linked API key; send the id of the workspace this request acts in.')],
    '401': [401, body('authentication_error', 'invalid x-api-key')],
    '402': [400, body('invalid_request_error', 'Your credit balance is too low to access the Anthropic API. Please go to Plans & Billing to upgrade or purchase credits.')],
    '403': [403, body('permission_error', 'Your API key does not have permission to use the specified resource.')],
    '404': [404, body('not_found_error', `model: ${model}`)],
    '429': [429, body('rate_limit_error', 'Number of request tokens has exceeded your per-minute rate limit.'), { 'retry-after': '1' }],
    '429-short': [429, body('rate_limit_error', 'Number of request tokens has exceeded your per-minute rate limit.'), { 'retry-after': '1' }],
    '429-quota': [429, body('rate_limit_error', 'Number of request tokens has exceeded your per-minute rate limit.')],
    '500': [500, body('api_error', 'Internal server error')],
    '503': [529, body('overloaded_error', 'Overloaded')],
    '529': [529, body('overloaded_error', 'Overloaded')],
  };
  const [status, payload, headers] = table[error] ?? table[baseSwitch(error)];
  log('->', `${status} ${error}`);
  return json(ctx.res, status, payload, headers);
}

// ---------------------------------------------------------------------------------------------

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    const match = argv[i].match(/^--([a-z-]+)(?:=(.*))?$/);
    if (!match) continue;
    out[match[1]] = match[2] ?? argv[++i];
  }
  return out;
}
