import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { afterEach, beforeEach, describe, it } from 'node:test';

import { PublishError, publishBrief, toJsonText } from '../publish-brief.mjs';
import { LIMITS, validateIndex } from '../validate-brief.mjs';
import { ROUTINE_DIR, makeBrief, tempDir } from './fixtures.mjs';

const PUBLISH = path.join(ROUTINE_DIR, 'publish-brief.mjs');

describe('publishBrief', () => {
  let dir;
  let cleanup;
  const read = (rel) => readFileSync(path.join(dir, rel), 'utf8');
  const readJson = (rel) => JSON.parse(read(rel));
  const statuses = (result) => Object.fromEntries(result.changes.map((c) => [c.file, c.status]));

  beforeEach(() => {
    ({ dir, cleanup } = tempDir());
  });
  afterEach(() => cleanup());

  it('writes brief, latest and index into an empty data dir', () => {
    const brief = makeBrief('2026-10-06');
    const result = publishBrief({ brief, dataDir: dir });
    assert.deepEqual(statuses(result), {
      'briefs/2026-10-06.json': 'neu',
      'latest.json': 'neu',
      'index.json': 'neu',
    });
    assert.equal(result.itemCount, 9);
    assert.equal(read('briefs/2026-10-06.json'), toJsonText(brief));
    assert.equal(read('latest.json'), toJsonText(brief));
    assert.ok(read('latest.json').endsWith('}\n'));
    assert.ok(read('latest.json').includes('\n  "schema": 1,\n'));
    assert.deepEqual(readJson('index.json'), {
      updatedAt: brief.generatedAt,
      days: [{ date: '2026-10-06', teaser: brief.teaser, itemCount: 9 }],
    });
  });

  it('is idempotent', () => {
    const brief = makeBrief();
    publishBrief({ brief, dataDir: dir });
    const snapshot = ['briefs/2026-10-06.json', 'latest.json', 'index.json'].map(read);
    const again = publishBrief({ brief, dataDir: dir });
    assert.deepEqual(Object.values(statuses(again)), ['unverändert', 'unverändert', 'unverändert']);
    assert.deepEqual(['briefs/2026-10-06.json', 'latest.json', 'index.json'].map(read), snapshot);
  });

  it('keeps a newer latest.json when backfilling an older day', () => {
    const today = makeBrief('2026-10-06');
    const older = makeBrief('2026-10-04');
    publishBrief({ brief: today, dataDir: dir });
    const result = publishBrief({ brief: older, dataDir: dir });
    assert.equal(statuses(result)['latest.json'], 'behalten');
    assert.equal(readJson('latest.json').date, '2026-10-06');
    assert.ok(existsSync(path.join(dir, 'briefs', '2026-10-04.json')));
    const index = readJson('index.json');
    assert.deepEqual(index.days.map((d) => d.date), ['2026-10-06', '2026-10-04']);
    assert.equal(index.updatedAt, today.generatedAt, 'updatedAt must not move backwards');
  });

  it('replaces latest.json and the index entry for the same or a newer day', () => {
    publishBrief({ brief: makeBrief('2026-10-05'), dataDir: dir });
    const fixed = makeBrief('2026-10-05');
    fixed.teaser = 'Korrigierter Teaser.';
    fixed.generatedAt = '2026-10-05T06:00:00+02:00';
    const result = publishBrief({ brief: fixed, dataDir: dir });
    assert.deepEqual(Object.values(statuses(result)), ['aktualisiert', 'aktualisiert', 'aktualisiert']);
    assert.equal(readJson('latest.json').teaser, 'Korrigierter Teaser.');
    const index = readJson('index.json');
    assert.equal(index.days.length, 1);
    assert.equal(index.days[0].teaser, 'Korrigierter Teaser.');
    assert.equal(index.updatedAt, '2026-10-05T06:00:00+02:00');

    publishBrief({ brief: makeBrief('2026-10-06'), dataDir: dir });
    assert.equal(readJson('latest.json').date, '2026-10-06');
    assert.deepEqual(readJson('index.json').days.map((d) => d.date), ['2026-10-06', '2026-10-05']);
  });

  it('caps the index at 400 days, dropping the oldest', () => {
    const start = Date.UTC(2026, 9, 5);
    const days = Array.from({ length: LIMITS.indexMaxDays }, (_, i) => ({
      date: new Date(start - i * 86_400_000).toISOString().slice(0, 10),
      teaser: `Tag ${i}`,
      itemCount: 10,
    }));
    writeFileSync(path.join(dir, 'index.json'), toJsonText({ updatedAt: '2026-10-05T05:00:00+02:00', days }));
    publishBrief({ brief: makeBrief('2026-10-06'), dataDir: dir });
    const index = readJson('index.json');
    assert.equal(index.days.length, LIMITS.indexMaxDays);
    assert.equal(index.days[0].date, '2026-10-06');
    assert.equal(index.days.at(-1).date, days.at(-2).date);
    assert.deepEqual(validateIndex(index).errors, []);
  });

  it('rebuilds a broken index from the brief files', () => {
    publishBrief({ brief: makeBrief('2026-10-04'), dataDir: dir });
    publishBrief({ brief: makeBrief('2026-10-05'), dataDir: dir });
    writeFileSync(path.join(dir, 'index.json'), '{ kaputt');
    writeFileSync(path.join(dir, 'briefs', '2026-10-01.json'), '{ auch kaputt');
    const result = publishBrief({ brief: makeBrief('2026-10-06'), dataDir: dir });
    assert.ok(result.notes.some((n) => n.includes('neu aufgebaut')));
    assert.ok(result.notes.some((n) => n.includes('2026-10-01.json')));
    assert.deepEqual(readJson('index.json').days.map((d) => d.date), ['2026-10-06', '2026-10-05', '2026-10-04']);
  });

  it('replaces an unreadable latest.json', () => {
    writeFileSync(path.join(dir, 'latest.json'), 'not json');
    const result = publishBrief({ brief: makeBrief(), dataDir: dir });
    const latest = result.changes.find((c) => c.file === 'latest.json');
    assert.equal(latest.status, 'aktualisiert');
    assert.match(latest.note, /unlesbar/);
    assert.equal(readJson('latest.json').date, '2026-10-06');
  });

  it('refuses invalid briefs and writes nothing', () => {
    const brief = makeBrief();
    brief.sections[0].items[0].url = 'http://unsicher.de/x';
    assert.throws(
      () => publishBrief({ brief, dataDir: dir }),
      (error) => error instanceof PublishError && error.details.some((d) => d.includes('url')),
    );
    assert.deepEqual(readdirSync(dir), []);
  });

  it('applies the freshness policy when asked', () => {
    const brief = makeBrief();
    brief.sections[0].items[0].publishedAt = '2026-10-01T10:00:00+02:00';
    assert.throws(() => publishBrief({ brief, dataDir: dir, maxAgeHours: 36 }), PublishError);
    assert.doesNotThrow(() => publishBrief({ brief, dataDir: dir }));
  });

  it('needs an existing data dir', () => {
    assert.throws(
      () => publishBrief({ brief: makeBrief(), dataDir: path.join(dir, 'fehlt') }),
      /existiert nicht/,
    );
  });

  it('writes nothing in dry-run mode', () => {
    const result = publishBrief({ brief: makeBrief(), dataDir: dir, dryRun: true });
    assert.deepEqual(Object.values(statuses(result)), ['neu', 'neu', 'neu']);
    assert.deepEqual(readdirSync(dir), []);
  });
});

