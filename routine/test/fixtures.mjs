/** Shared test helpers: minimal valid briefs (schema 1 and 2), sections and temp directories. */
import { mkdtempSync, rmSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROUTINE_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const REPO_DIR = path.resolve(ROUTINE_DIR, '..');
export const FIXTURES_DIR = path.join(ROUTINE_DIR, 'test', 'fixtures');
export const SAMPLE_BRIEF = path.join(REPO_DIR, 'app', 'src', 'main', 'assets', 'sample-brief.json');

function previousDay(date) {
  const previous = new Date(`${date}T00:00:00Z`);
  previous.setUTCDate(previous.getUTCDate() - 1);
  return previous.toISOString().slice(0, 10);
}

// ---------------------------------------------------------------------------------------------
// Schema 1
// ---------------------------------------------------------------------------------------------

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
 * A valid schema-1 brief for `date` (3 sections × 3 items), generated at 05:07 Berlin summer
 * time. Every item was published a few hours before generation.
 */
export function makeBrief(date = '2026-10-06') {
  const yesterday = previousDay(date);
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

// ---------------------------------------------------------------------------------------------
// Schema 2
// ---------------------------------------------------------------------------------------------

const FILLER = [
  'Die', 'Landesregierung', 'plant', 'neue', 'Regeln', 'für', 'Betriebe', 'in', 'Sachsen',
  'und', 'Leipzig', 'Fachleute', 'erwarten', 'spürbare', 'Folgen', 'im', 'kommenden', 'Jahr',
];

/**
 * An article body with exactly `words` counted words: three paragraphs and (optionally) one
 * `## ` subheading, which itself counts as one word.
 */
export function bodyWithWords(words, { heading = true } = {}) {
  const text = heading ? words - 1 : words;
  const all = Array.from({ length: text }, (_, i) => FILLER[i % FILLER.length]);
  const a = Math.floor(text / 3);
  const b = Math.floor((2 * text) / 3);
  const [p1, p2, p3] = [all.slice(0, a), all.slice(a, b), all.slice(b)].map((ws) => `${ws.join(' ')}.`);
  return heading ? `${p1}\n\n## Hintergrund\n\n${p2}\n\n${p3}` : `${p1}\n\n${p2}\n\n${p3}`;
}

/** A Commons image object that passes the validator. */
export function makeImage(n) {
  return {
    url: `https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Bild_${n}.jpg/1280px-Bild_${n}.jpg`,
    credit: 'Erika Mustermann',
    license: 'CC BY-SA 4.0',
    sourceUrl: `https://commons.wikimedia.org/wiki/File:Bild_${n}.jpg`,
    alt: `Ein passendes Foto zu Meldung ${n}`,
  };
}

/**
 * A valid schema-2 article. "top": 300 words, two sources, an image on even numbers;
 * "more": 150 words, one source.
 */
export function makeItemV2(n, { tier = 'top', topic = 'ki', publishedAt = '2026-10-06T04:30:00+02:00' } = {}) {
  const url = `https://www.tagesschau.de/inland/meldung-${n}-100.html`;
  const sources = [{ name: 'tagesschau', url }];
  if (tier === 'top') sources.push({ name: 'ZEIT ONLINE', url: `https://www.zeit.de/politik/meldung-${n}` });
  return {
    id: `${topic}-meldung-${n}`,
    tier,
    headline: `Meldung Nummer ${n} mit klarer Aussage`,
    summary: `Das ist der Vorspann von Meldung ${n}. Er erklärt kurz, warum das wichtig ist.`,
    body: bodyWithWords(tier === 'top' ? 300 : 150),
    source: 'tagesschau',
    url,
    sources,
    publishedAt,
    importance: 5 + (n % 5),
    readingMinutes: tier === 'top' ? 2 : 1,
    image: tier === 'top' && n % 2 === 0 ? makeImage(n) : null,
  };
}

const TOPIC_HEADS = {
  ki: { title: 'KI & Tech', emoji: '🤖' },
  wirtschaft: { title: 'Wirtschaft & Märkte', emoji: '📈' },
  politik: { title: 'Politik', emoji: '🏛️' },
  welt: { title: 'Welt', emoji: '🌍' },
  region: { title: 'Leipzig & Region', emoji: '📍' },
  sport: { title: 'Sport', emoji: '⚽' },
};

/** A valid schema-2 section: 2 top + 1 more, numbered from `start`. */
export function makeSection(topic = 'ki', { start = 1, date = '2026-10-06' } = {}) {
  const yesterday = previousDay(date);
  return {
    id: topic,
    ...TOPIC_HEADS[topic],
    items: [
      makeItemV2(start, { topic, tier: 'top', publishedAt: `${yesterday}T21:15:00+02:00` }),
      makeItemV2(start + 1, { topic, tier: 'top', publishedAt: `${date}T04:30:00+02:00` }),
      makeItemV2(start + 2, { topic, tier: 'more', publishedAt: `${yesterday}T12:00:00+02:00` }),
    ],
  };
}

/**
 * A valid schema-2 brief for `date`: ki, wirtschaft, region with 2 top + 1 more each
 * (9 articles, 3 images), generated at 05:07 Berlin summer time.
 */
export function makeBriefV2(date = '2026-10-06', topics = ['ki', 'wirtschaft', 'region']) {
  return {
    schema: 2,
    date,
    generatedAt: `${date}T05:07:12+02:00`,
    teaser: `Der Nachrichtentag am ${date} in einem Satz.`,
    sections: topics.map((topic, i) => makeSection(topic, { start: i * 3 + 1, date })),
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
