#!/usr/bin/env node
/**
 * Finds freely licensed pictures on Wikimedia Commons for an article of the Morgenbriefing
 * (contract: docs/brief-format.md, field `image`). Zero dependencies, Node >= 18 (global fetch), ESM.
 *
 *   node routine/find-image.mjs "<suchbegriffe>" [--limit 5]
 *
 * Prints a JSON array of candidates, best first:
 *
 *   [{ "url": "https://upload.wikimedia.org/…/1280px-….jpg", "credit": "Ralf Roletschek",
 *      "license": "CC BY-SA 3.0", "sourceUrl": "https://commons.wikimedia.org/wiki/File:….jpg",
 *      "width": 1280, "height": 850, "title": "File:….jpg", "description": "…" }]
 *
 * Only bitmap files under a license the validator accepts (CC0, public domain, PD-…, CC BY,
 * CC BY-SA) are returned; trademarked files, logos, files without a usable author credit and
 * tiny pictures are dropped. Landscape pictures at least 1000 px wide come first, otherwise the
 * Commons search order is kept. `url` is the ~1280 px thumbnail on upload.wikimedia.org (the host
 * the app accepts), without tracking parameters; `width`/`height` describe that thumbnail.
 *
 * Whether a candidate actually fits the story is an editorial decision the caller has to make –
 * the script only guarantees the license side.
 *
 * Exit codes: 0 = search done (the array may be empty), 1 = Commons not reachable / API error,
 * 2 = usage error. Diagnostics go to stderr, stdout carries only the JSON.
 */
import {
  IMAGE_SOURCE_PREFIX,
  IMAGE_URL_PREFIX,
  LIMITS,
  isFreeLicense,
  isMainModule,
  licenseNeedsCredit,
} from './validate-brief.mjs';

export const API_URL = 'https://commons.wikimedia.org/w/api.php';
/** Wikimedia asks every API client to identify itself with a descriptive User-Agent. */
export const USER_AGENT = 'MorgenbriefingRoutine/1.0 (https://github.com/CedrickGD/Morgenbriefing)';
export const DEFAULT_LIMIT = 5;
export const MAX_LIMIT = 20;
/** Requested thumbnail width; a standard Commons thumbnail step. */
export const THUMB_WIDTH = 1280;
/** Pictures at least this wide (and landscape) are preferred. */
export const PREFERRED_WIDTH = 1000;
/** Pictures narrower than this look blurry on a phone card and are dropped. */
export const MIN_WIDTH = 640;

const REQUEST_TIMEOUT_MS = 20_000;
const MAX_ATTEMPTS = 3;
const DESCRIPTION_MAX = 200;
const EXTMETADATA_FIELDS = [
  'Artist',
  'LicenseShortName',
  'ImageDescription',
  'ObjectName',
  'Restrictions',
  'NonFree',
  'AttributionRequired',
];

/** Raised when Commons cannot be reached or answers with an error; `message` is German. */
export class CommonsError extends Error {
  constructor(message, options) {
    super(message, options);
    this.name = 'CommonsError';
  }
}

/**
 * The search request: files (namespace 6) matching `terms`, bitmaps only, with URL, size and the
 * license metadata. Asks for more results than needed because many get filtered out.
 */
export function buildSearchUrl(terms, limit = DEFAULT_LIMIT) {
  const params = new URLSearchParams({
    action: 'query',
    generator: 'search',
    gsrnamespace: '6',
    gsrsearch: `${terms} filetype:bitmap`,
    gsrlimit: String(Math.min(50, Math.max(limit * 4, 20))),
    prop: 'imageinfo',
    iiprop: 'url|size|extmetadata',
    iiurlwidth: String(THUMB_WIDTH),
    iiextmetadatafilter: EXTMETADATA_FIELDS.join('|'),
    iiextmetadatalanguage: 'de',
    format: 'json',
    origin: '*',
  });
  return `${API_URL}?${params}`;
}

const NAMED_ENTITIES = {
  amp: '&',
  lt: '<',
  gt: '>',
  quot: '"',
  apos: "'",
  nbsp: ' ',
  ndash: '–',
  mdash: '—',
  hellip: '…',
  copy: '©',
};

