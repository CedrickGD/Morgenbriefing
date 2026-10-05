#!/usr/bin/env node
/**
 * Publishes a daily brief into a checkout of the `briefs` data branch
 * (contract: docs/brief-format.md). Zero dependencies, Node >= 18, ESM.
 *
 *   node routine/publish-brief.mjs --brief <pfad> --data-dir <checkout> [--max-age-hours <h>] [--dry-run]
 *
 * 1. validates the brief (validate-brief.mjs) and refuses to publish an invalid one,
 * 2. writes `briefs/<date>.json` (2-space JSON + trailing newline),
 * 3. replaces `latest.json` only if the brief's date is >= the date currently in it,
 * 4. upserts `{ date, teaser, itemCount }` into `index.json` (newest first, max. 400 days) and
 *    sets `updatedAt` to the brief's `generatedAt` – unless the index already carries a later
 *    one (backfilling an older day must not move `updatedAt` backwards). A missing or broken
 *    `index.json` is rebuilt from the files in `briefs/`.
 *
 * Idempotent: files whose content would not change are not touched, so running it twice
 * leaves a clean `git status`. Git itself (commit/push) is the caller's job.
 *
 * Exit codes: 0 = published (or nothing to do), 1 = invalid brief / I/O problem, 2 = usage error.
 */
import {
  mkdirSync,
  readFileSync,
  readdirSync,
  renameSync,
  statSync,
  unlinkSync,
  writeFileSync,
} from 'node:fs';
import path from 'node:path';
import {
  LIMITS,
  countItems,
  describe,
  isCalendarDate,
  isMainModule,
  parseHours,
  parseOffsetDateTime,
  readJsonFile,
  validateBrief,
  validateIndex,
} from './validate-brief.mjs';

/** Raised for anything that prevents publishing; `details` holds individual problems. */
export class PublishError extends Error {
  constructor(message, details = []) {
    super(message);
    this.name = 'PublishError';
    this.details = details;
  }
}

/** Canonical on-disk form of every data file: 2-space JSON plus trailing newline. */
export function toJsonText(value) {
  return `${JSON.stringify(value, null, 2)}\n`;
}

const BRIEF_FILE_RE = /^(\d{4}-\d{2}-\d{2})\.json$/;

/** Reads a file's raw text (BOM and all); `null` if it does not exist. */
function readTextOrNull(file) {
  try {
    return readFileSync(file, 'utf8');
  } catch (error) {
    if (error.code === 'ENOENT') return null;
    throw new PublishError(`${file} nicht lesbar: ${error.message}`);
  }
}

/** Reads and parses JSON; distinguishes missing from broken files. */
function readJsonState(file) {
  try {
    const { doc } = readJsonFile(file);
    return { exists: true, doc, problem: null };
  } catch (error) {
    if (error.code === 'ENOENT') return { exists: false, doc: undefined, problem: null };
    if (error.code === 'EJSON') return { exists: true, doc: undefined, problem: error.message };
    throw new PublishError(`${file}: ${error.message}`);
  }
}

/** Writes via temp file + rename so a crash never leaves a half-written JSON file behind. */
function writeAtomically(file, text) {
  mkdirSync(path.dirname(file), { recursive: true });
  const tmp = `${file}.tmp-${process.pid}`;
  try {
    writeFileSync(tmp, text, 'utf8');
    renameSync(tmp, file);
  } catch (error) {
    try {
      unlinkSync(tmp);
    } catch {
      // temp file was never created or is already gone
    }
    throw new PublishError(`${file} konnte nicht geschrieben werden: ${error.message}`);
  }
}

/** Writes `text` unless the file already has exactly this content. Returns the change status. */
function writeIfChanged(file, text, dryRun) {
  const before = readTextOrNull(file);
  if (before === text) return 'unverändert';
  if (!dryRun) writeAtomically(file, text);
  return before === null ? 'neu' : 'aktualisiert';
}

/** Index entry for a brief. */
export function indexEntryFor(brief) {
  return { date: brief.date, teaser: brief.teaser, itemCount: countItems(brief) };
}

/**
 * Rebuilds index entries from the brief files in `<dataDir>/briefs` (used when `index.json` is
 * missing or unusable). Unreadable or inconsistent files are skipped with a note.
 */
