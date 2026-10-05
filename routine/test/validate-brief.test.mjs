import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { describe, it } from 'node:test';

import {
  LIMITS,
  countItems,
  detectKind,
  httpsUrlProblem,
  isCalendarDate,
  parseOffsetDateTime,
  validateBrief,
  validateDocument,
  validateFile,
  validateIndex,
  wordCount,
} from '../validate-brief.mjs';
import { ROUTINE_DIR, SAMPLE_BRIEF, clone, makeBrief, tempDir } from './fixtures.mjs';

const VALIDATE = path.join(ROUTINE_DIR, 'validate-brief.mjs');

/** Asserts that exactly one error mentions `where` and returns it. */
function expectError(result, where) {
  const hits = result.errors.filter((e) => e.startsWith(`${where}:`));
  assert.equal(hits.length, 1, `expected one error at ${where}, got: ${JSON.stringify(result.errors)}`);
  return hits[0];
}

describe('primitives', () => {
  it('accepts only real calendar dates', () => {
    assert.ok(isCalendarDate('2026-10-06'));
    assert.ok(isCalendarDate('2028-02-29'));
    assert.ok(!isCalendarDate('2026-02-29'));
    assert.ok(!isCalendarDate('2026-13-01'));
    assert.ok(!isCalendarDate('2026-10-6'));
    assert.ok(!isCalendarDate('06.10.2026'));
    assert.ok(!isCalendarDate(20261006));
  });

  it('parses ISO date-times only with an offset', () => {
    const p = parseOffsetDateTime('2026-10-06T05:07:12+02:00');
    assert.equal(p.date, '2026-10-06');
    assert.equal(p.epochMs, Date.UTC(2026, 9, 6, 3, 7, 12));
    assert.equal(parseOffsetDateTime('2026-10-06T03:07:12Z').epochMs, p.epochMs);
    assert.equal(parseOffsetDateTime('2026-10-06T05:07+02:00').epochMs, Date.UTC(2026, 9, 6, 3, 7));
    assert.equal(parseOffsetDateTime('2026-10-06T05:07:12.123456+02:00').epochMs, p.epochMs + 123);
    assert.equal(parseOffsetDateTime('2026-10-06T05:07:12'), null);
    assert.equal(parseOffsetDateTime('2026-10-06'), null);
    assert.equal(parseOffsetDateTime('2026-10-06T24:00:00+02:00'), null);
    assert.equal(parseOffsetDateTime('2026-02-30T10:00:00+01:00'), null);
    assert.equal(parseOffsetDateTime('2026-10-06T05:07:12+0200'), null);
    assert.equal(parseOffsetDateTime('2026-10-06T05:07:12+19:00'), null);
  });

  it('accepts only absolute https URLs', () => {
    assert.equal(httpsUrlProblem('https://www.heise.de/news/x-123.html'), null);
    assert.match(httpsUrlProblem('http://www.heise.de/'), /https/);
    assert.ok(httpsUrlProblem('https://localhost/x'));
    assert.ok(httpsUrlProblem('www.heise.de/news'));
    assert.ok(httpsUrlProblem('https://www.heise.de/a b'));
    assert.ok(httpsUrlProblem(''));
    assert.ok(httpsUrlProblem(undefined));
  });

  it('counts words with letters or digits only', () => {
    assert.equal(wordCount('Euro rutscht auf 17-Monats-Tief'), 4);
    assert.equal(wordCount('Merz – Kiew & mehr'), 3);
  });

  it('detects the document kind by its keys', () => {
    assert.equal(detectKind(makeBrief()), 'brief');
    assert.equal(detectKind({ updatedAt: 'x', days: [] }), 'index');
    assert.equal(detectKind({ foo: 1 }), null);
    assert.equal(detectKind([]), null);
    assert.equal(detectKind(null), null);
  });
});

