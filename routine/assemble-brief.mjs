#!/usr/bin/env node
/**
 * Assembles the per-topic section files of the cloud routine into one schema-2 brief
 * (contract: docs/brief-format.md). Zero dependencies, Node >= 18, ESM.
 *
 *   node routine/assemble-brief.mjs --sections /tmp/sections --teaser "<satz>" --out /tmp/brief.json
 *        [--generated-at <ISO>] [--max-age-hours 36] [--skip-invalid]
 *   node routine/assemble-brief.mjs --overview <brief.json | ordner> [--topic <id>]
 *
 * Assemble mode reads every `*.json` in the sections directory (one `{ id, title, emoji, items }`
 * per topic, as the topic agents write them), drops empty topics, puts the sections in catalogue
 * order, sets title and emoji from the catalogue, adds `schema`, `date`, `generatedAt` (default:
 * now in Europe/Berlin) and the teaser, validates the result and only then writes it. The
 * articles themselves are copied unchanged – the main agent never has to retype them.
 *
 * Overview mode prints the headlines of a brief file or a sections directory – to write the
 * teaser, or to see what yesterday's brief already covered.
 *
 * Exit codes: 0 = written / printed, 1 = invalid sections or brief (nothing written), 2 = usage error.
 */
import { readdirSync, statSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import {
  SCHEMA_VERSION,
  TIER_MORE,
  TIER_TOP,
  countImages,
  describe,
  isMainModule,
  parseHours,
  parseOffsetDateTime,
  readJsonFile,
  topicById,
  topicRank,
  validateBrief,
  validateSection,
} from './validate-brief.mjs';

/** Raised when the brief cannot be assembled; `details` lists the individual problems. */
export class AssembleError extends Error {
  constructor(message, details = []) {
    super(message);
    this.name = 'AssembleError';
    this.details = details;
  }
}

/**
 * ISO-8601 timestamp of `date` in Europe/Berlin with offset, e.g. `2026-10-06T04:31:07+02:00`.
 * Uses Node's bundled ICU time zone data, so it also works in containers without tzdata.
 */
export function formatBerlin(date = new Date()) {
  const whole = new Date(Math.floor(date.getTime() / 1000) * 1000);
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('sv-SE', {
      timeZone: 'Europe/Berlin',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hourCycle: 'h23',
    })
      .formatToParts(whole)
      .map((p) => [p.type, p.value]),
  );
  const local = Date.UTC(+parts.year, +parts.month - 1, +parts.day, +parts.hour, +parts.minute, +parts.second);
  const offset = Math.round((local - whole.getTime()) / 60_000);
  const abs = Math.abs(offset);
  const sign = offset >= 0 ? '+' : '-';
  const hh = String(Math.floor(abs / 60)).padStart(2, '0');
  const mm = String(abs % 60).padStart(2, '0');
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}:${parts.second}${sign}${hh}:${mm}`;
}

function isPlainObject(value) {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** `*.json` files of a directory, sorted by name. */
function jsonFilesIn(dir) {
  let names;
  try {
    names = readdirSync(dir);
  } catch (error) {
    throw new AssembleError(
      error.code === 'ENOENT' ? `Ordner ${dir} gibt es nicht.` : `Ordner ${dir} nicht lesbar: ${error.message}`,
    );
  }
  return names
    .filter((n) => n.toLowerCase().endsWith('.json'))
    .sort()
    .map((n) => path.join(dir, n));
}

/**
 * Builds a schema-2 brief from the section files in `sectionsDir`.
 *
 * @param {object} params
 * @param {string} params.sectionsDir directory with one `<id>.json` per topic
 * @param {string} params.teaser one sentence about the whole news day
 * @param {string} [params.generatedAt] ISO-8601 with offset; default now in Europe/Berlin
 * @param {number} [params.maxAgeHours] freshness policy (checked against generatedAt)
 * @param {boolean} [params.skipInvalid] drop invalid sections instead of failing
 * @returns {{ brief: object, notes: string[], warnings: string[] }}
 * @throws {AssembleError}
 */
export function assembleBrief({ sectionsDir, teaser, generatedAt = formatBerlin(), maxAgeHours, skipInvalid = false }) {
  const generated = parseOffsetDateTime(generatedAt);
  if (generated === null) {
    throw new AssembleError(`generatedAt "${generatedAt}" ist kein ISO-8601-Zeitpunkt mit Zeitzone.`);
  }
  const notes = [];
  const warnings = [];
  const problems = [];
  const sections = [];
  const seen = new Map();

  for (const file of jsonFilesIn(sectionsDir)) {
    const name = path.basename(file);
    let doc;
    try {
      doc = readJsonFile(file).doc;
    } catch (error) {
      problems.push({ name, messages: [error.message] });
      continue;
    }
    if (isPlainObject(doc) && Array.isArray(doc.items) && doc.items.length === 0) {
      notes.push(`${name}: keine Artikel – Rubrik ausgelassen`);
      continue;
    }
    const result = validateSection(doc, { maxAgeHours, now: generated.epochMs });
    if (result.errors.length > 0) {
      problems.push({ name, messages: result.errors });
      continue;
    }
    if (seen.has(doc.id)) {
      problems.push({ name, messages: [`Rubrik "${doc.id}" steht schon in ${seen.get(doc.id)}`] });
      continue;
    }
    seen.set(doc.id, name);

    const topic = topicById(doc.id);
    const section = {
      id: doc.id,
      title: topic ? topic.title : doc.title,
      emoji: topic ? topic.emoji : doc.emoji,
      items: doc.items,
    };
    if (topic && (doc.title !== topic.title || doc.emoji !== topic.emoji)) {
      notes.push(`${name}: Titel/Emoji aus dem Katalog übernommen (${topic.emoji} ${topic.title})`);
    }
    sections.push({ section, name });
  }

  if (problems.length > 0) {
    const details = problems.flatMap((p) => p.messages.map((m) => `${p.name}: ${m}`));
    if (!skipInvalid) {
      throw new AssembleError(
        `${problems.length} Rubrik-${problems.length === 1 ? 'Datei ist' : 'Dateien sind'} ungültig – nichts geschrieben.`,
        details,
      );
    }
    for (const p of problems) notes.push(`${p.name}: ungültig – ausgelassen (--skip-invalid)`);
    warnings.push(...details);
  }

  sections.sort((a, b) => topicRank(a.section.id) - topicRank(b.section.id) || (a.name < b.name ? -1 : 1));
  if (sections.length === 0) {
    throw new AssembleError(`In ${sectionsDir} gibt es keine Rubrik mit Artikeln – nichts geschrieben.`);
  }

  const brief = {
    schema: SCHEMA_VERSION,
    date: generated.date,
    generatedAt,
    teaser: typeof teaser === 'string' ? teaser.trim() : '',
    sections: sections.map((s) => s.section),
  };
  const check = validateBrief(brief, { maxAgeHours });
  if (check.errors.length > 0) {
    throw new AssembleError('Das zusammengesetzte Briefing ist ungültig – nichts geschrieben.', check.errors);
  }
  // Section warnings reappear here with their brief path (sections[i]…), so these are complete.
  warnings.push(...check.warnings);
  return { brief, notes, warnings };
}

// ---------------------------------------------------------------------------------------------
// Overview
// ---------------------------------------------------------------------------------------------

/**
 * Text overview of a brief (file) or of a sections directory: one block per section with every
 * headline. Returns `null` if the path does not exist.
 *
 * @param {string} target brief JSON file or sections directory
 * @param {{ topic?: string }} [options] only this section
 * @returns {string[] | null} lines
 */
export function overview(target, { topic } = {}) {
  let stat;
  try {
    stat = statSync(target);
  } catch {
    return null;
  }
  let sections;
  let head = null;
  if (stat.isDirectory()) {
    sections = [];
    for (const file of jsonFilesIn(target)) {
      try {
        const doc = readJsonFile(file).doc;
        if (isPlainObject(doc)) sections.push(doc);
      } catch (error) {
        sections.push({ id: path.basename(file), title: `unlesbar: ${error.message}`, items: [] });
      }
    }
    sections.sort((a, b) => topicRank(a.id) - topicRank(b.id));
  } else {
    let doc;
    try {
      doc = readJsonFile(target).doc;
    } catch (error) {
      throw new AssembleError(`${target}: ${error.message}`);
    }
    if (isPlainObject(doc) && Array.isArray(doc.sections)) {
      sections = doc.sections.filter(isPlainObject);
      head = `${describe('brief', doc)}${doc.teaser ? `\nTeaser: ${doc.teaser}` : ''}`;
    } else if (isPlainObject(doc) && Array.isArray(doc.items)) {
      sections = [doc];
    } else {
      throw new AssembleError(`${target}: weder ein Briefing noch eine Rubrik.`);
    }
  }

  const lines = head ? [head] : [];
  for (const s of sections) {
    if (topic && s.id !== topic) continue;
    const items = Array.isArray(s.items) ? s.items.filter(isPlainObject) : [];
    const top = items.filter((i) => i.tier === TIER_TOP).length;
    const more = items.filter((i) => i.tier === TIER_MORE).length;
    const images = countImages(s);
    const tiers =
      top + more > 0
        ? ` (${top} Top, ${more} Mehr, ${images} ${images === 1 ? 'Bild' : 'Bilder'})`
        : ` (${items.length} ${items.length === 1 ? 'Meldung' : 'Meldungen'})`;
    lines.push(`## ${s.id} · ${s.emoji ? `${s.emoji} ` : ''}${s.title ?? ''}${tiers}`);
    for (const item of items) {
      const tier = item.tier ? `${item.tier} ` : '';
      const when = typeof item.publishedAt === 'string' ? `, ${item.publishedAt}` : '';
      lines.push(`- [${tier}${item.importance ?? '?'}] ${item.headline} (${item.source ?? '?'}${when})`);
    }
  }
  if (topic && lines.length === (head ? 1 : 0)) lines.push(`(keine Rubrik "${topic}")`);
  return lines;
}

