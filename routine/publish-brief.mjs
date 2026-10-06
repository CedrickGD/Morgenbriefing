#!/usr/bin/env node
/**
 * Publishes a daily brief into a checkout of the `briefs` data branch
 * (contract: docs/brief-format.md). Zero dependencies, Node >= 18, ESM.
 *
 *   node routine/publish-brief.mjs --brief <pfad> --data-dir <checkout> [--max-age-hours <h>] [--merge]
 *        [--branch <name>] [--dry-run]
 *   node routine/publish-brief.mjs --print-branch [--branch <name>]
 *
 * 1. validates the brief (validate-brief.mjs) and refuses to publish an invalid one,
 * 2. writes `briefs/<date>.json` (2-space JSON + trailing newline) – with `--merge` the brief's
 *    sections are upserted by id into an existing day file instead of replacing it (for setups
 *    where several routines each contribute some topics to the same day),
 * 3. replaces `latest.json` only if the brief's date is >= the date currently in it,
 * 4. upserts `{ date, teaser, itemCount }` into `index.json` (newest first, max. 400 days;
 *    `itemCount` counts every article, "top" and "more") and sets `updatedAt` to the brief's
 *    `generatedAt` – unless the index already carries a later one (backfilling an older day must
 *    not move `updatedAt` backwards). A missing or broken `index.json` is rebuilt from the files
 *    in `briefs/`.
 *
 * Idempotent: files whose content would not change are not touched, so running it twice
 * leaves a clean `git status`. Git itself (commit/push) is the caller's job.
 *
 * Target branch: `briefs` – the branch the app and the e-mail routine read. For a manual test
 * run the routine publishes to a test branch instead, chosen with `--branch` or the environment
 * variable `MB_BRIEFS_BRANCH` (the flag wins). Only `briefs` and `briefs-<name>` are accepted, so
 * a typo can never put data into `main`. If the data dir is the root of a git checkout, that
 * checkout must be on exactly the target branch – otherwise nothing is written. `--print-branch`
 * only prints the resolved name (the routine uses it for its git commands).
 *
 * Exit codes: 0 = published (or nothing to do), 1 = invalid brief / I/O problem, 2 = usage error.
 */