describe('validateBrief', () => {
  it('accepts a well-formed brief, also with the freshness policy', () => {
    const brief = makeBrief();
    assert.deepEqual(validateBrief(brief), { errors: [], warnings: [] });
    assert.deepEqual(validateBrief(brief, { maxAgeHours: 36 }), { errors: [], warnings: [] });
    assert.equal(countItems(brief), 9);
  });

  it('accepts the bundled sample brief of the app', { skip: !existsSync(SAMPLE_BRIEF) }, () => {
    const result = validateFile(SAMPLE_BRIEF, { maxAgeHours: 36 });
    assert.equal(result.kind, 'brief');
    assert.deepEqual(result.errors, []);
    assert.deepEqual(result.warnings, []);
    assert.equal(result.doc.schema, 2, 'the demo brief shows the full-article format');
  });

  it('rejects wrong schema, date and generatedAt', () => {
    const brief = makeBrief();
    brief.schema = 3;
    brief.date = '2026-02-30';
    brief.generatedAt = '2026-10-06T05:07:12';
    const result = validateBrief(brief);
    expectError(result, 'schema');
    expectError(result, 'date');
    expectError(result, 'generatedAt');
  });

  it('requires date to be the local date of generatedAt', () => {
    const brief = makeBrief();
    brief.date = '2026-10-05';
    assert.match(expectError(validateBrief(brief), 'generatedAt'), /2026-10-05/);
  });

  it('limits the teaser to 220 characters and requires it to be text', () => {
    const brief = makeBrief();
    brief.teaser = 'x'.repeat(LIMITS.teaser + 1);
    expectError(validateBrief(brief), 'teaser');
    brief.teaser = '🤖'.repeat(LIMITS.teaser); // code points, not UTF-16 units
    assert.deepEqual(validateBrief(brief).errors, []);
    delete brief.teaser;
    expectError(validateBrief(brief), 'teaser');
  });

  it('rejects missing or empty sections', () => {
    const brief = makeBrief();
    brief.sections = [];
    expectError(validateBrief(brief), 'sections');
    delete brief.sections;
    expectError(validateBrief(brief), 'sections');
  });

  it('rejects duplicate section ids and missing title/items', () => {
    const brief = makeBrief();
    brief.sections[1].id = 'ki';
    delete brief.sections[2].title;
    brief.sections[2].items = 'nope';
    const result = validateBrief(brief);
    assert.match(expectError(result, 'sections[1].id'), /doppelt/);
    expectError(result, 'sections[2].title');
    expectError(result, 'sections[2].items');
  });

  it('rejects a brief without any item', () => {
    const brief = makeBrief();
    for (const s of brief.sections) s.items = [];
    expectError(validateBrief(brief), 'sections');
  });

  it('checks every item field', () => {
    const brief = makeBrief();
    const [a, b, c] = brief.sections[0].items;
    a.headline = '   ';
    a.summary = 's'.repeat(LIMITS.summary + 1);
    a.source = '';
    b.headline = 'h'.repeat(LIMITS.headline + 1);
    b.url = 'http://www.tagesschau.de/x.html';
    b.importance = 11;
    c.url = 'kein link';
    c.importance = 7.5;
    c.publishedAt = 'gestern';
    const result = validateBrief(brief);
    expectError(result, 'sections[0].items[0].headline');
    expectError(result, 'sections[0].items[0].summary');
    expectError(result, 'sections[0].items[0].source');
    expectError(result, 'sections[0].items[1].headline');
    expectError(result, 'sections[0].items[1].url');
    expectError(result, 'sections[0].items[1].importance');
    expectError(result, 'sections[0].items[2].url');
    expectError(result, 'sections[0].items[2].importance');
    expectError(result, 'sections[0].items[2].publishedAt');
    assert.equal(result.errors.length, 9);
  });

  it('rejects the same article twice', () => {
    const brief = makeBrief();
    brief.sections[2].items[0].url = `${brief.sections[0].items[0].url}#kommentare`;
    assert.match(expectError(validateBrief(brief), 'sections[2].items[0].url'), /sections\[0\]\.items\[0\]/);
  });

  it('enforces the freshness policy relative to generatedAt', () => {
    const brief = makeBrief();
    brief.sections[1].items[0].publishedAt = '2026-10-04T17:00:00+02:00'; // 36.1 h before generation
    assert.deepEqual(validateBrief(brief).errors, []);
    assert.match(
      expectError(validateBrief(brief, { maxAgeHours: 36 }), 'sections[1].items[0].publishedAt'),
      /36\.1 h/,
    );
    brief.sections[1].items[0].publishedAt = '2026-10-04T17:07:12+02:00'; // exactly 36 h
    assert.deepEqual(validateBrief(brief, { maxAgeHours: 36 }).errors, []);
  });

  it('rejects publishedAt more than 2 h after generatedAt', () => {
    const brief = makeBrief();
    brief.sections[0].items[0].publishedAt = '2026-10-06T07:07:12+02:00';
    assert.deepEqual(validateBrief(brief).errors, []);
    brief.sections[0].items[0].publishedAt = '2026-10-06T05:30:00Z'; // 7:30 Berlin
    expectError(validateBrief(brief), 'sections[0].items[0].publishedAt');
  });

  it('requires publishedAt only under the freshness policy', () => {
    const brief = makeBrief();
    delete brief.sections[0].items[0].publishedAt;
    brief.sections[0].items[1].publishedAt = null;
    const lenient = validateBrief(brief);
    assert.deepEqual(lenient.errors, []);
    assert.equal(lenient.warnings.length, 2);
    const strict = validateBrief(brief, { maxAgeHours: 36 });
    expectError(strict, 'sections[0].items[0].publishedAt');
    expectError(strict, 'sections[0].items[1].publishedAt');
  });

  it('warns about editorial guidelines without failing', () => {
    const brief = makeBrief();
    brief.sections[0].items[0].headline = 'Eins zwei drei vier fünf sechs sieben acht neun zehn elf';
    brief.sections[0].items[0].sumary = 'Tippfehler';
    brief.sections[1].items.push(clone(brief.sections[1].items[0]), clone(brief.sections[1].items[0]));
    brief.sections[1].items[3].url = 'https://www.zeit.de/a';
    brief.sections[1].items[4].url = 'https://www.zeit.de/b';
    brief.sections[2].id = 'sport';
    delete brief.sections[2].emoji;
    const result = validateBrief(brief);
    assert.deepEqual(result.errors, []);
    const text = result.warnings.join('\n');
    assert.match(text, /11 Wörter/);
    assert.match(text, /"sumary"/);
    assert.match(text, /5 Meldungen/);
    assert.match(text, /gleiche Überschrift/);
    assert.match(text, /"politik"/);
    assert.match(text, /emoji/);
  });

  it('rejects non-objects', () => {
    assert.equal(validateBrief(null).errors.length, 1);
    assert.equal(validateBrief([]).errors.length, 1);
    assert.equal(validateDocument('text').kind, null);
  });
});

