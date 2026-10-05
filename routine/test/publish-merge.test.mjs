import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { afterEach, beforeEach, describe, it } from 'node:test';

import { PublishError, mergeBriefs, publishBrief, toJsonText } from '../publish-brief.mjs';
import { validateBrief } from '../validate-brief.mjs';
import { ROUTINE_DIR, clone, makeBrief, makeBriefV2, makeSection, tempDir } from './fixtures.mjs';

const PUBLISH = path.join(ROUTINE_DIR, 'publish-brief.mjs');

describe('publishBrief with schema 2', () => {
  let dir;
  let cleanup;
  const readJson = (rel) => JSON.parse(readFileSync(path.join(dir, rel), 'utf8'));

  beforeEach(() => {
    ({ dir, cleanup } = tempDir());
  });
  afterEach(() => cleanup());

  it('publishes a schema-2 brief; itemCount counts top and more', () => {
    const brief = makeBriefV2('2026-10-06');
    const result = publishBrief({ brief, dataDir: dir, maxAgeHours: 36 });
    assert.equal(result.itemCount, 9);
    assert.equal(result.merge, null);
    assert.deepEqual(readJson('briefs/2026-10-06.json'), brief);
    assert.deepEqual(readJson('index.json').days, [{ date: '2026-10-06', teaser: brief.teaser, itemCount: 9 }]);
  });

  it('mixes schema-1 and schema-2 days in the index', () => {
    publishBrief({ brief: makeBrief('2026-10-05'), dataDir: dir });
    publishBrief({ brief: makeBriefV2('2026-10-06'), dataDir: dir });
    assert.deepEqual(
      readJson('index.json').days.map((d) => [d.date, d.itemCount]),
      [
        ['2026-10-06', 9],
        ['2026-10-05', 9],
      ],
    );
    assert.equal(readJson('latest.json').schema, 2);
  });
});

describe('mergeBriefs', () => {
  it('replaces sections by id, keeps the rest and adds new ones in catalogue order', () => {
    const existing = makeBriefV2('2026-10-06', ['ki', 'wirtschaft']);
    const incoming = { ...makeBriefV2('2026-10-06', ['region']), sections: [makeSection('wirtschaft', { start: 50 }), makeSection('region', { start: 20 })] };
    incoming.generatedAt = '2026-10-06T09:00:00+02:00';
    incoming.teaser = 'Neuer Teaser.';
    const { brief, replaced, added, kept } = mergeBriefs(existing, incoming);
    assert.deepEqual(brief.sections.map((s) => s.id), ['ki', 'wirtschaft', 'region']);
    assert.equal(brief.sections[1].items[0].id, 'wirtschaft-meldung-50');
    assert.deepEqual({ replaced, added, kept }, { replaced: ['wirtschaft'], added: ['region'], kept: ['ki'] });
    assert.equal(brief.teaser, 'Neuer Teaser.');
    assert.equal(brief.generatedAt, '2026-10-06T09:00:00+02:00');
    assert.deepEqual(validateBrief(brief).errors, []);
  });

  it('keeps the later generatedAt and the old teaser when the new one is empty', () => {
    const existing = makeBriefV2('2026-10-06', ['ki']);
    existing.generatedAt = '2026-10-06T10:00:00+02:00';
    const incoming = makeBriefV2('2026-10-06', ['sport']);
    incoming.teaser = '';
    const { brief } = mergeBriefs(existing, incoming);
    assert.equal(brief.generatedAt, '2026-10-06T10:00:00+02:00');
    assert.equal(brief.teaser, existing.teaser);
  });

  it('puts unknown topics after the catalogue ones', () => {
    const existing = makeBriefV2('2026-10-06', ['region']);
    existing.sections.unshift({ ...makeSection('ki', { start: 40 }), id: 'mode', title: 'Mode', emoji: '👗' });
    for (const item of existing.sections[0].items) item.id = item.id.replace(/^ki-/, 'mode-');
    const incoming = makeBriefV2('2026-10-06', ['ki']);
    const { brief } = mergeBriefs(existing, incoming);
    assert.deepEqual(brief.sections.map((s) => s.id), ['ki', 'region', 'mode']);
  });

  it('keeps the order of schema-1 briefs and appends new sections', () => {
    const existing = makeBrief('2026-10-06');
    const incoming = makeBrief('2026-10-06');
    incoming.sections = [{ ...clone(incoming.sections[0]), id: 'sport', title: 'Sport', emoji: '⚽' }];
    for (const [i, item] of incoming.sections[0].items.entries()) item.url = `https://www.kicker.de/meldung-${i}`;
    const { brief } = mergeBriefs(existing, incoming);
    assert.deepEqual(brief.sections.map((s) => s.id), ['ki', 'wirtschaft', 'politik', 'sport']);
  });

  it('refuses to merge different schemas or dates', () => {
    assert.throws(() => mergeBriefs(makeBrief('2026-10-06'), makeBriefV2('2026-10-06')), /Schema 1.*Schema 2/);
    assert.throws(() => mergeBriefs(makeBriefV2('2026-10-05'), makeBriefV2('2026-10-06')), /Datum 2026-10-05/);
  });
});

