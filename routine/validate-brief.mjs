#!/usr/bin/env node
/**
 * Validator for the Morgenbriefing data files on the `briefs` branch
 * (contract: docs/brief-format.md). Zero dependencies, Node >= 18, ESM.
 *
 *   node routine/validate-brief.mjs <datei...> [--max-age-hours 36] [--require-images] [--no-repeat-days 14]
 *   node routine/validate-brief.mjs --section <rubrik.json...> [--max-age-hours 36] [--require-images]
 *        [--no-repeat-days 14] [--now <ISO>]
 *
 * Every file is classified by its keys: an object with `sections`/`schema` is a daily brief
 * (`briefs/YYYY-MM-DD.json`, `latest.json`), an object with `days` is `index.json`. With
 * `--section` every file is a single section (`{ id, title, emoji, items }`) as the per-topic
 * agents of the routine write it to `/tmp/sections/<id>.json`; sections always follow the
 * schema-2 rules.
 *
 * Briefs come in two schemas: 1 = headline + summary + link (v1.0 of the app), 2 = full original
 * articles with sources, tiers ("top"/"more") and optional Wikimedia Commons images.
 *
 * `--max-age-hours N` is the freshness policy of the cloud routine: every item needs a
 * `publishedAt`, and none may be older than N hours. For briefs the reference is the brief's
 * `generatedAt` (never "now"), so the check is deterministic and can also run in CI on files
 * published days ago. Sections have no `generatedAt`; they are checked against the current time
 * or the instant given with `--now`.
 *
 * `--require-images` is the picture policy of the routine: every schema-2 article needs an `image`
 * (without the option a missing picture is fine, so older briefs stay valid). The same picture
 * twice in one brief – or, with `--section`, in two of the given sections – is a warning.
 *
 * `--no-repeat-days N` keeps the pictures fresh across days: a picture whose Commons file (in any
 * size) ran in one of the briefs of the N days before the brief's date is an error. The previous
 * briefs come from git (`origin/briefs`, fetched if missing; `--recent-ref`) or from a directory
 * (`--recent-from`), see recent-images.mjs. Sections count for the Berlin date of `--now`/now.
 *
 * Exit codes: 0 = all files valid, 1 = at least one file invalid, 2 = usage error.
 * Messages are German because the routine (and the human reading CI logs) works in German.
 *
 * The validation functions are exported for reuse (publish-brief.mjs, assemble-brief.mjs,
 * find-image.mjs, tests).
 */
