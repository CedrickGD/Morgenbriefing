/** Shared test helpers: a minimal valid brief and temp directories. */
import { mkdtempSync, rmSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROUTINE_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const REPO_DIR = path.resolve(ROUTINE_DIR, '..');
export const SAMPLE_BRIEF = path.join(REPO_DIR, 'app', 'src', 'main', 'assets', 'sample-brief.json');

function item(n, publishedAt) {
  return {
    headline: `Meldung Nummer ${n} mit klarer Aussage`,
    summary: `Das ist die Zusammenfassung von Meldung ${n}. Sie erklärt kurz, warum das wichtig ist.`,
    source: 'tagesschau',
    url: `https://www.tagesschau.de/inland/meldung-${n}-100.html`,
    publishedAt,
    importance: 5 + (n % 5),
  };
}

/**
 * A valid brief for `date` (3 sections × 3 items), generated at 05:07 Berlin summer time.
 * Every item was published a few hours before generation.
 */
export function makeBrief(date = '2026-10-06') {
  const previous = new Date(`${date}T00:00:00Z`);
  previous.setUTCDate(previous.getUTCDate() - 1);
  const yesterday = previous.toISOString().slice(0, 10);
  let n = 0;
  const items = () => [
    item(++n, `${yesterday}T21:15:00+02:00`),
    item(++n, `${date}T04:30:00+02:00`),
    item(++n, `${yesterday}T12:00:00+02:00`),
  ];
  return {
    schema: 1,
    date,
    generatedAt: `${date}T05:07:12+02:00`,
    teaser: `Der Nachrichtentag am ${date} in einem Satz.`,
    sections: [
      { id: 'ki', title: 'KI & Tech', emoji: '🤖', items: items() },
      { id: 'wirtschaft', title: 'Wirtschaft & Märkte', emoji: '📈', items: items() },
      { id: 'politik', title: 'Politik', emoji: '🏛️', items: items() },
    ],
  };
}

export function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

/** Creates a temp dir and returns it with a cleanup function. */
export function tempDir(prefix = 'morgenbriefing-test-') {
  const dir = mkdtempSync(path.join(os.tmpdir(), prefix));
  return { dir, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}
