/**
 * No picture twice within 14 days: routine/recent-images.mjs (the pictures of the recent briefs,
 * from a directory or from the `briefs` branch of a git repository), find-image.mjs `--recent-days`
 * (hides those files, whatever their thumbnail size) and validate-brief.mjs `--no-repeat-days`
 * (reports them). No network: git repositories are created in temp directories and Commons is a
 * fake `fetch`.
 */
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { describe, it } from 'node:test';

import {
  RECENT_REASON,
  fallbackCandidates,
  loadFallbackCatalog,
  main as findImage,
  parseCandidates,
} from '../find-image.mjs';
import {
  DEFAULT_RECENT_DAYS,
  DEFAULT_RECENT_REF,
  briefImages,
  collectRecentImages,
  main as listRecent,
  parseCatFileBatch,
  previousDates,
} from '../recent-images.mjs';
import {
  berlinDate,
  imageFileKey,
  main as validate,
  validateBrief,
  validateDocument,
  validateSection,
} from '../validate-brief.mjs';
import { ROUTINE_DIR, clone, makeBriefV2, makeImage, makeSection, tempDir } from './fixtures.mjs';

const HAS_GIT = spawnSync('git', ['--version']).status === 0;
const FIND_IMAGE = path.join(ROUTINE_DIR, 'find-image.mjs');
const VALIDATE = path.join(ROUTINE_DIR, 'validate-brief.mjs');
const CATALOG = loadFallbackCatalog();

/** The same Commons file in another thumbnail size (the routine always uses 1280 px, older briefs may not). */
const smaller = (url) => url.replace('/1280px-', '/960px-');

/**
 * A schema-2 brief for `date` whose pictures are `files` (`{ section, n }` → `makeImage(n)` on the
 * first free article of that section). `sizes` maps a picture number to a thumbnail width.
 */
function briefWith(date, files, { sizes = {} } = {}) {
  const brief = makeBriefV2(date, ['ki', 'wirtschaft', 'region']);
  for (const section of brief.sections) for (const item of section.items) item.image = null;
  const next = new Map();
  for (const { section, n } of files) {
    const target = brief.sections.find((s) => s.id === section);
    const i = next.get(section) ?? 0;
    next.set(section, i + 1);
    const image = makeImage(n);
    if (sizes[n]) image.url = image.url.replace('/1280px-', `/${sizes[n]}px-`);
    target.items[i].image = image;
  }
  return brief;
}

/** Writes briefs into `<dir>/briefs/<date>.json` (the layout of a checkout of the `briefs` branch). */
function writeBriefs(dir, briefs) {
  mkdirSync(path.join(dir, 'briefs'), { recursive: true });
  for (const brief of briefs) writeFileSync(path.join(dir, 'briefs', `${brief.date}.json`), JSON.stringify(brief));
}

const capture = () => {
  const lines = { out: [], err: [] };
  return { lines, out: (s) => lines.out.push(s), err: (s) => lines.err.push(s) };
};

describe('previousDates', () => {
  it('counts back from the day before the brief, across month and year boundaries', () => {
    assert.deepEqual(previousDates('2026-10-17', 3), ['2026-10-16', '2026-10-15', '2026-10-14']);
    assert.deepEqual(previousDates('2026-03-01', 2), ['2026-02-28', '2026-02-27']);
    assert.deepEqual(previousDates('2024-03-01', 2), ['2024-02-29', '2024-02-28']);
    assert.deepEqual(previousDates('2027-01-02', 3), ['2027-01-01', '2026-12-31', '2026-12-30']);
    assert.equal(previousDates('2026-10-17', DEFAULT_RECENT_DAYS).length, 14);
    assert.throws(() => previousDates('gestern', 3), RangeError);
  });
});

describe('briefImages', () => {
  it('lists the article pictures of a brief or a single section with their file keys', () => {
    const brief = briefWith('2026-10-16', [{ section: 'ki', n: 1 }, { section: 'wirtschaft', n: 2 }]);
    const pictures = briefImages(brief);
    assert.equal(pictures.length, 2);
    assert.deepEqual(pictures[0].keys, ['bild 1.jpg']);
    assert.equal(pictures[0].file, 'Bild 1.jpg');
    assert.equal(pictures[0].section, 'ki');
    assert.equal(pictures[0].item, 'ki-meldung-1');
    assert.deepEqual(briefImages(brief.sections[1]).map((p) => p.file), ['Bild 2.jpg']);
    assert.deepEqual(briefImages(null), []);
    assert.deepEqual(briefImages({ sections: 'kaputt' }), []);
  });

  it('keys the url and the file page of one picture once and other hosts by their path', () => {
    const doc = { items: [{ id: 'a', image: { url: smaller(makeImage(5).url), sourceUrl: makeImage(5).sourceUrl } }, { id: 'b', image: { url: 'https://example.com/Foto.JPG?x=1' } }, { id: 'c', image: null }, { id: 'd' }] };
    const [commons, other] = briefImages(doc);
    assert.deepEqual(commons.keys, ['bild 5.jpg']);
    assert.deepEqual(other.keys, ['example.com/foto.jpg']);
  });
});

