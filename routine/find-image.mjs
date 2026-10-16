#!/usr/bin/env node
/**
 * Finds freely licensed pictures on Wikimedia Commons for an article of the Morgenbriefing
 * (contract: docs/brief-format.md, field `image`). Zero dependencies, Node >= 18 (global fetch), ESM.
 *
 *   node routine/find-image.mjs "<suchbegriffe>" [--limit 5] [--recent-days 14]
 *   node routine/find-image.mjs --fallback <motiv|rubrik> [--exclude <url,…>] [--exclude-from <pfad>] [--seed <text>]
 *        [--recent-days 14]
 *   node routine/find-image.mjs --list-fallbacks
 *   node routine/find-image.mjs --verify-fallbacks
 *
 * Search mode prints a JSON array of candidates, best first:
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
 * Fallback mode (`--fallback`) needs no network: it answers from the curated catalogue
 * `routine/fallback-images.json` – checked, tasteful symbol pictures per topic ("rubrik") and per
 * recurring subject ("motiv", e.g. bundestag, leipzig-hbf, windpark). The candidates have the
 * same fields plus a German `alt` text and `fallback` ("motiv:bundestag" / "rubrik:politik"). A
 * subject lists its own pictures first, then those of its topic, then those of the topic's other
 * subjects, so it practically never runs dry. Pictures named with `--exclude` or used in the
 * files of `--exclude-from` are skipped (no picture twice in one brief); `--seed` (the article
 * id) rotates the order, so parallel agents asking for the same subject get different pictures.
 * `--verify-fallbacks` re-checks every catalogue entry against Commons (file, license, author,
 * size) – for maintenance, not for the daily run.
 *
 * `--recent-days N` (search and fallback mode) leaves out every file that ran in the briefs of the
 * N days before the brief's date (`--date`, default today in Europe/Berlin) – in any thumbnail
 * size –, so the reader does not see the same picture every morning. The previous briefs come
 * from git (`origin/briefs`, `--recent-ref`) or a directory (`--recent-from`), see
 * recent-images.mjs; validate-brief.mjs `--no-repeat-days` checks the result.
 *
 * Exit codes: 0 = done (the array may be empty), 1 = Commons not reachable / API error, catalogue
 * unreadable or a verification problem, 2 = usage error (also an unknown subject or topic).
 * Diagnostics go to stderr, stdout carries only the JSON (or the list of `--list-fallbacks`).
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { collectRecentImages } from './recent-images.mjs';
import {
  IMAGE_SOURCE_PREFIX,
  IMAGE_URL_PREFIX,
  LIMITS,
  MAX_RECENT_DAYS,
  TOPICS,
  berlinDate,
  commonsFileKey,
  imageFileKey,
  imageKey,
  isCalendarDate,
  isFreeLicense,
  isMainModule,
  isSlug,
  licenseNeedsCredit,
  parseDays,
  topicById,
} from './validate-brief.mjs';

export const API_URL = 'https://commons.wikimedia.org/w/api.php';
/** Wikimedia asks every API client to identify itself with a descriptive User-Agent. */
export const USER_AGENT = 'MorgenbriefingRoutine/1.4 (https://github.com/CedrickGD/Morgenbriefing)';
export const DEFAULT_LIMIT = 5;
export const MAX_LIMIT = 20;
/** Requested thumbnail width; a standard Commons thumbnail step. */
export const THUMB_WIDTH = 1280;
/** Pictures at least this wide (and landscape) are preferred. */
export const PREFERRED_WIDTH = 1000;
/** Pictures narrower than this look blurry on a phone card and are dropped. */
export const MIN_WIDTH = 640;

/** The curated fallback catalogue next to this script. */
export const FALLBACK_CATALOG_PATH = fileURLToPath(new URL('./fallback-images.json', import.meta.url));
export const CATALOG_SCHEMA = 1;
/**
 * Rules for the catalogue: pictures per topic and per subject, minimum width of the thumbnail,
 * and how many pictures a topic offers together with its subjects (`topicPoolMin`): the routine
 * keeps every picture out for 14 days (`--recent-days 14`) and a topic draws two to six catalogue
 * pictures a day, so a smaller pool would run dry.
 */
export const CATALOG_LIMITS = Object.freeze({
  topicImagesMin: 4,
  topicImagesMax: 16,
  subjectImagesMin: 1,
  subjectImagesMax: 6,
  topicPoolMin: 30,
  minWidth: 1200,
});

const REQUEST_TIMEOUT_MS = 20_000;
const MAX_ATTEMPTS = 3;
const DESCRIPTION_MAX = 200;
const VERIFY_BATCH = 20;
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

/** Raised when the fallback catalogue cannot be read or breaks its rules; `message` is German. */
export class CatalogError extends Error {
  constructor(message, options) {
    super(message, options);
    this.name = 'CatalogError';
  }
}

/** Raised for an unknown subject or topic in fallback mode (a usage error); `message` is German. */
export class UnknownFallbackError extends Error {
  constructor(message) {
    super(message);
    this.name = 'UnknownFallbackError';
  }
}

/** The `imageinfo` part shared by the search and the file lookup. */
function imageInfoParams() {
  return {
    prop: 'imageinfo',
    iiprop: 'url|size|extmetadata',
    iiurlwidth: String(THUMB_WIDTH),
    iiextmetadatafilter: EXTMETADATA_FIELDS.join('|'),
    iiextmetadatalanguage: 'de',
    format: 'json',
    origin: '*',
  };
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
    ...imageInfoParams(),
  });
  return `${API_URL}?${params}`;
}