// ---------------------------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------------------------

const USAGE = `Setzt die Rubrik-Dateien der Routine zu einem Briefing (Schema 2) zusammen.

Aufruf:
  node routine/assemble-brief.mjs --sections <ordner> --teaser "<satz>" --out <datei> [Optionen]
  node routine/assemble-brief.mjs --overview <briefing.json | ordner> [--topic <id>]

Optionen:
  --sections <ordner>     Ordner mit einer <id>.json pro Rubrik ({ id, title, emoji, items }).
  --teaser "<satz>"       Ein Satz (höchstens 220 Zeichen) über den ganzen Nachrichtentag.
  --out <datei>           Ziel, z. B. /tmp/brief.json.
  --generated-at <ISO>    Erstellungszeitpunkt mit Zeitzone (Standard: jetzt, Europe/Berlin).
  --max-age-hours <h>     Frische-Regel wie validate-brief.mjs (Routine: 36).
  --skip-invalid          Ungültige Rubriken auslassen statt abzubrechen.
  --overview <pfad>       Nur die Überschriften eines Briefings oder Rubrik-Ordners zeigen.
  --topic <id>            Mit --overview: nur diese Rubrik.
  -h, --help              Diese Hilfe.

Exit-Code: 0 = geschrieben/angezeigt, 1 = ungültig (nichts geschrieben), 2 = falscher Aufruf.`;