describe('parseCatFileBatch', () => {
  it('cuts `git cat-file --batch` output per object by byte size, with missing objects as null', () => {
    const text = 'Bäume → Wald';
    const body = Buffer.from(text);
    const output = Buffer.concat([
      Buffer.from('1111 blob 5\nhello\n'),
      Buffer.from('origin/briefs:briefs/2026-10-01.json missing\n'),
      Buffer.from(`2222 blob ${body.length}\n`),
      body,
      Buffer.from('\n'),
      Buffer.from('3333 tree 4\nabcd\n'),
    ]);
    assert.deepEqual(parseCatFileBatch(output, 4), ['hello', null, text, null]);
    assert.deepEqual(parseCatFileBatch(Buffer.alloc(0), 2), []);
  });
});

describe('collectRecentImages from a directory', () => {
  const FIXED_DATE = '2026-10-17';

  it('collects the pictures of the 14 days before the brief – not its own day, not older ones', () => {
    const { dir, cleanup } = tempDir();
    try {
      writeBriefs(dir, [
        briefWith('2026-10-16', [{ section: 'ki', n: 1 }, { section: 'wirtschaft', n: 2 }]),
        briefWith('2026-10-15', [{ section: 'region', n: 1 }, { section: 'ki', n: 3 }], { sizes: { 1: 960 } }),
        briefWith('2026-10-03', [{ section: 'ki', n: 4 }]), // 14th day before: still counts
        briefWith('2026-10-02', [{ section: 'ki', n: 5 }]), // 15th day: out
        briefWith('2026-10-17', [{ section: 'ki', n: 6 }]), // the brief's own day: replaced by this run
      ]);
      const result = collectRecentImages({ date: FIXED_DATE, days: 14, from: dir });
      assert.deepEqual([...result.images.keys()].sort(), ['bild 1.jpg', 'bild 2.jpg', 'bild 3.jpg', 'bild 4.jpg']);
      assert.deepEqual(result.dates, ['2026-10-16', '2026-10-15', '2026-10-03']);
      assert.equal(result.source, dir);
      assert.deepEqual(result.warnings, []);
      // The newest use is reported, and on how many days the file ran (960 px and 1280 px are one file).
      assert.deepEqual(result.images.get('bild 1.jpg'), {
        date: '2026-10-16',
        section: 'ki',
        file: 'Bild 1.jpg',
        url: makeImage(1).url,
        days: 2,
      });
      assert.equal(result.images.get('bild 2.jpg').section, 'wirtschaft');

      assert.ok(collectRecentImages({ date: FIXED_DATE, days: 15, from: dir }).images.has('bild 5.jpg'));
      assert.deepEqual([...collectRecentImages({ date: FIXED_DATE, days: 1, from: dir }).images.keys()].sort(), ['bild 1.jpg', 'bild 2.jpg']);
    } finally {
      cleanup();
    }
  });

  it('also reads <dir>/<date>.json, skips days without a brief and warns about broken ones', () => {
    const { dir, cleanup } = tempDir();
    try {
      writeFileSync(path.join(dir, '2026-10-16.json'), JSON.stringify(briefWith('2026-10-16', [{ section: 'ki', n: 7 }])));
      writeFileSync(path.join(dir, '2026-10-15.json'), '{ kaputt');
      writeFileSync(path.join(dir, '2026-10-14.json'), `﻿${JSON.stringify(briefWith('2026-10-14', [{ section: 'ki', n: 8 }]))}`);
      const result = collectRecentImages({ date: FIXED_DATE, days: 5, from: dir });
      assert.deepEqual([...result.images.keys()].sort(), ['bild 7.jpg', 'bild 8.jpg']);
      assert.deepEqual(result.dates, ['2026-10-16', '2026-10-14']);
      assert.equal(result.warnings.length, 1);
      assert.match(result.warnings[0], /Briefing 2026-10-15 .* kein gültiges JSON – übersprungen/);
    } finally {
      cleanup();
    }
  });

  it('answers with an empty history and a warning when there is nothing to read – never an error', () => {
    const { dir, cleanup } = tempDir();
    try {
      const empty = collectRecentImages({ date: FIXED_DATE, days: 14, from: dir });
      assert.equal(empty.images.size, 0);
      assert.deepEqual(empty.dates, []);
      assert.match(empty.warnings[0], /Keine Briefings der 14 Tage vor 2026-10-17 gefunden .* keine Bilder ausgeschlossen/);
      const missing = collectRecentImages({ date: FIXED_DATE, days: 14, from: path.join(dir, 'gibt-es-nicht') });
      assert.equal(missing.images.size, 0);
      assert.equal(missing.warnings.length, 1);
    } finally {
      cleanup();
    }
  });

  it('rejects a window outside 1–60 days', () => {
    for (const days of [0, -1, 61, 1.5, NaN, undefined]) {
      assert.throws(() => collectRecentImages({ date: FIXED_DATE, days, from: '.' }), RangeError, String(days));
    }
  });
});