describe('validateIndex', () => {
  const index = () => ({
    updatedAt: '2026-10-06T05:07:12+02:00',
    days: [
      { date: '2026-10-06', teaser: 'Heute', itemCount: 10 },
      { date: '2026-10-05', teaser: 'Gestern', itemCount: 9 },
      { date: '2026-10-03', teaser: 'Vorgestern', itemCount: 11 },
    ],
  });

  it('accepts a sorted, unique index', () => {
    assert.deepEqual(validateIndex(index()), { errors: [], warnings: [] });
    assert.deepEqual(validateIndex({ updatedAt: '2026-10-06T05:07:12+02:00', days: [] }).errors, []);
  });

  it('rejects unsorted, duplicate and invalid dates', () => {
    const doc = index();
    doc.days.push({ date: '2026-10-04', teaser: '', itemCount: 1 });
    doc.days.push({ date: '2026-10-05', teaser: '', itemCount: 1 });
    doc.days.push({ date: '2026-02-30', teaser: '', itemCount: 1 });
    const result = validateIndex(doc);
    assert.match(expectError(result, 'days[3].date'), /neueste/);
    assert.match(expectError(result, 'days[4].date'), /doppelt/);
    expectError(result, 'days[5].date');
  });

  it('checks updatedAt, days and entry fields', () => {
    const doc = index();
    doc.updatedAt = '2026-10-06';
    doc.days[0].itemCount = -1;
    doc.days[1].teaser = 42;
    const result = validateIndex(doc);
    expectError(result, 'updatedAt');
    expectError(result, 'days[0].itemCount');
    expectError(result, 'days[1].teaser');
    expectError(validateIndex({ updatedAt: doc.updatedAt }), 'days');
  });

  it('allows at most 400 days', () => {
    const start = Date.UTC(2030, 0, 1);
    const days = Array.from({ length: LIMITS.indexMaxDays + 1 }, (_, i) => ({
      date: new Date(start - i * 86_400_000).toISOString().slice(0, 10),
      teaser: '',
      itemCount: 1,
    }));
    const doc = { updatedAt: '2030-01-01T05:00:00+01:00', days };
    expectError(validateIndex(doc), 'days');
    doc.days.pop();
    assert.deepEqual(validateIndex(doc).errors, []);
  });
});

