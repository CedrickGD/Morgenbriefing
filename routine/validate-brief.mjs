#!/usr/bin/env node
/**
 * Validator for the Morgenbriefing data files on the `briefs` branch
 * (contract: docs/brief-format.md). Zero dependencies, Node >= 18, ESM.
 *
 *   node routine/validate-brief.mjs <datei...> [--max-age-hours 36]
 *
 * Every file is classified by its keys: an object with `sections`/`schema` is a daily brief
 * (`briefs/YYYY-MM-DD.json`, `latest.json`), an object with `days` is `index.json`.
 *
 * `--max-age-hours N` is the freshness policy of the cloud routine: every item needs a
 * `publishedAt`, and none may be older than N hours relative to the brief's `generatedAt`.
 * The check is relative to `generatedAt` (never to "now"), so it is deterministic and can
 * also run in CI on files published days ago.
 *
 * Exit codes: 0 = all files valid, 1 = at least one file invalid, 2 = usage error.
 * Messages are German because the routine (and the human reading CI logs) works in German.
 *
 * The validation functions are exported for reuse (publish-brief.mjs, tests).
 */
import { readFileSync, realpathSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/** Highest brief schema version this validator understands. */
export const SCHEMA_VERSION = 1;

/** Limits from docs/brief-format.md plus the editorial guidelines of the routine. */
export const LIMITS = Object.freeze({
  teaser: 220,
  headline: 120,
  /** Editorial guideline only (warning). */
  headlineWords: 10,
  summary: 450,
  importanceMin: 1,
  importanceMax: 10,
  /** Editorial guideline only (warning). */
  itemsPerSectionMin: 3,
  /** Editorial guideline only (warning). */
  itemsPerSectionMax: 4,
  indexMaxDays: 400,
  /** How far a `publishedAt` may lie after `generatedAt` (clock skew, sloppy time zones). */
  futureToleranceHours: 2,
});

/** The three rubrics the app is designed around (others are allowed). */
export const EXPECTED_SECTION_IDS = Object.freeze(['ki', 'wirtschaft', 'politik']);

const BRIEF_KEYS = new Set(['schema', 'date', 'generatedAt', 'teaser', 'sections']);
const SECTION_KEYS = new Set(['id', 'title', 'emoji', 'items']);
const ITEM_KEYS = new Set(['headline', 'summary', 'source', 'url', 'publishedAt', 'importance']);
const INDEX_KEYS = new Set(['updatedAt', 'days']);
const INDEX_ENTRY_KEYS = new Set(['date', 'teaser', 'itemCount']);

const DATE_RE = /^(\d{4})-(\d{2})-(\d{2})$/;
// ISO-8601 local date-time with a mandatory offset, as java.time.OffsetDateTime.parse accepts it.
const DATE_TIME_RE =
  /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(Z|([+-])(\d{2}):(\d{2}))$/;
const HOUR_MS = 3_600_000;

// ---------------------------------------------------------------------------------------------
// Primitive checks
// ---------------------------------------------------------------------------------------------

function isLeapYear(year) {
  return (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0;
}

function daysInMonth(year, month) {
  return [31, isLeapYear(year) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1];
}

function isValidYmd(year, month, day) {
  return year >= 1 && month >= 1 && month <= 12 && day >= 1 && day <= daysInMonth(year, month);
}

/** True for `YYYY-MM-DD` strings that name a real calendar day (no 2026-02-30). */
export function isCalendarDate(value) {
  if (typeof value !== 'string') return false;
  const m = DATE_RE.exec(value);
  return m !== null && isValidYmd(Number(m[1]), Number(m[2]), Number(m[3]));
}

/**
 * Parses an ISO-8601 date-time WITH offset (`2026-10-06T05:07:12+02:00`, `…Z`).
 * Returns `{ epochMs, date, offsetMinutes }` where `date` is the local calendar date as written,
 * or `null` if the string is not such a timestamp (including impossible dates and times).
 */
export function parseOffsetDateTime(value) {
  if (typeof value !== 'string') return null;
  const m = DATE_TIME_RE.exec(value);
  if (m === null) return null;
  const [year, month, day, hour, minute] = [m[1], m[2], m[3], m[4], m[5]].map(Number);
  const second = m[6] === undefined ? 0 : Number(m[6]);
  const millis = m[7] === undefined ? 0 : Number(m[7].padEnd(3, '0').slice(0, 3));
  if (!isValidYmd(year, month, day) || hour > 23 || minute > 59 || second > 59) return null;

  let offsetMinutes = 0;
  if (m[8] !== 'Z') {
    const offHours = Number(m[10]);
    const offMinutes = Number(m[11]);
    if (offHours > 18 || offMinutes > 59 || offHours * 60 + offMinutes > 18 * 60) return null;
    offsetMinutes = (m[9] === '-' ? -1 : 1) * (offHours * 60 + offMinutes);
  }
  // setUTCFullYear avoids Date.UTC's "years 0–99 mean 19xx" quirk.
  const utc = new Date(0);
  utc.setUTCFullYear(year, month - 1, day);
  utc.setUTCHours(hour, minute, second, millis);
  return {
    epochMs: utc.getTime() - offsetMinutes * 60_000,
    date: `${m[1]}-${m[2]}-${m[3]}`,
    offsetMinutes,
  };
}

/** Returns `null` for a usable absolute https URL, otherwise a German reason. */
export function httpsUrlProblem(value) {
  if (typeof value !== 'string' || value.trim() === '') return 'muss eine https-URL sein';
  if (/\s/.test(value)) return 'darf keine Leerzeichen enthalten';
  let url;
  try {
    url = new URL(value);
  } catch {
    return 'ist keine gültige URL';
  }
  if (url.protocol !== 'https:') return `muss mit https:// beginnen (ist ${url.protocol}//)`;
  if (!url.hostname || !url.hostname.includes('.')) return 'hat keinen gültigen Hostnamen';
  if (url.username || url.password) return 'darf keine Zugangsdaten enthalten';
  return null;
}

/** True for absolute https URLs with a real host name. */
export function isHttpsUrl(value) {
  return httpsUrlProblem(value) === null;
}

/** Number of user-visible characters (code points, so an emoji counts once). */
export function textLength(value) {
  return Array.from(value).length;
}

/** Words that contain at least one letter or digit ("–" or "&" alone do not count). */
export function wordCount(value) {
  return value
    .trim()
    .split(/\s+/)
    .filter((w) => /[\p{L}\p{N}]/u.test(w)).length;
}

/** Total number of news items in a (structurally valid) brief. */
export function countItems(brief) {
  if (!brief || !Array.isArray(brief.sections)) return 0;
  return brief.sections.reduce(
    (sum, s) => sum + (s && Array.isArray(s.items) ? s.items.length : 0),
    0,
  );
}

/** `'brief'`, `'index'` or `null`, decided by the top-level keys. */
export function detectKind(doc) {
  if (!isPlainObject(doc)) return null;
  if ('sections' in doc || 'schema' in doc) return 'brief';
  if ('days' in doc) return 'index';
  return null;
}

function isPlainObject(value) {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** Short, single-line rendering of a value for error messages. */
function show(value) {
  if (value === undefined) return 'fehlt';
  let text = JSON.stringify(value);
  if (text === undefined) text = String(value);
  return text.length > 70 ? `${text.slice(0, 67)}…` : text;
}

/** Comparable form of a URL for duplicate detection (host case, fragment, trailing slash). */
function urlKey(value) {
  try {
    const u = new URL(value);
    return `${u.hostname.toLowerCase().replace(/^www\./, '')}${u.pathname.replace(/\/+$/, '')}${u.search}`;
  } catch {
    return value;
  }
}

// ---------------------------------------------------------------------------------------------
// Document validators
// ---------------------------------------------------------------------------------------------

function collector() {
  const errors = [];
  const warnings = [];
  const at = (where, msg) => (where ? `${where}: ${msg}` : msg);
  return {
    errors,
    warnings,
    error: (where, msg) => errors.push(at(where, msg)),
    warn: (where, msg) => warnings.push(at(where, msg)),
  };
}

function reportUnknownKeys(obj, allowed, where, c) {
  for (const key of Object.keys(obj)) {
    if (!allowed.has(key)) {
      c.warn(where, `unbekanntes Feld "${key}" wird von der App ignoriert (Tippfehler?)`);
    }
  }
}

/** Non-empty (not just whitespace) string with an optional maximum length. */
function checkText(value, where, c, { max } = {}) {
  if (value === undefined) {
    c.error(where, 'fehlt');
    return null;
  }
  if (typeof value !== 'string') {
    c.error(where, `muss ein Text sein (gefunden: ${show(value)})`);
    return null;
  }
  if (value.trim() === '') {
    c.error(where, 'darf nicht leer sein');
    return null;
  }
  if (max !== undefined && textLength(value) > max) {
    c.error(where, `ist ${textLength(value)} Zeichen lang, erlaubt sind höchstens ${max}`);
  }
  return value;
}

function checkDateTime(value, where, c) {
  const parsed = parseOffsetDateTime(value);
  if (parsed === null) {
    c.error(
      where,
      `muss ein ISO-8601-Zeitpunkt mit Zeitzone sein, z. B. "2026-10-06T05:07:12+02:00" (gefunden: ${show(value)})`,
    );
  }
  return parsed;
}

function checkDate(value, where, c) {
  if (!isCalendarDate(value)) {
    c.error(where, `muss ein echtes Datum im Format YYYY-MM-DD sein (gefunden: ${show(value)})`);
    return null;
  }
  return value;
}

/**
 * Validates a daily brief (`briefs/YYYY-MM-DD.json` or `latest.json`).
 *
 * @param {unknown} doc parsed JSON
 * @param {{ maxAgeHours?: number }} [options] `maxAgeHours` enables the freshness policy:
 *   every item needs `publishedAt` and must not be older than that relative to `generatedAt`.
 * @returns {{ errors: string[], warnings: string[] }} errors make the file unusable;
 *   warnings point at editorial guidelines (3–4 items, ≤ 10 words …) and typos.
 */
export function validateBrief(doc, options = {}) {
  const { maxAgeHours } = options;
  const c = collector();
  if (!isPlainObject(doc)) {
    c.error('', `Ein Briefing muss ein JSON-Objekt sein (gefunden: ${show(doc)}).`);
    return { errors: c.errors, warnings: c.warnings };
  }
  reportUnknownKeys(doc, BRIEF_KEYS, '', c);

  if (doc.schema !== SCHEMA_VERSION) {
    c.error('schema', `muss ${SCHEMA_VERSION} sein (gefunden: ${show(doc.schema)})`);
  }

  const date = checkDate(doc.date, 'date', c);
  const generated = checkDateTime(doc.generatedAt, 'generatedAt', c);
  if (date !== null && generated !== null && generated.date !== date) {
    c.error(
      'generatedAt',
      `fällt auf den ${generated.date}, date ist aber ${date} – date muss das Berliner Datum des Morgens sein`,
    );
  }

  if (typeof doc.teaser !== 'string') {
    c.error('teaser', `muss ein Text sein (gefunden: ${show(doc.teaser)})`);
  } else if (doc.teaser.trim() === '') {
    c.warn('teaser', 'ist leer – ein Satz, der den Nachrichtentag zusammenfasst, macht das Briefing lebendiger');
  } else if (textLength(doc.teaser) > LIMITS.teaser) {
    c.error('teaser', `ist ${textLength(doc.teaser)} Zeichen lang, erlaubt sind höchstens ${LIMITS.teaser}`);
  }

  if (!Array.isArray(doc.sections)) {
    c.error('sections', `muss eine Liste von Rubriken sein (gefunden: ${show(doc.sections)})`);
    return { errors: c.errors, warnings: c.warnings };
  }
  if (doc.sections.length === 0) {
    c.error('sections', 'darf nicht leer sein');
    return { errors: c.errors, warnings: c.warnings };
  }

  const ctx = {
    c,
    generated,
    maxAgeHours,
    urls: new Map(),
    headlines: new Map(),
  };
  const sectionIds = new Map();
  let itemTotal = 0;

  doc.sections.forEach((section, si) => {
    const where = `sections[${si}]`;
    if (!isPlainObject(section)) {
      c.error(where, `muss ein Objekt mit id, title und items sein (gefunden: ${show(section)})`);
      return;
    }
    reportUnknownKeys(section, SECTION_KEYS, where, c);

    const id = checkText(section.id, `${where}.id`, c);
    if (id !== null) {
      if (sectionIds.has(id)) {
        c.error(`${where}.id`, `"${id}" kommt doppelt vor (schon in sections[${sectionIds.get(id)}])`);
      } else {
        sectionIds.set(id, si);
      }
      if (!/^[a-z0-9_-]+$/.test(id)) {
        c.warn(`${where}.id`, `"${id}" sollte nur aus Kleinbuchstaben, Ziffern, - und _ bestehen`);
      }
    }
    checkText(section.title, `${where}.title`, c);
    if (section.emoji === undefined || section.emoji === '') {
      c.warn(`${where}.emoji`, 'fehlt – die App zeigt die Rubrik dann ohne Emoji');
    } else if (typeof section.emoji !== 'string') {
      c.error(`${where}.emoji`, `muss ein Text sein (gefunden: ${show(section.emoji)})`);
    }

    if (!Array.isArray(section.items)) {
      c.error(`${where}.items`, `muss eine Liste von Meldungen sein (gefunden: ${show(section.items)})`);
      return;
    }
    const n = section.items.length;
    itemTotal += n;
    if (n === 0) {
      c.warn(`${where}.items`, 'enthält keine Meldungen – die Rubrik bleibt in der App leer');
    } else if (n < LIMITS.itemsPerSectionMin || n > LIMITS.itemsPerSectionMax) {
      c.warn(
        `${where}.items`,
        `${n} Meldungen – vorgesehen sind ${LIMITS.itemsPerSectionMin}–${LIMITS.itemsPerSectionMax} pro Rubrik`,
      );
    }
    section.items.forEach((item, ii) => validateItem(item, `${where}.items[${ii}]`, ctx));
  });

  if (itemTotal === 0) {
    c.error('sections', 'enthält keine einzige Meldung');
  }
  const missing = EXPECTED_SECTION_IDS.filter((id) => !sectionIds.has(id));
  if (missing.length > 0 && sectionIds.size > 0) {
    const names = missing.map((id) => `"${id}"`).join(', ');
    c.warn('sections', missing.length === 1 ? `Rubrik ${names} fehlt` : `Rubriken ${names} fehlen`);
  }
  return { errors: c.errors, warnings: c.warnings };
}

function validateItem(item, where, ctx) {
  const { c, generated, maxAgeHours } = ctx;
  if (!isPlainObject(item)) {
    c.error(where, `muss ein Objekt sein (gefunden: ${show(item)})`);
    return;
  }
  reportUnknownKeys(item, ITEM_KEYS, where, c);

  const headline = checkText(item.headline, `${where}.headline`, c, { max: LIMITS.headline });
  if (headline !== null) {
    const words = wordCount(headline);
    if (words > LIMITS.headlineWords) {
      c.warn(`${where}.headline`, `hat ${words} Wörter – höchstens ${LIMITS.headlineWords} lesen sich besser`);
    }
    if (/[\r\n]/.test(headline)) {
      c.warn(`${where}.headline`, 'enthält einen Zeilenumbruch');
    }
    const key = headline.trim().toLowerCase();
    if (ctx.headlines.has(key)) {
      c.warn(`${where}.headline`, `gleiche Überschrift wie ${ctx.headlines.get(key)}`);
    } else {
      ctx.headlines.set(key, where);
    }
  }
  checkText(item.summary, `${where}.summary`, c, { max: LIMITS.summary });
  checkText(item.source, `${where}.source`, c);

  const urlProblem = httpsUrlProblem(item.url);
  if (urlProblem !== null) {
    c.error(`${where}.url`, `${urlProblem} (gefunden: ${show(item.url)})`);
  } else {
    const key = urlKey(item.url);
    if (ctx.urls.has(key)) {
      c.error(`${where}.url`, `derselbe Artikel ist schon ${ctx.urls.get(key)} – keine Duplikate`);
    } else {
      ctx.urls.set(key, where);
    }
  }

  if (item.publishedAt === undefined || item.publishedAt === null) {
    if (maxAgeHours !== undefined) {
      c.error(
        `${where}.publishedAt`,
        `fehlt – mit --max-age-hours braucht jede Meldung ein geprüftes Veröffentlichungsdatum`,
      );
    } else {
      c.warn(`${where}.publishedAt`, 'fehlt – das Alter der Meldung lässt sich nicht prüfen');
    }
  } else {
    const published = checkDateTime(item.publishedAt, `${where}.publishedAt`, c);
    if (published !== null && generated !== null) {
      const ageHours = (generated.epochMs - published.epochMs) / HOUR_MS;
      if (-ageHours > LIMITS.futureToleranceHours) {
        c.error(
          `${where}.publishedAt`,
          `liegt ${(-ageHours).toFixed(1)} h nach generatedAt – Zeitzone vertauscht?`,
        );
      } else if (maxAgeHours !== undefined && ageHours > maxAgeHours) {
        c.error(
          `${where}.publishedAt`,
          `Meldung ist ${ageHours.toFixed(1)} h alt (bezogen auf generatedAt), erlaubt sind höchstens ${maxAgeHours} h`,
        );
      }
    }
  }

  const imp = item.importance;
  if (!Number.isInteger(imp) || imp < LIMITS.importanceMin || imp > LIMITS.importanceMax) {
    c.error(
      `${where}.importance`,
      `muss eine ganze Zahl von ${LIMITS.importanceMin} bis ${LIMITS.importanceMax} sein (gefunden: ${show(imp)})`,
    );
  }
}

/**
 * Validates `index.json`: `updatedAt` with offset, `days` with real, unique dates, newest first,
 * at most {@link LIMITS.indexMaxDays} entries.
 *
 * @param {unknown} doc parsed JSON
 * @returns {{ errors: string[], warnings: string[] }}
 */
export function validateIndex(doc) {
  const c = collector();
  if (!isPlainObject(doc)) {
    c.error('', `index.json muss ein JSON-Objekt sein (gefunden: ${show(doc)}).`);
    return { errors: c.errors, warnings: c.warnings };
  }
  reportUnknownKeys(doc, INDEX_KEYS, '', c);
  checkDateTime(doc.updatedAt, 'updatedAt', c);

  if (!Array.isArray(doc.days)) {
    c.error('days', `muss eine Liste sein (gefunden: ${show(doc.days)})`);
    return { errors: c.errors, warnings: c.warnings };
  }
  if (doc.days.length > LIMITS.indexMaxDays) {
    c.error('days', `enthält ${doc.days.length} Einträge, erlaubt sind höchstens ${LIMITS.indexMaxDays}`);
  }

  const seen = new Map();
  let previous = null;
  doc.days.forEach((entry, i) => {
    const where = `days[${i}]`;
    if (!isPlainObject(entry)) {
      c.error(where, `muss ein Objekt mit date, teaser und itemCount sein (gefunden: ${show(entry)})`);
      return;
    }
    reportUnknownKeys(entry, INDEX_ENTRY_KEYS, where, c);

    const date = checkDate(entry.date, `${where}.date`, c);
    if (date !== null) {
      if (seen.has(date)) {
        c.error(`${where}.date`, `${date} kommt doppelt vor (schon in days[${seen.get(date)}])`);
      } else {
        seen.set(date, i);
        if (previous !== null && date > previous) {
          c.error(`${where}.date`, `${date} steht nach ${previous} – neueste Tage müssen zuerst kommen`);
        }
        previous = date;
      }
    }

    if (entry.teaser === undefined) {
      c.warn(`${where}.teaser`, 'fehlt – der Verlauf zeigt dann keinen Teaser');
    } else if (typeof entry.teaser !== 'string') {
      c.error(`${where}.teaser`, `muss ein Text sein (gefunden: ${show(entry.teaser)})`);
    } else if (textLength(entry.teaser) > LIMITS.teaser) {
      c.warn(`${where}.teaser`, `ist länger als ${LIMITS.teaser} Zeichen`);
    }

    if (entry.itemCount === undefined) {
      c.warn(`${where}.itemCount`, 'fehlt');
    } else if (!Number.isInteger(entry.itemCount) || entry.itemCount < 0) {
      c.error(`${where}.itemCount`, `muss eine ganze Zahl ≥ 0 sein (gefunden: ${show(entry.itemCount)})`);
    }
  });
  return { errors: c.errors, warnings: c.warnings };
}

/**
 * Detects the kind of a parsed document and validates it accordingly.
 *
 * @returns {{ kind: 'brief' | 'index' | null, errors: string[], warnings: string[] }}
 */
export function validateDocument(doc, options = {}) {
  const kind = detectKind(doc);
  if (kind === 'brief') return { kind, ...validateBrief(doc, options) };
  if (kind === 'index') return { kind, ...validateIndex(doc) };
  return {
    kind: null,
    errors: [
      'Unbekanntes Dokument: weder ein Briefing (Feld "sections") noch index.json (Feld "days").',
    ],
    warnings: [],
  };
}

// ---------------------------------------------------------------------------------------------
// Files
// ---------------------------------------------------------------------------------------------

/** Error for files that cannot be read or parsed; `message` is ready to show (German). */
export class JsonFileError extends Error {
  constructor(message, options) {
    super(message, options);
    this.name = 'JsonFileError';
  }
}

/**
 * Reads and parses a UTF-8 JSON file. A leading BOM is tolerated (reported as warning).
 *
 * @returns {{ doc: unknown, text: string, warnings: string[] }}
 * @throws {JsonFileError} with code `ENOENT` (missing), `EREAD` (unreadable) or `EJSON` (syntax)
 */
export function readJsonFile(file) {
  let text;
  try {
    text = readFileSync(file, 'utf8');
  } catch (cause) {
    const missing = cause && cause.code === 'ENOENT';
    const err = new JsonFileError(
      missing ? 'Datei nicht gefunden' : `Datei nicht lesbar (${cause && cause.message})`,
      { cause },
    );
    err.code = missing ? 'ENOENT' : 'EREAD';
    throw err;
  }
  const warnings = [];
  if (text.charCodeAt(0) === 0xfeff) {
    text = text.slice(1);
    warnings.push('Datei beginnt mit einem BOM – bitte als UTF-8 ohne BOM speichern');
  }
  try {
    return { doc: JSON.parse(text), text, warnings };
  } catch (cause) {
    const err = new JsonFileError(`kein gültiges JSON: ${describeJsonError(cause, text)}`, { cause });
    err.code = 'EJSON';
    throw err;
  }
}

function describeJsonError(error, text) {
  const message = String(error && error.message ? error.message : error);
  const m = /position (\d+)/.exec(message);
  if (m === null || /line \d+/.test(message)) return message;
  const pos = Number(m[1]);
  const before = text.slice(0, pos);
  const line = before.split('\n').length;
  const column = pos - before.lastIndexOf('\n');
  return `${message} (Zeile ${line}, Spalte ${column})`;
}

/**
 * Reads, parses and validates one file.
 *
 * @returns {{ file: string, kind: 'brief'|'index'|null, doc: unknown, errors: string[], warnings: string[] }}
 */
export function validateFile(file, options = {}) {
  let parsed;
  try {
    parsed = readJsonFile(file);
  } catch (error) {
    if (error instanceof JsonFileError) {
      return { file, kind: null, doc: undefined, errors: [error.message], warnings: [] };
    }
    throw error;
  }
  const result = validateDocument(parsed.doc, options);
  return {
    file,
    kind: result.kind,
    doc: parsed.doc,
    errors: result.errors,
    warnings: [...parsed.warnings, ...result.warnings],
  };
}

/** One-line description of a validated document, e.g. "Briefing 2026-10-06 · 3 Rubriken · 10 Meldungen". */
export function describe(kind, doc) {
  if (kind === 'brief' && isPlainObject(doc)) {
    const sections = Array.isArray(doc.sections) ? doc.sections.length : 0;
    const items = countItems(doc);
    return `Briefing ${typeof doc.date === 'string' ? doc.date : '?'} · ${sections} ${
      sections === 1 ? 'Rubrik' : 'Rubriken'
    } · ${items} ${items === 1 ? 'Meldung' : 'Meldungen'}`;
  }
  if (kind === 'index' && isPlainObject(doc)) {
    const days = Array.isArray(doc.days) ? doc.days.length : 0;
    const newest = days > 0 && isPlainObject(doc.days[0]) ? `, neuester ${doc.days[0].date}` : '';
    return `Index · ${days} ${days === 1 ? 'Tag' : 'Tage'}${newest}`;
  }
  return 'unbekanntes Dokument';
}

// ---------------------------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------------------------

/** True when the module at `metaUrl` is the script Node was started with. */
export function isMainModule(metaUrl) {
  if (!process.argv[1]) return false;
  try {
    const self = realpathSync(fileURLToPath(metaUrl));
    const entry = realpathSync(path.resolve(process.argv[1]));
    return process.platform === 'win32' ? self.toLowerCase() === entry.toLowerCase() : self === entry;
  } catch {
    return false;
  }
}

/** Parses a positive number of hours; returns `null` if invalid. */
export function parseHours(value) {
  if (typeof value !== 'string' || !/^\d+(?:\.\d+)?$/.test(value.trim())) return null;
  const hours = Number(value);
  return hours > 0 && Number.isFinite(hours) ? hours : null;
}

const USAGE = `Prüft Morgenbriefing-Dateien (Briefings und index.json) gegen docs/brief-format.md.

Aufruf:
  node routine/validate-brief.mjs <datei...> [--max-age-hours <h>]

Optionen:
  --max-age-hours <h>  Jede Meldung braucht publishedAt und darf höchstens <h> Stunden
                       vor generatedAt veröffentlicht worden sein (Routine: 36).
  -h, --help           Diese Hilfe.

Exit-Code: 0 = alles gültig, 1 = Fehler gefunden, 2 = falscher Aufruf.`;

function parseCliArgs(argv) {
  const files = [];
  let maxAgeHours;
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '-h' || arg === '--help') return { help: true };
    if (arg === '--max-age-hours' || arg.startsWith('--max-age-hours=')) {
      const raw = arg.includes('=') ? arg.slice(arg.indexOf('=') + 1) : argv[++i];
      const hours = parseHours(raw);
      if (hours === null) {
        return { problem: `--max-age-hours braucht eine positive Zahl (gefunden: ${raw ?? 'nichts'})` };
      }
      maxAgeHours = hours;
    } else if (arg === '--') {
      files.push(...argv.slice(i + 1));
      break;
    } else if (arg.startsWith('-') && arg !== '-') {
      return { problem: `Unbekannte Option: ${arg}` };
    } else {
      files.push(arg);
    }
  }
  if (files.length === 0) return { problem: 'Keine Datei angegeben.' };
  return { files, maxAgeHours };
}

/** Escapes text for a GitHub Actions workflow command. */
function ghEscape(text) {
  return text.replace(/%/g, '%25').replace(/\r/g, '%0D').replace(/\n/g, '%0A');
}

/**
 * Runs the CLI and returns the exit code (does not call `process.exit`).
 *
 * @param {string[]} argv arguments without `node` and the script path
 */
export function main(argv = process.argv.slice(2), out = console.log, err = console.error) {
  const args = parseCliArgs(argv);
  if (args.help) {
    out(USAGE);
    return 0;
  }
  if (args.problem) {
    err(`${args.problem}\n\n${USAGE}`);
    return 2;
  }

  const inActions = process.env.GITHUB_ACTIONS === 'true';
  let invalid = 0;
  for (const file of args.files) {
    const result = validateFile(file, { maxAgeHours: args.maxAgeHours });
    if (result.errors.length === 0) {
      const hint = result.warnings.length > 0 ? ` · ${result.warnings.length} Hinweis(e)` : '';
      out(`OK      ${file} – ${describe(result.kind, result.doc)}${hint}`);
    } else {
      invalid++;
      const what = result.kind ? `${describe(result.kind, result.doc)} · ` : '';
      out(`FEHLER  ${file} – ${what}${result.errors.length} Fehler`);
      for (const e of result.errors) {
        out(`        Fehler:  ${e}`);
        if (inActions) out(`::error file=${file}::${ghEscape(e)}`);
      }
    }
    for (const w of result.warnings) {
      out(`        Hinweis: ${w}`);
      if (inActions) out(`::warning file=${file}::${ghEscape(w)}`);
    }
  }

  if (args.files.length > 1) {
    out(
      invalid === 0
        ? `Alle ${args.files.length} Dateien sind gültig.`
        : `${invalid} von ${args.files.length} Dateien ungültig.`,
    );
  }
  return invalid === 0 ? 0 : 1;
}

if (isMainModule(import.meta.url)) {
  process.exitCode = main();
}
