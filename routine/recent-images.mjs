#!/usr/bin/env node
/**
 * The pictures of the recent briefs, so the routine does not show the same picture day after day
 * (routine/PROMPT.md, 5. BILD). Zero dependencies, Node >= 18, ESM.
 *
 *   node routine/recent-images.mjs [--date <JJJJ-MM-TT>] [--days 14] [--ref origin/briefs | --from <ordner>]
 *
 * Collects every article picture of the briefs of the `days` days before `date`. The brief's own
 * day does not count: a second run on the same day replaces that brief. Pictures are compared by
 * their file key (validate-brief.mjs `imageFileKey`) – the Commons file name, so the 960 px and the
 * 1280 px thumbnail of one file are the same picture.
 *
 * Where the briefs come from:
 * - git (default): `<ref>:briefs/<date>.json` in the repository of the current directory, ref
 *   `origin/briefs` – the cloud routine works in a clone of `main` and fetches `briefs` in step 1.
 *   If a `<remote>/<branch>` ref is missing, it is fetched first.
 * - a directory (`from`): `<dir>/briefs/<date>.json` (a checkout of the `briefs` branch) or
 *   `<dir>/<date>.json` – for tests and local trials.
 * Days without a brief are skipped. If the history cannot be read at all, the result is empty
 * with a warning: a missing history must never stop the morning run.
 *
 * Used by find-image.mjs (`--recent-days`) and validate-brief.mjs (`--no-repeat-days`); the CLI
 * lists the recent pictures (maintenance).
 *
 * Exit codes: 0 = listed, 2 = usage error.
 */
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import path from 'node:path';

import {
  MAX_RECENT_DAYS,
  berlinDate,
  commonsFileKey,
  imageFileKey,
  isCalendarDate,
  isMainModule,
  parseDays,
} from './validate-brief.mjs';

/** The data branch as the cloud routine sees it after step 1 of routine/PROMPT.md. */
export const DEFAULT_RECENT_REF = 'origin/briefs';
/** How many days the routine keeps a picture out of the brief. */
export const DEFAULT_RECENT_DAYS = 14;

const GIT_TIMEOUT_MS = 30_000;
const GIT_MAX_OUTPUT = 256 * 1024 * 1024;
// Plain ref names only – nothing git could read as an option or a revision expression.
const REF_RE = /^[A-Za-z0-9_][A-Za-z0-9._/-]*$/;

/** The `days` calendar days before `date` (`YYYY-MM-DD`), newest first. */
export function previousDates(date, days) {
  if (!isCalendarDate(date)) throw new RangeError(`Kein gültiges Datum: ${date}`);
  const day = new Date(`${date}T00:00:00Z`);
  const dates = [];
  for (let i = 0; i < days; i++) {
    day.setUTCDate(day.getUTCDate() - 1);
    dates.push(day.toISOString().slice(0, 10));
  }
  return dates;
}