describe('collectRecentImages from the briefs branch of a git repository', { skip: !HAS_GIT }, () => {
  /** Runs git in `dir` and returns stdout; fails the test on a non-zero exit. */
  function git(dir, ...args) {
    const result = spawnSync(
      'git',
      ['-C', dir, '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', '-c', 'commit.gpgsign=false', ...args],
      { encoding: 'utf8' },
    );
    assert.equal(result.status, 0, `git ${args.join(' ')}: ${result.stderr}`);
    return result.stdout;
  }

  /** A repository with an orphan `briefs` branch holding `briefs`; `origin/briefs` points at it like after `git fetch`. */
  function repoWithBriefs(dir, briefs) {
    git(dir, 'init', '-q');
    git(dir, 'symbolic-ref', 'HEAD', 'refs/heads/briefs');
    writeBriefs(dir, briefs);
    git(dir, 'add', 'briefs');
    git(dir, 'commit', '-q', '-m', 'Briefings');
    git(dir, 'update-ref', 'refs/remotes/origin/briefs', 'refs/heads/briefs');
  }

  it('reads origin/briefs by default, also pictures in another size, without touching the network', () => {
    const { dir, cleanup } = tempDir();
    try {
      repoWithBriefs(dir, [
        briefWith('2026-10-16', [{ section: 'ki', n: 1 }, { section: 'wirtschaft', n: 2 }], { sizes: { 2: 960 } }),
        briefWith('2026-10-14', [{ section: 'region', n: 3 }]),
        briefWith('2026-09-01', [{ section: 'ki', n: 9 }]),
      ]);
      assert.equal(DEFAULT_RECENT_REF, 'origin/briefs');
      const result = collectRecentImages({ date: '2026-10-17', days: 14, cwd: dir });
      assert.deepEqual([...result.images.keys()].sort(), ['bild 1.jpg', 'bild 2.jpg', 'bild 3.jpg']);
      assert.deepEqual(result.dates, ['2026-10-16', '2026-10-14']);
      assert.equal(result.source, 'origin/briefs');
      assert.deepEqual(result.warnings, []);
      // The history of an earlier date only sees the days before it.
      assert.deepEqual([...collectRecentImages({ date: '2026-10-15', days: 14, cwd: dir }).images.keys()], ['bild 3.jpg']);
      // The working tree is not read: a brief that only exists as a file does not count.
      writeFileSync(path.join(dir, 'briefs', '2026-10-13.json'), JSON.stringify(briefWith('2026-10-13', [{ section: 'ki', n: 4 }])));
      assert.ok(!collectRecentImages({ date: '2026-10-17', days: 14, cwd: dir }).images.has('bild 4.jpg'));
    } finally {
      cleanup();
    }
  });

  it('accepts another ref (--recent-ref) and reports an unknown or unsafe one as a hint', () => {
    const { dir, cleanup } = tempDir();
    try {
      repoWithBriefs(dir, [briefWith('2026-10-16', [{ section: 'ki', n: 1 }])]);
      const local = collectRecentImages({ date: '2026-10-17', days: 14, ref: 'briefs', cwd: dir });
      assert.deepEqual([...local.images.keys()], ['bild 1.jpg']);
      assert.equal(local.source, 'briefs');

      // No such branch and no remote to fetch it from: a hint, not a crash.
      const unknown = collectRecentImages({ date: '2026-10-17', days: 14, ref: 'origin/gibt-es-nicht', cwd: dir });
      assert.equal(unknown.images.size, 0);
      assert.match(unknown.warnings[0], /origin\/gibt-es-nicht .* keine Bilder ausgeschlossen/);
      const local404 = collectRecentImages({ date: '2026-10-17', days: 14, ref: 'kein-zweig', cwd: dir });
      assert.match(local404.warnings[0], /kein-zweig gibt es nicht – keine Bilder ausgeschlossen/);

      // Nothing git could read as an option or a revision expression is passed on.
      for (const ref of ['--upload-pack=x', '../evil', 'briefs~1', 'a b', '']) {
        const bad = collectRecentImages({ date: '2026-10-17', days: 14, ref, cwd: dir });
        assert.equal(bad.images.size, 0, ref);
        assert.match(bad.warnings[0], /kein gültiger Git-Stand/, ref);
      }
    } finally {
      cleanup();
    }
  });

  it('warns and goes on when the directory is no repository', () => {
    const { dir, cleanup } = tempDir();
    const ceiling = process.env.GIT_CEILING_DIRECTORIES;
    // Keep git from finding a repository further up (it would fetch from its remote).
    process.env.GIT_CEILING_DIRECTORIES = path.dirname(dir);
    try {
      const result = collectRecentImages({ date: '2026-10-17', days: 14, cwd: dir });
      assert.equal(result.images.size, 0);
      assert.equal(result.warnings.length, 1);
      assert.match(result.warnings[0], /keine Bilder ausgeschlossen/);
    } finally {
      if (ceiling === undefined) delete process.env.GIT_CEILING_DIRECTORIES;
      else process.env.GIT_CEILING_DIRECTORIES = ceiling;
      cleanup();
    }
  });

  it('find-image.mjs --recent-days reads origin/briefs of the repository it runs in', async () => {
    const { dir, cleanup } = tempDir();
    try {
      const [a, b, c] = fallbackCandidates(CATALOG, 'bundestag', { limit: 3 }).candidates;
      const brief = briefWith('2026-10-16', [{ section: 'ki', n: 1 }, { section: 'ki', n: 2 }]);
      brief.sections[0].items[0].image = { ...makeImage(1), url: smaller(a.url), sourceUrl: a.sourceUrl };
      brief.sections[0].items[1].image = { ...makeImage(2), url: b.url, sourceUrl: b.sourceUrl };
      repoWithBriefs(dir, [brief]);

      for (const extra of [[], ['--recent-ref', 'origin/briefs'], ['--recent-ref', 'briefs']]) {
        const io = capture();
        const argv = ['--fallback', 'bundestag', '--limit', '3', '--recent-days', '14', '--date', '2026-10-17', ...extra];
        assert.equal(await findImage(argv, { ...io, cwd: dir }), 0, extra.join(' '));
        const urls = JSON.parse(io.lines.out.join('\n')).map((x) => x.url);
        assert.ok(!urls.includes(a.url) && !urls.includes(b.url), extra.join(' '));
        assert.ok(urls.includes(c.url), extra.join(' '));
        assert.match(io.lines.err.join('\n'), /\(2 in den letzten 14 Tagen gelaufen\)/);
      }
    } finally {
      cleanup();
    }
  });

  it('both CLIs run end to end against the repository of their working directory', () => {
    const { dir, cleanup } = tempDir();
    try {
      repoWithBriefs(dir, [briefWith('2026-10-16', [{ section: 'ki', n: 2 }])]);
      const file = path.join(dir, 'neu.json');
      writeFileSync(file, JSON.stringify(makeBriefV2('2026-10-17')));
      const run = (script, args) => spawnSync(process.execPath, [script, ...args], { cwd: dir, encoding: 'utf8' });

      const repeated = run(VALIDATE, [file, '--no-repeat-days', '14']);
      assert.equal(repeated.status, 1, repeated.stdout + repeated.stderr);
      assert.match(repeated.stdout, /Bild „Bild 2\.jpg“ von Artikel "ki-meldung-2" lief schon am 2026-10-16 \(ki\) – bitte ein anderes wählen/);
      // A window of one day still reaches yesterday; three weeks later the 16th is long out of it.
      assert.equal(run(VALIDATE, [file, '--no-repeat-days', '1']).status, 1);
      writeFileSync(file, JSON.stringify(makeBriefV2('2026-11-06')));
      assert.equal(run(VALIDATE, [file, '--no-repeat-days', '14']).status, 0);

      const listed = run(path.join(ROUTINE_DIR, 'recent-images.mjs'), ['--date', '2026-10-17']);
      assert.equal(listed.status, 0, listed.stderr);
      assert.match(listed.stdout, /^14 Tage vor 2026-10-17: 1 Briefing \(origin\/briefs\), 1 Bild\n/);
      assert.match(listed.stdout, /2026-10-16 {2}ki +1× {2}Bild 2\.jpg/);

      const search = run(FIND_IMAGE, ['--fallback', 'bundestag', '--limit', '1', '--recent-days', '14', '--date', '2026-10-17']);
      assert.equal(search.status, 0, search.stderr);
      assert.equal(JSON.parse(search.stdout).length, 1);
    } finally {
      cleanup();
    }
  });
});