import { spawnSync } from 'node:child_process';
import {
  existsSync,
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
  topicRank,
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

/** The data branch the app and the e-mail routine read. */
export const DEFAULT_BRANCH = 'briefs';

/** Environment variable that selects another target branch (manual test run). */
export const BRANCH_ENV = 'MB_BRIEFS_BRANCH';

const BRANCH_RE = /^briefs(?:-[a-z0-9]+)*$/;

/** `null` if `name` is an allowed data branch (`briefs`, `briefs-<name>`), else the problem. */
export function branchProblem(name) {
  if (typeof name !== 'string' || name === '') return 'der Branch-Name fehlt';
  if (!BRANCH_RE.test(name)) {
    return `"${name}" ist kein erlaubter Daten-Branch – erlaubt sind "briefs" und "briefs-<name>" (Kleinbuchstaben, Ziffern, Bindestriche), z. B. "briefs-test"`;
  }
  return null;
}

/**
 * Resolves the target branch: `branch` (from `--branch`) wins over `env.MB_BRIEFS_BRANCH`; the
 * default is `briefs`. `test` is true for every branch the app does not read.
 *
 * @param {{ branch?: string, env?: Record<string, string | undefined> }} [options]
 * @returns {{ branch: string, from: string, test: boolean }}
 * @throws {PublishError} for names other than `briefs` / `briefs-<name>`
 */
export function resolveBranch({ branch, env = process.env } = {}) {
  let name = DEFAULT_BRANCH;
  let from = 'Standard';
  if (branch !== undefined) {
    name = branch;
    from = '--branch';
  } else if (typeof env[BRANCH_ENV] === 'string' && env[BRANCH_ENV].trim() !== '') {
    name = env[BRANCH_ENV].trim();
    from = BRANCH_ENV;
  }
  const problem = branchProblem(name);
  if (problem !== null) throw new PublishError(`${from}: ${problem}.`);
  return { branch: name, from, test: name !== DEFAULT_BRANCH };
}

/**
 * The branch of the git checkout whose root is `dir`: `checkout: false` if `dir` has no `.git`
 * (a plain directory, e.g. for a local trial), `branch: null` for a detached HEAD,
 * `gitMissing: true` if git cannot be started. An unborn orphan branch counts as that branch.
 *
 * @param {string} dir
 * @returns {{ checkout: boolean, branch: string | null, gitMissing?: boolean }}
 */
export function checkoutBranch(dir) {
  if (!existsSync(path.join(dir, '.git'))) return { checkout: false, branch: null };
  const result = spawnSync('git', ['-C', dir, 'symbolic-ref', '--quiet', '--short', 'HEAD'], {
    encoding: 'utf8',
  });
  if (result.error) return { checkout: true, branch: null, gitMissing: true };
  const name = result.status === 0 ? result.stdout.trim() : '';
  return { checkout: true, branch: name === '' ? null : name };
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

/** Index entry for a brief; `itemCount` counts every article ("top" and "more"). */
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

function isPlainObject(value) {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * Upserts the sections of `incoming` into `existing` (same day): sections with the same id are
 * replaced, new ones added, the others kept. Schema-2 briefs end up in catalogue order (unknown
 * topics after the known ones, in their previous order); schema-1 briefs keep the existing order
 * with new sections appended. The teaser of `incoming` wins unless it is empty; `generatedAt` is
 * the later of both.
 *
 * @param {object} existing the current day file (structurally a brief)
 * @param {object} incoming the brief being published
 * @returns {{ brief: object, replaced: string[], added: string[], kept: string[] }}
 * @throws {PublishError} if the schemas or dates differ
 */
export function mergeBriefs(existing, incoming) {
  if (existing.schema !== incoming.schema) {
    throw new PublishError(
      `Zusammenführen nicht möglich: briefs/${incoming.date}.json hat Schema ${existing.schema}, das neue Briefing Schema ${incoming.schema}.`,
    );
  }
  if (existing.date !== incoming.date) {
    throw new PublishError(
      `Zusammenführen nicht möglich: briefs/${incoming.date}.json enthält das Datum ${existing.date}.`,
    );
  }
  const incomingById = new Map(incoming.sections.map((s) => [s.id, s]));
  const existingIds = new Set();
  const replaced = [];
  const kept = [];
  const sections = [];
  for (const section of existing.sections) {
    if (!isPlainObject(section)) continue;
    existingIds.add(section.id);
    if (incomingById.has(section.id)) {
      sections.push(incomingById.get(section.id));
      replaced.push(section.id);
    } else {
      sections.push(section);
      kept.push(section.id);
    }
  }
  const added = [];
  for (const section of incoming.sections) {
    if (!existingIds.has(section.id)) {
      sections.push(section);
      added.push(section.id);
    }
  }
  const ordered =
    incoming.schema === 2
      ? sections
          .map((s, i) => ({ s, i }))
          .sort((a, b) => topicRank(a.s.id) - topicRank(b.s.id) || a.i - b.i)
          .map((x) => x.s)
      : sections;

  const teaser =
    typeof incoming.teaser === 'string' && incoming.teaser.trim() !== '' ? incoming.teaser : existing.teaser;
  return {
    brief: {
      ...existing,
      ...incoming,
      generatedAt: laterTimestamp(existing.generatedAt, incoming.generatedAt),
      teaser: typeof teaser === 'string' ? teaser : '',
      sections: ordered,
    },
    replaced,
    added,
    kept,
  };
}

/**
 * Resolves what goes into `briefs/<date>.json` in merge mode. A missing day file means there is
 * nothing to merge with; an unreadable one is replaced (with a note).
 */
function resolveMerge(brief, dayFile, notes) {
  const state = readJsonState(dayFile);
  if (!state.exists) {
    notes.push(`--merge: briefs/${brief.date}.json gibt es noch nicht – das Briefing wird unverändert geschrieben`);
    return { brief, merge: null };
  }
  const doc = state.doc;
  if (state.problem !== null || !isPlainObject(doc) || !Array.isArray(doc.sections)) {
    notes.push(`--merge: briefs/${brief.date}.json war unlesbar und wird ersetzt`);
    return { brief, merge: null };
  }
  const merged = mergeBriefs(doc, brief);
  // Freshness was checked on the incoming brief; sections from earlier runs are only checked
  // structurally (their age relative to the new generatedAt says nothing about this run).
  const check = validateBrief(merged.brief);
  if (check.errors.length > 0) {
    throw new PublishError(
      `Das zusammengeführte Briefing für ${brief.date} wäre ungültig – nichts veröffentlicht.`,
      check.errors,
    );
  }
  return { brief: merged.brief, merge: { replaced: merged.replaced, added: merged.added, kept: merged.kept } };
}

/**
 * Publishes `brief` into `dataDir` (a checkout of the `briefs` branch).
 *
 * @param {object} params
 * @param {unknown} params.brief parsed brief JSON
 * @param {string} params.dataDir existing directory
 * @param {number} [params.maxAgeHours] freshness policy passed to the validator
 * @param {boolean} [params.merge] upsert the sections into an existing day file instead of replacing it
 * @param {string} [params.branch] target branch (`briefs` or `briefs-<name>`); if given and
 *   `dataDir` is the root of a git checkout, the checkout must be on this branch
 * @param {boolean} [params.dryRun] compute the changes without writing anything
 * @returns {{ date: string, itemCount: number, brief: object, warnings: string[], notes: string[],
 *   branch: string | null, merge: { replaced: string[], added: string[], kept: string[] } | null,
 *   changes: { file: string, status: 'neu'|'aktualisiert'|'unverändert'|'behalten', note?: string }[] }}
 *   `brief` is what was written to `briefs/<date>.json` (the merged brief in merge mode)
 * @throws {PublishError} if the brief is invalid, the checkout is on another branch or a file
 *   cannot be read/written
 */
export function publishBrief({ brief, dataDir, maxAgeHours, merge = false, branch, dryRun = false }) {
  const { errors, warnings } = validateBrief(brief, { maxAgeHours });
  if (errors.length > 0) {
    throw new PublishError('Das Briefing ist ungültig – nichts veröffentlicht.', errors);
  }
  if (branch !== undefined) {
    const problem = branchProblem(branch);
    if (problem !== null) throw new PublishError(`Ziel-Branch: ${problem}.`);
  }
  const target = branch ?? DEFAULT_BRANCH;
  let stat;
  try {
    stat = statSync(dataDir);
  } catch {
    throw new PublishError(`Datenverzeichnis ${dataDir} existiert nicht (Checkout des Branches "${target}" anlegen).`);
  }
  if (!stat.isDirectory()) throw new PublishError(`${dataDir} ist kein Verzeichnis.`);

  const notes = [];
  if (branch !== undefined) {
    const checkout = checkoutBranch(dataDir);
    if (checkout.gitMissing) {
      notes.push(`git nicht gefunden – ob ${dataDir} auf Branch "${branch}" steht, wurde nicht geprüft`);
    } else if (checkout.checkout && checkout.branch !== branch) {
      const actual =
        checkout.branch === null
          ? 'auf keinem Branch (detached HEAD oder kein gültiger Checkout)'
          : `auf Branch "${checkout.branch}"`;
      throw new PublishError(
        `${dataDir} steht ${actual}, Ziel ist "${branch}" – nichts veröffentlicht. Den Checkout für "${branch}" anlegen oder --branch anpassen.`,
      );
    }
  }
  const changes = [];
  const briefsDir = path.join(dataDir, 'briefs');
  const dayFile = path.join(briefsDir, `${brief.date}.json`);

  const resolved = merge ? resolveMerge(brief, dayFile, notes) : { brief, merge: null };
  const final = resolved.brief;
  const briefText = toJsonText(final);

  // 1. briefs/<date>.json
  changes.push({
    file: `briefs/${final.date}.json`,
    status: writeIfChanged(dayFile, briefText, dryRun),
    ...(resolved.merge ? { note: 'zusammengeführt' } : {}),
  });

  // 2. latest.json — never replace a newer day with an older one (e.g. when backfilling).
  const latestFile = path.join(dataDir, 'latest.json');
  const latest = readJsonState(latestFile);
  const latestDate = latest.doc && typeof latest.doc === 'object' ? latest.doc.date : undefined;
  if (latest.exists && latest.problem === null && isCalendarDate(latestDate) && final.date < latestDate) {
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
  let updatedAt = final.generatedAt;
  if (current.exists && current.problem === null && validateIndex(current.doc).errors.length === 0) {
    days = current.doc.days;
    updatedAt = laterTimestamp(current.doc.updatedAt, final.generatedAt);
  } else {
    if (current.exists) {
      notes.push('index.json war ungültig und wurde aus den Dateien unter briefs/ neu aufgebaut');
    }
    days = entriesFromDisk(briefsDir, notes);
  }
  const mergedDays = [indexEntryFor(final), ...days.filter((d) => d.date !== final.date)]
    .sort((a, b) => (a.date < b.date ? 1 : a.date > b.date ? -1 : 0))
    .slice(0, LIMITS.indexMaxDays);
  const index = { updatedAt, days: mergedDays };
  const indexCheck = validateIndex(index);
  if (indexCheck.errors.length > 0) {
    throw new PublishError('Interner Fehler: der neue Index wäre ungültig.', indexCheck.errors);
  }
  changes.push({
    file: 'index.json',
    status: writeIfChanged(indexFile, toJsonText(index), dryRun),
    note: `${mergedDays.length} ${mergedDays.length === 1 ? 'Tag' : 'Tage'}`,
  });

  return {
    date: final.date,
    itemCount: countItems(final),
    brief: final,
    branch: branch ?? null,
    merge: resolved.merge,
    warnings,
    notes,
    changes,
  };
}

// ---------------------------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------------------------

const USAGE = `Veröffentlicht ein Briefing im Checkout des Daten-Branches "briefs".

Aufruf:
  node routine/publish-brief.mjs --brief <pfad> --data-dir <checkout> [Optionen]
  node routine/publish-brief.mjs --print-branch [--branch <name>]

Optionen:
  --brief <pfad>          Das Briefing (JSON, Format: docs/brief-format.md).
  --data-dir <pfad>       Checkout des Ziel-Branches (muss existieren).
  --max-age-hours <h>     Zusätzlich die Frische-Regel prüfen (wie validate-brief.mjs).
  --merge                 Rubriken nach id in ein schon vorhandenes Briefing desselben Tages
                          einfügen bzw. ersetzen, statt es komplett zu überschreiben.
  --branch <name>         Ziel-Branch: "briefs" (Standard, liest die App) oder für einen
                          manuellen Testlauf "briefs-<name>", z. B. "briefs-test". Ohne die
                          Option gilt die Umgebungsvariable ${BRANCH_ENV}. Ist --data-dir ein
                          Git-Checkout, muss er auf genau diesem Branch stehen.
  --print-branch          Nur den Ziel-Branch ausgeben (aus --branch, ${BRANCH_ENV} oder
                          "briefs") – für die Git-Befehle der Routine.
  --dry-run               Nur anzeigen, was sich ändern würde.
  -h, --help              Diese Hilfe.

Schreibt briefs/<datum>.json, latest.json (nur wenn nicht schon ein neuerer Tag drinsteht)
und index.json. Mehrfaches Ausführen ändert nichts weiter (idempotent).`;

function parseCliArgs(argv) {
  const args = { dryRun: false, merge: false, printBranch: false };
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
    } else if (arg === '--merge') {
      args.merge = true;
    } else if (arg === '--print-branch') {
      args.printBranch = true;
    } else if (name === '--brief' || name === '--data-dir' || name === '--max-age-hours' || name === '--branch') {
      const r = valueOf(i, name);
      if (r.problem) return { problem: r.problem };
      i = r.next;
      if (name === '--brief') args.brief = r.value;
      if (name === '--data-dir') args.dataDir = r.value;
      if (name === '--branch') args.branch = r.value;
      if (name === '--max-age-hours') {
        const hours = parseHours(r.value);
        if (hours === null) return { problem: `--max-age-hours braucht eine positive Zahl (gefunden: ${r.value})` };
        args.maxAgeHours = hours;
      }
    } else {
      return { problem: `Unbekanntes Argument: ${arg}` };
    }
  }
  if (args.printBranch) return args;
  if (!args.brief) return { problem: '--brief fehlt.' };
  if (!args.dataDir) return { problem: '--data-dir fehlt.' };
  return args;
}

/**
 * Runs the CLI and returns the exit code (does not call `process.exit`).
 *
 * @param {string[]} argv arguments without `node` and the script path
 * @param {Record<string, string | undefined>} [env] environment (for `MB_BRIEFS_BRANCH`)
 */
export function main(argv = process.argv.slice(2), out = console.log, err = console.error, env = process.env) {
  const args = parseCliArgs(argv);
  if (args.help) {
    out(USAGE);
    return 0;
  }
  if (args.problem) {
    err(`${args.problem}\n\n${USAGE}`);
    return 2;
  }
  let target;
  try {
    target = resolveBranch({ branch: args.branch, env });
  } catch (error) {
    if (!(error instanceof PublishError)) throw error;
    err(`FEHLER: ${error.message}`);
    return 2;
  }
  if (args.printBranch) {
    out(target.branch);
    return 0;
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
      merge: args.merge,
      branch: target.branch,
      dryRun: args.dryRun,
    });

    out(`${describe('brief', result.brief)}`);
    out(
      `Ziel: ${path.resolve(args.dataDir)} · Branch ${target.branch}${args.dryRun ? '  (Probelauf – nichts geschrieben)' : ''}`,
    );
    if (target.test) {
      out(`Testlauf: Ziel ist "${target.branch}" (${target.from}) – App und E-Mail lesen weiter "${DEFAULT_BRANCH}".`);
    }
    if (result.merge) {
      const list = (ids) => (ids.length > 0 ? ids.join(', ') : '–');
      out(
        `Zusammengeführt: ersetzt ${list(result.merge.replaced)} · neu ${list(result.merge.added)} · behalten ${list(result.merge.kept)}`,
      );
    }
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
