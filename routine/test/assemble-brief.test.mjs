import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { afterEach, beforeEach, describe, it } from 'node:test';

import { AssembleError, assembleBrief, formatBerlin, overview, previousBriefDate } from '../assemble-brief.mjs';
import { validateBrief } from '../validate-brief.mjs';
import { ROUTINE_DIR, SAMPLE_BRIEF, makeBriefV2, makeSection, tempDir } from './fixtures.mjs';

const ASSEMBLE = path.join(ROUTINE_DIR, 'assemble-brief.mjs');
const GENERATED = '2026-10-06T05:07:12+02:00';

describe('formatBerlin', () => {
  it('formats instants in Europe/Berlin with the right offset', () => {
    assert.equal(formatBerlin(new Date('2026-10-06T02:31:07.900Z')), '2026-10-06T04:31:07+02:00');
    assert.equal(formatBerlin(new Date('2026-12-24T23:30:00Z')), '2026-12-25T00:30:00+01:00');
    assert.equal(formatBerlin(new Date('2026-10-25T00:59:59Z')), '2026-10-25T02:59:59+02:00');
    assert.equal(formatBerlin(new Date('2026-10-25T01:00:00Z')), '2026-10-25T02:00:00+01:00');
  });
});

describe('assembleBrief', () => {
  let dir;
  let cleanup;
  const write = (name, doc) => writeFileSync(path.join(dir, name), typeof doc === 'string' ? doc : JSON.stringify(doc));

  beforeEach(() => {
    ({ dir, cleanup } = tempDir());
  });
  afterEach(() => cleanup());

  it('builds a valid schema-2 brief in catalogue order and skips empty topics', () => {
    write('region.json', makeSection('region', { start: 7 }));
    write('ki.json', makeSection('ki', { start: 1 }));
    write('wirtschaft.json', makeSection('wirtschaft', { start: 4 }));
    write('sport.json', { id: 'sport', title: 'Sport', emoji: '⚽', items: [] });
    const { brief, notes } = assembleBrief({ sectionsDir: dir, teaser: '  Ein Satz über den Tag.  ', generatedAt: GENERATED, maxAgeHours: 36 });
    assert.deepEqual(brief, { ...makeBriefV2('2026-10-06'), teaser: 'Ein Satz über den Tag.' });
    assert.deepEqual(validateBrief(brief, { maxAgeHours: 36 }), { errors: [], warnings: [] });
    assert.ok(notes.some((n) => n.startsWith('sport.json: keine Artikel')));
  });

  it('takes title and emoji from the catalogue', () => {
    write('ki.json', { ...makeSection('ki'), title: 'Künstliche Intelligenz', emoji: '🧠' });
    const { brief, notes } = assembleBrief({ sectionsDir: dir, teaser: 'T.', generatedAt: GENERATED });
    assert.equal(brief.sections[0].title, 'KI & Tech');
    assert.equal(brief.sections[0].emoji, '🤖');
    assert.ok(notes.some((n) => n.includes('aus dem Katalog')));
  });

  it('derives the date from generatedAt', () => {
    write('ki.json', makeSection('ki', { date: '2026-12-25' }));
    const { brief } = assembleBrief({ sectionsDir: dir, teaser: 'T.', generatedAt: '2026-12-25T04:12:00+01:00' });
    assert.equal(brief.date, '2026-12-25');
    assert.equal(brief.schema, 2);
  });

  it('fails on invalid sections unless asked to skip them', () => {
    write('ki.json', makeSection('ki', { start: 1 }));
    const broken = makeSection('wirtschaft', { start: 4 });
    broken.items[0].body = 'Zu kurz.';
    write('wirtschaft.json', broken);
    write('kaputt.json', '{ nope');
    assert.throws(
      () => assembleBrief({ sectionsDir: dir, teaser: 'T.', generatedAt: GENERATED }),
      (e) =>
        e instanceof AssembleError &&
        /2 Rubrik-Dateien sind ungültig/.test(e.message) &&
        e.details.some((d) => d.startsWith('wirtschaft.json: items[0].body')) &&
        e.details.some((d) => d.startsWith('kaputt.json: kein gültiges JSON')),
    );
    const { brief, notes } = assembleBrief({ sectionsDir: dir, teaser: 'T.', generatedAt: GENERATED, skipInvalid: true });
    assert.deepEqual(brief.sections.map((s) => s.id), ['ki']);
    assert.ok(notes.some((n) => n.startsWith('wirtschaft.json: ungültig')));
  });

  it('applies the freshness policy relative to generatedAt', () => {
    const old = makeSection('ki');
    old.items[2].publishedAt = '2026-10-04T12:00:00+02:00';
    write('ki.json', old);
    assert.throws(() => assembleBrief({ sectionsDir: dir, teaser: 'T.', generatedAt: GENERATED, maxAgeHours: 36 }), /ungültig/);
    assert.doesNotThrow(() => assembleBrief({ sectionsDir: dir, teaser: 'T.', generatedAt: GENERATED }));
  });

  it('rejects the same topic twice and duplicates across topics', () => {
    write('ki.json', makeSection('ki', { start: 1 }));
    write('ki-2.json', makeSection('ki', { start: 10 }));
    assert.throws(
      () => assembleBrief({ sectionsDir: dir, teaser: 'T.', generatedAt: GENERATED }),
      (e) => e.details.some((d) => d === 'ki.json: Rubrik "ki" steht schon in ki-2.json'),
    );
  });

  it('rejects stories that two topics both cover', () => {
    write('ki.json', makeSection('ki', { start: 1 }));
    write('wirtschaft.json', makeSection('wirtschaft', { start: 1 })); // same URLs as ki
    assert.throws(
      () => assembleBrief({ sectionsDir: dir, teaser: 'T.', generatedAt: GENERATED }),
      (e) => /zusammengesetzte Briefing ist ungültig/.test(e.message) && e.details.some((d) => /Duplikate/.test(d)),
    );
  });

  it('reassembles the demo brief of the app from its sections unchanged', { skip: !existsSync(SAMPLE_BRIEF) }, () => {
    const sample = JSON.parse(readFileSync(SAMPLE_BRIEF, 'utf8'));
    for (const section of [...sample.sections].reverse()) write(`${section.id}.json`, section);
    const { brief, warnings } = assembleBrief({
      sectionsDir: dir,
      teaser: sample.teaser,
      generatedAt: sample.generatedAt,
      maxAgeHours: 36,
    });
    assert.deepEqual(brief, sample);
    assert.deepEqual(warnings, []);
  });

  it('needs at least one topic with articles and a valid generatedAt', () => {
    write('sport.json', { id: 'sport', title: 'Sport', emoji: '⚽', items: [] });
    assert.throws(() => assembleBrief({ sectionsDir: dir, teaser: 'T.', generatedAt: GENERATED }), /keine Rubrik mit Artikeln/);
    assert.throws(() => assembleBrief({ sectionsDir: path.join(dir, 'fehlt'), teaser: 'T.', generatedAt: GENERATED }), /gibt es nicht/);
    assert.throws(() => assembleBrief({ sectionsDir: dir, teaser: 'T.', generatedAt: 'heute' }), /ISO-8601/);
  });
});