/** Decodes the HTML entities Commons metadata typically contains. */
export function decodeEntities(text) {
  return text.replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (match, entity) => {
    if (entity[0] === '#') {
      const code = entity[1].toLowerCase() === 'x' ? parseInt(entity.slice(2), 16) : parseInt(entity.slice(1), 10);
      return Number.isFinite(code) && code > 0 && code <= 0x10ffff ? String.fromCodePoint(code) : match;
    }
    return NAMED_ENTITIES[entity.toLowerCase()] ?? match;
  });
}

/** Plain text from an HTML snippet (Commons' `Artist`, `ImageDescription`): tags out, entities decoded. */
export function stripHtml(html) {
  if (typeof html !== 'string') return '';
  return decodeEntities(
    html
      .replace(/<br\s*\/?>/gi, ' ')
      .replace(/<\/(?:p|div|li)>/gi, ' ')
      .replace(/<[^>]*>/g, ''),
  )
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * Canonical picture URL: Commons now hands out thumbnails on thumb.wikimedia.org with `utm_*`
 * parameters; the same path is served by upload.wikimedia.org, which is what the app accepts.
 * Returns `null` for anything that is not a Wikimedia upload/thumb URL.
 */
export function canonicalImageUrl(value) {
  let url;
  try {
    url = new URL(value);
  } catch {
    return null;
  }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') return null;
  if (url.hostname === 'thumb.wikimedia.org' || url.hostname === 'upload.wikimedia.org') {
    url.protocol = 'https:';
    url.hostname = 'upload.wikimedia.org';
  } else {
    return null;
  }
  for (const key of [...url.searchParams.keys()]) {
    if (key.toLowerCase().startsWith('utm_')) url.searchParams.delete(key);
  }
  url.hash = '';
  const text = url.toString().replace(/\?$/, '');
  return text.startsWith(IMAGE_URL_PREFIX) ? text : null;
}

/**
 * License name as the validator expects it. Multi-version tags such as "CC BY-SA 3.0,2.5,2.0,1.0"
 * (the author offers each version) become the first version, "CC BY-SA 3.0".
 */
export function normalizeLicense(value) {
  const text = stripHtml(value);
  const multi = /^(cc by(?:-sa)?) (\d\.\d)(?:\s*,\s*\d\.\d)+$/i.exec(text);
  return multi ? `${multi[1]} ${multi[2]}` : text;
}

const UNKNOWN_AUTHOR_RE = /^(?:unknown|unbekannt|anonymous|anonym)\b/i;
const LOGO_RE = /\blogo/i;

/**
 * Turns a Commons API response into candidates, best first.
 *
 * @param {any} response parsed JSON of {@link buildSearchUrl}
 * @param {{ limit?: number }} [options]
 * @returns {{ candidates: object[], rejected: Record<string, number> }} `rejected` counts the
 *   dropped files per reason (for diagnostics).
 */
export function parseCandidates(response, { limit = DEFAULT_LIMIT } = {}) {
  if (response && response.error) {
    const { code, info } = response.error;
    throw new CommonsError(`Commons-API meldet einen Fehler: ${code ?? '?'} – ${info ?? 'ohne Beschreibung'}`);
  }
  const pages = response?.query?.pages;
  const list = Array.isArray(pages) ? pages : pages && typeof pages === 'object' ? Object.values(pages) : [];
  const rejected = {};
  const reject = (reason) => {
    rejected[reason] = (rejected[reason] ?? 0) + 1;
  };

  const seen = new Set();
  const accepted = [];
  for (const page of list) {
    const info = Array.isArray(page?.imageinfo) ? page.imageinfo[0] : undefined;
    if (!info) {
      reject('ohne Bildinfo');
      continue;
    }
    const meta = info.extmetadata ?? {};
    const value = (field) => (meta[field] && typeof meta[field].value === 'string' ? meta[field].value : '');
    const title = typeof page.title === 'string' ? page.title : '';

    if (value('NonFree').trim().toLowerCase() === 'true') {
      reject('nicht frei');
      continue;
    }
    const license = normalizeLicense(value('LicenseShortName'));
    if (!isFreeLicense(license)) {
      reject('Lizenz');
      continue;
    }
    const restrictions = value('Restrictions').toLowerCase();
    if (restrictions.includes('trademark') || LOGO_RE.test(title)) {
      reject('Logo/Marke');
      continue;
    }

    let credit = stripHtml(value('Artist'));
    if (UNKNOWN_AUTHOR_RE.test(credit)) credit = '';
    const needsCredit = licenseNeedsCredit(license);
    if (needsCredit && (credit === '' || credit.length > LIMITS.imageCredit)) {
      reject('kein Urheber');
      continue;
    }
    if (!needsCredit && credit.length > LIMITS.imageCredit) credit = '';

    const url = canonicalImageUrl(info.thumburl || info.url);
    const sourceUrl = typeof info.descriptionurl === 'string' ? info.descriptionurl : '';
    if (url === null || !sourceUrl.startsWith(IMAGE_SOURCE_PREFIX)) {
      reject('URL');
      continue;
    }
    if (seen.has(url)) continue;
    seen.add(url);

    // For originals narrower than the requested thumbnail, Commons returns the original file as
    // `thumburl` but still reports the requested (upscaled) size – use the real one.
    const originalWidth = Number(info.width) || 0;
    const originalHeight = Number(info.height) || 0;
    let width = Number(info.thumbwidth) || originalWidth;
    let height = Number(info.thumbheight) || originalHeight;
    if (originalWidth > 0 && originalHeight > 0 && width > originalWidth) {
      width = originalWidth;
      height = originalHeight;
    }
    if (width < MIN_WIDTH || height <= 0) {
      reject('zu klein');
      continue;
    }

    let description = stripHtml(value('ImageDescription')) || stripHtml(value('ObjectName'));
    if (description.length > DESCRIPTION_MAX) description = `${description.slice(0, DESCRIPTION_MAX - 1).trimEnd()}…`;

    accepted.push({
      candidate: { url, credit, license, sourceUrl, width, height, title, description },
      rank: Number.isFinite(page.index) ? page.index : Number.MAX_SAFE_INTEGER,
      tier: preferenceTier(width, height),
    });
  }

  accepted.sort((a, b) => a.tier - b.tier || a.rank - b.rank);
  return { candidates: accepted.slice(0, limit).map((a) => a.candidate), rejected };
}

/** 0 = landscape ≥ 1000 px, 1 = smaller landscape, 2 = portrait/square ≥ 1000 px, 3 = the rest. */
function preferenceTier(width, height) {
  const landscape = width > height;
  const wide = width >= PREFERRED_WIDTH;
  if (landscape && wide) return 0;
  if (landscape) return 1;
  if (wide) return 2;
  return 3;
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Queries Commons and returns the parsed candidates. Retries network errors, 429 and 5xx up to
 * three times (honouring `Retry-After`, at most 10 s).
 *
 * @param {string} terms search terms (English or German)
 * @param {{ limit?: number, fetchImpl?: typeof fetch, retryDelayMs?: number }} [options]
 * @throws {CommonsError}
 */
export async function findImages(terms, { limit = DEFAULT_LIMIT, fetchImpl = globalThis.fetch, retryDelayMs = 1500 } = {}) {
  if (typeof fetchImpl !== 'function') throw new CommonsError('fetch ist nicht verfügbar – Node.js ≥ 18 nötig.');
  const url = buildSearchUrl(terms, limit);
  let lastProblem = '';
  for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
    let response;
    try {
      response = await fetchImpl(url, {
        headers: { 'User-Agent': USER_AGENT, 'Api-User-Agent': USER_AGENT, Accept: 'application/json' },
        signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      });
    } catch (error) {
      lastProblem = `Commons nicht erreichbar (${error?.name === 'TimeoutError' ? 'Zeitüberschreitung' : error?.message ?? error})`;
      if (attempt < MAX_ATTEMPTS) await sleep(retryDelayMs * attempt);
      continue;
    }
    if (response.status === 429 || response.status >= 500) {
      lastProblem = `Commons antwortet mit HTTP ${response.status}`;
      if (attempt < MAX_ATTEMPTS) {
        const retryAfter = Number(response.headers?.get?.('retry-after'));
        const wait = Number.isFinite(retryAfter) && retryAfter > 0 ? Math.min(retryAfter * 1000, 10_000) : retryDelayMs * attempt;
        await sleep(wait);
      }
      continue;
    }
    if (!response.ok) throw new CommonsError(`Commons antwortet mit HTTP ${response.status}`);
    let json;
    try {
      json = await response.json();
    } catch (cause) {
      throw new CommonsError('Commons hat kein gültiges JSON geliefert', { cause });
    }
    return parseCandidates(json, { limit });
  }
  throw new CommonsError(`${lastProblem} – nach ${MAX_ATTEMPTS} Versuchen aufgegeben`);
}

// ---------------------------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------------------------

const USAGE = `Sucht frei lizenzierte Bilder auf Wikimedia Commons (CC0, gemeinfrei, CC BY, CC BY-SA).

Aufruf:
  node routine/find-image.mjs "<suchbegriffe>" [--limit <n>]

Optionen:
  --limit <n>   Höchstens so viele Kandidaten (1–${MAX_LIMIT}, Standard ${DEFAULT_LIMIT}).
  -h, --help    Diese Hilfe.

Ausgabe (stdout): JSON-Liste [{ url, credit, license, sourceUrl, width, height, title, description }],
beste zuerst (Querformat ab ${PREFERRED_WIDTH} px). Leere Liste = nichts Passendes gefunden.
Ob ein Bild wirklich zur Geschichte passt, entscheidest du – das Skript prüft nur die Lizenz.

Exit-Code: 0 = Suche erledigt, 1 = Commons nicht erreichbar, 2 = falscher Aufruf.`;

function parseCliArgs(argv) {
  const terms = [];
  let limit = DEFAULT_LIMIT;
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '-h' || arg === '--help') return { help: true };
    if (arg === '--limit' || arg.startsWith('--limit=')) {
      const raw = arg.includes('=') ? arg.slice(arg.indexOf('=') + 1) : argv[++i];
      const n = Number(raw);
      if (!/^\d+$/.test(String(raw ?? '')) || n < 1 || n > MAX_LIMIT) {
        return { problem: `--limit braucht eine ganze Zahl von 1 bis ${MAX_LIMIT} (gefunden: ${raw ?? 'nichts'})` };
      }
      limit = n;
    } else if (arg === '--') {
      terms.push(...argv.slice(i + 1));
      break;
    } else if (arg.startsWith('--')) {
      return { problem: `Unbekannte Option: ${arg}` };
    } else {
      terms.push(arg);
    }
  }
  const query = terms.join(' ').replace(/\s+/g, ' ').trim();
  if (query === '') return { problem: 'Keine Suchbegriffe angegeben.' };
  if (query.length > 250) return { problem: 'Suchbegriffe sind zu lang (höchstens 250 Zeichen).' };
  return { query, limit };
}