/** The lookup of known files ("File:…" titles) with the same metadata as the search. */
export function buildFileInfoUrl(titles) {
  const params = new URLSearchParams({
    action: 'query',
    titles: titles.join('|'),
    redirects: '1',
    ...imageInfoParams(),
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

/** The pages of a Commons API response, in either result format. */
function responsePages(response) {
  const pages = response?.query?.pages;
  return Array.isArray(pages) ? pages : pages && typeof pages === 'object' ? Object.values(pages) : [];
}

function throwIfApiError(response) {
  if (response && response.error) {
    const { code, info } = response.error;
    throw new CommonsError(`Commons-API meldet einen Fehler: ${code ?? '?'} – ${info ?? 'ohne Beschreibung'}`);
  }
}

/**
 * Turns one page of a Commons API response into a candidate – or says why the file is unusable.
 *
 * @param {any} page a page with `imageinfo` (search result or file lookup)
 * @returns {{ candidate: { url: string, credit: string, license: string, sourceUrl: string,
 *   width: number, height: number, title: string, description: string } } | { reason: string }}
 *   `reason` is one of "ohne Bildinfo", "nicht frei", "Lizenz", "Logo/Marke", "kein Urheber",
 *   "URL", "zu klein".
 */
export function pageToCandidate(page) {
  const info = Array.isArray(page?.imageinfo) ? page.imageinfo[0] : undefined;
  if (!info) return { reason: 'ohne Bildinfo' };
  const meta = info.extmetadata ?? {};
  const value = (field) => (meta[field] && typeof meta[field].value === 'string' ? meta[field].value : '');
  const title = typeof page.title === 'string' ? page.title : '';

  if (value('NonFree').trim().toLowerCase() === 'true') return { reason: 'nicht frei' };
  const license = normalizeLicense(value('LicenseShortName'));
  if (!isFreeLicense(license)) return { reason: 'Lizenz' };
  const restrictions = value('Restrictions').toLowerCase();
  if (restrictions.includes('trademark') || LOGO_RE.test(title)) return { reason: 'Logo/Marke' };

  let credit = stripHtml(value('Artist'));
  if (UNKNOWN_AUTHOR_RE.test(credit)) credit = '';
  const needsCredit = licenseNeedsCredit(license);
  if (needsCredit && (credit === '' || credit.length > LIMITS.imageCredit)) return { reason: 'kein Urheber' };
  if (!needsCredit && credit.length > LIMITS.imageCredit) credit = '';

  const url = canonicalImageUrl(info.thumburl || info.url);
  const sourceUrl = typeof info.descriptionurl === 'string' ? info.descriptionurl : '';
  if (url === null || !sourceUrl.startsWith(IMAGE_SOURCE_PREFIX)) return { reason: 'URL' };

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
  if (width < MIN_WIDTH || height <= 0) return { reason: 'zu klein' };

  let description = stripHtml(value('ImageDescription')) || stripHtml(value('ObjectName'));
  if (description.length > DESCRIPTION_MAX) description = `${description.slice(0, DESCRIPTION_MAX - 1).trimEnd()}…`;

  return { candidate: { url, credit, license, sourceUrl, width, height, title, description } };
}

/** Reason in `rejected` for a file that ran in one of the recent briefs (`--recent-days`). */
export const RECENT_REASON = 'lief kürzlich';

/**
 * Turns a Commons API response into candidates, best first.
 *
 * @param {any} response parsed JSON of {@link buildSearchUrl}
 * @param {{ limit?: number, recent?: { has(key: string): boolean } }} [options] `recent`: file
 *   keys ({@link imageFileKey}) of the pictures of the recent briefs – those files are dropped
 * @returns {{ candidates: object[], rejected: Record<string, number> }} `rejected` counts the
 *   dropped files per reason (for diagnostics).
 */
export function parseCandidates(response, { limit = DEFAULT_LIMIT, recent } = {}) {
  throwIfApiError(response);
  const rejected = {};
  const seen = new Set();
  const accepted = [];
  for (const page of responsePages(response)) {
    const result = pageToCandidate(page);
    const reason = result.reason ?? (recent?.has(imageFileKey(result.candidate.url)) ? RECENT_REASON : undefined);
    if (reason) {
      rejected[reason] = (rejected[reason] ?? 0) + 1;
      continue;
    }
    const { candidate } = result;
    if (seen.has(candidate.url)) continue;
    seen.add(candidate.url);
    accepted.push({
      candidate,
      rank: Number.isFinite(page.index) ? page.index : Number.MAX_SAFE_INTEGER,
      tier: preferenceTier(candidate.width, candidate.height),
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
 * GETs a Commons API URL and returns the parsed JSON. Retries network errors, 429 and 5xx up to
 * three times (honouring `Retry-After`, at most 10 s).
 *
 * @param {string} url
 * @param {{ fetchImpl?: typeof fetch, retryDelayMs?: number }} [options]
 * @throws {CommonsError}
 */
export async function fetchCommonsJson(url, { fetchImpl = globalThis.fetch, retryDelayMs = 1500 } = {}) {
  if (typeof fetchImpl !== 'function') throw new CommonsError('fetch ist nicht verfügbar – Node.js ≥ 18 nötig.');
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
    try {
      return await response.json();
    } catch (cause) {
      throw new CommonsError('Commons hat kein gültiges JSON geliefert', { cause });
    }
  }
  throw new CommonsError(`${lastProblem} – nach ${MAX_ATTEMPTS} Versuchen aufgegeben`);
}

/**
 * Queries Commons and returns the parsed candidates.
 *
 * @param {string} terms search terms (English or German)
 * @param {{ limit?: number, fetchImpl?: typeof fetch, retryDelayMs?: number,
 *   recent?: { has(key: string): boolean } }} [options] `recent` as in {@link parseCandidates}
 * @throws {CommonsError}
 */
export async function findImages(terms, { limit = DEFAULT_LIMIT, fetchImpl = globalThis.fetch, retryDelayMs = 1500, recent } = {}) {
  const json = await fetchCommonsJson(buildSearchUrl(terms, limit), { fetchImpl, retryDelayMs });
  return parseCandidates(json, { limit, recent });
}

// ---------------------------------------------------------------------------------------------
// Fallback catalogue
// ---------------------------------------------------------------------------------------------

/** "Leipzig Hbf" → "leipzig-hbf", "Kyjiw" → "kyjiw", "Börse" → "boerse". */
export function normalizeKey(value) {
  return String(value ?? '')
    .toLowerCase()
    .replace(/ä/g, 'ae')
    .replace(/ö/g, 'oe')
    .replace(/ü/g, 'ue')
    .replace(/ß/g, 'ss')
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
}

function isPlainObject(value) {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** Problems of one catalogue picture (empty list = fine). */
function entryProblems(entry, where) {
  if (!isPlainObject(entry)) return [`${where}: muss ein Objekt sein`];
  const problems = [];
  const text = (field) => typeof entry[field] === 'string' && entry[field].trim() !== '';
  if (!text('title') || !/^File:/.test(entry.title)) problems.push(`${where}.title: muss "File:…" sein`);
  if (!text('url') || !entry.url.startsWith(IMAGE_URL_PREFIX) || !entry.url.includes(`/${THUMB_WIDTH}px-`)) {
    problems.push(`${where}.url: muss das ${THUMB_WIDTH}-px-Vorschaubild auf ${IMAGE_URL_PREFIX} sein`);
  }
  if (!text('license') || !isFreeLicense(entry.license)) {
    problems.push(`${where}.license: "${entry.license}" ist keine erlaubte freie Lizenz`);
  } else if (licenseNeedsCredit(entry.license) && !text('credit')) {
    problems.push(`${where}.credit: fehlt – ${entry.license} verlangt den Urheber`);
  }
  if (entry.credit !== undefined && (typeof entry.credit !== 'string' || entry.credit.length > LIMITS.imageCredit || /<[^>]+>/.test(entry.credit))) {
    problems.push(`${where}.credit: höchstens ${LIMITS.imageCredit} Zeichen reiner Text`);
  }
  if (!text('sourceUrl') || !entry.sourceUrl.startsWith(IMAGE_SOURCE_PREFIX)) {
    problems.push(`${where}.sourceUrl: muss die Dateiseite auf ${IMAGE_SOURCE_PREFIX} sein`);
  }
  if (text('title') && text('url') && text('sourceUrl')) {
    const file = commonsFileKey(entry.title);
    if (commonsFileKey(entry.url) !== file || commonsFileKey(entry.sourceUrl) !== file) {
      problems.push(`${where}: title, url und sourceUrl zeigen nicht auf dieselbe Datei`);
    }
  }
  if (!text('alt') || entry.alt.length > LIMITS.imageAlt) {
    problems.push(`${where}.alt: kurze deutsche Bildbeschreibung (höchstens ${LIMITS.imageAlt} Zeichen) fehlt`);
  }
  if (!Number.isInteger(entry.width) || entry.width < CATALOG_LIMITS.minWidth || !Number.isInteger(entry.height) || entry.height <= 0) {
    problems.push(`${where}.width: mindestens ${CATALOG_LIMITS.minWidth} px breit`);
  } else if (entry.width <= entry.height) {
    problems.push(`${where}: nur Querformat (${entry.width}×${entry.height})`);
  }
  if (!isCalendarDate(entry.verifiedAt)) problems.push(`${where}.verifiedAt: Datum der Prüfung (YYYY-MM-DD) fehlt`);
  return problems;
}

/**
 * Checks the structure of the fallback catalogue: every topic of the catalogue of the app with
 * 4–16 pictures ({@link CATALOG_LIMITS}), subjects with a known topic, label and 1–6 pictures,
 * unique keys and aliases, no file twice, and every picture within the rules of the validator
 * (free license, credit, Commons URLs, German alt text, landscape ≥ 1200 px, verification date).
 * How many pictures a topic offers in total is a separate rule: {@link catalogPoolProblems}.
 *
 * @param {unknown} catalog parsed `fallback-images.json`
 * @returns {string[]} problems in German (empty = fine)
 */
export function catalogProblems(catalog) {
  if (!isPlainObject(catalog)) return ['Der Katalog muss ein JSON-Objekt sein.'];
  const problems = [];
  if (catalog.schema !== CATALOG_SCHEMA) problems.push(`schema: muss ${CATALOG_SCHEMA} sein`);
  if (!isCalendarDate(catalog.verifiedAt)) problems.push('verifiedAt: Datum der letzten Prüfung (YYYY-MM-DD) fehlt');
  const files = new Map();
  const names = new Map();
  const claim = (name, where) => {
    if (names.has(name)) problems.push(`${where}: "${name}" ist schon ${names.get(name)}`);
    else names.set(name, where);
  };
  const checkImages = (images, where, min, max) => {
    if (!Array.isArray(images) || images.length < min || images.length > max) {
      problems.push(`${where}: ${min}–${max} Bilder vorgesehen (gefunden: ${Array.isArray(images) ? images.length : 'keine Liste'})`);
      return;
    }
    images.forEach((entry, i) => {
      const at = `${where}[${i}]`;
      problems.push(...entryProblems(entry, at));
      const file = isPlainObject(entry) ? commonsFileKey(entry.title) : null;
      if (file === null) return;
      if (files.has(file)) problems.push(`${at}: dieselbe Datei steht schon in ${files.get(file)}`);
      else files.set(file, at);
    });
  };

  if (!isPlainObject(catalog.topics)) {
    problems.push('topics: muss ein Objekt { rubrik: [Bilder] } sein');
  } else {
    for (const topic of TOPICS) {
      if (!(topic.id in catalog.topics)) problems.push(`topics.${topic.id}: fehlt`);
    }
    for (const [id, images] of Object.entries(catalog.topics)) {
      if (topicById(id) === undefined) problems.push(`topics.${id}: keine Rubrik aus dem Katalog der App`);
      claim(id, `topics.${id}`);
      checkImages(images, `topics.${id}`, CATALOG_LIMITS.topicImagesMin, CATALOG_LIMITS.topicImagesMax);
    }
  }

  if (!isPlainObject(catalog.subjects)) {
    problems.push('subjects: muss ein Objekt { motiv: { topic, label, images } } sein');
  } else {
    for (const [key, subject] of Object.entries(catalog.subjects)) {
      const where = `subjects.${key}`;
      if (!isSlug(key)) problems.push(`${where}: Schlüssel nur aus Kleinbuchstaben, Ziffern und Bindestrichen`);
      claim(key, where);
      if (!isPlainObject(subject)) {
        problems.push(`${where}: muss ein Objekt sein`);
        continue;
      }
      if (topicById(subject.topic) === undefined) problems.push(`${where}.topic: "${subject.topic}" ist keine Rubrik`);
      if (typeof subject.label !== 'string' || subject.label.trim() === '') problems.push(`${where}.label: fehlt`);
      if (subject.aliases !== undefined) {
        if (!Array.isArray(subject.aliases) || !subject.aliases.every(isSlug)) {
          problems.push(`${where}.aliases: Liste von Slugs`);
        } else {
          for (const alias of subject.aliases) claim(alias, `${where}.aliases`);
        }
      }
      checkImages(subject.images, `${where}.images`, CATALOG_LIMITS.subjectImagesMin, CATALOG_LIMITS.subjectImagesMax);
    }
  }
  return problems;
}

/**
 * How many catalogue pictures `--fallback <topic>` can hand out per topic: the topic's own pictures
 * plus those of all its subjects (the order of {@link fallbackCandidates}).
 *
 * @param {object} catalog parsed `fallback-images.json`
 * @returns {Record<string, number>} picture count per topic id
 */
export function topicPoolSizes(catalog) {
  const sizes = {};
  for (const [id, images] of Object.entries(catalog?.topics ?? {})) {
    sizes[id] = Array.isArray(images) ? images.length : 0;
  }
  for (const subject of Object.values(catalog?.subjects ?? {})) {
    if (isPlainObject(subject) && subject.topic in sizes && Array.isArray(subject.images)) sizes[subject.topic] += subject.images.length;
  }
  return sizes;
}

/**
 * The size rule of the catalogue: every topic of the app offers at least
 * {@link CATALOG_LIMITS}.topicPoolMin pictures in total. The routine keeps a picture out of the
 * brief for 14 days (`--recent-days 14`) and a topic draws two to six catalogue pictures a day, so
 * a smaller pool would run dry. Kept apart from {@link catalogProblems}, which every catalogue
 * (also the small ones of the tests) has to pass.
 *
 * @param {object} catalog parsed `fallback-images.json`
 * @returns {string[]} problems in German (empty = fine)
 */
export function catalogPoolProblems(catalog) {
  const sizes = topicPoolSizes(catalog);
  return TOPICS.filter((topic) => (sizes[topic.id] ?? 0) < CATALOG_LIMITS.topicPoolMin).map(
    (topic) =>
      `topics.${topic.id}: mindestens ${CATALOG_LIMITS.topicPoolMin} Bilder mit allen Motiven der Rubrik vorgesehen (gefunden: ${sizes[topic.id] ?? 0})`,
  );
}

/**
 * Reads and checks the fallback catalogue.
 *
 * @param {string} [file] default: `routine/fallback-images.json`
 * @throws {CatalogError} when the file is missing, not JSON or breaks the rules
 */
export function loadFallbackCatalog(file = FALLBACK_CATALOG_PATH) {
  let catalog;
  try {
    catalog = JSON.parse(readFileSync(file, 'utf8').replace(/^﻿/, ''));
  } catch (cause) {
    throw new CatalogError(`Bildkatalog ${file} nicht lesbar: ${cause.message}`, { cause });
  }
  const problems = catalogProblems(catalog);
  if (problems.length > 0) {
    const shown = problems.slice(0, 5).join('; ');
    throw new CatalogError(`Bildkatalog ${file} ist ungültig (${problems.length} Problem(e)): ${shown}`);
  }
  return catalog;
}

/**
 * What a key names in the catalogue: a subject (by key or alias) or a topic.
 *
 * @returns {{ kind: 'motiv' | 'rubrik', key: string, topic: string } | null}
 */
export function resolveFallbackKey(catalog, value) {
  const key = normalizeKey(value);
  if (key === '') return null;
  if (isPlainObject(catalog.topics) && Array.isArray(catalog.topics[key])) return { kind: 'rubrik', key, topic: key };
  for (const [subjectKey, subject] of Object.entries(catalog.subjects ?? {})) {
    if (subjectKey === key || (Array.isArray(subject.aliases) && subject.aliases.includes(key))) {
      return { kind: 'motiv', key: subjectKey, topic: subject.topic };
    }
  }
  return null;
}

/** 32-bit FNV-1a – a stable, dependency-free hash for the rotation. */
function hash(text) {
  let h = 0x811c9dc5;
  for (const ch of String(text)) {
    h ^= ch.codePointAt(0);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h;
}

function rotate(list, seed) {
  if (list.length < 2 || !seed) return list;
  const offset = hash(seed) % list.length;
  return [...list.slice(offset), ...list.slice(0, offset)];
}

/**
 * Catalogue pictures for a subject or topic, best first, without the excluded ones.
 *
 * Order for a subject: its own pictures, then the pictures of its topic, then those of the
 * topic's other subjects. For a topic: its pictures, then those of its subjects. Within each group
 * `seed` rotates the order (same seed, same order), so parallel agents spread over the pictures.
 *
 * @param {object} catalog see {@link loadFallbackCatalog}
 * @param {string} key subject (key or alias) or topic id
 * @param {{ exclude?: Iterable<string>, seed?: string, limit?: number,
 *   recent?: { has(key: string): boolean } }} [options] `exclude`: picture URLs, file pages or
 *   "File:" titles already used in the brief; `recent`: file keys ({@link imageFileKey}) of the
 *   pictures of the recent briefs
 * @returns {{ kind: 'motiv' | 'rubrik', key: string, topic: string, excluded: number,
 *   recentlyUsed: number, candidates: object[] }} `excluded`: skipped because already in the
 *   brief; `recentlyUsed`: skipped because they ran in a recent brief
 * @throws {UnknownFallbackError} for a key the catalogue does not know
 */
export function fallbackCandidates(catalog, key, { exclude = [], seed = '', limit = DEFAULT_LIMIT, recent } = {}) {
  const resolved = resolveFallbackKey(catalog, key);
  if (resolved === null) {
    throw new UnknownFallbackError(
      `„${key}“ ist weder ein Motiv noch eine Rubrik des Bildkatalogs – node routine/find-image.mjs --list-fallbacks zeigt alle.`,
    );
  }
  const blocked = new Set([...exclude].filter((v) => typeof v === 'string' && v.trim() !== '').map((v) => imageKey(v.trim())));
  const subjects = Object.entries(catalog.subjects ?? {}).filter(([, s]) => s.topic === resolved.topic);
  const fromSubjects = (list) => list.flatMap(([k, s]) => s.images.map((entry) => ({ entry, origin: `motiv:${k}` })));
  const topicGroup = catalog.topics[resolved.topic].map((entry) => ({ entry, origin: `rubrik:${resolved.topic}` }));

  const groups =
    resolved.kind === 'motiv'
      ? [
          fromSubjects(subjects.filter(([k]) => k === resolved.key)),
          topicGroup,
          fromSubjects(subjects.filter(([k]) => k !== resolved.key)),
        ]
      : [topicGroup, fromSubjects(subjects)];

  let excluded = 0;
  let recentlyUsed = 0;
  const candidates = [];
  for (const group of groups) {
    for (const { entry, origin } of rotate(group, seed)) {
      if (blocked.has(imageKey(entry.url)) || blocked.has(imageKey(entry.sourceUrl))) {
        excluded++;
        continue;
      }
      if (recent?.has(imageFileKey(entry.url)) || recent?.has(imageFileKey(entry.sourceUrl))) {
        recentlyUsed++;
        continue;
      }
      candidates.push({
        url: entry.url,
        credit: entry.credit ?? '',
        license: entry.license,
        sourceUrl: entry.sourceUrl,
        alt: entry.alt,
        width: entry.width,
        height: entry.height,
        title: entry.title,
        fallback: origin,
      });
    }
  }
  return { ...resolved, excluded, recentlyUsed, candidates: candidates.slice(0, limit) };
}

/**
 * Splits `--exclude` values at commas or whitespace that precede another URL or "File:" title,
 * and at line breaks – commas inside file names ("Reichstag,_Berlin-Mitte.jpg") stay intact.
 */
export function splitExcludeList(value) {
  return String(value ?? '')
    .split(/\s*,\s*(?=https?:\/\/|File:|Datei:)|\s+(?=https?:\/\/|File:|Datei:)|\n/i)
    .map((v) => v.trim())
    .filter((v) => v !== '');
}

function collectImageRefs(doc, refs) {
  const sections = isPlainObject(doc) && Array.isArray(doc.sections) ? doc.sections : [doc];
  for (const section of sections) {
    if (!isPlainObject(section) || !Array.isArray(section.items)) continue;
    for (const item of section.items) {
      if (!isPlainObject(item) || !isPlainObject(item.image)) continue;
      for (const field of ['url', 'sourceUrl']) {
        if (typeof item.image[field] === 'string') refs.push(item.image[field]);
      }
    }
  }
}

/**
 * Picture references (url and sourceUrl) used in a section or brief file, or in all `*.json`
 * files of a directory (e.g. /tmp/sections). Unreadable files only produce warnings.
 *
 * @returns {{ refs: string[], warnings: string[] }}
 */
export function usedImagesIn(target) {
  const refs = [];
  const warnings = [];
  let files;
  try {
    files = statSync(target).isDirectory()
      ? readdirSync(target)
          .filter((f) => f.toLowerCase().endsWith('.json'))
          .sort()
          .map((f) => path.join(target, f))
      : [target];
  } catch {
    return { refs, warnings: [`${target} nicht gefunden – nichts ausgeschlossen`] };
  }
  for (const file of files) {
    try {
      collectImageRefs(JSON.parse(readFileSync(file, 'utf8').replace(/^﻿/, '')), refs);
    } catch (error) {
      warnings.push(`${file} übersprungen (${error.message})`);
    }
  }
  return { refs, warnings };
}

/** Every catalogue picture with a readable location ("rubrik ki #1", "motiv bundestag #2"). */
export function catalogEntries(catalog) {
  const all = [];
  for (const [topic, images] of Object.entries(catalog.topics ?? {})) {
    images.forEach((entry, i) => all.push({ where: `rubrik ${topic} #${i + 1}`, entry }));
  }
  for (const [key, subject] of Object.entries(catalog.subjects ?? {})) {
    subject.images.forEach((entry, i) => all.push({ where: `motiv ${key} #${i + 1}`, entry }));
  }
  return all;
}

/**
 * Re-checks every catalogue picture against Commons: the file still exists, is free, credited as
 * in the catalogue, not restricted by trademark or personality rights, and its 1280 px thumbnail
 * is still the catalogue URL and at least 1200 px wide.
 *
 * @param {object} catalog
 * @param {{ fetchImpl?: typeof fetch, retryDelayMs?: number }} [options]
 * @returns {Promise<{ checked: number, problems: string[] }>}
 * @throws {CommonsError} when Commons cannot be reached
 */
export async function verifyFallbackCatalog(catalog, { fetchImpl = globalThis.fetch, retryDelayMs = 1500 } = {}) {
  const entries = catalogEntries(catalog);
  const problems = [];
  for (let start = 0; start < entries.length; start += VERIFY_BATCH) {
    const batch = entries.slice(start, start + VERIFY_BATCH);
    const json = await fetchCommonsJson(buildFileInfoUrl(batch.map(({ entry }) => entry.title)), { fetchImpl, retryDelayMs });
    throwIfApiError(json);
    const renamed = new Map();
    for (const step of [...(json?.query?.normalized ?? []), ...(json?.query?.redirects ?? [])]) {
      if (step && typeof step.from === 'string' && typeof step.to === 'string') renamed.set(step.from, step.to);
    }
    const pages = new Map(responsePages(json).map((page) => [page.title, page]));
    for (const { where, entry } of batch) {
      let title = entry.title;
      for (let hops = 0; renamed.has(title) && hops < 3; hops++) title = renamed.get(title);
      const page = pages.get(title);
      const label = `${where} (${entry.title})`;
      if (!page || page.missing !== undefined || page.invalid !== undefined) {
        problems.push(`${label}: Datei nicht (mehr) auf Commons`);
        continue;
      }
      if (title !== entry.title) problems.push(`${label}: Datei heißt jetzt ${title}`);
      const result = pageToCandidate(page);
      if (result.reason) {
        problems.push(`${label}: nicht mehr verwendbar (${result.reason})`);
        continue;
      }
      const { candidate } = result;
      const restrictions = String(page.imageinfo[0]?.extmetadata?.Restrictions?.value ?? '').toLowerCase();
      if (restrictions.includes('personality')) problems.push(`${label}: Persönlichkeitsrechte-Hinweis auf Commons`);
      if (candidate.url !== entry.url) problems.push(`${label}: Bild-URL ist jetzt ${candidate.url}`);
      if (candidate.license !== entry.license) problems.push(`${label}: Lizenz ist jetzt ${candidate.license}`);
      if (candidate.credit !== (entry.credit ?? '')) problems.push(`${label}: Urheber ist jetzt „${candidate.credit}“`);
      if (candidate.width < CATALOG_LIMITS.minWidth) problems.push(`${label}: nur ${candidate.width} px breit`);
    }
  }
  return { checked: entries.length, problems };
}

/** The human-readable overview of `--list-fallbacks`. */
export function describeCatalog(catalog) {
  const lines = [
    `Bildkatalog (geprüft ${catalog.verifiedAt}): ${catalogEntries(catalog).length} freie Commons-Bilder.`,
    'Aufruf: node routine/find-image.mjs --fallback <motiv|rubrik> --seed <artikel-id> [--exclude <url,…>]',
    '',
  ];
  for (const topic of TOPICS) {
    const images = catalog.topics?.[topic.id] ?? [];
    lines.push(`${topic.emoji} ${topic.id} – Rubrik, ${images.length} Bilder`);
    for (const [key, subject] of Object.entries(catalog.subjects ?? {})) {
      if (subject.topic !== topic.id) continue;
      const aliases = Array.isArray(subject.aliases) && subject.aliases.length > 0 ? `; auch: ${subject.aliases.join(', ')}` : '';
      lines.push(`  ${key} – ${subject.label} (${subject.images.length}${aliases})`);
    }
  }
  return lines.join('\n');
}

// ---------------------------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------------------------

const USAGE = `Sucht frei lizenzierte Bilder auf Wikimedia Commons (CC0, gemeinfrei, CC BY, CC BY-SA).

Aufruf:
  node routine/find-image.mjs "<suchbegriffe>" [--limit <n>] [--recent-days <n>]
  node routine/find-image.mjs --fallback <motiv|rubrik> [--seed <artikel-id>] [--exclude <url,…>] [--exclude-from <pfad>] [--limit <n>] [--recent-days <n>]
  node routine/find-image.mjs --list-fallbacks
  node routine/find-image.mjs --verify-fallbacks

Optionen:
  --limit <n>            Höchstens so viele Kandidaten (1–${MAX_LIMIT}, Standard ${DEFAULT_LIMIT}).
  --recent-days <n>      Bei Suche und Katalog alle Bilder auslassen, deren Commons-Datei (egal in
                         welcher Größe) in den Briefings der <n> Tage vor dem Briefing-Datum schon
                         lief (Routine: 14) – sonst sieht der Leser jeden Morgen dasselbe Bild.
  --recent-ref <ref>     Mit --recent-days: frühere Briefings aus diesem Git-Stand lesen
                         (Standard: origin/briefs, wird bei Bedarf geholt).
  --recent-from <pfad>   Mit --recent-days: frühere Briefings stattdessen aus diesem Ordner
                         (<pfad>/briefs/<datum>.json oder <pfad>/<datum>.json).
  --date <datum>         Mit --recent-days: Datum des Briefings (JJJJ-MM-TT, Standard: heute).
  --fallback <name>      Kein Netz nötig: geprüfte Ersatzbilder aus routine/fallback-images.json
                         für ein Motiv (z. B. bundestag, leipzig-hbf, windpark) oder eine Rubrik
                         (ki, wirtschaft, politik, …). Erst das Motiv, dann seine Rubrik, dann die
                         anderen Motive der Rubrik. Jeder Kandidat hat zusätzlich "alt" (deutsche
                         Bildbeschreibung) und "fallback" (woher er kommt).
  --seed <text>          Mischt die Reihenfolge reproduzierbar (Artikel-id angeben), damit
                         parallele Rubriken nicht dasselbe Bild nehmen.
  --exclude <url,…>      Diese Bilder überspringen (url, sourceUrl oder File:-Titel; mehrfach
                         erlaubt) – z. B. die schon vergebenen Bilder deiner Rubrik.
  --exclude-from <pfad>  Alle Bilder überspringen, die in dieser Rubrik- oder Briefing-Datei bzw.
                         in den *.json-Dateien dieses Ordners stehen (z. B. /tmp/sections).
  --list-fallbacks       Alle Motive und Rubriken des Katalogs anzeigen.
  --verify-fallbacks     Jeden Katalogeintrag erneut gegen Commons prüfen (Wartung).
  --catalog <datei>      Anderer Katalog statt routine/fallback-images.json.
  -h, --help             Diese Hilfe.

Ausgabe (stdout): JSON-Liste [{ url, credit, license, sourceUrl, width, height, title, description }],
beste zuerst (Querformat ab ${PREFERRED_WIDTH} px); mit --fallback [{ url, credit, license, sourceUrl,
alt, width, height, title, fallback }]. Leere Liste = nichts Passendes (bzw. alles schon vergeben
oder in den letzten Tagen gelaufen).
Ob ein Bild wirklich zur Geschichte passt, entscheidest du – das Skript prüft nur die Lizenz.

Exit-Code: 0 = erledigt, 1 = Commons nicht erreichbar, Katalog ungültig oder Prüfung fehlgeschlagen,
2 = falscher Aufruf (auch: unbekanntes Motiv).`;

function readValue(argv, i, arg) {
  if (arg.includes('=')) return { value: arg.slice(arg.indexOf('=') + 1), next: i };
  return { value: argv[i + 1], next: i + 1 };
}

function parseCliArgs(argv) {
  const terms = [];
  const exclude = [];
  const excludeFrom = [];
  let limit = DEFAULT_LIMIT;
  let fallback;
  let seed = '';
  let catalogPath;
  let list = false;
  let verify = false;
  const recent = {};
  const optionWithValue = (name) => (arg) => arg === name || arg.startsWith(`${name}=`);
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '-h' || arg === '--help') return { help: true };
    const takes = (name) => optionWithValue(name)(arg);
    if (takes('--limit')) {
      const { value: raw, next } = readValue(argv, i, arg);
      i = next;
      const n = Number(raw);
      if (!/^\d+$/.test(String(raw ?? '')) || n < 1 || n > MAX_LIMIT) {
        return { problem: `--limit braucht eine ganze Zahl von 1 bis ${MAX_LIMIT} (gefunden: ${raw ?? 'nichts'})` };
      }
      limit = n;
    } else if (takes('--recent-days')) {
      const { value: raw, next } = readValue(argv, i, arg);
      i = next;
      recent.days = parseDays(raw);
      if (recent.days === null) {
        return { problem: `--recent-days braucht eine ganze Zahl von 1 bis ${MAX_RECENT_DAYS} (gefunden: ${raw ?? 'nichts'})` };
      }
    } else if (['--fallback', '--exclude', '--exclude-from', '--seed', '--catalog', '--recent-from', '--recent-ref', '--date'].some(takes)) {
      const name = arg.split('=')[0];
      const { value, next } = readValue(argv, i, arg);
      i = next;
      if (value === undefined || value.trim() === '' || (value.startsWith('--') && !arg.includes('='))) {
        return { problem: `${name} braucht einen Wert.` };
      }
      if (name === '--fallback') fallback = value;
      else if (name === '--exclude') exclude.push(...splitExcludeList(value));
      else if (name === '--exclude-from') excludeFrom.push(value);
      else if (name === '--seed') seed = value;
      else if (name === '--recent-from') recent.from = value;
      else if (name === '--recent-ref') recent.ref = value;
      else if (name === '--date') {
        if (!isCalendarDate(value)) return { problem: `--date braucht ein Datum JJJJ-MM-TT (gefunden: ${value})` };
        recent.date = value;
      } else catalogPath = value;
    } else if (arg === '--list-fallbacks') {
      list = true;
    } else if (arg === '--verify-fallbacks') {
      verify = true;
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
  const modes = [query !== '', fallback !== undefined, list, verify].filter(Boolean).length;
  if (modes === 0) return { problem: 'Keine Suchbegriffe angegeben.' };
  if (modes > 1) {
    return { problem: 'Suchbegriffe, --fallback, --list-fallbacks und --verify-fallbacks schließen sich gegenseitig aus.' };
  }
  if (fallback === undefined && (exclude.length > 0 || excludeFrom.length > 0 || seed !== '')) {
    return { problem: '--exclude, --exclude-from und --seed gelten nur zusammen mit --fallback.' };
  }
  if (recent.days === undefined && (recent.from !== undefined || recent.ref !== undefined || recent.date !== undefined)) {
    return { problem: '--recent-from, --recent-ref und --date gelten nur zusammen mit --recent-days.' };
  }
  if (recent.from !== undefined && recent.ref !== undefined) {
    return { problem: '--recent-from und --recent-ref schließen sich gegenseitig aus.' };
  }
  if (recent.days !== undefined && (list || verify)) {
    return { problem: '--recent-days gilt nur für die Suche und --fallback.' };
  }
  if (query.length > 250) return { problem: 'Suchbegriffe sind zu lang (höchstens 250 Zeichen).' };
  const history = recent.days === undefined ? null : recent;
  if (fallback !== undefined) return { mode: 'fallback', key: fallback, exclude, excludeFrom, seed, limit, catalogPath, history };
  if (list) return { mode: 'list', catalogPath };
  if (verify) return { mode: 'verify', catalogPath };
  return { mode: 'search', query, limit, history };
}

/**
 * `--recent-days`: the file keys of the pictures of the recent briefs (empty set without the
 * option). Problems reading the history are only hints – the search goes on without them.
 */
function recentImagesFor(history, { cwd, err }) {
  if (history === null) return { keys: new Set(), days: 0 };
  const result = collectRecentImages({ date: history.date ?? berlinDate(), days: history.days, from: history.from, ref: history.ref, cwd });
  for (const w of result.warnings) err(`Hinweis: ${w}`);
  return { keys: new Set(result.images.keys()), days: history.days };
}

/**
 * Runs the CLI and resolves to the exit code (does not call `process.exit`).
 *
 * @param {string[]} argv arguments without `node` and the script path
 * @param {{ out?: Function, err?: Function, fetchImpl?: typeof fetch, retryDelayMs?: number,
 *   catalogPath?: string, cwd?: string }} [io] `cwd`: repository for `--recent-days` (git)
 */
export async function main(
  argv = process.argv.slice(2),
  { out = console.log, err = console.error, fetchImpl = globalThis.fetch, retryDelayMs, catalogPath: defaultCatalog, cwd } = {},
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
    if (args.mode === 'search') {
      const recent = recentImagesFor(args.history, { cwd, err });
      const { candidates, rejected } = await findImages(args.query, { limit: args.limit, fetchImpl, retryDelayMs, recent: recent.keys });
      out(JSON.stringify(candidates, null, 2));
      const dropped = Object.entries(rejected)
        .map(([reason, n]) => `${n}× ${reason}`)
        .join(', ');
      err(
        `${candidates.length} ${candidates.length === 1 ? 'Kandidat' : 'Kandidaten'} für „${args.query}“` +
          (dropped ? ` (verworfen: ${dropped})` : ''),
      );
      return 0;
    }

    const catalog = loadFallbackCatalog(args.catalogPath ?? defaultCatalog ?? FALLBACK_CATALOG_PATH);
    if (args.mode === 'list') {
      out(describeCatalog(catalog));
      return 0;
    }
    if (args.mode === 'verify') {
      const { checked, problems } = await verifyFallbackCatalog(catalog, { fetchImpl, retryDelayMs });
      for (const p of problems) err(`Problem: ${p}`);
      out(
        problems.length === 0
          ? `OK – alle ${checked} Katalogbilder sind auf Commons unverändert frei verfügbar.`
          : `FEHLER – ${problems.length} Problem(e) bei ${checked} Katalogbildern.`,
      );
      return problems.length === 0 ? 0 : 1;
    }

    const exclude = [...args.exclude];
    for (const target of args.excludeFrom) {
      const { refs, warnings } = usedImagesIn(target);
      exclude.push(...refs);
      for (const w of warnings) err(`Hinweis: ${w}`);
    }
    const recent = recentImagesFor(args.history, { cwd, err });
    const result = fallbackCandidates(catalog, args.key, { exclude, seed: args.seed, limit: args.limit, recent: recent.keys });
    out(JSON.stringify(result.candidates, null, 2));
    const what = result.kind === 'motiv' ? `Motiv „${result.key}“ (Rubrik ${result.topic})` : `Rubrik „${result.key}“`;
    const reasons = [
      ...(result.excluded > 0 ? [`${result.excluded} schon vergeben`] : []),
      ...(result.recentlyUsed > 0 ? [`${result.recentlyUsed} in den letzten ${recent.days} Tagen gelaufen`] : []),
    ];
    const skipped = reasons.length > 0 ? ` (${reasons.join(', ')})` : '';
    err(
      result.candidates.length === 0
        ? `Kein freies Katalogbild mehr für ${what}${skipped} – anderes Motiv, andere Rubrik oder eine Commons-Suche versuchen.`
        : `${result.candidates.length} Katalogbild${result.candidates.length === 1 ? '' : 'er'} für ${what}${skipped}`,
    );
    return 0;
  } catch (error) {
    if (error instanceof UnknownFallbackError) {
      err(`FEHLER: ${error.message}`);
      return 2;
    }
    if (error instanceof CommonsError || error instanceof CatalogError) {
      err(`FEHLER: ${error.message}`);
      return 1;
    }
    throw error;
  }
}

if (isMainModule(import.meta.url)) {
  process.exitCode = await main();
}