function isPlainObject(value) {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * The article pictures of a brief (or a single section): per picture its file keys (of `url`
 * and `sourceUrl`, usually one), the file name for messages, the URL, section id and article id.
 *
 * @returns {{ keys: string[], file: string, url: string, section: string, item: string }[]}
 */
export function briefImages(doc) {
  const pictures = [];
  const sections = isPlainObject(doc) && Array.isArray(doc.sections) ? doc.sections : [doc];
  for (const section of sections) {
    if (!isPlainObject(section) || !Array.isArray(section.items)) continue;
    for (const item of section.items) {
      if (!isPlainObject(item) || !isPlainObject(item.image)) continue;
      const { url, sourceUrl } = item.image;
      const keys = [...new Set([imageFileKey(url), imageFileKey(sourceUrl)].filter((k) => k !== null))];
      if (keys.length === 0) continue;
      pictures.push({
        keys,
        file: commonsFileKey(url) ?? commonsFileKey(sourceUrl) ?? String(url ?? sourceUrl),
        url: typeof url === 'string' ? url : '',
        section: typeof section.id === 'string' ? section.id : '',
        item: typeof item.id === 'string' ? item.id : '',
      });
    }
  }
  return pictures;
}

/** Brief texts per date from a directory: `<dir>/briefs/<date>.json`, else `<dir>/<date>.json`. */
function readFromDirectory(dir, dates) {
  const texts = new Map();
  const warnings = [];
  for (const date of dates) {
    for (const file of [path.join(dir, 'briefs', `${date}.json`), path.join(dir, `${date}.json`)]) {
      try {
        texts.set(date, readFileSync(file, 'utf8'));
        break;
      } catch (error) {
        if (error.code === 'ENOENT' || error.code === 'ENOTDIR') continue;
        warnings.push(`${file} nicht lesbar (${error.message})`);
        break;
      }
    }
  }
  return { texts, warnings };
}

function git(args, { cwd, input } = {}) {
  return spawnSync('git', args, { cwd, input, timeout: GIT_TIMEOUT_MS, maxBuffer: GIT_MAX_OUTPUT, windowsHide: true });
}

/** First line of git's complaint, or the reason git could not run at all. */
function gitProblem(result) {
  if (result.error) return result.error.code === 'ENOENT' ? 'git nicht gefunden' : result.error.message;
  const line = String(result.stderr ?? '').trim().split('\n')[0];
  return line === '' ? `git endete mit Code ${result.status}` : line;
}

const refExists = (ref, cwd) => git(['rev-parse', '--verify', '--quiet', `${ref}^{commit}`], { cwd }).status === 0;

/**
 * Splits the output of `git cat-file --batch` into one text per requested object (`null` for a
 * missing one). Sizes are in bytes, so the output is cut as a Buffer before decoding.
 */
export function parseCatFileBatch(output, count) {
  const texts = [];
  let pos = 0;
  for (let i = 0; i < count; i++) {
    const end = output.indexOf(0x0a, pos);
    if (end === -1) break;
    const header = /^[0-9a-f]+ (\S+) (\d+)$/.exec(output.toString('utf8', pos, end));
    pos = end + 1;
    if (header === null) {
      texts.push(null); // "<name> missing" (or "ambiguous"): no content follows
      continue;
    }
    const size = Number(header[2]);
    texts.push(header[1] === 'blob' ? output.toString('utf8', pos, pos + size) : null);
    pos += size + 1;
  }
  return texts;
}

/** Brief texts per date from `<ref>:briefs/<date>.json`; fetches a missing `<remote>/<branch>` ref. */
function readFromGit(ref, dates, cwd) {
  const texts = new Map();
  if (!REF_RE.test(ref)) return { texts, warnings: [`"${ref}" ist kein gültiger Git-Stand – keine Bilder ausgeschlossen`] };
  if (!refExists(ref, cwd)) {
    const remote = /^([^/]+)\/(.+)$/.exec(ref);
    const fetched = remote
      ? git(['fetch', '-q', remote[1], `+refs/heads/${remote[2]}:refs/remotes/${remote[1]}/${remote[2]}`], { cwd })
      : null;
    if (fetched === null || fetched.status !== 0 || !refExists(ref, cwd)) {
      const why = fetched === null ? 'gibt es nicht' : `ließ sich nicht holen: ${gitProblem(fetched)}`;
      return { texts, warnings: [`${ref} ${why} – keine Bilder ausgeschlossen`] };
    }
  }
  const batch = git(['cat-file', '--batch'], { cwd, input: dates.map((d) => `${ref}:briefs/${d}.json\n`).join('') });
  if (batch.status !== 0) {
    return { texts, warnings: [`${ref} nicht lesbar: ${gitProblem(batch)} – keine Bilder ausgeschlossen`] };
  }
  parseCatFileBatch(batch.stdout, dates.length).forEach((text, i) => {
    if (text !== null) texts.set(dates[i], text);
  });
  return { texts, warnings: [] };
}

/**
 * Collects the pictures of the briefs of the `days` days before `date`.
 *
 * @param {object} params
 * @param {string} params.date the date of the brief being made (`YYYY-MM-DD`); not included
 * @param {number} params.days how many days before `date` count
 * @param {string} [params.from] directory with the briefs (instead of git)
 * @param {string} [params.ref] git ref of the data branch, default `origin/briefs`
 * @param {string} [params.cwd] repository for git, default the current directory
 * @returns {{ images: Map<string, { date: string, section: string, file: string, url: string, days: number }>,
 *   dates: string[], source: string, warnings: string[] }} `images`: file key → its newest use
 *   and on how many of the days it ran; `dates`: the days with a brief, newest first
 */
export function collectRecentImages({ date, days, from, ref, cwd = process.cwd() }) {
  if (!Number.isInteger(days) || days < 1 || days > MAX_RECENT_DAYS) {
    throw new RangeError(`days muss eine ganze Zahl von 1 bis ${MAX_RECENT_DAYS} sein (gefunden: ${days})`);
  }
  const wanted = previousDates(date, days);
  const source = from ?? ref ?? DEFAULT_RECENT_REF;
  const read = from !== undefined ? readFromDirectory(from, wanted) : readFromGit(source, wanted, cwd);
  const warnings = [...read.warnings];
  const images = new Map();
  const dates = [];
  for (const day of wanted) {
    const text = read.texts.get(day);
    if (text === undefined) continue;
    let doc;
    try {
      doc = JSON.parse(text.replace(/^﻿/, ''));
    } catch {
      warnings.push(`Briefing ${day} (${source}) ist kein gültiges JSON – übersprungen`);
      continue;
    }
    dates.push(day);
    const today = new Set();
    for (const picture of briefImages(doc)) {
      for (const key of picture.keys) {
        if (today.has(key)) continue;
        today.add(key);
        const use = images.get(key);
        if (use) use.days++;
        else images.set(key, { date: day, section: picture.section, file: picture.file, url: picture.url, days: 1 });
      }
    }
  }
  if (dates.length === 0 && warnings.length === 0) {
    warnings.push(`Keine Briefings der ${days} Tage vor ${date} gefunden (${source}) – keine Bilder ausgeschlossen`);
  }
  return { images, dates, source, warnings };
}

// ---------------------------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------------------------

const USAGE = `Zeigt die Bilder der Briefings der letzten Tage (dieselbe Datei in jeder Größe zählt einmal).

Aufruf:
  node routine/recent-images.mjs [--date <JJJJ-MM-TT>] [--days <n>] [--ref <ref> | --from <ordner>]

Optionen:
  --date <datum>   Datum des Briefings, für das gezählt wird (Standard: heute, Europe/Berlin).
                   Dieser Tag selbst zählt nicht.
  --days <n>       So viele Tage davor (1–${MAX_RECENT_DAYS}, Standard ${DEFAULT_RECENT_DAYS}).
  --ref <ref>      Briefings aus diesem Git-Stand lesen (Standard: ${DEFAULT_RECENT_REF}, wird bei
                   Bedarf geholt).
  --from <ordner>  Briefings stattdessen aus <ordner>/briefs/<datum>.json bzw. <ordner>/<datum>.json.
  -h, --help       Diese Hilfe.

find-image.mjs --recent-days <n> blendet diese Bilder aus, validate-brief.mjs --no-repeat-days <n>
meldet sie als Fehler.

Exit-Code: 0 = angezeigt, 2 = falscher Aufruf.`;

function parseCliArgs(argv) {
  const args = { date: berlinDate(), days: DEFAULT_RECENT_DAYS };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '-h' || arg === '--help') return { help: true };
    const name = arg.split('=')[0];
    if (!['--date', '--days', '--ref', '--from'].includes(name)) return { problem: `Unbekannte Option: ${arg}` };
    const value = arg.includes('=') ? arg.slice(arg.indexOf('=') + 1) : argv[++i];
    if (value === undefined || value.trim() === '' || (value.startsWith('--') && !arg.includes('='))) {
      return { problem: `${name} braucht einen Wert.` };
    }
    if (name === '--date') {
      if (!isCalendarDate(value)) return { problem: `--date braucht ein Datum JJJJ-MM-TT (gefunden: ${value})` };
      args.date = value;
    } else if (name === '--days') {
      args.days = parseDays(value);
      if (args.days === null) return { problem: `--days braucht eine ganze Zahl von 1 bis ${MAX_RECENT_DAYS} (gefunden: ${value})` };
    } else if (name === '--ref') {
      args.ref = value;
    } else {
      args.from = value;
    }
  }
  if (args.ref !== undefined && args.from !== undefined) return { problem: '--ref und --from schließen sich gegenseitig aus.' };
  return args;
}