describe('find-image: the same file in another thumbnail size is out', () => {
  /** A Commons API search page for "File:<name>.jpg" in the shape pageToCandidate reads. */
  function searchPage(id, name) {
    const file = `${name.replace(/ /g, '_')}.jpg`;
    return {
      pageid: id,
      ns: 6,
      title: `File:${name}.jpg`,
      index: id,
      imageinfo: [
        {
          width: 4000,
          height: 3000,
          thumburl: `https://thumb.wikimedia.org/wikipedia/commons/thumb/a/ab/${file}/1280px-${file}?utm_source=commons.wikimedia.org`,
          thumbwidth: 1280,
          thumbheight: 960,
          url: `https://upload.wikimedia.org/wikipedia/commons/a/ab/${file}`,
          descriptionurl: `https://commons.wikimedia.org/wiki/File:${file}`,
          extmetadata: {
            Artist: { value: 'Erika Mustermann' },
            LicenseShortName: { value: 'CC BY-SA 4.0' },
            ImageDescription: { value: `Beschreibung ${name}` },
          },
        },
      ],
    };
  }
  const response = (...pages) => ({ batchcomplete: '', query: { pages: Object.fromEntries(pages.map((p) => [p.pageid, p])) } });

  it('parseCandidates drops a recently used file whatever size, original or file page the brief held', () => {
    const json = response(searchPage(1, 'Reichstag Nacht'), searchPage(2, 'Reichstag Tag'), searchPage(3, 'Bundestag Saal'));
    const used = (...urls) => new Set(urls.map((u) => imageFileKey(u)));
    const sizeOf = (name, px) => `https://upload.wikimedia.org/wikipedia/commons/thumb/f/fd/${name}.jpg/${px}px-${name}.jpg`;

    const none = parseCandidates(json, { limit: 5 });
    assert.equal(none.candidates.length, 3);
    assert.deepEqual(none.rejected, {});

    const recent = used(sizeOf('Reichstag_Nacht', 960), 'https://commons.wikimedia.org/wiki/File:reichstag_tag.jpg');
    const result = parseCandidates(json, { limit: 5, recent });
    assert.deepEqual(result.candidates.map((c) => c.title), ['File:Bundestag Saal.jpg']);
    assert.deepEqual(result.rejected, { [RECENT_REASON]: 2 });
    assert.equal(RECENT_REASON, 'lief kürzlich');

    // The limit counts what is left, not what was found.
    const limited = parseCandidates(json, { limit: 1, recent: used(sizeOf('Reichstag_Nacht', 320)) });
    assert.deepEqual(limited.candidates.map((c) => c.title), ['File:Reichstag Tag.jpg']);
  });

  it('fallbackCandidates skips recently used catalogue pictures – by url in any size or by file page', () => {
    const catalog = { topics: { politik: CATALOG.topics.politik }, subjects: { bundestag: CATALOG.subjects.bundestag } };
    const all = fallbackCandidates(catalog, 'bundestag', { limit: 50 });
    assert.equal(all.recentlyUsed, 0);
    assert.equal(all.candidates.length, CATALOG.subjects.bundestag.images.length + CATALOG.topics.politik.length);

    const [own1, own2] = CATALOG.subjects.bundestag.images;
    const topic1 = CATALOG.topics.politik[0];
    const recent = new Set([
      imageFileKey(smaller(own1.url)),
      imageFileKey(own2.sourceUrl),
      imageFileKey(topic1.url.replace('/1280px-', '/330px-')),
    ]);
    const result = fallbackCandidates(catalog, 'bundestag', { limit: 50, recent });
    assert.equal(result.recentlyUsed, 3);
    assert.equal(result.excluded, 0);
    assert.equal(result.candidates.length, all.candidates.length - 3);
    const urls = result.candidates.map((c) => c.url);
    for (const gone of [own1.url, own2.url, topic1.url]) assert.ok(!urls.includes(gone), gone);

    // "Already in this brief" and "ran recently" are counted apart.
    const both = fallbackCandidates(catalog, 'bundestag', { limit: 50, recent, exclude: [CATALOG.topics.politik[1].url] });
    assert.equal(both.excluded, 1);
    assert.equal(both.recentlyUsed, 3);
  });

  it('--fallback --recent-days leaves out what ran in the last days and says how many', async () => {
    const { dir, cleanup } = tempDir();
    try {
      const [a, b, c] = fallbackCandidates(CATALOG, 'bundestag', { limit: 3 }).candidates;
      const brief = briefWith('2026-10-15', [{ section: 'ki', n: 1 }, { section: 'ki', n: 2 }, { section: 'wirtschaft', n: 3 }]);
      brief.sections[0].items[0].image = { ...makeImage(1), url: smaller(a.url), sourceUrl: a.sourceUrl };
      brief.sections[0].items[1].image = { ...makeImage(2), url: b.url.replace('/1280px-', '/500px-'), sourceUrl: b.sourceUrl };
      writeBriefs(dir, [brief]);

      const io = capture();
      const code = await findImage(
        ['--fallback', 'bundestag', '--limit', '3', '--recent-days', '14', '--date', '2026-10-17', '--recent-from', dir],
        io,
      );
      assert.equal(code, 0);
      const urls = JSON.parse(io.lines.out.join('\n')).map((x) => x.url);
      assert.equal(urls.length, 3);
      assert.ok(!urls.includes(a.url) && !urls.includes(b.url));
      assert.ok(urls.includes(c.url));
      assert.match(io.lines.err.join('\n'), /3 Katalogbilder für Motiv „bundestag“ .*\(2 in den letzten 14 Tagen gelaufen\)/);

      // Without --recent-days nothing is hidden.
      const plain = capture();
      assert.equal(await findImage(['--fallback', 'bundestag', '--limit', '3'], plain), 0);
      assert.deepEqual(JSON.parse(plain.lines.out.join('\n')).map((x) => x.url), [a.url, b.url, c.url]);
    } finally {
      cleanup();
    }
  });

  it('a search with --recent-days drops the files of the last days and names the reason', async () => {
    const { dir, cleanup } = tempDir();
    try {
      writeBriefs(dir, [briefWith('2026-10-16', [{ section: 'ki', n: 11 }], { sizes: { 11: 960 } })]);
      const fetchImpl = async () => ({
        status: 200,
        ok: true,
        headers: { get: () => null },
        json: async () => response(searchPage(1, 'Bild 11'), searchPage(2, 'Bild 12')),
      });
      const io = capture();
      const code = await findImage(['Reichstag', '--recent-days', '14', '--date', '2026-10-17', '--recent-from', dir], { ...io, fetchImpl, retryDelayMs: 0 });
      assert.equal(code, 0);
      assert.deepEqual(JSON.parse(io.lines.out.join('\n')).map((x) => x.title), ['File:Bild 12.jpg']);
      assert.match(io.lines.err.join('\n'), /1× lief kürzlich/);

      // A history that cannot be read only produces a hint – the search itself goes on.
      const hint = capture();
      const missing = path.join(dir, 'gibt-es-nicht');
      assert.equal(await findImage(['Reichstag', '--recent-days', '14', '--date', '2026-10-17', '--recent-from', missing], { ...hint, fetchImpl, retryDelayMs: 0 }), 0);
      assert.equal(JSON.parse(hint.lines.out.join('\n')).length, 2);
      assert.match(hint.lines.err.join('\n'), /Hinweis: Keine Briefings der 14 Tage vor 2026-10-17/);
    } finally {
      cleanup();
    }
  });

  it('rejects wrong --recent-days combinations with exit code 2', async () => {
    const noFetch = async () => {
      throw new Error('kein Netz nötig');
    };
    const NEEDS_DAYS = /^--recent-from, --recent-ref und --date gelten nur zusammen mit --recent-days/;
    for (const [argv, problem] of [
      [['Reichstag', '--recent-days', '0'], /^--recent-days braucht eine ganze Zahl von 1 bis 60 \(gefunden: 0\)/],
      [['Reichstag', '--recent-days', 'viele'], /^--recent-days braucht eine ganze Zahl von 1 bis 60 \(gefunden: viele\)/],
      [['Reichstag', '--recent-days', '61'], /^--recent-days braucht eine ganze Zahl von 1 bis 60/],
      [['Reichstag', '--recent-days'], /^--recent-days braucht eine ganze Zahl von 1 bis 60 \(gefunden: nichts\)/],
      [['Reichstag', '--recent-from', '.'], NEEDS_DAYS],
      [['Reichstag', '--recent-ref', 'origin/briefs'], NEEDS_DAYS],
      [['Reichstag', '--date', '2026-10-17'], NEEDS_DAYS],
      [['Reichstag', '--recent-days', '14', '--date', 'heute'], /^--date braucht ein Datum JJJJ-MM-TT \(gefunden: heute\)/],
      [['Reichstag', '--recent-days', '14', '--recent-from', '.', '--recent-ref', 'origin/briefs'], /^--recent-from und --recent-ref schließen sich gegenseitig aus/],
      [['--list-fallbacks', '--recent-days', '14'], /^--recent-days gilt nur für die Suche und --fallback/],
      [['--verify-fallbacks', '--recent-days', '14'], /^--recent-days gilt nur für die Suche und --fallback/],
    ]) {
      const io = capture();
      assert.equal(await findImage(argv, { ...io, fetchImpl: noFetch }), 2, argv.join(' '));
      assert.match(io.lines.err.join('\n'), problem, argv.join(' '));
    }
    const help = capture();
    assert.equal(await findImage(['--help'], help), 0);
    for (const flag of ['--recent-days', '--recent-from', '--recent-ref', '--date']) assert.ok(help.lines.out.join('\n').includes(flag), flag);
  });
});