function entriesFromDisk(briefsDir, notes) {
  let names;
  try {
    names = readdirSync(briefsDir);
  } catch (error) {
    if (error.code === 'ENOENT') return [];
    throw new PublishError(`${briefsDir} nicht lesbar: ${error.message}`);
  }
  const entries = [];
  for (const name of names) {
    const m = BRIEF_FILE_RE.exec(name);
    if (m === null) continue;
    const state = readJsonState(path.join(briefsDir, name));
    const doc = state.doc;
    if (state.problem !== null || typeof doc !== 'object' || doc === null || doc.date !== m[1]) {
      notes.push(`briefs/${name} übersprungen (unlesbar oder Datum passt nicht zum Dateinamen)`);
      continue;
    }
    entries.push({
      date: doc.date,
      teaser: typeof doc.teaser === 'string' ? doc.teaser : '',
      itemCount: countItems(doc),
    });
  }
  return entries;
}

/** The later of two ISO timestamps (by instant); invalid values lose. */
function laterTimestamp(a, b) {
  const pa = parseOffsetDateTime(a);
  const pb = parseOffsetDateTime(b);
  if (pa === null) return b;
  if (pb === null) return a;
  return pb.epochMs >= pa.epochMs ? b : a;
}

/**
 * Publishes `brief` into `dataDir` (a checkout of the `briefs` branch).
 *
 * @param {object} params
 * @param {unknown} params.brief parsed brief JSON
 * @param {string} params.dataDir existing directory
 * @param {number} [params.maxAgeHours] freshness policy passed to the validator
 * @param {boolean} [params.dryRun] compute the changes without writing anything
 * @returns {{ date: string, itemCount: number, warnings: string[], notes: string[],
 *   changes: { file: string, status: 'neu'|'aktualisiert'|'unverändert'|'behalten', note?: string }[] }}
 * @throws {PublishError} if the brief is invalid or a file cannot be read/written
 */
export function publishBrief({ brief, dataDir, maxAgeHours, dryRun = false }) {
  const { errors, warnings } = validateBrief(brief, { maxAgeHours });
  if (errors.length > 0) {
    throw new PublishError('Das Briefing ist ungültig – nichts veröffentlicht.', errors);
  }
  let stat;
  try {
    stat = statSync(dataDir);
  } catch {
    throw new PublishError(`Datenverzeichnis ${dataDir} existiert nicht (Checkout des Branches "briefs" anlegen).`);
  }
  if (!stat.isDirectory()) throw new PublishError(`${dataDir} ist kein Verzeichnis.`);

  const notes = [];
  const changes = [];
  const briefText = toJsonText(brief);
  const briefsDir = path.join(dataDir, 'briefs');

  // 1. briefs/<date>.json
  changes.push({
    file: `briefs/${brief.date}.json`,
    status: writeIfChanged(path.join(briefsDir, `${brief.date}.json`), briefText, dryRun),
  });

  // 2. latest.json — never replace a newer day with an older one (e.g. when backfilling).
  const latestFile = path.join(dataDir, 'latest.json');
  const latest = readJsonState(latestFile);
  const latestDate = latest.doc && typeof latest.doc === 'object' ? latest.doc.date : undefined;
  if (latest.exists && latest.problem === null && isCalendarDate(latestDate) && brief.date < latestDate) {
    changes.push({ file: 'latest.json', status: 'behalten', note: `enthält schon den neueren ${latestDate}` });
  } else {
    const status = writeIfChanged(latestFile, briefText, dryRun);
    const note = latest.exists && (latest.problem !== null || !isCalendarDate(latestDate))
      ? 'war unlesbar und wurde ersetzt'
      : undefined;
    changes.push({ file: 'latest.json', status, ...(note ? { note } : {}) });
  }

  // 3. index.json — upsert today's entry; rebuild from the brief files if it is missing/broken.
  const indexFile = path.join(dataDir, 'index.json');
  const current = readJsonState(indexFile);
  let days;
  let updatedAt = brief.generatedAt;
  if (current.exists && current.problem === null && validateIndex(current.doc).errors.length === 0) {
    days = current.doc.days;
    updatedAt = laterTimestamp(current.doc.updatedAt, brief.generatedAt);
  } else {
    if (current.exists) {
      notes.push('index.json war ungültig und wurde aus den Dateien unter briefs/ neu aufgebaut');
    }
    days = entriesFromDisk(briefsDir, notes);
  }
  const merged = [indexEntryFor(brief), ...days.filter((d) => d.date !== brief.date)]
    .sort((a, b) => (a.date < b.date ? 1 : a.date > b.date ? -1 : 0))
    .slice(0, LIMITS.indexMaxDays);
  const index = { updatedAt, days: merged };
  const indexCheck = validateIndex(index);
  if (indexCheck.errors.length > 0) {
    throw new PublishError('Interner Fehler: der neue Index wäre ungültig.', indexCheck.errors);
  }
  changes.push({
    file: 'index.json',
    status: writeIfChanged(indexFile, toJsonText(index), dryRun),
    note: `${merged.length} ${merged.length === 1 ? 'Tag' : 'Tage'}`,
  });

  return { date: brief.date, itemCount: countItems(brief), warnings, notes, changes };
}