/**
 * Runs the CLI and returns the exit code (does not call `process.exit`).
 *
 * @param {string[]} argv arguments without `node` and the script path
 * @param {{ out?: (line: string) => void, err?: (line: string) => void, cwd?: string }} [io]
 */
export function main(argv = process.argv.slice(2), { out = console.log, err = console.error, cwd } = {}) {
  const args = parseCliArgs(argv);
  if (args.help) {
    out(USAGE);
    return 0;
  }
  if (args.problem) {
    err(`${args.problem}\n\n${USAGE}`);
    return 2;
  }
  const result = collectRecentImages({ date: args.date, days: args.days, from: args.from, ref: args.ref, cwd });
  const pictures = [...new Map([...result.images.values()].map((use) => [use.url || use.file, use])).values()];
  out(
    `${args.days} Tage vor ${args.date}: ${result.dates.length} ${result.dates.length === 1 ? 'Briefing' : 'Briefings'} ` +
      `(${result.source}), ${pictures.length} ${pictures.length === 1 ? 'Bild' : 'Bilder'}`,
  );
  pictures
    .sort((a, b) => b.days - a.days || (a.date < b.date ? 1 : a.date > b.date ? -1 : 0) || a.file.localeCompare(b.file))
    .forEach((use) => out(`  ${use.date}  ${use.section.padEnd(13)}  ${use.days}×  ${use.file}`));
  for (const w of result.warnings) err(`Hinweis: ${w}`);
  return 0;
}

if (isMainModule(import.meta.url)) {
  process.exitCode = main();
}