describe('validate-brief: --no-repeat-days', () => {
  const KEY2 = imageFileKey(makeImage(2).url);
  const recent = (date = '2026-10-05', section = 'ki') => new Map([[KEY2, { date, section }]]);

  it('reports a picture of the last days as an error that names file, article, day and section', () => {
    const brief = makeBriefV2('2026-10-06'); // pictures 2, 4 and 8
    const result = validateBrief(brief, { recentImages: recent() });
    assert.deepEqual(result.errors, [
      'sections[0].items[1].image.url: Bild „Bild 2.jpg“ von Artikel "ki-meldung-2" lief schon am 2026-10-05 (ki) – bitte ein anderes wählen',
    ]);
    assert.deepEqual(result.warnings, []);

    // Without a history – or with an empty one – the brief is as valid as before.
    assert.deepEqual(validateBrief(brief).errors, []);
    assert.deepEqual(validateBrief(brief, { recentImages: new Map() }).errors, []);
  });

  it('recognises the file in any thumbnail size and by its file page', () => {
    const smallerImage = makeBriefV2('2026-10-06');
    smallerImage.sections[0].items[1].image.url = smaller(smallerImage.sections[0].items[1].image.url);
    assert.equal(validateBrief(smallerImage, { recentImages: recent() }).errors.length, 1);

    const pageOnly = makeBriefV2('2026-10-06');
    pageOnly.sections[0].items[1].image.url = makeImage(99).url; // a different file …
    pageOnly.sections[0].items[1].image.sourceUrl = makeImage(2).sourceUrl; // … but the file page of the old one
    const result = validateBrief(pageOnly, { recentImages: recent('2026-09-30', 'wirtschaft') });
    assert.equal(result.errors.length, 1);
    assert.match(result.errors[0], /lief schon am 2026-09-30 \(wirtschaft\) – bitte ein anderes wählen$/);
  });

  it('omits the section when the history does not know it, and every repeated picture is its own error', () => {
    const brief = makeBriefV2('2026-10-06');
    const history = new Map([
      [KEY2, { date: '2026-10-05' }],
      [imageFileKey(makeImage(4).url), { date: '2026-10-01', section: '' }],
      [imageFileKey(makeImage(8).url), { date: '2026-09-30', section: 'region' }],
    ]);
    const { errors } = validateBrief(brief, { recentImages: history });
    assert.equal(errors.length, 3);
    assert.match(errors[0], /von Artikel "ki-meldung-2" lief schon am 2026-10-05 – bitte/);
    assert.match(errors[1], /sections\[1\]\.items\[0\]\.image\.url: Bild „Bild 4\.jpg“ .* lief schon am 2026-10-01 – bitte/);
    assert.match(errors[2], /lief schon am 2026-09-30 \(region\) – bitte/);
  });

  it('checks single sections the same way (items[…] without the section prefix)', () => {
    const section = makeSection('ki');
    assert.deepEqual(validateSection(section, { recentImages: recent() }).errors, [
      'items[1].image.url: Bild „Bild 2.jpg“ von Artikel "ki-meldung-2" lief schon am 2026-10-05 (ki) – bitte ein anderes wählen',
    ]);
    assert.deepEqual(validateSection(clone(section), {}).errors, []);
  });

  it('asks the history for the date of the brief – for a section the Berlin date of "now" – and passes its hints on', () => {
    const dates = [];
    const history = (date) => {
      dates.push(date);
      return { images: recent(), warnings: ['origin/briefs gibt es nicht – keine Bilder ausgeschlossen'] };
    };
    const brief = validateDocument(makeBriefV2('2026-10-06'), { history });
    assert.equal(brief.kind, 'brief');
    assert.equal(brief.errors.length, 1);
    assert.deepEqual(brief.warnings, ['origin/briefs gibt es nicht – keine Bilder ausgeschlossen']);

    // 23:30 UTC on the 5th is already the 6th in Berlin (summer time).
    const now = Date.parse('2026-10-05T23:30:00Z');
    const section = validateDocument(makeSection('ki', { date: '2026-10-05' }), { section: true, now, history });
    assert.equal(section.kind, 'section');
    assert.equal(section.errors.length, 1);
    assert.equal(berlinDate(now), '2026-10-06');
    assert.deepEqual(dates, ['2026-10-06', '2026-10-06']);

    // index.json has no pictures: the history is not even read.
    validateDocument({ schema: 1, days: [] }, { history });
    assert.equal(dates.length, 2);
  });

  describe('CLI', () => {
    /** Runs the validator on `docs` (name → document) with `argv` and returns the exit code and the lines. */
    async function run(docs, argv, { history = [] } = {}) {
      const { dir, cleanup } = tempDir();
      try {
        const past = path.join(dir, 'past');
        writeBriefs(past, history);
        const files = Object.entries(docs).map(([name, doc]) => {
          const file = path.join(dir, name);
          writeFileSync(file, JSON.stringify(doc));
          return file;
        });
        const io = capture();
        const code = await validate([...files, ...argv.map((a) => (a === '<past>' ? past : a))], io.out, io.err);
        return { code, out: io.lines.out.join('\n'), err: io.lines.err.join('\n') };
      } finally {
        cleanup();
      }
    }

    it('fails a brief with a repeated picture and passes one without (exit 1 / 0)', async () => {
      const brief = makeBriefV2('2026-10-06'); // pictures 2, 4, 8
      const yesterday = briefWith('2026-10-05', [{ section: 'ki', n: 2 }, { section: 'region', n: 77 }], { sizes: { 2: 960 } });
      const repeated = await run({ 'brief.json': brief }, ['--no-repeat-days', '14', '--recent-from', '<past>'], { history: [yesterday] });
      assert.equal(repeated.code, 1);
      assert.match(repeated.out, /FEHLER .*brief\.json .* 1 Fehler/);
      assert.match(repeated.out, /Fehler:  sections\[0\]\.items\[1\]\.image\.url: Bild „Bild 2\.jpg“ von Artikel "ki-meldung-2" lief schon am 2026-10-05 \(ki\) – bitte ein anderes wählen/);

      const fresh = await run({ 'brief.json': brief }, ['--no-repeat-days=14', '--recent-from', '<past>'], {
        history: [briefWith('2026-10-05', [{ section: 'ki', n: 50 }])],
      });
      assert.equal(fresh.code, 0);
      assert.match(fresh.out, /^OK .*brief\.json/);

      // Without the option the same repeat is not looked for.
      const off = await run({ 'brief.json': brief }, [], { history: [yesterday] });
      assert.equal(off.code, 0);
    });

    it('counts only the last N days, and never the brief\'s own day', async () => {
      const brief = makeBriefV2('2026-10-06');
      const old = briefWith('2026-09-20', [{ section: 'ki', n: 2 }]); // 16 days before
      const sameDay = briefWith('2026-10-06', [{ section: 'ki', n: 2 }, { section: 'wirtschaft', n: 4 }]);
      const argv = ['--no-repeat-days', '14', '--recent-from', '<past>'];
      const out = await run({ 'brief.json': brief }, argv, { history: [old, sameDay] });
      assert.equal(out.code, 0, out.out);
      const wide = await run({ 'brief.json': brief }, ['--no-repeat-days', '30', '--recent-from', '<past>'], { history: [old, sameDay] });
      assert.equal(wide.code, 1);
      assert.match(wide.out, /lief schon am 2026-09-20/);
    });

    it('checks each file against the days before its own date and shows the history hint once', async () => {
      const a = makeBriefV2('2026-10-06');
      const b = makeBriefV2('2026-10-07');
      const history = [briefWith('2026-10-06', [{ section: 'ki', n: 2 }])]; // yesterday for the 7th, the 6th itself for a
      const result = await run({ 'a.json': a, 'b.json': b }, ['--no-repeat-days', '3', '--recent-from', '<past>'], { history });
      assert.equal(result.code, 1);
      assert.match(result.out, /OK .*a\.json/);
      assert.match(result.out, /FEHLER .*b\.json .* 1 Fehler/);
      assert.match(result.out, /1 von 2 Dateien ungültig\./);
    });

    it('validates sections with --now against the Berlin date of that moment', async () => {
      const history = [briefWith('2026-10-05', [{ section: 'ki', n: 2 }])];
      const common = ['--section', '--no-repeat-days', '14', '--recent-from', '<past>', '--now', '2026-10-06T05:00:00+02:00'];
      const hit = await run({ 'ki.json': makeSection('ki') }, common, { history });
      assert.equal(hit.code, 1);
      assert.match(hit.out, /Fehler:  items\[1\]\.image\.url: Bild „Bild 2\.jpg“ von Artikel "ki-meldung-2" lief schon am 2026-10-05 \(ki\)/);
      // Run on the 5th itself, the 5th is not "before".
      const sameDay = await run({ 'ki.json': makeSection('ki', { date: '2026-10-05' }) }, ['--section', '--no-repeat-days', '14', '--recent-from', '<past>', '--now', '2026-10-05T05:00:00+02:00'], { history });
      assert.equal(sameDay.code, 0, sameDay.out);
    });

    it('warns (exit 0) when the history cannot be read – a missing history must never stop the run', async () => {
      const result = await run({ 'brief.json': makeBriefV2('2026-10-06') }, ['--no-repeat-days', '14', '--recent-from', '<past>']);
      assert.equal(result.code, 0);
      assert.match(result.out, /Hinweis: Keine Briefings der 14 Tage vor 2026-10-06 gefunden .* keine Bilder ausgeschlossen/);
    });

    it('rejects wrong options with exit code 2', async () => {
      const brief = { 'brief.json': makeBriefV2('2026-10-06') };
      for (const argv of [
        ['--no-repeat-days', '0'],
        ['--no-repeat-days', 'x'],
        ['--no-repeat-days', '61'],
        ['--no-repeat-days'],
        ['--recent-from', '<past>'],
        ['--recent-ref', 'origin/briefs'],
        ['--no-repeat-days', '14', '--recent-from', '<past>', '--recent-ref', 'origin/briefs'],
        ['--no-repeat-days', '14', '--recent-ref'],
      ]) {
        const result = await run(brief, argv);
        assert.equal(result.code, 2, argv.join(' '));
        assert.match(result.err, /--no-repeat-days|--recent-/, argv.join(' '));
      }
      const help = capture();
      assert.equal(await validate(['--help'], help.out, help.err), 0);
      const text = help.lines.out.join('\n');
      for (const flag of ['--no-repeat-days', '--recent-ref', '--recent-from']) assert.ok(text.includes(flag), flag);
    });
  });
});