import { readFileSync, realpathSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/** Highest brief schema version this validator understands. */
export const SCHEMA_VERSION = 2;

/** Every schema version this validator accepts. */
export const SUPPORTED_SCHEMAS = Object.freeze([1, 2]);

/**
 * The topic catalogue – same ids, titles, emojis and order as
 * `app/src/main/java/com/cedrickgd/morgenbriefing/data/model/Topics.kt`.
 */
export const TOPICS = Object.freeze(
  [
    { id: 'ki', title: 'KI & Tech', emoji: '🤖' },
    { id: 'wirtschaft', title: 'Wirtschaft & Märkte', emoji: '📈' },
    { id: 'politik', title: 'Politik', emoji: '🏛️' },
    { id: 'welt', title: 'Welt', emoji: '🌍' },
    { id: 'region', title: 'Leipzig & Region', emoji: '📍' },
    { id: 'sport', title: 'Sport', emoji: '⚽' },
    { id: 'wissenschaft', title: 'Wissenschaft & Raumfahrt', emoji: '🔭' },
    { id: 'gesundheit', title: 'Gesundheit', emoji: '🩺' },
    { id: 'klima', title: 'Klima & Umwelt', emoji: '🌱' },
    { id: 'entertainment', title: 'Games & Entertainment', emoji: '🎮' },
  ].map((t) => Object.freeze(t)),
);

const TOPIC_RANK = new Map(TOPICS.map((t, i) => [t.id, i]));

/** The catalogue entry for `id`, or `undefined` for topics the app does not know. */
export function topicById(id) {
  const rank = TOPIC_RANK.get(id);
  return rank === undefined ? undefined : TOPICS[rank];
}

/** Position of `id` in the catalogue; unknown ids sort after all known ones. */
export function topicRank(id) {
  return TOPIC_RANK.get(id) ?? TOPICS.length;
}

export const TIER_TOP = 'top';
export const TIER_MORE = 'more';

/** Limits from docs/brief-format.md plus the editorial guidelines of the routine. */
export const LIMITS = Object.freeze({
  teaser: 220,
  headline: 120,
  /** Editorial guideline only (warning). */
  headlineWords: 10,
  /** Schema 1. */
  summary: 450,
  /** Schema 2: the lead is shorter, the article carries the rest. */
  summaryV2: 300,
  /** Editorial guideline only (warning). */
  summarySentences: 2,
  importanceMin: 1,
  importanceMax: 10,
  /** Schema 1, editorial guideline only (warning). */
  itemsPerSectionMin: 3,
  /** Schema 1, editorial guideline only (warning). */
  itemsPerSectionMax: 4,
  /** Schema 2: per section 1–3 `top` items … */
  topPerSectionMin: 1,
  topPerSectionMax: 3,
  /** … and up to 5 `more` items behind "+ Mehr". */
  morePerSectionMax: 5,
  /** Schema 2: body length in words per tier. */
  bodyWords: Object.freeze({
    top: Object.freeze({ min: 250, max: 700 }),
    more: Object.freeze({ min: 120, max: 400 }),
  }),
  /** Schema 2: minimum number of distinct sources per tier. */
  sourcesMin: Object.freeze({ top: 2, more: 1 }),
  /** Reading speed behind `readingMinutes`. */
  wordsPerMinute: 200,
  /** `readingMinutes` may differ this much from the computed value before a warning. */
  readingMinutesTolerance: 1,
  /** A quoted passage must stay below this many words (legal guideline, error). */
  quoteWords: 15,
  /** Item ids (slugs). */
  idLength: 80,
  imageCredit: 200,
  imageAlt: 200,
  /** Editorial guideline only (warning). */
  paragraphWords: 200,
  subheading: 80,
  indexMaxDays: 400,
  /** How far a `publishedAt` may lie after the reference time (clock skew, sloppy time zones). */
  futureToleranceHours: 2,
});

/** The three rubrics a schema-1 brief is designed around (others are allowed). */
export const EXPECTED_SECTION_IDS = Object.freeze(['ki', 'wirtschaft', 'politik']);

/** Images must be Commons files, loaded from Wikimedia's upload server. */
export const IMAGE_URL_PREFIX = 'https://upload.wikimedia.org/';
/** Attribution links point to the file page on Commons. */
export const IMAGE_SOURCE_PREFIX = 'https://commons.wikimedia.org/';

const BRIEF_KEYS = new Set(['schema', 'date', 'generatedAt', 'teaser', 'sections']);
const SECTION_KEYS = new Set(['id', 'title', 'emoji', 'items']);
const ITEM_KEYS_V1 = new Set(['headline', 'summary', 'source', 'url', 'publishedAt', 'importance']);
const ITEM_KEYS_V2 = new Set([
  'id',
  'tier',
  'headline',
  'summary',
  'body',
  'source',
  'url',
  'sources',
  'publishedAt',
  'importance',
  'readingMinutes',
  'image',
]);
const SOURCE_KEYS = new Set(['name', 'url']);
const IMAGE_KEYS = new Set(['url', 'credit', 'license', 'sourceUrl', 'alt']);
const INDEX_KEYS = new Set(['updatedAt', 'days']);
const INDEX_ENTRY_KEYS = new Set(['date', 'teaser', 'itemCount']);

const DATE_RE = /^(\d{4})-(\d{2})-(\d{2})$/;
// ISO-8601 local date-time with a mandatory offset, as java.time.OffsetDateTime.parse accepts it.
const DATE_TIME_RE =
  /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(Z|([+-])(\d{2}):(\d{2}))$/;
const SLUG_RE = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
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

/** Words that contain at least one letter or digit ("–", "&", "##" or "-" alone do not count). */
export function wordCount(value) {
  return value
    .trim()
    .split(/\s+/)
    .filter((w) => /[\p{L}\p{N}]/u.test(w)).length;
}

/** `readingMinutes` for a body of `words` words: round(words / 200), at least 1. */
export function expectedReadingMinutes(words) {
  return Math.max(1, Math.round(words / LIMITS.wordsPerMinute));
}

/** True for lowercase slugs like `ki-openai-chips-2026` (schema-2 item ids). */
export function isSlug(value) {
  return typeof value === 'string' && value.length <= LIMITS.idLength && SLUG_RE.test(value);
}

// Free licenses we accept for pictures: CC0, public domain (incl. Commons' PD-* tags), CC BY and
// CC BY-SA in any version, optionally ported ("CC BY-SA 3.0 de", "CC BY 3.0 igo").
// NC/ND variants, GFDL-only, fair use and "all rights reserved" are deliberately not covered.
const FREE_LICENSE_RE =
  /^(?:cc0(?:[ -]1\.0)?|public[ -]domain|pd-[a-z0-9][a-z0-9.-]*|cc[ -]by(?:-sa)?[ -]\d\.\d(?:[ -][a-z]{2,4})?)$/i;
const NO_ATTRIBUTION_RE = /^(?:cc0(?:[ -]1\.0)?|public[ -]domain|pd-[a-z0-9][a-z0-9.-]*)$/i;

/** Normalizes whitespace in a license name ("CC  BY-SA 4.0 " → "CC BY-SA 4.0"). */
function normalizeLicense(value) {
  return typeof value === 'string' ? value.trim().replace(/\s+/g, ' ') : '';
}

/**
 * True for the free licenses an image may carry (case-insensitive): `CC0`, `Public domain`,
 * `PD-…`, `CC BY x.y` and `CC BY-SA x.y` (optionally with a jurisdiction suffix).
 */
export function isFreeLicense(license) {
  return FREE_LICENSE_RE.test(normalizeLicense(license));
}

/** False for CC0 and public-domain images, which need no author credit. */
export function licenseNeedsCredit(license) {
  return !NO_ATTRIBUTION_RE.test(normalizeLicense(license));
}

/** Total number of news items in a (structurally valid) brief. */
export function countItems(brief) {
  if (!brief || !Array.isArray(brief.sections)) return 0;
  return brief.sections.reduce(
    (sum, s) => sum + (s && Array.isArray(s.items) ? s.items.length : 0),
    0,
  );
}

/** Number of items with an image in a brief or a single section. */
export function countImages(doc) {
  const sections = Array.isArray(doc?.sections) ? doc.sections : [doc];
  let n = 0;
  for (const s of sections) {
    if (!s || !Array.isArray(s.items)) continue;
    for (const item of s.items) if (isPlainObject(item) && isPlainObject(item.image)) n++;
  }
  return n;
}

/** `'brief'`, `'index'`, `'section'` or `null`, decided by the top-level keys. */
export function detectKind(doc) {
  if (!isPlainObject(doc)) return null;
  if ('sections' in doc || 'schema' in doc) return 'brief';
  if ('days' in doc) return 'index';
  if ('items' in doc) return 'section';
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
export function urlKey(value) {
  try {
    const u = new URL(value);
    return `${u.hostname.toLowerCase().replace(/^www\./, '')}${u.pathname.replace(/\/+$/, '')}${u.search}`;
  } catch {
    return value;
  }
}

function hostOf(value) {
  try {
    return new URL(value).hostname.toLowerCase().replace(/^www\./, '');
  } catch {
    return '';
  }
}

const FILE_TITLE_RE = /^(?:file|datei):(.+)$/i;
const UPLOAD_PATH_RE = /^\/wikipedia\/commons\/(?:thumb\/)?[0-9a-f]\/[0-9a-f]{2}\/([^/]+)/i;

/**
 * Name of the Commons file a picture refers to ("Reichstag pano.jpg"), taken from an
 * upload.wikimedia.org URL (original or thumbnail of any size), a file page on
 * commons.wikimedia.org or a "File:…" title. Same file → same name, so duplicates are found even
 * when one article uses the 1280 px and another the 960 px thumbnail. `null` for anything else.
 */
export function commonsFileKey(value) {
  if (typeof value !== 'string') return null;
  const text = value.trim();
  let name = null;
  const title = FILE_TITLE_RE.exec(text);
  if (title) {
    name = title[1];
  } else {
    let url;
    try {
      url = new URL(text);
    } catch {
      return null;
    }
    const host = url.hostname.toLowerCase();
    if (host === 'upload.wikimedia.org' || host === 'thumb.wikimedia.org') {
      const m = UPLOAD_PATH_RE.exec(url.pathname);
      if (m) name = m[1];
    } else if (host === 'commons.wikimedia.org' || host === 'commons.m.wikimedia.org') {
      const m = /^\/wiki\/(.+)$/.exec(url.pathname);
      const page = m ? safeDecode(m[1]) : url.searchParams.get('title') ?? '';
      const file = FILE_TITLE_RE.exec(page);
      if (file) return tidyFileName(file[1]);
    }
  }
  return name === null ? null : tidyFileName(safeDecode(name));
}

function safeDecode(text) {
  try {
    return decodeURIComponent(text);
  } catch {
    return text;
  }
}

/** MediaWiki spelling of a file name: spaces instead of underscores, first letter capitalized. */
function tidyFileName(name) {
  const tidy = name.replace(/_/g, ' ').replace(/\s+/g, ' ').trim();
  return tidy === '' ? null : tidy.charAt(0).toUpperCase() + tidy.slice(1);
}

/** Key for "same picture" checks: the Commons file name, otherwise the normalized URL. */
export function imageKey(value) {
  return commonsFileKey(value) ?? urlKey(value);
}

/**
 * Key for "same picture on another day" (`--no-repeat-days`, find-image.mjs `--recent-days`):
 * the Commons file name in lower case – any thumbnail size, the original, the file page or a
 * "File:" title –, otherwise host and path of the URL in lower case. `null` for empty values.
 */
export function imageFileKey(value) {
  if (typeof value !== 'string' || value.trim() === '') return null;
  const file = commonsFileKey(value);
  if (file !== null) return file.toLowerCase();
  try {
    const u = new URL(value.trim());
    return `${u.hostname.replace(/^www\./i, '')}${u.pathname}`.toLowerCase();
  } catch {
    return value.trim().toLowerCase();
  }
}

/** Calendar date (`YYYY-MM-DD`) of an instant in Europe/Berlin – the date the brief of that morning carries. */
export function berlinDate(epochMs = Date.now()) {
  return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Europe/Berlin' }).format(new Date(epochMs));
}

// ---------------------------------------------------------------------------------------------
// Article bodies (schema 2)
// ---------------------------------------------------------------------------------------------

const HTML_TAG_RE = /<\/?[a-z][a-z0-9-]*(?:\s[^<>]*)?\/?>|<!--/i;
const HTML_ENTITY_RE = /&(?:[a-z]{2,8}|#\d{2,6}|#x[0-9a-f]{2,6});/i;
const MD_IMAGE_RE = /!\[[^\]]*\]\([^)]*\)/;
const MD_LINK_RE = /\[[^\]\n]+\]\([^)\n]*\)/;
const URL_IN_TEXT_RE = /\bhttps?:\/\/|\bwww\.[a-z0-9-]+\.[a-z]/i;
const MD_EMPHASIS_RE = /\*\*[^*\n]+\*\*|__[^_\n]+__/;
const BULLET_RE = /^- \S/;
const WRONG_BULLET_RE = /^[*+•] /;
// Quoted passages: German „…“, guillemets »…« / «…», English “…” and straight "…".
const QUOTE_RES = [/„([^“”"\n]+)[“”"]/g, /»([^«\n]+)«/g, /«([^»\n]+)»/g, /“([^”\n]+)”/g, /"([^"\n]+)"/g];