describe('publish CLI', () => {
  it('publishes, then reports nothing to do', () => {
    const { dir, cleanup } = tempDir();
    try {
      const data = path.join(dir, 'data');
      mkdirSync(data);
      const briefFile = path.join(dir, 'brief.json');
      writeFileSync(briefFile, JSON.stringify(makeBrief()));
      const run = () =>
        spawnSync(process.execPath, [PUBLISH, '--brief', briefFile, '--data-dir', data], { encoding: 'utf8' });

      const first = run();
      assert.equal(first.status, 0, first.stdout + first.stderr);
      assert.match(first.stdout, /Briefing 2026-10-06 · 3 Rubriken · 9 Meldungen/);
      assert.match(first.stdout, /neu\s+briefs\/2026-10-06\.json/);
      assert.match(first.stdout, /3 Dateien geschrieben/);

      const second = run();
      assert.equal(second.status, 0);
      assert.match(second.stdout, /Nichts zu tun/);
    } finally {
      cleanup();
    }
  });

  it('exits 1 for invalid briefs and 2 for usage errors', () => {
    const { dir, cleanup } = tempDir();
    try {
      const briefFile = path.join(dir, 'brief.json');
      const brief = makeBrief();
      brief.schema = 3;
      writeFileSync(briefFile, JSON.stringify(brief));
      const bad = spawnSync(process.execPath, [PUBLISH, '--brief', briefFile, '--data-dir', dir], {
        encoding: 'utf8',
      });
      assert.equal(bad.status, 1);
      assert.match(bad.stderr, /schema: muss 1 sein/);

      const usage = spawnSync(process.execPath, [PUBLISH, '--brief', briefFile], { encoding: 'utf8' });
      assert.equal(usage.status, 2);
      assert.match(usage.stderr, /--data-dir fehlt/);
    } finally {
      cleanup();
    }
  });
});