describe('recent-images CLI', () => {
  it('lists the pictures of the recent briefs, most frequent first', () => {
    const { dir, cleanup } = tempDir();
    try {
      writeBriefs(dir, [
        briefWith('2026-10-16', [{ section: 'ki', n: 1 }, { section: 'wirtschaft', n: 2 }]),
        briefWith('2026-10-15', [{ section: 'ki', n: 1 }], { sizes: { 1: 960 } }),
      ]);
      const io = capture();
      assert.equal(listRecent(['--date', '2026-10-17', '--days', '14', '--from', dir], io), 0);
      const lines = io.lines.out;
      assert.match(lines[0], /^14 Tage vor 2026-10-17: 2 Briefings .*, 2 Bilder$/);
      assert.match(lines[1], /2026-10-16 {2}ki +2× {2}Bild 1\.jpg/);
      assert.match(lines[2], /2026-10-16 {2}wirtschaft +1× {2}Bild 2\.jpg/);
    } finally {
      cleanup();
    }
  });

  it('rejects wrong options with exit code 2', () => {
    for (const argv of [['--days', '0'], ['--days', 'x'], ['--date', 'gestern'], ['--ref', 'a', '--from', 'b'], ['--wat'], ['--from']]) {
      const io = capture();
      assert.equal(listRecent(argv, io), 2, argv.join(' '));
    }
    assert.equal(listRecent(['--help'], capture()), 0);
  });
});