/** Splits a body into blocks (paragraphs, subheadings, bullet lists) at blank lines. */
export function bodyBlocks(body) {
  return body
    .replace(/\r\n?/g, '\n')
    .split(/\n[ \t]*\n/)
    .map((b) => b.trim())
    .filter((b) => b !== '');
}

/** Quoted passages in `text` (outermost quotes; German quotes first). */
export function quotedPassages(text) {
  const found = [];
  let rest = text;
  for (const re of QUOTE_RES) {
    rest = rest.replace(re, (_, inner) => {
      found.push(inner.trim());
      return ' ';
    });
  }
  return found;
}

/**
 * Analyses a schema-2 article body: word count, structure and format problems.
 *
 * @param {string} body
 * @returns {{ words: number, paragraphs: number, errors: string[], warnings: string[] }}
 */
export function analyzeBody(body) {
  const errors = [];
  const warnings = [];
  const blocks = bodyBlocks(body);
  let paragraphs = 0;

  const tag = HTML_TAG_RE.exec(body);
  if (tag) errors.push(`enthält HTML (${show(tag[0])}) – nur reiner Text`);
  if (HTML_ENTITY_RE.test(body)) warnings.push('enthält HTML-Entitäten wie &amp; – Zeichen direkt schreiben');
  if (MD_IMAGE_RE.test(body)) errors.push('enthält ein Markdown-Bild – Bilder gehören nur in "image"');
  else if (MD_LINK_RE.test(body)) errors.push('enthält einen Markdown-Link – Links gehören nur in "sources"');
  if (URL_IN_TEXT_RE.test(body)) errors.push('enthält eine URL – Links gehören nur in "sources"');
  if (MD_EMPHASIS_RE.test(body)) warnings.push('enthält **Fett**/__Unterstrichen__ – die App zeigt das nicht an');

  blocks.forEach((block, bi) => {
    const lines = block.split('\n').map((l) => l.trim());
    const label = `Block ${bi + 1}`;
    if (lines[0].startsWith('#')) {
      if (!/^## \S/.test(lines[0]) || lines[0].startsWith('###')) {
        errors.push(`${label}: Zwischenüberschriften nur als „## Titel“ (gefunden: ${show(lines[0])})`);
      } else if (textLength(lines[0]) - 3 > LIMITS.subheading) {
        warnings.push(`${label}: Zwischenüberschrift ist länger als ${LIMITS.subheading} Zeichen`);
      }
      if (lines.length > 1) {
        warnings.push(`${label}: nach einer Zwischenüberschrift eine Leerzeile setzen`);
      }
      if (bi === blocks.length - 1) warnings.push(`${label}: Text endet mit einer Zwischenüberschrift`);
      return;
    }
    if (lines.some((l) => l.startsWith('#'))) {
      errors.push(`${label}: Zwischenüberschriften brauchen einen eigenen Block (Leerzeile davor und danach)`);
    }
    const bullets = lines.filter((l) => BULLET_RE.test(l)).length;
    if (bullets === lines.length) return; // a bullet list
    paragraphs++;
    if (lines.some((l) => WRONG_BULLET_RE.test(l))) {
      warnings.push(`${label}: Aufzählungen nur mit „- “ am Zeilenanfang`);
    } else if (bullets > 0) {
      warnings.push(`${label}: Absatz und Aufzählung mit einer Leerzeile trennen`);
    } else if (lines.length > 1) {
      warnings.push(`${label}: Zeilenumbruch mitten im Absatz – Absätze nur mit einer Leerzeile trennen`);
    }
    const words = wordCount(block);
    if (words > LIMITS.paragraphWords) {
      warnings.push(`${label}: Absatz mit ${words} Wörtern – kürzere Absätze lesen sich besser`);
    }
  });

  const quotes = quotedPassages(body).map((q) => ({ q, words: wordCount(q) }));
  for (const { q, words } of quotes) {
    if (words >= LIMITS.quoteWords) {
      errors.push(
        `wörtliches Zitat mit ${words} Wörtern (${show(q)}) – höchstens ${LIMITS.quoteWords - 1} Wörter, sonst in eigenen Worten`,
      );
    }
  }
  const longish = quotes.filter((x) => x.words >= 5).length;
  if (longish > 1) {
    warnings.push(`${longish} längere Passagen in Anführungszeichen – höchstens ein kurzes Zitat pro Artikel`);
  }

  return { words: wordCount(body), paragraphs, errors, warnings };
}

// Abbreviations and ordinals whose period does not end a sentence.
const NON_TERMINAL_RE =
  /\b(?:z\. ?B|d\. ?h|u\. ?a|v\. ?a|u\. ?U|o\. ?ä|s\. ?o|Mrd|Mio|Tsd|Nr|Dr|Prof|St|ca|bzw|vgl|inkl|exkl|etc|Jh|Jhd|Abs|Art|Bd|ggf|evtl|sog|usw|Str|Hbf|gem|geb|max|min|rd)\.|\b\d{1,4}\./g;