function parseCliArgs(argv) {
  const args = { skipInvalid: false };
  const takesValue = new Set(['--sections', '--teaser', '--out', '--generated-at', '--max-age-hours', '--overview', '--topic']);
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '-h' || arg === '--help') return { help: true };
    if (arg === '--skip-invalid') {
      args.skipInvalid = true;
      continue;
    }
    const name = arg.includes('=') ? arg.slice(0, arg.indexOf('=')) : arg;
    if (!takesValue.has(name)) return { problem: `Unbekanntes Argument: ${arg}` };
    let value;
    if (arg.includes('=')) {
      value = arg.slice(arg.indexOf('=') + 1);
    } else {
      if (i + 1 >= argv.length) return { problem: `${name} braucht einen Wert` };
      value = argv[++i];
    }
    const key = name.slice(2).replace(/-([a-z])/g, (_, ch) => ch.toUpperCase());
    args[key] = value;
  }
  if (args.maxAgeHours !== undefined) {
    const hours = parseHours(args.maxAgeHours);
    if (hours === null) return { problem: `--max-age-hours braucht eine positive Zahl (gefunden: ${args.maxAgeHours})` };
    args.maxAgeHours = hours;
  }
  if (args.overview !== undefined) return args;
  if (args.topic !== undefined) return { problem: '--topic gilt nur zusammen mit --overview.' };
  if (!args.sections) return { problem: '--sections fehlt.' };
  if (!args.out) return { problem: '--out fehlt.' };
  if (args.teaser === undefined || args.teaser.trim() === '') return { problem: '--teaser fehlt (ein Satz über den Tag).' };
  if (args.generatedAt !== undefined && parseOffsetDateTime(args.generatedAt) === null) {
    return { problem: `--generated-at braucht einen ISO-8601-Zeitpunkt mit Zeitzone (gefunden: ${args.generatedAt})` };
  }
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
    if (args.overview !== undefined) {
      const lines = overview(args.overview, { topic: args.topic });
      if (lines === null) {
        out(`Nichts gefunden: ${args.overview} gibt es nicht.`);
        return 0;
      }
      for (const line of lines) out(line);
      return 0;
    }

    const { brief, notes, warnings } = assembleBrief({
      sectionsDir: args.sections,
      teaser: args.teaser,
      generatedAt: args.generatedAt,
      maxAgeHours: args.maxAgeHours,
      skipInvalid: args.skipInvalid,
    });
    try {
      writeFileSync(args.out, `${JSON.stringify(brief, null, 2)}\n`, 'utf8');
    } catch (error) {
      throw new AssembleError(`${args.out} konnte nicht geschrieben werden: ${error.message}`);
    }
    out(`OK      ${args.out} – ${describe('brief', brief)}`);
    for (const s of brief.sections) {
      const top = s.items.filter((i) => i.tier === TIER_TOP).length;
      const more = s.items.filter((i) => i.tier === TIER_MORE).length;
      const images = countImages(s);
      out(`        ${s.emoji} ${s.id}: ${top} Top, ${more} Mehr, ${images} ${images === 1 ? 'Bild' : 'Bilder'}`);
    }
    for (const n of notes) out(`        Hinweis: ${n}`);
    for (const w of warnings) out(`        Hinweis: ${w}`);
    return 0;
  } catch (error) {
    if (!(error instanceof AssembleError)) throw error;
    err(`FEHLER: ${error.message}`);
    for (const detail of error.details) err(`  Fehler: ${detail}`);
    return 1;
  }
}

if (isMainModule(import.meta.url)) {
  process.exitCode = main();
}