describe('CLI', () => {
  const run = (...args) =>
    spawnSync(process.execPath, [VALIDATE, ...args], {
      encoding: 'utf8',
      env: { ...process.env, GITHUB_ACTIONS: '' },
    });

  it('exits 0 with an OK line for valid files', () => {
    const { dir, cleanup } = tempDir();
    try {
      const file = path.join(dir, 'brief.json');
      writeFileSync(file, JSON.stringify(makeBrief()));
      const result = run(file, '--max-age-hours', '36');
      assert.equal(result.status, 0, result.stdout + result.stderr);
      assert.match(result.stdout, /^OK .*Briefing 2026-10-06 · 3 Rubriken · 9 Meldungen/m);
    } finally {
      cleanup();
    }
  });

  it('exits 1 and lists every problem for invalid files', () => {
    const { dir, cleanup } = tempDir();
    try {
      const good = path.join(dir, 'good.json');
      const bad = path.join(dir, 'bad.json');
      const broken = path.join(dir, 'broken.json');
      const brief = makeBrief();
      writeFileSync(good, JSON.stringify(brief));
      brief.sections[0].items[0].url = 'http://example.com/x';
      writeFileSync(bad, JSON.stringify(brief));
      writeFileSync(broken, '{\n  "schema": 1,\n  "date": "2026-10-06",\n}\n');
      const result = run(good, bad, broken, path.join(dir, 'missing.json'));
      assert.equal(result.status, 1);
      assert.match(result.stdout, /FEHLER .*bad\.json/);
      assert.match(result.stdout, /sections\[0\]\.items\[0\]\.url: muss mit https:\/\/ beginnen/);
      assert.match(result.stdout, /kein gültiges JSON/);
      assert.match(result.stdout, /Datei nicht gefunden/);
      assert.match(result.stdout, /3 von 4 Dateien ungültig/);
    } finally {
      cleanup();
    }
  });

  it('exits 2 on usage errors', () => {
    assert.equal(run().status, 2);
    assert.equal(run('x.json', '--max-age-hours', 'abc').status, 2);
    assert.equal(run('x.json', '--wat').status, 2);
    assert.equal(run('--help').status, 0);
  });

  it('emits GitHub annotations inside Actions', () => {
    const { dir, cleanup } = tempDir();
    try {
      const file = path.join(dir, 'bad.json');
      writeFileSync(file, JSON.stringify({ updatedAt: 'nie', days: [] }));
      const result = spawnSync(process.execPath, [VALIDATE, file], {
        encoding: 'utf8',
        env: { ...process.env, GITHUB_ACTIONS: 'true' },
      });
      assert.equal(result.status, 1);
      assert.match(result.stdout, /^::error file=.*bad\.json::updatedAt: /m);
    } finally {
      cleanup();
    }
  });
});
