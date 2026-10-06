#!/usr/bin/env node
/**
 * Renders the daily Morgenbriefing e-mail from a brief (contract: docs/brief-format.md) plus live
 * weather from Open-Meteo. Zero dependencies, Node >= 18 (global fetch), ESM.
 *
 *   node routine/render-email.mjs --brief <latest.json> --out-dir <ordner>
 *        [--weather-file <json> | --no-weather] [--now <ISO>] [--expect-date <YYYY-MM-DD>]
 *
 * Writes three files into the output directory:
 *
 *   email.html   e-mail-safe HTML (tables, inline styles, max. 600 px, hidden preheader)
 *   email.txt    the plain-text alternative
 *   subject.txt  the subject line (at most 80 characters)
 *
 * Content: date line and a friendly serif headline, the weather ("Vor der Tür" in Bad Düben now
 * and the next hours, Leipzig 10–18 Uhr hour by hour, what to wear), then the news – the teaser
 * and, per topic in catalogue order, the top articles (headline, lead, Commons picture with
 * credit, link to the primary source) and a one-line list of the further headlines.
 *
 * Safety: every text from the brief is HTML-escaped, only https links are emitted, pictures only
 * from upload.wikimedia.org. If the weather cannot be fetched, the e-mail goes out without the
 * weather block. Gmail cuts off messages above ~102 KB, so a very long brief is shortened
 * (fewer full articles per topic, the rest moves to the headline list) until the HTML fits.
 *
 * `--weather-file` takes `{ "home": <Open-Meteo forecast>, "day": <Open-Meteo forecast> }` (the raw
 * API answers for Bad Düben and Leipzig) instead of calling the API – for tests and dry runs.
 *
 * Exit codes: 0 = written, 1 = brief/weather file unusable or files not writable,
 * 2 = usage error, 3 = the brief is not from `--expect-date`.
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { formatBerlin } from './assemble-brief.mjs';
import {
  IMAGE_URL_PREFIX,
  JsonFileError,
  TIER_MORE,
  countItems,
  isCalendarDate,
  isFreeLicense,
  isMainModule,
  licenseNeedsCredit,
  parseOffsetDateTime,
  readJsonFile,
  topicById,
  topicRank,
} from './validate-brief.mjs';

// ---------------------------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------------------------

/** The two places of the app: home ("Vor der Tür") and the day in Leipzig. */
export const PLACES = Object.freeze({
  home: Object.freeze({ name: 'Bad Düben', lat: 51.5928, lon: 12.5853 }),
  day: Object.freeze({ name: 'Leipzig', lat: 51.3397, lon: 12.3731 }),
});
/** Hour window for Leipzig, inclusive (same default as the app). */
export const DAY_WINDOW = Object.freeze({ start: 10, end: 18 });
/** Hours shown after "now" for home. */
export const NEXT_HOURS = 4;

export const OPEN_METEO_URL = 'https://api.open-meteo.com/v1/forecast';
/** Open-Meteo asks API users to identify themselves; no key needed. */
export const USER_AGENT = 'MorgenbriefingEmail/1.0 (persoenliche Morgen-E-Mail; https://github.com/CedrickGD/Morgenbriefing)';
const CURRENT_FIELDS = 'temperature_2m,apparent_temperature,precipitation,weather_code,is_day,wind_speed_10m,wind_gusts_10m';
const HOURLY_FIELDS = 'temperature_2m,apparent_temperature,precipitation_probability,precipitation,weather_code,is_day,wind_gusts_10m';
const DAILY_FIELDS = 'sunrise,sunset,uv_index_max';
const REQUEST_TIMEOUT_MS = 15_000;
const MAX_ATTEMPTS = 2;

export const SUBJECT_MAX = 80;
/** Gmail clips messages larger than 102 KB ("[Nachricht gekürzt]"); stay safely below. */
export const HTML_BUDGET_BYTES = 100_000;
/** Text guards (the validator allows less; these only bound a broken brief). */
const MAX_HEADLINE = 160;
const MAX_SUMMARY = 420;
const MAX_TEASER = 300;
const MAX_CREDIT = 120;
const MAX_ALT = 200;

export const FOOTER_TEXT =
  'Volle Artikel, Live-News und Zuhören in der Morgenbriefing-App · Abbestellen: in der App unter Einstellungen → E-Mail-Briefing';

/** Warm palette of the app (docs/DESIGN.md, docs/ARCHITECTURE.md) – light and dark. */
export const COLORS = Object.freeze({
  page: '#F3F1EC',
  card: '#FCFCFB',
  tile: '#F6F4EF',
  line: '#E8E4DC',
  ink: '#2E2C27',
  body: '#3D3B35',
  soft: '#6B6A63',
  clay: '#C6613F',
  /** Clay darkened for text: ≥ 4.5:1 on the card colour. */
  clayText: '#A3492A',
  rain: '#33658C',
});
const DARK = Object.freeze({
  page: '#151412',
  card: '#1E1D1A',
  tile: '#26241F',
  line: '#3A372F',
  ink: '#ECEAE4',
  body: '#D9D6CE',
  soft: '#B3B0A6',
  clayText: '#EB9A76',
  rain: '#8DB8DA',
});
const SANS = "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";
const SERIF = "Georgia,'Times New Roman',serif";
const CONTENT_WIDTH = 536; // 600 - 2 × 32 px padding

const WEEKDAYS = ['Sonntag', 'Montag', 'Dienstag', 'Mittwoch', 'Donnerstag', 'Freitag', 'Samstag'];
const MONTHS = [
  'Januar', 'Februar', 'März', 'April', 'Mai', 'Juni',
  'Juli', 'August', 'September', 'Oktober', 'November', 'Dezember',
];

/** Raised when the weather cannot be fetched or understood; `message` is German. */
export class WeatherError extends Error {
  constructor(message, options) {
    super(message, options);
    this.name = 'WeatherError';
  }
}

// ---------------------------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------------------------