// ---------------------------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------------------------

const USAGE = `Veröffentlicht ein Briefing im Checkout des Daten-Branches "briefs".

Aufruf:
  node routine/publish-brief.mjs --brief <pfad> --data-dir <checkout> [Optionen]

Optionen:
  --brief <pfad>          Das Briefing (JSON, Format: docs/brief-format.md).
  --data-dir <pfad>       Checkout des Branches "briefs" (muss existieren).
  --max-age-hours <h>     Zusätzlich die Frische-Regel prüfen (wie validate-brief.mjs).
  --dry-run               Nur anzeigen, was sich ändern würde.
  -h, --help              Diese Hilfe.

Schreibt briefs/<datum>.json, latest.json (nur wenn nicht schon ein neuerer Tag drinsteht)
und index.json. Mehrfaches Ausführen ändert nichts weiter (idempotent).`;

function parseCliArgs(argv) {
  const args = { dryRun: false };
  const valueOf = (i, name) => {
    const raw = argv[i];
    if (raw.includes('=')) return { value: raw.slice(raw.indexOf('=') + 1), next: i };
    if (i + 1 >= argv.length || argv[i + 1].startsWith('--')) return { problem: `${name} braucht einen Wert` };
    return { value: argv[i + 1], next: i + 1 };
  };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    const name = arg.split('=')[0];
    if (arg === '-h' || arg === '--help') return { help: true };
    if (arg === '--dry-run') {
      args.dryRun = true;
    } else if (name === '--brief' || name === '--data-dir' || name === '--max-age-hours') {
      const r = valueOf(i, name);
      if (r.problem) return { problem: r.problem };
      i = r.next;
      if (name === '--brief') args.brief = r.value;
      if (name === '--data-dir') args.dataDir = r.value;
      if (name === '--max-age-hours') {
        const hours = parseHours(r.value);
        if (hours === null) return { problem: `--max-age-hours braucht eine positive Zahl (gefunden: ${r.value})` };
        args.maxAgeHours = hours;
      }
    } else {
      return { problem: `Unbekanntes Argument: ${arg}` };
    }
  }
  if (!args.brief) return { problem: '--brief fehlt.' };
  if (!args.dataDir) return { problem: '--data-dir fehlt.' };
  return args;
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

  try {
    let brief;
    try {
      brief = readJsonFile(args.brief).doc;
    } catch (error) {
      throw new PublishError(`${args.brief}: ${error.message}`);
    }
    const result = publishBrief({
      brief,
      dataDir: args.dataDir,
      maxAgeHours: args.maxAgeHours,
      dryRun: args.dryRun,
    });

    out(`${describe('brief', brief)}`);
    out(`Ziel: ${path.resolve(args.dataDir)}${args.dryRun ? '  (Probelauf – nichts geschrieben)' : ''}`);
    const width = Math.max(...result.changes.map((c) => c.status.length));
    for (const change of result.changes) {
      out(`  ${change.status.padEnd(width)}  ${change.file}${change.note ? ` · ${change.note}` : ''}`);
    }
    for (const note of result.notes) out(`  Hinweis: ${note}`);
    for (const warning of result.warnings) out(`  Hinweis: ${warning}`);

    const written = result.changes.filter((c) => c.status === 'neu' || c.status === 'aktualisiert').length;
    if (written === 0) {
      out('Nichts zu tun – alles ist schon auf dem neuesten Stand.');
    } else {
      out(`${written} ${written === 1 ? 'Datei' : 'Dateien'} ${args.dryRun ? 'würden geschrieben' : 'geschrieben'}.`);
    }
    return 0;
  } catch (error) {
    if (!(error instanceof PublishError)) throw error;
    err(`FEHLER: ${error.message}`);
    for (const detail of error.details) err(`  Fehler: ${detail}`);
    return 1;
  }
}

if (isMainModule(import.meta.url)) {
  process.exitCode = main();
}