/**
 * Runs the CLI and resolves to the exit code (does not call `process.exit`).
 *
 * @param {string[]} argv arguments without `node` and the script path
 */
export async function main(
  argv = process.argv.slice(2),
  { out = console.log, err = console.error, fetchImpl = globalThis.fetch, retryDelayMs } = {},
) {
  const args = parseCliArgs(argv);
  if (args.help) {
    out(USAGE);
    return 0;
  }
  if (args.problem) {
    err(`${args.problem}\n\n${USAGE}`);
    return 2;
  }
  try {
    const { candidates, rejected } = await findImages(args.query, { limit: args.limit, fetchImpl, retryDelayMs });
    out(JSON.stringify(candidates, null, 2));
    const dropped = Object.entries(rejected)
      .map(([reason, n]) => `${n}× ${reason}`)
      .join(', ');
    err(
      `${candidates.length} ${candidates.length === 1 ? 'Kandidat' : 'Kandidaten'} für „${args.query}“` +
        (dropped ? ` (verworfen: ${dropped})` : ''),
    );
    return 0;
  } catch (error) {
    if (!(error instanceof CommonsError)) throw error;
    err(`FEHLER: ${error.message}`);
    return 1;
  }
}

if (isMainModule(import.meta.url)) {
  process.exitCode = await main();
}