describe('overview', () => {
  let dir;
  let cleanup;
  beforeEach(() => {
    ({ dir, cleanup } = tempDir());
  });
  afterEach(() => cleanup());

  it('lists the headlines of a brief, optionally of one topic', () => {
    const file = path.join(dir, 'latest.json');
    writeFileSync(file, JSON.stringify(makeBriefV2()));
    const lines = overview(file);
    assert.match(lines[0], /^Briefing 2026-10-06 · 3 Rubriken · 9 Artikel · 3 Bilder\nErstellt: 2026-10-06T05:07:12\+02:00\nTeaser: /);
    assert.ok(lines.includes('## ki · 🤖 KI & Tech (2 Top, 1 Mehr, 1 Bild)'));
    assert.ok(lines.includes('- [top 6] Meldung Nummer 1 mit klarer Aussage (tagesschau, 2026-10-05T21:15:00+02:00)'));
    const region = overview(file, { topic: 'region' });
    assert.equal(region.length, 5);
    assert.match(overview(file, { topic: 'sport' }).at(-1), /keine Rubrik "sport"/);
  });

  it('lists a sections directory and returns null for missing paths', () => {
    writeFileSync(path.join(dir, 'wirtschaft.json'), JSON.stringify(makeSection('wirtschaft')));
    writeFileSync(path.join(dir, 'ki.json'), JSON.stringify(makeSection('ki', { start: 4 })));
    const lines = overview(dir);
    assert.equal(lines[0], '## ki · 🤖 KI & Tech (2 Top, 1 Mehr, 1 Bild)');
    assert.equal(overview(path.join(dir, 'fehlt.json')), null);
  });
});