/** Rough sentence count for leads (abbreviations like "z. B." and "1. FC" are ignored). */
export function sentenceCount(text) {
  const cleaned = text.replace(NON_TERMINAL_RE, (m) => m.replace(/\./g, ''));
  const parts = cleaned
    .split(/[.!?…]+["“”»«']?(?:\s+|$)/)
    .map((p) => p.trim())
    .filter((p) => /[\p{L}\p{N}]/u.test(p));
  return parts.length;
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
 * Shared state while validating the items of one brief (or one section): duplicate detection,
 * the reference time of the freshness check and the pictures of the previous days.
 */
function itemContext(c, { referenceMs, referenceLabel, maxAgeHours, requireImages = false, recentImages = null }) {
  return {
    c,
    referenceMs,
    referenceLabel,
    maxAgeHours,
    requireImages,
    recentImages,
    urls: new Map(),
    headlines: new Map(),
    ids: new Map(),
    images: new Map(),
  };
}

function checkHeadline(item, where, ctx) {
  const { c } = ctx;
  const headline = checkText(item.headline, `${where}.headline`, c, { max: LIMITS.headline });
  if (headline === null) return;
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

/** Checks the article URL; returns it when usable. */
function checkItemUrl(item, where, ctx) {
  const { c } = ctx;
  const urlProblem = httpsUrlProblem(item.url);
  if (urlProblem !== null) {
    c.error(`${where}.url`, `${urlProblem} (gefunden: ${show(item.url)})`);
    return null;
  }
  const key = urlKey(item.url);
  if (ctx.urls.has(key)) {
    c.error(`${where}.url`, `derselbe Artikel ist schon ${ctx.urls.get(key)} – keine Duplikate`);
  } else {
    ctx.urls.set(key, where);
  }
  return item.url;
}

function checkPublishedAt(item, where, ctx) {
  const { c, referenceMs, referenceLabel, maxAgeHours } = ctx;
  if (item.publishedAt === undefined || item.publishedAt === null) {
    if (maxAgeHours !== undefined) {
      c.error(
        `${where}.publishedAt`,
        `fehlt – mit --max-age-hours braucht jede Meldung ein geprüftes Veröffentlichungsdatum`,
      );
    } else {
      c.warn(`${where}.publishedAt`, 'fehlt – das Alter der Meldung lässt sich nicht prüfen');
    }
    return;
  }
  const published = checkDateTime(item.publishedAt, `${where}.publishedAt`, c);
  if (published === null || referenceMs === null) return;
  const ageHours = (referenceMs - published.epochMs) / HOUR_MS;
  if (-ageHours > LIMITS.futureToleranceHours) {
    c.error(
      `${where}.publishedAt`,
      `liegt ${(-ageHours).toFixed(1)} h nach ${referenceLabel} – Zeitzone vertauscht?`,
    );
  } else if (maxAgeHours !== undefined && ageHours > maxAgeHours) {
    c.error(
      `${where}.publishedAt`,
      `Meldung ist ${ageHours.toFixed(1)} h alt (bezogen auf ${referenceLabel}), erlaubt sind höchstens ${maxAgeHours} h`,
    );
  }
}

function checkImportance(item, where, c) {
  const imp = item.importance;
  if (!Number.isInteger(imp) || imp < LIMITS.importanceMin || imp > LIMITS.importanceMax) {
    c.error(
      `${where}.importance`,
      `muss eine ganze Zahl von ${LIMITS.importanceMin} bis ${LIMITS.importanceMax} sein (gefunden: ${show(imp)})`,
    );
  }
}

/** Common section fields: id (unique), title, emoji. Returns the id when usable. */
function checkSectionHead(section, where, c, sectionIds, schema) {
  reportUnknownKeys(section, SECTION_KEYS, where, c);
  const id = checkText(section.id, `${where}.id`, c);
  if (id !== null) {
    if (sectionIds) {
      if (sectionIds.has(id)) {
        c.error(`${where}.id`, `"${id}" kommt doppelt vor (schon in sections[${sectionIds.get(id)}])`);
      } else {
        sectionIds.set(id, where);
      }
    }
    if (!/^[a-z0-9_-]+$/.test(id)) {
      c.warn(`${where}.id`, `"${id}" sollte nur aus Kleinbuchstaben, Ziffern, - und _ bestehen`);
    }
  }
  const title = checkText(section.title, `${where}.title`, c);
  if (section.emoji === undefined || section.emoji === '') {
    c.warn(`${where}.emoji`, 'fehlt – die App zeigt die Rubrik dann ohne Emoji');
  } else if (typeof section.emoji !== 'string') {
    c.error(`${where}.emoji`, `muss ein Text sein (gefunden: ${show(section.emoji)})`);
  }

  if (schema === 2 && id !== null) {
    const topic = topicById(id);
    if (topic === undefined) {
      c.warn(
        `${where}.id`,
        `"${id}" ist keine Rubrik aus dem Katalog (${TOPICS.map((t) => t.id).join(', ')}) – die App zeigt sie neutral an`,
      );
    } else {
      if (title !== null && title !== topic.title) {
        c.warn(`${where}.title`, `"${title}" weicht vom Katalog ab ("${topic.title}")`);
      }
      if (typeof section.emoji === 'string' && section.emoji !== '' && section.emoji !== topic.emoji) {
        c.warn(`${where}.emoji`, `"${section.emoji}" weicht vom Katalog ab ("${topic.emoji}")`);
      }
    }
  }
  return id;
}

// ----- schema 1 --------------------------------------------------------------------------------

function validateSectionV1(section, where, ctx, sectionIds) {
  const { c } = ctx;
  checkSectionHead(section, where, c, sectionIds, 1);
  if (!Array.isArray(section.items)) {
    c.error(`${where}.items`, `muss eine Liste von Meldungen sein (gefunden: ${show(section.items)})`);
    return 0;
  }
  const n = section.items.length;
  if (n === 0) {
    c.warn(`${where}.items`, 'enthält keine Meldungen – die Rubrik bleibt in der App leer');
  } else if (n < LIMITS.itemsPerSectionMin || n > LIMITS.itemsPerSectionMax) {
    c.warn(
      `${where}.items`,
      `${n} Meldungen – vorgesehen sind ${LIMITS.itemsPerSectionMin}–${LIMITS.itemsPerSectionMax} pro Rubrik`,
    );
  }
  section.items.forEach((item, ii) => validateItemV1(item, `${where}.items[${ii}]`, ctx));
  return n;
}

function validateItemV1(item, where, ctx) {
  const { c } = ctx;
  if (!isPlainObject(item)) {
    c.error(where, `muss ein Objekt sein (gefunden: ${show(item)})`);
    return;
  }
  reportUnknownKeys(item, ITEM_KEYS_V1, where, c);
  checkHeadline(item, where, ctx);
  checkText(item.summary, `${where}.summary`, c, { max: LIMITS.summary });
  checkText(item.source, `${where}.source`, c);
  checkItemUrl(item, where, ctx);
  checkPublishedAt(item, where, ctx);
  checkImportance(item, where, c);
}

// ----- schema 2 --------------------------------------------------------------------------------

function validateSectionV2(section, where, ctx, sectionIds) {
  const { c } = ctx;
  checkSectionHead(section, where, c, sectionIds, 2);
  if (!Array.isArray(section.items)) {
    c.error(`${where}.items`, `muss eine Liste von Artikeln sein (gefunden: ${show(section.items)})`);
    return 0;
  }
  if (section.items.length === 0) {
    c.error(`${where}.items`, 'ist leer – Rubriken ohne Artikel ganz weglassen');
    return 0;
  }

  let top = 0;
  let more = 0;
  let firstMore = null;
  section.items.forEach((item, ii) => {
    const itemWhere = `${where}.items[${ii}]`;
    const tier = validateItemV2(item, itemWhere, ctx);
    if (tier === TIER_TOP) {
      top++;
      if (firstMore !== null) {
        c.error(`${itemWhere}.tier`, `"top"-Artikel muss vor allen "more"-Artikeln stehen (erster "more": ${firstMore})`);
      }
    } else if (tier === TIER_MORE) {
      more++;
      if (firstMore === null) firstMore = itemWhere;
    }
  });
  if (top < LIMITS.topPerSectionMin || top > LIMITS.topPerSectionMax) {
    c.error(
      `${where}.items`,
      `${top} "top"-Artikel – erlaubt sind ${LIMITS.topPerSectionMin}–${LIMITS.topPerSectionMax} pro Rubrik`,
    );
  }
  if (more > LIMITS.morePerSectionMax) {
    c.error(`${where}.items`, `${more} "more"-Artikel – erlaubt sind höchstens ${LIMITS.morePerSectionMax} pro Rubrik`);
  }
  return section.items.length;
}

/** Validates one schema-2 article; returns its tier when valid, otherwise `null`. */
function validateItemV2(item, where, ctx) {
  const { c } = ctx;
  if (!isPlainObject(item)) {
    c.error(where, `muss ein Objekt sein (gefunden: ${show(item)})`);
    return null;
  }
  reportUnknownKeys(item, ITEM_KEYS_V2, where, c);

  // id
  if (item.id === undefined) {
    c.error(`${where}.id`, 'fehlt – ein eindeutiger Slug wie "ki-chipfabrik-dresden"');
  } else if (!isSlug(item.id)) {
    c.error(
      `${where}.id`,
      `muss ein Slug aus Kleinbuchstaben, Ziffern und Bindestrichen sein, höchstens ${LIMITS.idLength} Zeichen (gefunden: ${show(item.id)})`,
    );
  } else if (ctx.ids.has(item.id)) {
    c.error(`${where}.id`, `"${item.id}" kommt doppelt vor (schon ${ctx.ids.get(item.id)})`);
  } else {
    ctx.ids.set(item.id, where);
  }

  // tier
  let tier = null;
  if (item.tier === TIER_TOP || item.tier === TIER_MORE) {
    tier = item.tier;
  } else {
    c.error(`${where}.tier`, `muss "top" oder "more" sein (gefunden: ${show(item.tier)})`);
  }

  checkHeadline(item, where, ctx);

  // summary (the lead)
  const summary = checkText(item.summary, `${where}.summary`, c, { max: LIMITS.summaryV2 });
  if (summary !== null) {
    if (/[\r\n]/.test(summary)) c.warn(`${where}.summary`, 'enthält einen Zeilenumbruch');
    const sentences = sentenceCount(summary);
    if (sentences > LIMITS.summarySentences) {
      c.warn(`${where}.summary`, `hat ${sentences} Sätze – 1–2 Sätze als Vorspann`);
    }
  }

  // body
  const body = checkText(item.body, `${where}.body`, c);
  let words = null;
  if (body !== null) {
    const analysis = analyzeBody(body);
    words = analysis.words;
    for (const e of analysis.errors) c.error(`${where}.body`, e);
    for (const w of analysis.warnings) c.warn(`${where}.body`, w);
    if (tier !== null) {
      const { min, max } = LIMITS.bodyWords[tier];
      if (words < min || words > max) {
        c.error(`${where}.body`, `hat ${words} Wörter – für "${tier}" sind ${min}–${max} Wörter vorgeschrieben`);
      }
      const minParagraphs = tier === TIER_TOP ? 3 : 2;
      if (analysis.paragraphs < minParagraphs) {
        c.warn(`${where}.body`, `nur ${analysis.paragraphs} Absatz/Absätze – gliedere den Text mit Leerzeilen`);
      }
    }
  }

  // readingMinutes
  const rm = item.readingMinutes;
  if (!Number.isInteger(rm) || rm < 1) {
    const hint = words !== null ? ` – für ${words} Wörter: ${expectedReadingMinutes(words)}` : '';
    c.error(`${where}.readingMinutes`, `muss eine ganze Zahl ≥ 1 sein (gefunden: ${show(rm)})${hint}`);
  } else if (words !== null) {
    const expected = expectedReadingMinutes(words);
    if (Math.abs(rm - expected) > LIMITS.readingMinutesTolerance) {
      c.warn(`${where}.readingMinutes`, `ist ${rm}, für ${words} Wörter passt ${expected} (Wörter / 200, gerundet)`);
    }
  }

  // source, url, sources
  const source = checkText(item.source, `${where}.source`, c);
  const url = checkItemUrl(item, where, ctx);
  checkSources(item, where, ctx, { tier, url, source });

  checkPublishedAt(item, where, ctx);
  checkImportance(item, where, c);
  if (ctx.requireImages && (item.image === undefined || item.image === null)) {
    const id = typeof item.id === 'string' ? `"${item.id}" ` : '';
    c.error(
      `${where}.image`,
      `Artikel ${id}hat kein Bild – mit --require-images braucht jeder Artikel eins (Commons-Suche, sonst node routine/find-image.mjs --fallback <motiv|rubrik>)`,
    );
  }
  checkImage(item.image, `${where}.image`, ctx, item.id);

  return tier;
}

function checkSources(item, where, ctx, { tier, url, source }) {
  const { c } = ctx;
  if (!Array.isArray(item.sources)) {
    c.error(`${where}.sources`, `muss eine Liste von Quellen { name, url } sein (gefunden: ${show(item.sources)})`);
    return;
  }
  const keys = new Map();
  const hosts = new Set();
  item.sources.forEach((ref, ri) => {
    const refWhere = `${where}.sources[${ri}]`;
    if (!isPlainObject(ref)) {
      c.error(refWhere, `muss ein Objekt { name, url } sein (gefunden: ${show(ref)})`);
      return;
    }
    reportUnknownKeys(ref, SOURCE_KEYS, refWhere, c);
    const name = checkText(ref.name, `${refWhere}.name`, c);
    const problem = httpsUrlProblem(ref.url);
    if (problem !== null) {
      c.error(`${refWhere}.url`, `${problem} (gefunden: ${show(ref.url)})`);
      return;
    }
    const key = urlKey(ref.url);
    if (keys.has(key)) {
      c.warn(`${refWhere}.url`, `dieselbe Quelle steht schon in sources[${keys.get(key).index}]`);
      return;
    }
    keys.set(key, { index: ri, name });
    hosts.add(hostOf(ref.url));
  });

  if (tier !== null) {
    const min = LIMITS.sourcesMin[tier];
    if (keys.size < min) {
      c.error(
        `${where}.sources`,
        `${keys.size} Quelle(n) – "${tier}"-Artikel brauchen mindestens ${min} verschiedene, selbst geöffnete Quellen`,
      );
    } else if (tier === TIER_TOP && hosts.size === 1) {
      c.warn(`${where}.sources`, `alle Quellen stammen von ${[...hosts][0]} – unabhängige Medien sind besser`);
    }
  }
  if (url !== null && keys.size > 0) {
    const match = keys.get(urlKey(url));
    if (match === undefined) {
      c.error(`${where}.url`, 'muss eine der Adressen aus "sources" sein (die Hauptquelle)');
    } else if (source !== null && typeof match.name === 'string' && match.name.trim() !== source.trim()) {
      c.warn(`${where}.source`, `"${source}" heißt in sources[${match.index}] "${match.name}"`);
    }
  }
}

/**
 * `--no-repeat-days`: a picture whose Commons file already ran in one of the previous briefs is an
 * error – the reader would see it again (find-image.mjs `--recent-days` hides those files).
 */
function checkRecentImage(image, where, ctx, itemId) {
  if (!ctx.recentImages) return;
  const use = [image.url, image.sourceUrl]
    .map((value) => imageFileKey(value))
    .map((key) => (key === null ? undefined : ctx.recentImages.get(key)))
    .find((found) => found !== undefined);
  if (use === undefined) return;
  const file = commonsFileKey(image.url) ?? image.url;
  const article = typeof itemId === 'string' ? ` von Artikel "${itemId}"` : '';
  const topic = typeof use.section === 'string' && use.section !== '' ? ` (${use.section})` : '';
  ctx.c.error(`${where}.url`, `Bild „${file}“${article} lief schon am ${use.date}${topic} – bitte ein anderes wählen`);
}

function checkImage(image, where, ctx, itemId) {
  const { c } = ctx;
  if (image === undefined || image === null) return;
  if (!isPlainObject(image)) {
    c.error(where, `muss null oder ein Objekt { url, credit, license, sourceUrl, alt } sein (gefunden: ${show(image)})`);
    return;
  }
  reportUnknownKeys(image, IMAGE_KEYS, where, c);

  const urlProblem = httpsUrlProblem(image.url);
  if (urlProblem !== null) {
    c.error(`${where}.url`, `${urlProblem} (gefunden: ${show(image.url)})`);
  } else if (!image.url.startsWith(IMAGE_URL_PREFIX)) {
    c.error(`${where}.url`, `muss mit ${IMAGE_URL_PREFIX} beginnen – nur Bilder von Wikimedia Commons (gefunden: ${show(image.url)})`);
  } else {
    const key = imageKey(image.url);
    if (ctx.images.has(key)) {
      c.warn(`${where}.url`, `dasselbe Bild hat schon ${ctx.images.get(key)} – jedes Bild nur einmal im Briefing`);
    } else {
      ctx.images.set(key, where);
    }
    checkRecentImage(image, where, ctx, itemId);
  }

  const license = checkText(image.license, `${where}.license`, c);
  let needsCredit = true;
  if (license !== null) {
    if (!isFreeLicense(license)) {
      c.error(
        `${where}.license`,
        `"${license}" ist keine erlaubte freie Lizenz (CC0, Public domain, PD-…, CC BY x.y, CC BY-SA x.y)`,
      );
    } else {
      needsCredit = licenseNeedsCredit(license);
    }
  }

  if (needsCredit) {
    checkText(image.credit, `${where}.credit`, c, { max: LIMITS.imageCredit });
  } else if (image.credit !== undefined && typeof image.credit !== 'string') {
    c.error(`${where}.credit`, `muss ein Text sein (gefunden: ${show(image.credit)})`);
  }
  if (typeof image.credit === 'string' && /<[^>]+>/.test(image.credit)) {
    c.error(`${where}.credit`, 'enthält HTML – nur den Namen als Text');
  }

  const sourceProblem = httpsUrlProblem(image.sourceUrl);
  if (sourceProblem !== null) {
    c.error(`${where}.sourceUrl`, `${sourceProblem} (gefunden: ${show(image.sourceUrl)})`);
  } else if (!image.sourceUrl.startsWith(IMAGE_SOURCE_PREFIX)) {
    c.error(`${where}.sourceUrl`, `muss die Dateiseite auf ${IMAGE_SOURCE_PREFIX} sein (gefunden: ${show(image.sourceUrl)})`);
  }

  if (image.alt === undefined || image.alt === null || (typeof image.alt === 'string' && image.alt.trim() === '')) {
    c.warn(`${where}.alt`, 'fehlt – eine kurze deutsche Bildbeschreibung hilft Screenreadern');
  } else if (typeof image.alt !== 'string') {
    c.error(`${where}.alt`, `muss ein Text sein (gefunden: ${show(image.alt)})`);
  } else if (textLength(image.alt) > LIMITS.imageAlt) {
    c.warn(`${where}.alt`, `ist länger als ${LIMITS.imageAlt} Zeichen`);
  }
}

/** Which rules to apply when `schema` itself is wrong: schema 2 if any item looks like one. */
function guessSchema(doc) {
  const sections = Array.isArray(doc.sections) ? doc.sections : [];
  for (const s of sections) {
    if (!isPlainObject(s) || !Array.isArray(s.items)) continue;
    for (const item of s.items) {
      if (isPlainObject(item) && ('body' in item || 'tier' in item || 'sources' in item)) return 2;
    }
  }
  return 1;
}

/**
 * Validates a daily brief (`briefs/YYYY-MM-DD.json` or `latest.json`), schema 1 or 2.
 *
 * @param {unknown} doc parsed JSON
 * @param {{ maxAgeHours?: number, requireImages?: boolean,
 *   recentImages?: Map<string, { date: string, section?: string }> }} [options] `maxAgeHours`
 *   enables the freshness policy: every item needs `publishedAt` and must not be older than that
 *   relative to `generatedAt`. `requireImages` makes a missing `image` of a schema-2 article an
 *   error (the routine's rule for new briefs; older briefs without it stay valid).
 *   `recentImages` are the pictures of the previous days by {@link imageFileKey}
 *   (recent-images.mjs): every article picture among them is an error.
 * @returns {{ errors: string[], warnings: string[] }} errors make the file unusable;
 *   warnings point at editorial guidelines (≤ 10 words, catalogue order …) and typos.
 */
export function validateBrief(doc, options = {}) {
  const { maxAgeHours, requireImages, recentImages } = options;
  const c = collector();
  if (!isPlainObject(doc)) {
    c.error('', `Ein Briefing muss ein JSON-Objekt sein (gefunden: ${show(doc)}).`);
    return { errors: c.errors, warnings: c.warnings };
  }
  reportUnknownKeys(doc, BRIEF_KEYS, '', c);

  let schema = doc.schema;
  if (!SUPPORTED_SCHEMAS.includes(schema)) {
    c.error('schema', `muss ${SUPPORTED_SCHEMAS.join(' oder ')} sein (gefunden: ${show(doc.schema)})`);
    schema = guessSchema(doc);
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

  const ctx = itemContext(c, {
    referenceMs: generated === null ? null : generated.epochMs,
    referenceLabel: 'generatedAt',
    maxAgeHours,
    requireImages,
    recentImages,
  });
  const sectionIds = new Map();
  let itemTotal = 0;
  let previousRank = -1;
  let previousId = null;

  doc.sections.forEach((section, si) => {
    const where = `sections[${si}]`;
    if (!isPlainObject(section)) {
      c.error(where, `muss ein Objekt mit id, title und items sein (gefunden: ${show(section)})`);
      return;
    }
    itemTotal +=
      schema === 2
        ? validateSectionV2(section, where, ctx, sectionIds)
        : validateSectionV1(section, where, ctx, sectionIds);
    if (schema === 2 && typeof section.id === 'string' && topicById(section.id) !== undefined) {
      const rank = topicRank(section.id);
      if (rank < previousRank) {
        c.warn(`${where}.id`, `"${section.id}" steht nach "${previousId}" – Rubriken in Katalog-Reihenfolge ordnen`);
      } else {
        previousRank = rank;
        previousId = section.id;
      }
    }
  });

  if (itemTotal === 0) {
    c.error('sections', 'enthält keine einzige Meldung');
  }
  if (schema === 1) {
    const missing = EXPECTED_SECTION_IDS.filter((id) => !sectionIds.has(id));
    if (missing.length > 0 && sectionIds.size > 0) {
      const names = missing.map((id) => `"${id}"`).join(', ');
      c.warn('sections', missing.length === 1 ? `Rubrik ${names} fehlt` : `Rubriken ${names} fehlen`);
    }
  }
  return { errors: c.errors, warnings: c.warnings };
}

/**
 * Validates a single schema-2 section (`{ id, title, emoji, items }`), as the per-topic agents of
 * the routine write it. Without `generatedAt`, the freshness check uses `now`.
 *
 * @param {unknown} doc parsed JSON
 * @param {{ maxAgeHours?: number, now?: number, requireImages?: boolean,
 *   recentImages?: Map<string, { date: string, section?: string }> }} [options] `now` =
 *   reference instant (epoch ms), default `Date.now()`; `requireImages` and `recentImages` as in
 *   {@link validateBrief}
 * @returns {{ errors: string[], warnings: string[] }}
 */
export function validateSection(doc, options = {}) {
  const { maxAgeHours, now = Date.now(), requireImages, recentImages } = options;
  const c = collector();
  if (!isPlainObject(doc)) {
    c.error('', `Eine Rubrik muss ein JSON-Objekt { id, title, emoji, items } sein (gefunden: ${show(doc)}).`);
    return { errors: c.errors, warnings: c.warnings };
  }
  if ('sections' in doc || 'schema' in doc) {
    c.error('', 'Das ist ein ganzes Briefing, keine einzelne Rubrik – ohne --section prüfen.');
    return { errors: c.errors, warnings: c.warnings };
  }
  const ctx = itemContext(c, { referenceMs: now, referenceLabel: 'jetzt', maxAgeHours, requireImages, recentImages });
  validateSectionV2(doc, '', ctx, null);
  // `where` is empty for the section itself, so the messages read "id: …", "items[0].body: …".
  const strip = (list) => list.map((m) => m.replace(/^\./, ''));
  return { errors: strip(c.errors), warnings: strip(c.warnings) };
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
 * Runs `validate` with the pictures of the days before `date` from `options.history`, if given;
 * the history's warnings (e.g. "nicht lesbar") are passed on.
 */
function validateWithHistory(validate, doc, date, options) {
  if (typeof options.history !== 'function' || !isCalendarDate(date)) return validate(doc, options);
  const history = options.history(date);
  const result = validate(doc, { ...options, recentImages: history.images });
  return { errors: result.errors, warnings: [...result.warnings, ...history.warnings] };
}

/**
 * Detects the kind of a parsed document and validates it accordingly. With `options.section`
 * the document is always validated as a single section.
 *
 * @param {unknown} doc
 * @param {{ maxAgeHours?: number, section?: boolean, now?: number, requireImages?: boolean,
 *   history?: (date: string) => { images: Map<string, { date: string, section?: string }>, warnings: string[] } }} [options]
 *   `history` supplies the pictures of the days before a brief (`--no-repeat-days`, see
 *   recent-images.mjs): it is called with the brief's `date` – for a section with the Berlin date
 *   of `now` – and every article picture among them is an error.
 * @returns {{ kind: 'brief' | 'index' | 'section' | null, errors: string[], warnings: string[] }}
 */
export function validateDocument(doc, options = {}) {
  if (options.section) {
    return { kind: 'section', ...validateWithHistory(validateSection, doc, berlinDate(options.now), options) };
  }
  const kind = detectKind(doc);
  if (kind === 'brief') return { kind, ...validateWithHistory(validateBrief, doc, doc.date, options) };
  if (kind === 'index') return { kind, ...validateIndex(doc) };
  if (kind === 'section') {
    return {
      kind,
      errors: ['Das ist eine einzelne Rubrik, kein Briefing – mit --section prüfen.'],
      warnings: [],
    };
  }
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
 * @param {string} file
 * @param {{ maxAgeHours?: number, section?: boolean, now?: number, requireImages?: boolean, history?: Function }} [options]
 *   as in {@link validateDocument}
 * @returns {{ file: string, kind: 'brief'|'index'|'section'|null, doc: unknown, errors: string[], warnings: string[] }}
 */
export function validateFile(file, options = {}) {
  let parsed;
  try {
    parsed = readJsonFile(file);
  } catch (error) {
    if (error instanceof JsonFileError) {
      return { file, kind: options.section ? 'section' : null, doc: undefined, errors: [error.message], warnings: [] };
    }
    throw error;
  }
  const result = validateDocument(parsed.doc, options);
  const warnings = [...parsed.warnings, ...result.warnings];
  if (options.section && isPlainObject(parsed.doc) && typeof parsed.doc.id === 'string') {
    const base = path.basename(file).replace(/\.json$/i, '');
    if (base !== parsed.doc.id) {
      warnings.push(`Dateiname "${path.basename(file)}" passt nicht zur Rubrik "${parsed.doc.id}" (erwartet: ${parsed.doc.id}.json)`);
    }
  }
  return { file, kind: result.kind, doc: parsed.doc, errors: result.errors, warnings };
}

function plural(n, one, many) {
  return `${n} ${n === 1 ? one : many}`;
}

/** "3 Artikel (2 Top, 1 Mehr)" for schema-2 item lists. */
function describeTiers(items) {
  const list = Array.isArray(items) ? items.filter(isPlainObject) : [];
  const top = list.filter((i) => i.tier === TIER_TOP).length;
  const more = list.filter((i) => i.tier === TIER_MORE).length;
  return `${plural(list.length, 'Artikel', 'Artikel')} (${top} Top, ${more} Mehr)`;
}

/**
 * One-line description of a validated document, e.g.
 * "Briefing 2026-10-06 · 3 Rubriken · 10 Meldungen" (schema 1) or
 * "Briefing 2026-10-06 · 10 Rubriken · 52 Artikel · 31 Bilder" (schema 2).
 */
export function describe(kind, doc) {
  if (kind === 'brief' && isPlainObject(doc)) {
    const sections = Array.isArray(doc.sections) ? doc.sections.length : 0;
    const items = countItems(doc);
    const head = `Briefing ${typeof doc.date === 'string' ? doc.date : '?'} · ${plural(sections, 'Rubrik', 'Rubriken')}`;
    if (doc.schema === 2) {
      return `${head} · ${plural(items, 'Artikel', 'Artikel')} · ${plural(countImages(doc), 'Bild', 'Bilder')}`;
    }
    return `${head} · ${plural(items, 'Meldung', 'Meldungen')}`;
  }
  if (kind === 'index' && isPlainObject(doc)) {
    const days = Array.isArray(doc.days) ? doc.days.length : 0;
    const newest = days > 0 && isPlainObject(doc.days[0]) ? `, neuester ${doc.days[0].date}` : '';
    return `Index · ${plural(days, 'Tag', 'Tage')}${newest}`;
  }
  if (kind === 'section' && isPlainObject(doc)) {
    const id = typeof doc.id === 'string' ? doc.id : '?';
    return `Rubrik ${id} · ${describeTiers(doc.items)} · ${plural(countImages(doc), 'Bild', 'Bilder')}`;
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

/** How far back `--no-repeat-days` / `--recent-days` may look (each day is one brief to read). */
export const MAX_RECENT_DAYS = 60;

/** Parses a whole number of days from 1 to {@link MAX_RECENT_DAYS}; returns `null` if invalid. */
export function parseDays(value) {
  if (typeof value !== 'string' || !/^\d+$/.test(value.trim())) return null;
  const days = Number(value);
  return days >= 1 && days <= MAX_RECENT_DAYS ? days : null;
}

const USAGE = `Prüft Morgenbriefing-Dateien (Briefings, index.json, einzelne Rubriken) gegen docs/brief-format.md.

Aufruf:
  node routine/validate-brief.mjs <datei...> [--max-age-hours <h>] [--require-images] [--no-repeat-days <n>]
  node routine/validate-brief.mjs --section <rubrik.json...> [--max-age-hours <h>] [--require-images] [--no-repeat-days <n>] [--now <zeitpunkt>]

Optionen:
  --section            Jede Datei ist eine einzelne Rubrik { id, title, emoji, items }
                       (Schema 2), wie sie die Rubrik-Agenten der Routine schreiben.
                       Mehrere Rubriken zusammen: dasselbe Bild in zwei Dateien ist ein Hinweis.
  --max-age-hours <h>  Jede Meldung braucht publishedAt und darf höchstens <h> Stunden
                       alt sein (Routine: 36). Bezug: generatedAt des Briefings, bei
                       Rubriken die aktuelle Uhrzeit bzw. --now.
  --require-images     Jeder Artikel (Schema 2) braucht ein Bild – ein fehlendes ist ein
                       Fehler (Routine). Ohne die Option bleibt "image" freiwillig.
  --no-repeat-days <n> Ein Bild, dessen Commons-Datei (egal in welcher Größe) in den Briefings
                       der <n> Tage vor dem Datum des Briefings schon lief, ist ein Fehler
                       (Routine: 14). Rubriken zählen für das Datum von --now bzw. heute.
  --recent-ref <ref>   Mit --no-repeat-days: frühere Briefings aus diesem Git-Stand lesen
                       (Standard: origin/briefs, wird bei Bedarf geholt).
  --recent-from <pfad> Mit --no-repeat-days: frühere Briefings stattdessen aus diesem Ordner
                       (<pfad>/briefs/<datum>.json oder <pfad>/<datum>.json).
  --now <zeitpunkt>    Bezugszeitpunkt für Rubriken, ISO-8601 mit Zeitzone.
  -h, --help           Diese Hilfe.

Exit-Code: 0 = alles gültig, 1 = Fehler gefunden, 2 = falscher Aufruf.`;

function parseCliArgs(argv) {
  const files = [];
  let maxAgeHours;
  let section = false;
  let requireImages = false;
  let now;
  let noRepeatDays;
  let recentRef;
  let recentFrom;
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '-h' || arg === '--help') return { help: true };
    if (arg === '--section') {
      section = true;
    } else if (arg === '--require-images') {
      requireImages = true;
    } else if (arg === '--no-repeat-days' || arg.startsWith('--no-repeat-days=')) {
      const raw = arg.includes('=') ? arg.slice(arg.indexOf('=') + 1) : argv[++i];
      noRepeatDays = parseDays(raw);
      if (noRepeatDays === null) {
        return { problem: `--no-repeat-days braucht eine ganze Zahl von 1 bis ${MAX_RECENT_DAYS} (gefunden: ${raw ?? 'nichts'})` };
      }
    } else if (['--recent-ref', '--recent-from'].some((name) => arg === name || arg.startsWith(`${name}=`))) {
      const name = arg.split('=')[0];
      const raw = arg.includes('=') ? arg.slice(arg.indexOf('=') + 1) : argv[++i];
      if (raw === undefined || raw.trim() === '' || (raw.startsWith('--') && !arg.includes('='))) {
        return { problem: `${name} braucht einen Wert.` };
      }
      if (name === '--recent-ref') recentRef = raw;
      else recentFrom = raw;
    } else if (arg === '--max-age-hours' || arg.startsWith('--max-age-hours=')) {
      const raw = arg.includes('=') ? arg.slice(arg.indexOf('=') + 1) : argv[++i];
      const hours = parseHours(raw);
      if (hours === null) {
        return { problem: `--max-age-hours braucht eine positive Zahl (gefunden: ${raw ?? 'nichts'})` };
      }
      maxAgeHours = hours;
    } else if (arg === '--now' || arg.startsWith('--now=')) {
      const raw = arg.includes('=') ? arg.slice(arg.indexOf('=') + 1) : argv[++i];
      const parsed = parseOffsetDateTime(raw);
      if (parsed === null) {
        return { problem: `--now braucht einen ISO-8601-Zeitpunkt mit Zeitzone (gefunden: ${raw ?? 'nichts'})` };
      }
      now = parsed.epochMs;
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
  if (now !== undefined && !section) return { problem: '--now gilt nur zusammen mit --section.' };
  if ((recentRef !== undefined || recentFrom !== undefined) && noRepeatDays === undefined) {
    return { problem: '--recent-ref und --recent-from gelten nur zusammen mit --no-repeat-days.' };
  }
  if (recentRef !== undefined && recentFrom !== undefined) {
    return { problem: '--recent-ref und --recent-from schließen sich gegenseitig aus.' };
  }
  return { files, maxAgeHours, section, now, requireImages, noRepeatDays, recentRef, recentFrom };
}

/**
 * Pictures that a section shares with sections checked before it (same Commons file). Records
 * the section's own pictures in `seen` (image key → "datei items[i]").
 *
 * @param {unknown} doc a parsed section `{ id, title, emoji, items }`
 * @param {string} label how the section is named in messages (usually the file name)
 * @param {Map<string, string>} seen shared between the sections of one run
 * @returns {string[]} warnings like "items[2].image.url: dasselbe Bild hat schon ki.json items[0] …"
 */
export function crossSectionImageWarnings(doc, label, seen) {
  const warnings = [];
  const own = new Map();
  const items = isPlainObject(doc) && Array.isArray(doc.items) ? doc.items : [];
  items.forEach((item, i) => {
    if (!isPlainObject(item) || !isPlainObject(item.image) || typeof item.image.url !== 'string') return;
    const key = imageKey(item.image.url);
    const where = `items[${i}]`;
    if (seen.has(key)) {
      warnings.push(`${where}.image.url: dasselbe Bild hat schon ${seen.get(key)} – jedes Bild nur einmal im Briefing`);
    } else if (!own.has(key)) {
      own.set(key, `${label} ${where}`);
    }
  });
  for (const [key, where] of own) seen.set(key, where);
  return warnings;
}

/** Escapes text for a GitHub Actions workflow command. */
function ghEscape(text) {
  return text.replace(/%/g, '%25').replace(/\r/g, '%0D').replace(/\n/g, '%0A');
}

/**
 * `--no-repeat-days`: the pictures of the days before a date, read once per date. The history's
 * warnings are reported with the first file only.
 */
async function historyLoader({ noRepeatDays, recentRef, recentFrom }) {
  // Loaded on demand: recent-images.mjs builds on this module, so a static import would be circular.
  const { collectRecentImages } = await import('./recent-images.mjs');
  const loaded = new Map();
  return (date) => {
    if (loaded.has(date)) return { images: loaded.get(date), warnings: [] };
    const history = collectRecentImages({ date, days: noRepeatDays, ref: recentRef, from: recentFrom });
    loaded.set(date, history.images);
    return { images: history.images, warnings: history.warnings };
  };
}

/**
 * Runs the CLI and resolves to the exit code (does not call `process.exit`).
 *
 * @param {string[]} argv arguments without `node` and the script path
 */
export async function main(argv = process.argv.slice(2), out = console.log, err = console.error) {
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
  const options = {
    maxAgeHours: args.maxAgeHours,
    section: args.section,
    now: args.now,
    requireImages: args.requireImages,
    history: args.noRepeatDays === undefined ? undefined : await historyLoader(args),
  };
  const seenImages = new Map();
  let invalid = 0;
  for (const file of args.files) {
    const result = validateFile(file, options);
    if (args.section && args.files.length > 1) {
      result.warnings.push(...crossSectionImageWarnings(result.doc, path.basename(file), seenImages));
    }
    if (result.errors.length === 0) {
      const hint = result.warnings.length > 0 ? ` · ${result.warnings.length} Hinweis(e)` : '';
      out(`OK      ${file} – ${describe(result.kind, result.doc)}${hint}`);
    } else {
      invalid++;
      const what = result.kind && result.doc !== undefined ? `${describe(result.kind, result.doc)} · ` : '';
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
  // Deliberately no top-level await: main() may import recent-images.mjs, which imports this
  // module – it would wait for this module to finish evaluating, and that would never happen.
  main().then((code) => {
    process.exitCode = code;
  });
}