function isPlainObject(value) {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** Escapes text for HTML element content and (double- or single-quoted) attribute values. */
export function escapeHtml(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/** C0/C1 control characters and the Unicode line/paragraph separators (U+2028, U+2029). */
const CONTROL_RE = new RegExp(`[\\x00-\\x1f\\x7f-\\x9f${String.fromCharCode(0x2028, 0x2029)}]+`, 'g');

/** Single-line plain text: control characters out, whitespace collapsed, trimmed. */
export function cleanText(value) {
  if (typeof value !== 'string') return '';
  return value.replace(CONTROL_RE, ' ').replace(/\s+/g, ' ').trim();
}

/** Cuts `text` to at most `max` characters (code points), ending with "…". */
export function clip(text, max) {
  const chars = Array.from(text);
  if (chars.length <= max) return text;
  return `${chars.slice(0, max - 1).join('').trimEnd()}…`;
}

function capitalize(text) {
  return text.length === 0 ? text : text[0].toLocaleUpperCase('de-DE') + text.slice(1);
}

/** A normalized absolute https URL with a real host name, or `null`. */
export function safeHttpsUrl(value) {
  if (typeof value !== 'string' || value.trim() === '' || /\s/.test(value.trim())) return null;
  let url;
  try {
    url = new URL(value.trim());
  } catch {
    return null;
  }
  if (url.protocol !== 'https:' || !url.hostname.includes('.') || url.username || url.password) return null;
  return url.href;
}

/** Picture URLs: https on upload.wikimedia.org only (the host the app accepts). */
export function safeImageUrl(value) {
  const href = safeHttpsUrl(value);
  return href !== null && href.startsWith(IMAGE_URL_PREFIX) ? href : null;
}

/** Attribution links: the file page on commons.wikimedia.org. */
function safeCommonsUrl(value) {
  const href = safeHttpsUrl(value);
  return href !== null && new URL(href).hostname === 'commons.wikimedia.org' ? href : null;
}

function finite(value) {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

/** Rounds half away from zero and never yields -0 (like the app's Fmt.temp). */
function roundHalfAway(value) {
  const r = Math.sign(value) * Math.round(Math.abs(value));
  return r === 0 ? 0 : r;
}

/** 9.4 → "9°", -0.4 → "0°". */
export function formatTemp(value) {
  return Number.isFinite(value) ? `${roundHalfAway(value)}°` : '–°';
}

/** No-break space: keeps "30 %" together in narrow table cells. */
const NBSP = String.fromCharCode(0xa0);

function percent(value) {
  return `${Math.round(value)}${NBSP}%`;
}

function plural(n, one, many) {
  return `${n} ${n === 1 ? one : many}`;
}

/** `{ long: "Dienstag, 6. Oktober 2026", short: "Di, 6.10." }` for a `YYYY-MM-DD` date. */
export function germanDate(date) {
  const [y, m, d] = date.split('-').map(Number);
  const weekday = WEEKDAYS[new Date(Date.UTC(y, m - 1, d)).getUTCDay()];
  return { long: `${weekday}, ${d}. ${MONTHS[m - 1]} ${y}`, short: `${weekday.slice(0, 2)}, ${d}.${m}.` };
}

/** Berlin wall-clock parts of an instant: `{ date: "2026-10-06", hour: 5, minute: 20, clock: "05:20" }`. */
export function berlinParts(epochMs) {
  const iso = formatBerlin(new Date(epochMs));
  return { date: iso.slice(0, 10), hour: Number(iso.slice(11, 13)), minute: Number(iso.slice(14, 16)), clock: iso.slice(11, 16) };
}

function nextDate(date) {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + 1);
  return d.toISOString().slice(0, 10);
}

/** "Guten Morgen" (4–10), "Guten Tag" (11–17), "Guten Abend" (18–22), "Gute Nacht" (like the app). */
export function greeting(hour) {
  if (hour >= 4 && hour <= 10) return 'Guten Morgen';
  if (hour >= 11 && hour <= 17) return 'Guten Tag';
  if (hour >= 18 && hour <= 22) return 'Guten Abend';
  return 'Gute Nacht';
}

// ---------------------------------------------------------------------------------------------
// Weather: conditions, fetching, model
// ---------------------------------------------------------------------------------------------

const CONDITIONS = {
  CLEAR: { label: 'klar', day: '☀️', night: '🌙' },
  MOSTLY_CLEAR: { label: 'überwiegend sonnig', day: '🌤️', night: '🌙' },
  PARTLY_CLOUDY: { label: 'teils bewölkt', day: '⛅', night: '☁️' },
  OVERCAST: { label: 'bedeckt', day: '☁️', night: '☁️' },
  FOG: { label: 'Nebel', day: '🌫️', night: '🌫️' },
  DRIZZLE: { label: 'Nieselregen', day: '🌦️', night: '🌧️', wet: true },
  RAIN: { label: 'Regen', day: '🌧️', night: '🌧️', wet: true },
  HEAVY_RAIN: { label: 'Starkregen', day: '🌧️', night: '🌧️', wet: true },
  FREEZING_RAIN: { label: 'gefrierender Regen', day: '🧊', night: '🧊', wet: true, wintry: true },
  SNOW: { label: 'Schnee', day: '🌨️', night: '🌨️', wet: true, wintry: true },
  SHOWERS: { label: 'Schauer', day: '🌦️', night: '🌧️', wet: true },
  SNOW_SHOWERS: { label: 'Schneeschauer', day: '🌨️', night: '🌨️', wet: true, wintry: true },
  THUNDER: { label: 'Gewitter', day: '⛈️', night: '⛈️', wet: true },
};

/** WMO weather code (Open-Meteo) → condition, same mapping as the app's `Condition.fromCode`. */
export function conditionOf(code) {
  let key;
  switch (code) {
    case 0: key = 'CLEAR'; break;
    case 1: key = 'MOSTLY_CLEAR'; break;
    case 2: key = 'PARTLY_CLOUDY'; break;
    case 45: case 48: key = 'FOG'; break;
    case 51: case 53: case 55: key = 'DRIZZLE'; break;
    case 56: case 57: case 66: case 67: key = 'FREEZING_RAIN'; break;
    case 61: case 63: key = 'RAIN'; break;
    case 65: key = 'HEAVY_RAIN'; break;
    case 71: case 73: case 75: case 77: key = 'SNOW'; break;
    case 80: case 81: case 82: key = 'SHOWERS'; break;
    case 85: case 86: key = 'SNOW_SHOWERS'; break;
    case 95: case 96: case 99: key = 'THUNDER'; break;
    default: key = 'OVERCAST';
  }
  const c = CONDITIONS[key];
  return { key, label: c.label, wet: c.wet === true, wintry: c.wintry === true, emoji: (isDay) => (isDay ? c.day : c.night) };
}

/** "sonnig" reads better than "klar" during the day. */
function conditionLabel(code, isDay) {
  const c = conditionOf(code);
  return c.key === 'CLEAR' && isDay ? 'sonnig' : c.label;
}

/** Forecast request for one place: current values, hourly and daily, Berlin time, 2 days. */
export function buildForecastUrl(place) {
  const params = new URLSearchParams({
    latitude: place.lat.toFixed(4),
    longitude: place.lon.toFixed(4),
    current: CURRENT_FIELDS,
    hourly: HOURLY_FIELDS,
    daily: DAILY_FIELDS,
    timezone: 'Europe/Berlin',
    forecast_days: '2',
    wind_speed_unit: 'kmh',
  });
  return `${OPEN_METEO_URL}?${params}`;
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function fetchForecast(place, { fetchImpl, retryDelayMs }) {
  let problem = '';
  for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
    let response;
    try {
      response = await fetchImpl(buildForecastUrl(place), {
        headers: { 'User-Agent': USER_AGENT, Accept: 'application/json' },
        signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      });
    } catch (error) {
      problem = `nicht erreichbar (${error?.name === 'TimeoutError' ? 'Zeitüberschreitung' : error?.message ?? error})`;
      if (attempt < MAX_ATTEMPTS) await sleep(retryDelayMs);
      continue;
    }
    if (response.status === 429 || response.status >= 500) {
      problem = `HTTP ${response.status}`;
      if (attempt < MAX_ATTEMPTS) await sleep(retryDelayMs);
      continue;
    }
    if (!response.ok) throw new WeatherError(`Open-Meteo (${place.name}): HTTP ${response.status}`);
    try {
      return await response.json();
    } catch (cause) {
      throw new WeatherError(`Open-Meteo (${place.name}): Antwort ist kein JSON`, { cause });
    }
  }
  throw new WeatherError(`Open-Meteo (${place.name}): ${problem}`);
}

/**
 * Fetches the raw forecasts for both places (in parallel). A place that cannot be fetched is
 * `null` and explained in `problems`; only when both fail does it throw.
 *
 * @returns {Promise<{ home: object|null, day: object|null, problems: string[] }>}
 * @throws {WeatherError}
 */
export async function fetchWeather({ fetchImpl = globalThis.fetch, retryDelayMs = 1500 } = {}) {
  if (typeof fetchImpl !== 'function') throw new WeatherError('fetch ist nicht verfügbar – Node.js ≥ 18 nötig.');
  const results = await Promise.allSettled([
    fetchForecast(PLACES.home, { fetchImpl, retryDelayMs }),
    fetchForecast(PLACES.day, { fetchImpl, retryDelayMs }),
  ]);
  const problems = [];
  const [home, day] = results.map((result) => {
    if (result.status === 'fulfilled') return result.value;
    if (!(result.reason instanceof WeatherError)) throw result.reason;
    problems.push(result.reason.message);
    return null;
  });
  if (home === null && day === null) throw new WeatherError(problems.join('; '));
  return { home, day, problems };
}

const LOCAL_TIME_RE = /^(\d{4}-\d{2}-\d{2})T(\d{2}):(\d{2})$/;

/** Minutes since the epoch for a local `YYYY-MM-DDTHH:MM` (only for differences). */
function localMinutes(text) {
  const m = LOCAL_TIME_RE.exec(text);
  if (m === null) return null;
  const [y, mo, d] = m[1].split('-').map(Number);
  return Date.UTC(y, mo - 1, d, Number(m[2]), Number(m[3])) / 60_000;
}

/** Hourly points with a valid time and temperature, in API order. */
function hourlyPoints(raw) {
  const h = isPlainObject(raw) && isPlainObject(raw.hourly) ? raw.hourly : null;
  if (h === null || !Array.isArray(h.time)) return [];
  const at = (field, i) => (Array.isArray(h[field]) ? finite(h[field][i]) : null);
  const points = [];
  h.time.forEach((time, i) => {
    if (typeof time !== 'string') return;
    const m = LOCAL_TIME_RE.exec(time.trim());
    const temp = at('temperature_2m', i);
    if (m === null || temp === null) return;
    const hour = Number(m[2]);
    const isDay = at('is_day', i);
    points.push({
      time: `${m[1]}T${m[2]}:00`,
      date: m[1],
      hour,
      temp,
      feels: at('apparent_temperature', i) ?? temp,
      pop: Math.min(100, Math.max(0, Math.round(at('precipitation_probability', i) ?? 0))),
      precipMm: Math.max(0, at('precipitation', i) ?? 0),
      code: Math.round(at('weather_code', i) ?? 3),
      isDay: isDay === null ? hour >= 7 && hour <= 19 : isDay >= 0.5,
      gustKmh: at('wind_gusts_10m', i) ?? 0,
    });
  });
  return points;
}

/** Daily value of `field` for `date` ("2026-10-06"), or `null`. */
function dailyValue(raw, field, date) {
  const d = isPlainObject(raw) && isPlainObject(raw.daily) ? raw.daily : null;
  if (d === null || !Array.isArray(d.time) || !Array.isArray(d[field])) return null;
  const i = d.time.indexOf(date);
  return i < 0 ? null : d[field][i] ?? null;
}

/** "2026-10-06T07:14" → "07:14"; null otherwise. */
function clockOf(value) {
  return typeof value === 'string' && LOCAL_TIME_RE.test(value.trim()) ? value.trim().slice(11, 16) : null;
}

function buildHome(raw, now) {
  const points = hourlyPoints(raw);
  const hourKey = `${now.date}T${String(now.hour).padStart(2, '0')}:00`;
  const nowMinutes = localMinutes(`${now.date}T${now.clock}`);
  const hourPoint = points.find((p) => p.time === hourKey) ?? null;

  // Current values when they are recent; otherwise the forecast of the current hour.
  const c = isPlainObject(raw?.current) ? raw.current : null;
  const currentMinutes = c !== null && typeof c.time === 'string' ? localMinutes(c.time.trim()) : null;
  const fresh = currentMinutes !== null && Math.abs(currentMinutes - nowMinutes) <= 90 && finite(c.temperature_2m) !== null;
  let current;
  if (fresh) {
    const temp = finite(c.temperature_2m);
    const isDay = finite(c.is_day);
    current = {
      temp,
      feels: finite(c.apparent_temperature) ?? temp,
      code: Math.round(finite(c.weather_code) ?? hourPoint?.code ?? 3),
      isDay: isDay === null ? hourPoint?.isDay ?? (now.hour >= 7 && now.hour <= 19) : isDay >= 0.5,
      windKmh: finite(c.wind_speed_10m) ?? 0,
      gustKmh: finite(c.wind_gusts_10m) ?? hourPoint?.gustKmh ?? 0,
      precipMm: Math.max(0, finite(c.precipitation) ?? 0),
    };
  } else if (hourPoint !== null) {
    current = {
      temp: hourPoint.temp,
      feels: hourPoint.feels,
      code: hourPoint.code,
      isDay: hourPoint.isDay,
      windKmh: null,
      gustKmh: hourPoint.gustKmh,
      precipMm: hourPoint.precipMm,
    };
  } else {
    throw new WeatherError(`Open-Meteo (${PLACES.home.name}): keine aktuellen Werte für ${hourKey}`);
  }

  const nextHours = points.filter((p) => p.time > hourKey).slice(0, NEXT_HOURS);
  return {
    place: PLACES.home.name,
    current,
    nextHours,
    sunrise: clockOf(dailyValue(raw, 'sunrise', now.date)),
    sunset: clockOf(dailyValue(raw, 'sunset', now.date)),
  };
}

function buildDay(raw, now) {
  const points = hourlyPoints(raw);
  const isTomorrow = now.hour >= DAY_WINDOW.end;
  const date = isTomorrow ? nextDate(now.date) : now.date;
  const hours = [];
  for (let h = DAY_WINDOW.start; h <= DAY_WINDOW.end; h++) {
    const point = points.find((p) => p.time === `${date}T${String(h).padStart(2, '0')}:00`);
    if (point) hours.push(point);
  }
  if (hours.length === 0) throw new WeatherError(`Open-Meteo (${PLACES.day.name}): keine Vorhersage für den ${date}`);
  const temps = hours.map((p) => p.temp);
  const feels = hours.map((p) => p.feels);
  return {
    place: PLACES.day.name,
    date,
    isTomorrow,
    start: DAY_WINDOW.start,
    end: DAY_WINDOW.end,
    hours,
    minTemp: Math.min(...temps),
    maxTemp: Math.max(...temps),
    minFeels: Math.min(...feels),
    maxFeels: Math.max(...feels),
    maxPop: Math.max(...hours.map((p) => p.pop)),
    precipSum: Math.round(hours.reduce((sum, p) => sum + p.precipMm, 0) * 10) / 10,
    maxGust: Math.max(...hours.map((p) => p.gustKmh)),
    uvMax: finite(dailyValue(raw, 'uv_index_max', date)) ?? 0,
  };
}

/**
 * Turns the raw Open-Meteo answers into the weather model of the e-mail. Each place is built on
 * its own: if one is missing or unusable it is `null` and listed in `problems`. `null` in `raw`
 * means "could not be fetched" (the fetcher already reported why) and is skipped silently.
 *
 * @param {{ home?: unknown, day?: unknown }} raw
 * @param {number} nowMs reference instant
 * @returns {{ home: object|null, day: object|null, problems: string[] }}
 */
export function buildWeather(raw, nowMs) {
  const now = berlinParts(nowMs);
  const problems = [];
  const attempt = (fn, value, place) => {
    if (value === null) return null;
    if (!isPlainObject(value)) {
      problems.push(`Wetterdaten für ${place.name} fehlen`);
      return null;
    }
    try {
      return fn(value, now);
    } catch (error) {
      if (!(error instanceof WeatherError)) throw error;
      problems.push(error.message);
      return null;
    }
  };
  return {
    home: attempt(buildHome, isPlainObject(raw) ? raw.home : undefined, PLACES.home),
    day: attempt(buildDay, isPlainObject(raw) ? raw.day : undefined, PLACES.day),
    problems,
  };
}

// ---------------------------------------------------------------------------------------------
// Weather texts
// ---------------------------------------------------------------------------------------------

/**
 * Clothing bands by felt temperature, coldest first (same thresholds as the app). `wear` is the
 * short form ("Morgens Jacke oder Fleece"), `some` the form with article ("reicht ein T-Shirt").
 */
const BANDS = [
  { wear: 'dicker Wintermantel, Mütze, Handschuhe und Schal', some: 'ein dicker Wintermantel mit Mütze, Handschuhen und Schal', plural: false, winter: true, emoji: '🧤' },
  { wear: 'Winterjacke, Mütze und Handschuhe', some: 'Winterjacke, Mütze und Handschuhe', plural: true, winter: true, emoji: '🧤' },
  { wear: 'warme Jacke', some: 'eine warme Jacke', plural: false, emoji: '🧥' },
  { wear: 'Jacke oder Fleece', some: 'eine Jacke oder ein Fleece', plural: false, emoji: '🧥' },
  { wear: 'leichte Jacke oder Pulli', some: 'eine leichte Jacke oder ein Pulli', plural: false, emoji: '🧥' },
  { wear: 'Langarm oder dünner Pulli', some: 'ein Langarmshirt oder ein dünner Pulli', plural: false, emoji: '🧶' },
  { wear: 'T-Shirt', some: 'ein T-Shirt', plural: false, emoji: '👕' },
  { wear: 'luftige Sachen und kurze Hose', some: 'luftige Sachen und eine kurze Hose', plural: true, emoji: '🩳' },
];

function bandOf(feels) {
  if (feels < -5) return 0;
  if (feels < 2) return 1;
  if (feels < 8) return 2;
  if (feels < 13) return 3;
  if (feels < 17) return 4;
  if (feels < 21) return 5;
  if (feels <= 26) return 6;
  return 7;
}

const verb = (band) => (BANDS[band].plural ? 'reichen' : 'reicht');

/** First hour in the window that counts as wet, or `undefined`. */
function firstWetHour(day) {
  return day.hours.find((p) => p.pop >= 50 || p.precipMm >= 0.3 || conditionOf(p.code).wet);
}

/**
 * Short clothing hint from simple rules: felt temperature in the morning vs. the warmest (or
 * coldest) hour in Leipzig, rain gear, slippery ground, sunscreen.
 *
 * @returns {{ emoji: string, text: string } | null}
 */
export function clothingHint(weather) {
  const home = weather?.home ?? null;
  const day = weather?.day ?? null;
  if (home === null && day === null) return null;
  const tomorrow = day?.isTomorrow === true;
  const morningFeels = home !== null && !tomorrow ? home.current.feels : day.hours[0].feels;
  const morning = bandOf(morningFeels);
  const sentences = [];

  const warm = day !== null ? bandOf(day.maxFeels) : morning;
  const cold = day !== null ? bandOf(day.minFeels) : morning;
  const bothWinter = (a, b) => BANDS[a].winter && BANDS[b].winter;
  if (day !== null && warm > morning && !bothWinter(warm, morning)) {
    const at = day.hours.find((p) => bandOf(p.feels) >= warm);
    const when = at ? `ab ${at.hour} Uhr` : 'später';
    sentences.push(`Morgens ${BANDS[morning].wear}, ${when} ${verb(warm)} ${BANDS[warm].some}.`);
  } else if (day !== null && cold < morning && !bothWinter(cold, morning)) {
    const at = day.hours.find((p) => bandOf(p.feels) <= cold);
    const when = at ? `ab ${at.hour} Uhr` : 'später';
    sentences.push(`Morgens ${BANDS[morning].wear}, ${when} wird's kühler – dann ${BANDS[cold].some}.`);
  } else if (morning >= 5) {
    sentences.push(`Heute ${verb(morning)} ${BANDS[morning].some}.`);
  } else {
    sentences.push(`Den ganzen Tag ${BANDS[morning].wear}.`);
  }

  const wetNow = home !== null && !tomorrow && (conditionOf(home.current.code).wet || home.current.precipMm > 0);
  const wetLater = day !== null ? firstWetHour(day) : undefined;
  const needsRainGear = wetNow || (day !== null && (day.maxPop >= 40 || day.precipSum >= 0.5));
  const gust = Math.max(day?.maxGust ?? 0, home !== null && !tomorrow ? home.current.gustKmh ?? 0 : 0);
  if (!needsRainGear) {
    sentences.push('Kein Schirm nötig.');
  } else if (gust >= 40) {
    sentences.push('Regenjacke statt Schirm – zu windig.');
  } else if (wetNow) {
    sentences.push('Schirm einpacken – draußen ist es gerade nass.');
  } else if (wetLater !== undefined && wetLater.hour > (day?.start ?? 0)) {
    sentences.push(`Schirm einpacken – ab ${wetLater.hour} Uhr wird's nass.`);
  } else {
    sentences.push(`Schirm einpacken – bis ${percent(day?.maxPop ?? 0)} Regenrisiko.`);
  }

  const wintry = (day?.hours ?? []).some((p) => conditionOf(p.code).wintry) || (home !== null && !tomorrow && conditionOf(home.current.code).wintry);
  const icy = wintry || ((wetNow || needsRainGear) && Math.min(day?.minTemp ?? Infinity, home?.current.temp ?? Infinity) <= 1);
  if (icy) sentences.push('Feste Schuhe an – es kann glatt sein.');
  if ((day?.uvMax ?? 0) >= 5) sentences.push('Sonnencreme nicht vergessen.');

  return { emoji: BANDS[morning].emoji, text: sentences.join(' ') };
}

/** Friendly serif headline, e.g. "Guten Morgen! Nebel zum Start, dann Sonne satt bei bis zu 21° in Leipzig." */
export function weatherHeadline(weather, nowMs) {
  const hello = `${greeting(berlinParts(nowMs).hour)}!`;
  const day = weather?.day ?? null;
  const home = weather?.home ?? null;
  if (day === null) {
    if (home === null) return `${hello} Hier ist dein Überblick für heute.`;
    return `${hello} ${capitalize(conditionLabel(home.current.code, home.current.isDay))} bei ${formatTemp(home.current.temp)} vor der Tür.`;
  }
  const max = formatTemp(day.maxTemp);
  const codes = day.hours.map((p) => conditionOf(p.code));
  const wet = firstWetHour(day);
  const wetHours = day.hours.filter((p) => p.pop >= 50 || p.precipMm >= 0.3 || conditionOf(p.code).wet).length;
  let phrase;
  if (codes.some((c) => c.key === 'THUNDER')) phrase = `Gewitter möglich bei bis zu ${max}`;
  else if (codes.some((c) => c.wintry)) phrase = `Schnee in Sicht bei höchstens ${max}`;
  else if (wet !== undefined && (wet.hour === day.start || wetHours * 2 >= day.hours.length)) phrase = `ein nasser Tag mit bis zu ${max}`;
  else if (wet !== undefined) phrase = `ab ${wet.hour} Uhr Regen, bis ${max}`;
  else if (codes.filter((c) => c.key === 'CLEAR' || c.key === 'MOSTLY_CLEAR').length * 3 >= day.hours.length * 2) phrase = `Sonne satt bei bis zu ${max}`;
  else if (codes.filter((c) => ['CLEAR', 'MOSTLY_CLEAR', 'PARTLY_CLOUDY'].includes(c.key)).length >= 3) phrase = `Sonne und Wolken, bis ${max}`;
  else if (codes.filter((c) => c.key === 'FOG').length * 2 >= day.hours.length) phrase = `neblig bei höchstens ${max}`;
  else phrase = `grau, aber trocken – bis ${max}`;

  if (day.isTomorrow) return `${hello} Morgen in Leipzig: ${phrase}.`;
  const foggyStart = home !== null && conditionOf(home.current.code).key === 'FOG';
  return foggyStart
    ? `${hello} Nebel zum Start, dann ${phrase} in Leipzig.`
    : `${hello} ${capitalize(phrase)} in Leipzig.`;
}

/** "trocken · Böen bis 45 km/h" – one line under Leipzig's min → max. */
function daySummary(day) {
  const parts = [];
  const wet = firstWetHour(day);
  if (day.maxPop < 20 && day.precipSum < 0.2 && wet === undefined) parts.push('trocken');
  else if (wet !== undefined && wet.hour > day.start) parts.push(`ab ${wet.hour} Uhr Regen möglich (bis ${percent(day.maxPop)})`);
  else parts.push(`Regenrisiko bis ${percent(day.maxPop)}`);
  if (day.precipSum >= 0.5) parts.push(`${String(day.precipSum).replace('.', ',')} mm`);
  if (day.maxGust >= 40) parts.push(`Böen bis ${Math.round(day.maxGust)} km/h`);
  if (day.uvMax >= 5) parts.push(`UV ${Math.round(day.uvMax)}`);
  return capitalize(parts.join(' · '));
}

/** "Bedeckt, gefühlt 7°" and "Wind 12 km/h · Sonnenaufgang 07:14". */
function homeLines(home, nowMs) {
  const c = home.current;
  const first = `${capitalize(conditionLabel(c.code, c.isDay))}, gefühlt ${formatTemp(c.feels)}`;
  const second = [];
  if (c.windKmh !== null) second.push(`Wind ${Math.round(c.windKmh)} km/h`);
  if (c.gustKmh >= 40) second.push(`Böen ${Math.round(c.gustKmh)} km/h`);
  const clock = berlinParts(nowMs).clock;
  if (home.sunrise && clock < home.sunrise) second.push(`Sonnenaufgang ${home.sunrise}`);
  else if (home.sunset && clock < home.sunset) second.push(`Sonnenuntergang ${home.sunset}`);
  return { first, second: second.join(' · ') };
}

// ---------------------------------------------------------------------------------------------
// News model
// ---------------------------------------------------------------------------------------------

function prepareImage(image) {
  if (!isPlainObject(image)) return null;
  const url = safeImageUrl(image.url);
  const license = cleanText(image.license);
  if (url === null || !isFreeLicense(license)) return null;
  const credit = clip(cleanText(image.credit), MAX_CREDIT);
  if (credit === '' && licenseNeedsCredit(license)) return null;
  return { url, license, credit, sourceUrl: safeCommonsUrl(image.sourceUrl), alt: clip(cleanText(image.alt), MAX_ALT) };
}

function prepareItem(item) {
  const minutes = Number.isInteger(item.readingMinutes) && item.readingMinutes > 0 ? item.readingMinutes : null;
  return {
    headline: clip(cleanText(item.headline), MAX_HEADLINE),
    summary: clip(cleanText(item.summary), MAX_SUMMARY),
    source: clip(cleanText(item.source), 80),
    url: safeHttpsUrl(item.url),
    readingMinutes: minutes,
    hasBody: typeof item.body === 'string' && item.body.trim() !== '',
    image: prepareImage(item.image),
  };
}

/**
 * Sections in catalogue order with their top and further articles. Schema-1 items (no `tier`)
 * count as top articles; items without a headline are skipped; empty sections are dropped.
 */
export function newsSections(brief) {
  const list = Array.isArray(brief?.sections) ? brief.sections : [];
  return list
    .map((section, index) => ({ section, index }))
    .filter(({ section }) => isPlainObject(section))
    .sort((a, b) => topicRank(a.section.id) - topicRank(b.section.id) || a.index - b.index)
    .map(({ section }) => {
      const topic = typeof section.id === 'string' ? topicById(section.id) : undefined;
      const items = (Array.isArray(section.items) ? section.items : [])
        .filter((item) => isPlainObject(item) && cleanText(item.headline) !== '')
        .map((item) => ({ tier: item.tier, ...prepareItem(item) }));
      return {
        id: typeof section.id === 'string' ? section.id : '',
        title: topic?.title ?? (clip(cleanText(section.title), 60) || 'Weitere Meldungen'),
        emoji: topic?.emoji ?? (clip(cleanText(section.emoji), 4) || '📰'),
        top: items.filter((i) => i.tier !== TIER_MORE),
        more: items.filter((i) => i.tier === TIER_MORE),
      };
    })
    .filter((s) => s.top.length + s.more.length > 0);
}

/** Limits the full articles per topic; the rest joins the headline list. */
function limitTop(sections, maxTop) {
  return sections.map((s) => ({ ...s, top: s.top.slice(0, maxTop), more: [...s.top.slice(maxTop), ...s.more] }));
}

// ---------------------------------------------------------------------------------------------
// Subject
// ---------------------------------------------------------------------------------------------

/** "☀️ 9° in Bad Düben · 21 Meldungen · Di, 6.10." – at most 80 characters. */
export function buildSubject({ date, itemCount, weather }) {
  const short = germanDate(date).short;
  const count = itemCount > 0 ? plural(itemCount, 'Meldung', 'Meldungen') : 'Wetter & News';
  let lead = '☀️ Dein Morgenbriefing';
  if (weather?.home) {
    const c = weather.home.current;
    lead = `${conditionOf(c.code).emoji(c.isDay)} ${formatTemp(c.temp)} in ${weather.home.place}`;
  } else if (weather?.day) {
    const d = weather.day;
    lead = `${conditionOf(d.hours[0].code).emoji(true)} ${formatTemp(d.minTemp)} → ${formatTemp(d.maxTemp)} in ${d.place}`;
  }
  for (const parts of [[lead, count, short], [lead, count], [lead]]) {
    const subject = parts.join(' · ');
    if (Array.from(subject).length <= SUBJECT_MAX) return subject;
  }
  return clip(lead, SUBJECT_MAX);
}

// ---------------------------------------------------------------------------------------------
// HTML
// ---------------------------------------------------------------------------------------------

const e = escapeHtml;
const TABLE = 'role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"';
const OVERLINE = `margin:0;font-size:12px;line-height:1.4;font-weight:700;letter-spacing:1.4px;text-transform:uppercase;color:${COLORS.clayText}`;
const TILE = `background:${COLORS.tile};border-radius:20px`;

function headStyles() {
  const d = DARK;
  return [
    '<style>',
    ':root{color-scheme:light dark;supported-color-schemes:light dark}',
    'body{margin:0;padding:0;-webkit-text-size-adjust:100%}',
    'a{text-decoration:none}',
    '@media (max-width:620px){.px{padding-left:18px!important;padding-right:18px!important}.tp{padding-left:16px!important;padding-right:16px!important}.h1{font-size:25px!important}}',
    '</style>',
    '<style>',
    `@media (prefers-color-scheme:dark){.pg{background:${d.page}!important}.cd{background:${d.card}!important}.tl{background:${d.tile}!important}` +
      `.ink{color:${d.ink}!important}.bd{color:${d.body}!important}.sf{color:${d.soft}!important}.cl{color:${d.clayText}!important}` +
      `.rn{color:${d.rain}!important}.ln{border-color:${d.line}!important}}`,
    '</style>',
  ];
}

/** Hour columns: label, emoji, temperature, rain probability. */
function hourCells(points, { label, emojiSize }) {
  const width = `${Math.floor(100 / Math.max(points.length, 1))}%`;
  return points.map((p) => {
    const rainy = p.pop >= 30;
    return (
      `<td align="center" width="${width}" class="sf" style="padding:10px 0 0;font-size:11px;line-height:1.5;color:${COLORS.soft}">` +
      `${e(label(p))}<br><span style="font-size:${emojiSize}px;line-height:1.5">${conditionOf(p.code).emoji(p.isDay)}</span><br>` +
      `<b class="ink" style="font-size:14px;color:${COLORS.ink}">${formatTemp(p.temp)}</b><br>` +
      `<span class="${rainy ? 'rn' : 'sf'}" style="color:${rainy ? COLORS.rain : COLORS.soft}${rainy ? ';font-weight:700' : ''}">${percent(p.pop)}</span></td>`
    );
  });
}

function weatherRows(view) {
  const rows = [];
  const { weather, nowMs, clothing } = view;
  if (weather.home !== null) {
    const home = weather.home;
    const c = home.current;
    const lines = homeLines(home, nowMs);
    rows.push(
      `<tr><td class="px" style="padding:24px 32px 0">`,
      `<table ${TABLE} class="tl" style="${TILE}"><tr><td class="tp" style="padding:18px 20px 16px">`,
      `<p class="cl" style="${OVERLINE}">Vor der Tür · ${e(home.place)}</p>`,
      `<table role="presentation" cellpadding="0" cellspacing="0" border="0" style="margin-top:10px"><tr>`,
      `<td style="font-size:38px;line-height:1;padding-right:10px">${conditionOf(c.code).emoji(c.isDay)}</td>`,
      `<td class="ink" style="font-family:${SERIF};font-size:46px;line-height:1;font-weight:600;color:${COLORS.ink};padding-right:16px">${formatTemp(c.temp)}</td>`,
      `<td class="sf" style="font-size:14px;line-height:1.5;color:${COLORS.soft}"><b class="ink" style="color:${COLORS.ink}">${e(lines.first)}</b>${lines.second ? `<br>${e(lines.second)}` : ''}</td>`,
      '</tr></table>',
    );
    if (home.nextHours.length > 0) {
      rows.push(
        `<table ${TABLE} class="ln" style="margin-top:14px;border-top:1px solid ${COLORS.line};table-layout:fixed"><tr>`,
        ...hourCells(home.nextHours, { label: (p) => `${p.hour} Uhr`, emojiSize: 20 }),
        '</tr></table>',
      );
    }
    rows.push('</td></tr></table>', '</td></tr>');
  }
  if (weather.day !== null) {
    const day = weather.day;
    const title = `${day.isTomorrow ? 'Morgen in ' : ''}${day.place} · ${day.start}–${day.end} Uhr`;
    rows.push(
      `<tr><td class="px" style="padding:12px 32px 0">`,
      `<table ${TABLE} class="tl" style="${TILE}"><tr><td class="tp" style="padding:18px 20px 16px">`,
      `<p class="cl" style="${OVERLINE}">${e(title)}</p>`,
      `<p class="ink" style="margin:8px 0 0;font-family:${SERIF};font-size:26px;line-height:1.2;font-weight:600;color:${COLORS.ink}">${formatTemp(day.minTemp)} → ${formatTemp(day.maxTemp)}</p>`,
      `<p class="sf" style="margin:4px 0 0;font-size:14px;line-height:1.5;color:${COLORS.soft}">${e(daySummary(day))}</p>`,
      `<table ${TABLE} class="ln" style="margin-top:12px;border-top:1px solid ${COLORS.line};table-layout:fixed"><tr>`,
      ...hourCells(day.hours, { label: (p) => String(p.hour), emojiSize: 17 }),
      '</tr></table>',
      '</td></tr></table>',
      '</td></tr>',
    );
  }
  if (clothing !== null) {
    rows.push(
      `<tr><td class="px" style="padding:12px 32px 0">`,
      `<table ${TABLE} class="tl" style="${TILE}"><tr><td class="tp ink" style="padding:14px 20px;font-size:15px;line-height:1.5;color:${COLORS.ink}">` +
        `<b>${clothing.emoji} Anziehen:</b> ${e(clothing.text)}</td></tr></table>`,
      '</td></tr>',
    );
  }
  return rows;
}

function imageCredit(image) {
  const who = image.credit !== '' ? image.credit : 'Wikimedia Commons';
  const label = `${who} · ${image.license}${image.credit !== '' ? ' · Wikimedia Commons' : ''}`;
  const text =
    image.sourceUrl !== null
      ? `<a href="${e(image.sourceUrl)}" class="sf" style="color:${COLORS.soft};text-decoration:underline">${e(label)}</a>`
      : e(label);
  return `<p class="sf" style="margin:6px 0 0;font-size:11px;line-height:1.4;color:${COLORS.soft}">Bild: ${text}</p>`;
}

function itemRows(item, first) {
  const rows = [`<tr><td class="px" style="padding:${first ? 18 : 22}px 32px 0">`];
  if (!first) rows.push(`<div class="ln" style="border-top:1px solid ${COLORS.line};padding-top:22px">`);
  if (item.image !== null) {
    const alt = item.image.alt !== '' ? item.image.alt : item.headline;
    rows.push(
      `<img src="${e(item.image.url)}" width="${CONTENT_WIDTH}" alt="${e(alt)}" style="display:block;width:100%;max-width:${CONTENT_WIDTH}px;height:auto;border:0;border-radius:16px">`,
      imageCredit(item.image),
    );
  }
  rows.push(
    `<h3 class="ink" style="margin:${item.image !== null ? 14 : 0}px 0 0;font-family:${SERIF};font-size:22px;line-height:1.28;font-weight:600;color:${COLORS.ink}">${e(item.headline)}</h3>`,
  );
  if (item.summary !== '') {
    rows.push(`<p class="bd" style="margin:8px 0 0;font-size:16px;line-height:1.55;color:${COLORS.body}">${e(item.summary)}</p>`);
  }
  const meta = [];
  if (item.url !== null) {
    meta.push(`<a href="${e(item.url)}" class="cl" style="color:${COLORS.clayText};font-weight:700;text-decoration:none">${e(item.source || 'Quelle')}&nbsp;↗</a>`);
  } else if (item.source !== '') {
    meta.push(e(item.source));
  }
  if (item.hasBody) meta.push(item.readingMinutes !== null ? `ganzer Artikel in der App · ${item.readingMinutes}&nbsp;Min.` : 'ganzer Artikel in der App');
  if (meta.length > 0) {
    rows.push(`<p class="sf" style="margin:10px 0 0;font-size:13px;line-height:1.5;color:${COLORS.soft}">${meta.join(' · ')}</p>`);
  }
  if (!first) rows.push('</div>');
  rows.push('</td></tr>');
  return rows;
}

function sectionRows(section) {
  const rows = [
    `<tr><td class="px" style="padding:36px 32px 0">`,
    `<table ${TABLE}><tr><td class="ink ln" style="border-top:2px solid ${COLORS.ink};padding-top:12px;font-family:${SERIF};font-size:21px;line-height:1.3;font-weight:600;color:${COLORS.ink}">${e(section.emoji)} ${e(section.title)}</td></tr></table>`,
    '</td></tr>',
  ];
  section.top.forEach((item, i) => rows.push(...itemRows(item, i === 0)));
  if (section.more.length > 0) {
    const label = section.top.length > 0 ? 'Mehr in der App:' : 'In der App:';
    rows.push(
      `<tr><td class="px" style="padding:${section.top.length > 0 ? 18 : 14}px 32px 0">`,
      `<p class="sf" style="margin:0;font-size:14px;line-height:1.55;color:${COLORS.soft}"><b class="ink" style="color:${COLORS.ink}">${label}</b> ${section.more.map((i) => e(i.headline)).join(' · ')}</p>`,
      '</td></tr>',
    );
  }
  return rows;
}

function renderHtml(view) {
  const preheaderPad = '&zwnj;&nbsp;'.repeat(40);
  const L = [
    '<!DOCTYPE html>',
    '<html lang="de" xmlns="http://www.w3.org/1999/xhtml">',
    '<head>',
    '<meta charset="utf-8">',
    '<meta name="viewport" content="width=device-width,initial-scale=1">',
    '<meta name="x-apple-disable-message-reformatting">',
    '<meta name="format-detection" content="telephone=no,date=no,address=no,email=no">',
    '<meta name="color-scheme" content="light dark">',
    '<meta name="supported-color-schemes" content="light dark">',
    `<title>${e(view.subject)}</title>`,
    ...headStyles(),
    '</head>',
    `<body class="pg" style="margin:0;padding:0;background:${COLORS.page}">`,
    `<div style="display:none;max-height:0;overflow:hidden;opacity:0;mso-hide:all;font-size:1px;line-height:1px;color:${COLORS.page}">${e(view.preheader)}${preheaderPad}</div>`,
    `<table ${TABLE} class="pg" style="background:${COLORS.page}"><tr><td align="center" style="padding:20px 10px">`,
    '<!--[if mso]><table role="presentation" width="600" cellpadding="0" cellspacing="0" border="0"><tr><td><![endif]-->',
    `<table ${TABLE} class="cd" style="max-width:600px;background:${COLORS.card};border-radius:24px;font-family:${SANS};color:${COLORS.ink}">`,
    `<tr><td class="px" style="padding:32px 32px 0">`,
    `<p class="cl" style="${OVERLINE}">☀️ Morgenbriefing · ${e(view.dateLong)}</p>`,
    `<h1 class="ink h1" style="margin:10px 0 0;font-family:${SERIF};font-size:30px;line-height:1.22;font-weight:600;color:${COLORS.ink}">${e(view.headline)}</h1>`,
    '</td></tr>',
  ];
  L.push(...weatherRows(view));
  if (view.sections.length > 0 || view.teaser !== '') {
    L.push(
      `<tr><td class="px" style="padding:36px 32px 0">`,
      `<p class="cl" style="${OVERLINE}">Die Nachrichten</p>`,
    );
    if (view.teaser !== '') {
      L.push(`<p class="ink" style="margin:8px 0 0;font-family:${SERIF};font-size:19px;line-height:1.45;font-style:italic;color:${COLORS.ink}">${e(view.teaser)}</p>`);
    }
    L.push('</td></tr>');
  }
  for (const section of view.sections) L.push(...sectionRows(section));
  L.push(
    `<tr><td class="px" style="padding:40px 32px 32px">`,
    `<table ${TABLE}><tr><td class="sf ln" style="border-top:1px solid ${COLORS.line};padding-top:20px;font-size:13px;line-height:1.6;color:${COLORS.soft}">`,
    `<b class="ink" style="color:${COLORS.ink}">${e(FOOTER_TEXT)}</b>`,
    `<br><br>Wetter: <a href="https://open-meteo.com/" class="sf" style="color:${COLORS.soft};text-decoration:underline">Open-Meteo.com</a> (CC BY 4.0) · Bilder: Wikimedia Commons, Urheber und Lizenz am Bild · Texte: eigene Artikel der Morgenbriefing-Routine nach den genannten Quellen.`,
    '<br><br>Bis morgen ☀️',
    '</td></tr></table>',
    '</td></tr>',
    '</table>',
    '<!--[if mso]></td></tr></table><![endif]-->',
    '</td></tr></table>',
    '</body>',
    '</html>',
  );
  return `${L.join('\n')}\n`;
}

// ---------------------------------------------------------------------------------------------
// Plain text
// ---------------------------------------------------------------------------------------------

function renderText(view) {
  const L = [`MORGENBRIEFING · ${view.dateLong.toLocaleUpperCase('de-DE')}`, '', view.headline];
  const { weather, nowMs, clothing } = view;
  if (weather.home !== null) {
    const home = weather.home;
    const c = home.current;
    const lines = homeLines(home, nowMs);
    L.push('', `VOR DER TÜR · ${home.place.toLocaleUpperCase('de-DE')}`);
    L.push(`${conditionOf(c.code).emoji(c.isDay)} ${formatTemp(c.temp)} – ${lines.first}${lines.second ? ` · ${lines.second}` : ''}`);
    if (home.nextHours.length > 0) {
      L.push(`Nächste Stunden: ${home.nextHours.map((p) => `${p.hour} Uhr ${conditionOf(p.code).emoji(p.isDay)} ${formatTemp(p.temp)}`).join(' · ')}`);
    }
  }
  if (weather.day !== null) {
    const day = weather.day;
    L.push('', `${day.isTomorrow ? 'MORGEN IN ' : ''}${day.place.toLocaleUpperCase('de-DE')} · ${day.start}–${day.end} UHR`);
    L.push(`${formatTemp(day.minTemp)} → ${formatTemp(day.maxTemp)} · ${daySummary(day)}`);
    for (const p of day.hours) {
      L.push(`${String(p.hour).padStart(2, '0')} Uhr  ${conditionOf(p.code).emoji(p.isDay)} ${formatTemp(p.temp).padStart(3, ' ')}  Regen ${percent(p.pop)}`);
    }
  }
  if (clothing !== null) L.push('', `${clothing.emoji} Anziehen: ${clothing.text}`);
  if (view.teaser !== '' || view.sections.length > 0) {
    L.push('', '', 'DIE NACHRICHTEN');
    if (view.teaser !== '') L.push(view.teaser);
  }
  for (const section of view.sections) {
    L.push('', '', `${section.emoji} ${section.title}`, '─'.repeat(32));
    for (const item of section.top) {
      L.push('', item.headline);
      if (item.summary !== '') L.push(item.summary);
      const source = [item.source, item.url].filter((part) => part !== '' && part !== null).join(' – ');
      if (source !== '') L.push(`Quelle: ${source}`);
    }
    if (section.more.length > 0) {
      L.push('', `${section.top.length > 0 ? 'Mehr in der App' : 'In der App'}: ${section.more.map((i) => i.headline).join(' · ')}`);
    }
  }
  L.push(
    '',
    '',
    '—',
    FOOTER_TEXT,
    'Wetter: Open-Meteo.com (CC BY 4.0) · Texte: eigene Artikel der Morgenbriefing-Routine nach den genannten Quellen.',
    'Bis morgen ☀️',
  );
  return `${L.join('\n')}\n`;
}

// ---------------------------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------------------------

/**
 * Renders subject, preheader, HTML and plain text.
 *
 * @param {object} params
 * @param {object} params.brief the parsed brief (schema 1 or 2)
 * @param {{ home: object|null, day: object|null } | null} [params.weather] from `buildWeather`
 * @param {number} [params.nowMs] reference instant (greeting, sunrise/sunset)
 * @returns {{ subject: string, preheader: string, html: string, text: string,
 *   stats: { sections: number, items: number, fullArticles: number, images: number, htmlBytes: number, shortened: boolean } }}
 */
export function renderEmail({ brief, weather = null, nowMs = Date.now() }) {
  if (!isPlainObject(brief) || !isCalendarDate(brief.date)) {
    throw new TypeError('Das Briefing braucht ein gültiges "date" (YYYY-MM-DD).');
  }
  const w = { home: weather?.home ?? null, day: weather?.day ?? null };
  const itemCount = countItems(brief);
  const subject = buildSubject({ date: brief.date, itemCount, weather: w });
  const teaser = clip(cleanText(brief.teaser), MAX_TEASER);
  const all = newsSections(brief);
  const base = {
    subject,
    preheader: teaser !== '' ? teaser : 'Wetter und Nachrichten für deinen Morgen.',
    dateLong: germanDate(brief.date).long,
    headline: weatherHeadline(w, nowMs),
    weather: w,
    nowMs,
    clothing: clothingHint(w),
    teaser,
  };

  const mostTop = Math.max(0, ...all.map((s) => s.top.length));
  let view;
  let html;
  for (const maxTop of [Infinity, 2, 1, 0]) {
    if (maxTop !== Infinity && maxTop >= mostTop) continue;
    view = { ...base, sections: maxTop === Infinity ? all : limitTop(all, maxTop) };
    html = renderHtml(view);
    if (Buffer.byteLength(html, 'utf8') <= HTML_BUDGET_BYTES) break;
  }
  const fullArticles = view.sections.reduce((n, s) => n + s.top.length, 0);
  return {
    subject,
    preheader: base.preheader,
    html,
    text: renderText(view),
    stats: {
      sections: view.sections.length,
      items: itemCount,
      fullArticles,
      images: view.sections.reduce((n, s) => n + s.top.filter((i) => i.image !== null).length, 0),
      htmlBytes: Buffer.byteLength(html, 'utf8'),
      shortened: fullArticles < all.reduce((n, s) => n + s.top.length, 0),
    },
  };
}

// ---------------------------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------------------------

const USAGE = `Erstellt die Morgenbriefing-E-Mail (HTML, Text, Betreff) aus einem Briefing und Live-Wetter.

Aufruf:
  node routine/render-email.mjs --brief <latest.json> --out-dir <ordner> [Optionen]

Optionen:
  --brief <datei>          Briefing (Schema 1 oder 2), z. B. latest.json vom Branch briefs.
  --out-dir <ordner>       Zielordner für email.html, email.txt und subject.txt.
  --weather-file <datei>   Wetter aus einer Datei { "home": <Open-Meteo>, "day": <Open-Meteo> }
                           statt live von Open-Meteo (für Tests).
  --no-weather             Ohne Wetterblock.
  --now <zeitpunkt>        Bezugszeitpunkt (ISO-8601 mit Zeitzone), Standard: jetzt.
  --expect-date <datum>    Abbrechen (Exit-Code 3), wenn das Briefing nicht von diesem Tag ist.
  -h, --help               Diese Hilfe.

Ist das Wetter nicht erreichbar, entsteht die E-Mail ohne Wetterblock (Hinweis auf stderr).
Exit-Code: 0 = geschrieben, 1 = Briefing/Wetterdatei unbrauchbar oder nicht schreibbar,
2 = falscher Aufruf, 3 = Briefing ist nicht vom erwarteten Tag.`;

function parseCliArgs(argv) {
  const args = { weather: 'live' };
  const value = (i, arg) => (arg.includes('=') ? arg.slice(arg.indexOf('=') + 1) : argv[i + 1]);
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    const name = arg.includes('=') ? arg.slice(0, arg.indexOf('=')) : arg;
    const takes = (key) => {
      const raw = value(i, arg);
      if (!arg.includes('=')) i++;
      if (raw === undefined || raw === '' || (!arg.includes('=') && raw.startsWith('--'))) {
        return { problem: `${name} braucht einen Wert.` };
      }
      args[key] = raw;
      return null;
    };
    let problem = null;
    switch (name) {
      case '-h':
      case '--help':
        return { help: true };
      case '--brief': problem = takes('brief'); break;
      case '--out-dir': problem = takes('outDir'); break;
      case '--weather-file': problem = takes('weatherFile'); break;
      case '--now': problem = takes('now'); break;
      case '--expect-date': problem = takes('expectDate'); break;
      case '--no-weather': args.weather = 'none'; break;
      default:
        return { problem: arg.startsWith('-') ? `Unbekannte Option: ${arg}` : `Unerwartetes Argument: ${arg}` };
    }
    if (problem) return problem;
  }
  if (!args.brief) return { problem: '--brief fehlt.' };
  if (!args.outDir) return { problem: '--out-dir fehlt.' };
  if (args.weatherFile !== undefined && args.weather === 'none') return { problem: '--weather-file und --no-weather schließen sich aus.' };
  if (args.weatherFile !== undefined) args.weather = 'file';
  if (args.now !== undefined) {
    const parsed = parseOffsetDateTime(args.now);
    if (parsed === null) return { problem: `--now braucht einen ISO-8601-Zeitpunkt mit Zeitzone (gefunden: ${args.now})` };
    args.nowMs = parsed.epochMs;
  }
  if (args.expectDate !== undefined && !isCalendarDate(args.expectDate)) {
    return { problem: `--expect-date braucht ein Datum YYYY-MM-DD (gefunden: ${args.expectDate})` };
  }
  return args;
}

function kb(bytes) {
  return `${Math.max(1, Math.round(bytes / 1024))} KB`;
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
  const nowMs = args.nowMs ?? Date.now();

  let brief;
  try {
    brief = readJsonFile(args.brief).doc;
  } catch (error) {
    if (!(error instanceof JsonFileError)) throw error;
    err(`FEHLER: Briefing ${args.brief}: ${error.message}`);
    return 1;
  }
  if (!isPlainObject(brief) || !isCalendarDate(brief.date) || !Array.isArray(brief.sections)) {
    err(`FEHLER: ${args.brief} ist kein Briefing (es fehlen "date" oder "sections").`);
    return 1;
  }
  if (args.expectDate !== undefined && brief.date !== args.expectDate) {
    err(`Briefing ist vom ${brief.date}, nicht vom ${args.expectDate} – keine E-Mail erstellt.`);
    return 3;
  }

  let weather = null;
  let weatherNote = 'ohne Wetter (--no-weather)';
  if (args.weather === 'file') {
    let raw;
    try {
      raw = readJsonFile(args.weatherFile).doc;
    } catch (error) {
      if (!(error instanceof JsonFileError)) throw error;
      err(`FEHLER: Wetterdatei ${args.weatherFile}: ${error.message}`);
      return 1;
    }
    weather = buildWeather(raw, nowMs);
    weatherNote = `Wetter aus ${path.basename(args.weatherFile)}`;
  } else if (args.weather === 'live') {
    try {
      const raw = await fetchWeather({ fetchImpl, ...(retryDelayMs !== undefined ? { retryDelayMs } : {}) });
      for (const problem of raw.problems) err(`Hinweis: ${problem} – dieser Teil des Wetters fehlt.`);
      weather = buildWeather(raw, nowMs);
      weatherNote = 'Wetter live von Open-Meteo';
    } catch (error) {
      if (!(error instanceof WeatherError)) throw error;
      err(`Hinweis: Wetter nicht verfügbar – ${error.message}. Die E-Mail kommt ohne Wetterblock.`);
      weatherNote = 'ohne Wetter (nicht erreichbar)';
    }
  }
  if (weather !== null) {
    for (const problem of weather.problems) err(`Hinweis: ${problem} – dieser Teil des Wetters fehlt.`);
    if (weather.home === null && weather.day === null) weatherNote = 'ohne Wetter (Daten unbrauchbar)';
  }

  const email = renderEmail({ brief, weather, nowMs });
  const files = { 'email.html': email.html, 'email.txt': email.text, 'subject.txt': `${email.subject}\n` };
  try {
    mkdirSync(args.outDir, { recursive: true });
    for (const [name, content] of Object.entries(files)) writeFileSync(path.join(args.outDir, name), content, 'utf8');
  } catch (error) {
    err(`FEHLER: ${args.outDir} nicht beschreibbar: ${error.message}`);
    return 1;
  }

  const s = email.stats;
  out(`OK      ${args.outDir} – E-Mail zum Briefing ${brief.date}`);
  out(`        Betreff: ${email.subject}`);
  out(
    `        Inhalt:  ${plural(s.items, 'Meldung', 'Meldungen')} in ${plural(s.sections, 'Rubrik', 'Rubriken')}, ` +
      `${s.fullArticles} ausführlich, ${plural(s.images, 'Bild', 'Bilder')} · ${weatherNote}`,
  );
  out(`        Dateien: email.html (${kb(s.htmlBytes)}), email.txt (${kb(Buffer.byteLength(email.text, 'utf8'))}), subject.txt`);
  if (s.shortened) out('        Hinweis: gekürzt, damit Gmail die E-Mail nicht abschneidet – weitere Artikel stehen als Überschriften drin.');
  return 0;
}

if (isMainModule(import.meta.url)) {
  process.exitCode = await main();
}