describe('previousBriefDate', () => {
  const index = {
    updatedAt: '2026-10-06T04:11:00+02:00',
    days: [
      { date: '2026-10-06', teaser: 'Heute.', itemCount: 20 },
      { date: '2026-10-05', teaser: 'Gestern.', itemCount: 30 },
      { date: '2026-10-02', teaser: 'Älter.', itemCount: 25 },
    ],
  };

  it("picks the newest brief before the given day, not today's", () => {
    assert.equal(previousBriefDate(index, '2026-10-06'), '2026-10-05');
    assert.equal(previousBriefDate(index, '2026-10-07'), '2026-10-06');
    assert.equal(previousBriefDate(index, '2026-10-05'), '2026-10-02');
  });

  it('ignores order and invalid entries and returns null if nothing is older', () => {
    const shuffled = { days: [index.days[2], { date: 'gestern' }, null, index.days[1], index.days[0]] };
    assert.equal(previousBriefDate(shuffled, '2026-10-06'), '2026-10-05');
    assert.equal(previousBriefDate(index, '2026-10-02'), null);
    assert.equal(previousBriefDate({ days: [] }, '2026-10-06'), null);
    assert.equal(previousBriefDate('kaputt', '2026-10-06'), null);
  });
});

describe('assemble CLI', () => {
  const run = (...args) => spawnSync(process.execPath, [ASSEMBLE, ...args], { encoding: 'utf8' });

  it('writes the brief and reports what it contains', () => {
    const { dir, cleanup } = tempDir();
    try {
      writeFileSync(path.join(dir, 'ki.json'), JSON.stringify(makeSection('ki')));
      const out = path.join(dir, 'brief.json');
      const result = run('--sections', dir, '--teaser', 'Ein Satz.', '--out', out, '--generated-at', GENERATED, '--max-age-hours', '36');
      assert.equal(result.status, 0, result.stdout + result.stderr);
      assert.match(result.stdout, /^OK .*brief\.json – Briefing 2026-10-06 · 1 Rubrik · 3 Artikel · 1 Bild$/m);
      assert.match(result.stdout, /🤖 ki: 2 Top, 1 Mehr, 1 Bild/);
      const brief = JSON.parse(readFileSync(out, 'utf8'));
      assert.equal(brief.generatedAt, GENERATED);
      assert.ok(readFileSync(out, 'utf8').endsWith('}\n'));
    } finally {
      cleanup();
    }
  });

  it('uses the current Berlin time when --generated-at is missing', () => {
    const { dir, cleanup } = tempDir();
    try {
      const section = makeSection('ki');
      const now = formatBerlin();
      for (const item of section.items) item.publishedAt = now;
      writeFileSync(path.join(dir, 'ki.json'), JSON.stringify(section));
      const out = path.join(dir, 'brief.json');
      const result = run('--sections', dir, '--teaser', 'Ein Satz.', '--out', out);
      assert.equal(result.status, 0, result.stdout + result.stderr);
      const brief = JSON.parse(readFileSync(out, 'utf8'));
      assert.match(brief.generatedAt, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\+0[12]:00$/);
      assert.equal(brief.date, brief.generatedAt.slice(0, 10));
    } finally {
      cleanup();
    }
  });

  it('exits 1 without writing for invalid input and 2 for usage errors', () => {
    const { dir, cleanup } = tempDir();
    try {
      const bad = makeSection('ki');
      bad.items[0].tier = 'oben';
      writeFileSync(path.join(dir, 'ki.json'), JSON.stringify(bad));
      const out = path.join(dir, 'brief.json');
      const result = run('--sections', dir, '--teaser', 'T.', '--out', out, '--generated-at', GENERATED);
      assert.equal(result.status, 1);
      assert.match(result.stderr, /ki\.json: items\[0\]\.tier/);
      assert.ok(!existsSync(out));

      assert.equal(run('--sections', dir, '--out', out).status, 2, 'teaser missing');
      assert.equal(run('--sections', dir, '--teaser', 'T.').status, 2, 'out missing');
      assert.equal(run('--sections', dir, '--teaser', 'T.', '--out', out, '--generated-at', 'jetzt').status, 2);
      assert.equal(run('--topic', 'ki').status, 2);
      assert.equal(run('--wat').status, 2);
      assert.equal(run('--help').status, 0);
    } finally {
      cleanup();
    }
  });

  it('prints the previous brief date from index.json', () => {
    const { dir, cleanup } = tempDir();
    try {
      const file = path.join(dir, 'index.json');
      writeFileSync(
        file,
        JSON.stringify({
          updatedAt: '2026-10-06T04:11:00+02:00',
          days: [
            { date: '2026-10-06', teaser: 'Heute.', itemCount: 20 },
            { date: '2026-10-05', teaser: 'Gestern.', itemCount: 30 },
          ],
        }),
      );
      const found = run('--previous-date', file, '--before', '2026-10-06');
      assert.equal(found.status, 0, found.stderr);
      assert.equal(found.stdout.trim(), '2026-10-05');

      const none = run('--previous-date', file, '--before', '2026-10-05');
      assert.equal(none.status, 0);
      assert.equal(none.stdout, '');

      const missing = run('--previous-date', path.join(dir, 'fehlt.json'), '--before', '2026-10-06');
      assert.equal(missing.status, 0);
      assert.equal(missing.stdout, '');

      writeFileSync(file, '{ kaputt');
      const broken = run('--previous-date', file, '--before', '2026-10-06');
      assert.equal(broken.status, 1);
      assert.equal(broken.stdout, '');

      assert.equal(run('--previous-date', file).status, 2, '--before missing');
      assert.equal(run('--previous-date', file, '--before', 'heute').status, 2);
      assert.equal(run('--before', '2026-10-06').status, 2);
    } finally {
      cleanup();
    }
  });

  it('shows an overview and copes with a missing previous brief', () => {
    const { dir, cleanup } = tempDir();
    try {
      const file = path.join(dir, 'latest.json');
      writeFileSync(file, JSON.stringify(makeBriefV2()));
      const shown = run('--overview', file, '--topic', 'wirtschaft');
      assert.equal(shown.status, 0);
      assert.match(shown.stdout, /## wirtschaft · 📈 Wirtschaft & Märkte/);
      assert.match(shown.stdout, /^Erstellt: 2026-10-06T05:07:12\+02:00$/m);
      assert.doesNotMatch(shown.stdout, /## ki/);
      const missing = run('--overview', path.join(dir, 'previous.json'));
      assert.equal(missing.status, 0);
      assert.match(missing.stdout, /Nichts gefunden/);
      writeFileSync(file, '{ kaputt');
      assert.equal(run('--overview', file).status, 1);
    } finally {
      cleanup();
    }
  });
});