describe('publishBrief --merge', () => {
  let dir;
  let cleanup;
  const dayFile = (date = '2026-10-06') => path.join(dir, 'briefs', `${date}.json`);
  const readJson = (rel) => JSON.parse(readFileSync(path.join(dir, rel), 'utf8'));

  beforeEach(() => {
    ({ dir, cleanup } = tempDir());
  });
  afterEach(() => cleanup());

  it('upserts sections into the existing day file and updates latest and index', () => {
    publishBrief({ brief: makeBriefV2('2026-10-06', ['ki', 'wirtschaft']), dataDir: dir });
    const incoming = makeBriefV2('2026-10-06', ['politik', 'sport']);
    for (const s of incoming.sections) {
      for (const item of s.items) item.url = item.url.replace('meldung-', `${s.id}-`);
      for (const item of s.items) item.sources[0].url = item.url;
    }
    incoming.generatedAt = '2026-10-06T06:30:00+02:00';
    const result = publishBrief({ brief: incoming, dataDir: dir, merge: true, maxAgeHours: 36 });

    assert.deepEqual(result.merge, { replaced: [], added: ['politik', 'sport'], kept: ['ki', 'wirtschaft'] });
    const day = readJson('briefs/2026-10-06.json');
    assert.deepEqual(day.sections.map((s) => s.id), ['ki', 'wirtschaft', 'politik', 'sport']);
    assert.equal(day.generatedAt, '2026-10-06T06:30:00+02:00');
    assert.deepEqual(readJson('latest.json'), day);
    assert.deepEqual(readJson('index.json').days, [{ date: '2026-10-06', teaser: incoming.teaser, itemCount: 12 }]);
    assert.equal(readJson('index.json').updatedAt, '2026-10-06T06:30:00+02:00');
    assert.equal(result.itemCount, 12);
    assert.equal(result.changes[0].note, 'zusammengeführt');

    const again = publishBrief({ brief: incoming, dataDir: dir, merge: true });
    assert.deepEqual(
      again.changes.map((c) => c.status),
      ['unverändert', 'unverändert', 'unverändert'],
      'merging the same brief twice changes nothing',
    );
  });

  it('writes the brief as is when there is no day file yet', () => {
    const brief = makeBriefV2('2026-10-06');
    const result = publishBrief({ brief, dataDir: dir, merge: true });
    assert.equal(result.merge, null);
    assert.ok(result.notes.some((n) => n.includes('gibt es noch nicht')));
    assert.equal(readFileSync(dayFile(), 'utf8'), toJsonText(brief));
  });

  it('replaces an unreadable day file', () => {
    mkdirSync(path.join(dir, 'briefs'));
    writeFileSync(dayFile(), '{ kaputt');
    const brief = makeBriefV2('2026-10-06');
    const result = publishBrief({ brief, dataDir: dir, merge: true });
    assert.ok(result.notes.some((n) => n.includes('unlesbar')));
    assert.deepEqual(readJson('briefs/2026-10-06.json'), brief);
  });

  it('refuses a merge that would produce an invalid brief and writes nothing', () => {
    publishBrief({ brief: makeBriefV2('2026-10-06', ['ki']), dataDir: dir });
    const before = readFileSync(dayFile(), 'utf8');
    const incoming = makeBriefV2('2026-10-06', ['wirtschaft']);
    incoming.sections[0].items[0].id = 'ki-meldung-1'; // collides with the kept ki section
    assert.throws(
      () => publishBrief({ brief: incoming, dataDir: dir, merge: true }),
      (error) => error instanceof PublishError && error.details.some((d) => /doppelt/.test(d)),
    );
    assert.equal(readFileSync(dayFile(), 'utf8'), before);
  });

  it('checks freshness only for the incoming sections', () => {
    const early = makeBriefV2('2026-10-06', ['ki']);
    early.sections[0].items[2].publishedAt = '2026-10-04T18:00:00+02:00'; // 35 h before 05:07
    publishBrief({ brief: early, dataDir: dir, maxAgeHours: 36 });
    const late = { ...makeBriefV2('2026-10-06', ['region']), sections: [makeSection('region', { start: 20 })] };
    late.generatedAt = '2026-10-06T20:00:00+02:00';
    const result = publishBrief({ brief: late, dataDir: dir, merge: true, maxAgeHours: 36 });
    assert.deepEqual(result.merge.kept, ['ki']);
  });

  it('refuses schema mismatches', () => {
    publishBrief({ brief: makeBrief('2026-10-06'), dataDir: dir });
    assert.throws(() => publishBrief({ brief: makeBriefV2('2026-10-06'), dataDir: dir, merge: true }), PublishError);
  });

  it('merges via the CLI', () => {
    const data = path.join(dir, 'data');
    mkdirSync(data);
    const first = path.join(dir, 'first.json');
    const second = path.join(dir, 'second.json');
    writeFileSync(first, JSON.stringify(makeBriefV2('2026-10-06', ['ki'])));
    writeFileSync(second, JSON.stringify({ ...makeBriefV2('2026-10-06', ['region']), sections: [makeSection('region', { start: 20 })] }));
    const run = (file, ...extra) =>
      spawnSync(process.execPath, [PUBLISH, '--brief', file, '--data-dir', data, ...extra], { encoding: 'utf8' });
    assert.equal(run(first).status, 0);
    const merged = run(second, '--merge');
    assert.equal(merged.status, 0, merged.stdout + merged.stderr);
    assert.match(merged.stdout, /Zusammengeführt: ersetzt – · neu region · behalten ki/);
    assert.match(merged.stdout, /Briefing 2026-10-06 · 2 Rubriken · 6 Artikel/);
    assert.deepEqual(readdirSync(path.join(data, 'briefs')), ['2026-10-06.json']);
  });
});
