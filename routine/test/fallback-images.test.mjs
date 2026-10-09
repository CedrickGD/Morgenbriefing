/**
 * Tests for the fallback catalogue routine/fallback-images.json and the fallback mode of
 * routine/find-image.mjs. No network: Commons is replaced by a fake `fetch` built from the
 * catalogue itself (the live check is `node routine/find-image.mjs --verify-fallbacks`).
 */
import assert from 'node:assert/strict';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { describe, it } from 'node:test';

import {
  CATALOG_LIMITS,
  CatalogError,
  CommonsError,
  FALLBACK_CATALOG_PATH,
  UnknownFallbackError,
  buildFileInfoUrl,
  catalogEntries,
  catalogProblems,
  fallbackCandidates,
  loadFallbackCatalog,
  main,
  normalizeKey,
  resolveFallbackKey,
  splitExcludeList,
  usedImagesIn,
  verifyFallbackCatalog,
} from '../find-image.mjs';
import { TOPICS, commonsFileKey, validateSection } from '../validate-brief.mjs';
import { clone, makeSection, tempDir } from './fixtures.mjs';

const CATALOG = loadFallbackCatalog();

/** A catalogue picture as find-image.mjs would deliver it for "File:<name>.jpg". */
function entry(name, alt = `Symbolbild ${name}`) {
  const file = `${name.replace(/ /g, '_')}.jpg`;
  return {
    title: `File:${name}.jpg`,
    url: `https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/${encodeURIComponent(file)}/1280px-${encodeURIComponent(file)}`,
    credit: 'Erika Mustermann',
    license: 'CC BY-SA 4.0',
    sourceUrl: `https://commons.wikimedia.org/wiki/File:${encodeURIComponent(file)}`,
    alt,
    width: 1280,
    height: 853,
    verifiedAt: '2026-10-09',
  };
}

/** A small, valid catalogue: every topic with 4 pictures, politik with three subjects. */
function smallCatalog() {
  const topics = Object.fromEntries(TOPICS.map((t) => [t.id, [1, 2, 3, 4].map((n) => entry(`${t.id} ${n}`))]));
  return {
    schema: 1,
    verifiedAt: '2026-10-09',
    topics,
    subjects: {
      bundestag: {
        topic: 'politik',
        label: 'Bundestag',
        aliases: ['reichstag'],
        images: [entry('Bundestag 1'), entry('Bundestag 2')],
      },
      kanzleramt: { topic: 'politik', label: 'Kanzleramt', images: [entry('Kanzleramt 1')] },
      windpark: { topic: 'klima', label: 'Windpark', aliases: ['windkraft'], images: [entry('Windpark 1')] },
    },
  };
}

describe('fallback catalogue (routine/fallback-images.json)', () => {
  it('follows all catalogue rules', () => {
    assert.deepEqual(catalogProblems(CATALOG), []);
    assert.equal(CATALOG.verifiedAt, '2026-10-09');
  });

  it('has 4–8 pictures for every topic of the app and 1–3 per subject', () => {
    assert.deepEqual(Object.keys(CATALOG.topics), TOPICS.map((t) => t.id));
    for (const [id, images] of Object.entries(CATALOG.topics)) {
      assert.ok(images.length >= CATALOG_LIMITS.topicImagesMin && images.length <= CATALOG_LIMITS.topicImagesMax, id);
    }
    const subjects = Object.entries(CATALOG.subjects);
    assert.ok(subjects.length >= 40, `${subjects.length} Motive`);
    for (const [key, subject] of subjects) {
      assert.ok(subject.images.length >= 1 && subject.images.length <= 3, key);
    }
    for (const topic of TOPICS) {
      assert.ok(subjects.some(([, s]) => s.topic === topic.id), `Rubrik ${topic.id} ohne Motive`);
    }
  });

  it('knows the recurring subjects of the routine', () => {
    for (const key of ['bundestag', 'kanzleramt', 'eu-parlament', 'boerse-frankfurt', 'ezb', 'leipzig-skyline',
      'leipzig-hbf', 'bad-dueben', 'nordsachsen', 'fussball-stadion', 'rb-leipzig-arena', 'handball', 'labor',
      'raumfahrt-rakete', 'krankenhaus', 'apotheke', 'windpark', 'solar', 'braunkohle', 'hochwasser', 'wald',
      'rechenzentrum', 'smartphone', 'chip', 'roboter', 'gaming-controller', 'kino', 'konzert', 'ukraine-kyjiw',
      'washington-capitol', 'un-new-york', 'nahost', 'Kiew', 'Leipzig Hbf', 'DAX']) {
      assert.notEqual(resolveFallbackKey(CATALOG, key), null, key);
    }
  });

  it('only holds 1280 px landscape thumbnails that pass the validator as article images', () => {
    for (const { where, entry: e } of catalogEntries(CATALOG)) {
      assert.match(e.url, /^https:\/\/upload\.wikimedia\.org\/wikipedia\/commons\/thumb\/.+\/1280px-/, where);
      assert.equal(e.width, 1280, where);
      assert.ok(e.height < e.width && e.height >= 400, where);
      assert.ok(e.verifiedAt <= CATALOG.verifiedAt, where);
      assert.doesNotMatch(e.title, /logo/i, where);
      const section = makeSection('ki');
      for (const item of section.items) item.image = null;
      section.items[0].image = { url: e.url, credit: e.credit, license: e.license, sourceUrl: e.sourceUrl, alt: e.alt };
      const result = validateSection(section, { requireImages: false });
      assert.deepEqual(result.errors, [], where);
      assert.deepEqual(result.warnings.filter((w) => w.includes('image')), [], where);
    }
  });
});

describe('catalogProblems', () => {
  it('accepts a minimal valid catalogue', () => {
    assert.deepEqual(catalogProblems(smallCatalog()), []);
  });

  it('reports structural problems', () => {
    const c = smallCatalog();
    c.schema = 2;
    delete c.verifiedAt;
    delete c.topics.sport;
    c.topics.ki.splice(1);
    c.subjects.kanzleramt.topic = 'innenpolitik';
    c.subjects.windpark.aliases = ['reichstag'];
    c.subjects['Bad Düben'] = { topic: 'region', label: '', images: [] };
    const problems = catalogProblems(c).join('\n');
    assert.match(problems, /schema: muss 1 sein/);
    assert.match(problems, /verifiedAt: Datum/);
    assert.match(problems, /topics\.sport: fehlt/);
    assert.match(problems, /topics\.ki: 4–8 Bilder vorgesehen \(gefunden: 1\)/);
    assert.match(problems, /subjects\.kanzleramt\.topic: "innenpolitik" ist keine Rubrik/);
    assert.match(problems, /subjects\.windpark\.aliases: "reichstag" ist schon subjects\.bundestag\.aliases/);
    assert.match(problems, /subjects\.Bad Düben: Schlüssel nur aus Kleinbuchstaben/);
    assert.match(problems, /subjects\.Bad Düben\.label: fehlt/);
    assert.match(problems, /subjects\.Bad Düben\.images: 1–3 Bilder vorgesehen/);
    assert.deepEqual(catalogProblems(null), ['Der Katalog muss ein JSON-Objekt sein.']);
  });

  it('applies the license and picture rules to every entry', () => {
    const c = smallCatalog();
    const [a, b, d, e] = c.topics.ki;
    a.license = 'CC BY-NC-SA 4.0';
    delete b.credit;
    d.alt = ' ';
    d.width = 800;
    e.height = 1600;
    c.topics.wirtschaft[0].url = c.topics.wirtschaft[0].url.replace('/1280px-', '/960px-');
    c.topics.wirtschaft[1].sourceUrl = c.topics.wirtschaft[2].sourceUrl;
    c.topics.wirtschaft[3].verifiedAt = 'gestern';
    c.topics.politik[0] = clone(c.topics.ki[2]);
    const problems = catalogProblems(c).join('\n');
    assert.match(problems, /topics\.ki\[0\]\.license: "CC BY-NC-SA 4\.0" ist keine erlaubte freie Lizenz/);
    assert.match(problems, /topics\.ki\[1\]\.credit: fehlt – CC BY-SA 4\.0 verlangt den Urheber/);
    assert.match(problems, /topics\.ki\[2\]\.alt:/);
    assert.match(problems, /topics\.ki\[2\]\.width: mindestens 1200 px/);
    assert.match(problems, /topics\.ki\[3\]: nur Querformat/);
    assert.match(problems, /topics\.wirtschaft\[0\]\.url: muss das 1280-px-Vorschaubild/);
    assert.match(problems, /topics\.wirtschaft\[1\]: title, url und sourceUrl zeigen nicht auf dieselbe Datei/);
    assert.match(problems, /topics\.wirtschaft\[3\]\.verifiedAt/);
    assert.match(problems, /topics\.politik\[0\]: dieselbe Datei steht schon in topics\.ki\[2\]/);
  });

  it('allows public-domain pictures without credit', () => {
    const c = smallCatalog();
    c.topics.ki[0].license = 'Public domain';
    delete c.topics.ki[0].credit;
    assert.deepEqual(catalogProblems(c), []);
  });
});

describe('loadFallbackCatalog', () => {
  it('reads the catalogue next to the script by default', () => {
    assert.ok(FALLBACK_CATALOG_PATH.endsWith(`${path.sep}fallback-images.json`));
    assert.equal(loadFallbackCatalog(FALLBACK_CATALOG_PATH).schema, 1);
  });

  it('rejects missing, broken and invalid catalogues', () => {
    const { dir, cleanup } = tempDir();
    try {
      assert.throws(() => loadFallbackCatalog(path.join(dir, 'fehlt.json')), (e) => e instanceof CatalogError && /nicht lesbar/.test(e.message));
      writeFileSync(path.join(dir, 'kaputt.json'), '{ "schema": 1,');
      assert.throws(() => loadFallbackCatalog(path.join(dir, 'kaputt.json')), CatalogError);
      const invalid = smallCatalog();
      invalid.topics.ki = [];
      writeFileSync(path.join(dir, 'ungueltig.json'), JSON.stringify(invalid));
      assert.throws(() => loadFallbackCatalog(path.join(dir, 'ungueltig.json')), (e) => e instanceof CatalogError && /ungültig \(1 Problem/.test(e.message));
    } finally {
      cleanup();
    }
  });
});

describe('normalizeKey and resolveFallbackKey', () => {
  it('turns free text into catalogue keys', () => {
    assert.equal(normalizeKey('Leipzig Hbf'), 'leipzig-hbf');
    assert.equal(normalizeKey('  Börse  '), 'boerse');
    assert.equal(normalizeKey('Straßenbahn/ÖPNV'), 'strassenbahn-oepnv');
    assert.equal(normalizeKey('Kyjiw'), 'kyjiw');
    assert.equal(normalizeKey('Élysée'), 'elysee');
    assert.equal(normalizeKey(undefined), '');
  });

  it('finds topics, subjects and aliases', () => {
    const c = smallCatalog();
    assert.deepEqual(resolveFallbackKey(c, 'Politik'), { kind: 'rubrik', key: 'politik', topic: 'politik' });
    assert.deepEqual(resolveFallbackKey(c, 'bundestag'), { kind: 'motiv', key: 'bundestag', topic: 'politik' });
    assert.deepEqual(resolveFallbackKey(c, 'Reichstag'), { kind: 'motiv', key: 'bundestag', topic: 'politik' });
    assert.equal(resolveFallbackKey(c, 'mond'), null);
    assert.equal(resolveFallbackKey(c, '  '), null);
  });
});

describe('fallbackCandidates', () => {
  const titles = (result) => result.candidates.map((c) => c.title.replace(/^File:|\.jpg$/g, ''));

  it('lists the subject, then its topic, then the other subjects of the topic', () => {
    const result = fallbackCandidates(smallCatalog(), 'bundestag', { limit: 20 });
    assert.equal(result.kind, 'motiv');
    assert.equal(result.topic, 'politik');
    assert.deepEqual(titles(result), ['Bundestag 1', 'Bundestag 2', 'politik 1', 'politik 2', 'politik 3', 'politik 4', 'Kanzleramt 1']);
    assert.deepEqual(result.candidates.map((c) => c.fallback), [
      'motiv:bundestag', 'motiv:bundestag', 'rubrik:politik', 'rubrik:politik', 'rubrik:politik', 'rubrik:politik', 'motiv:kanzleramt',
    ]);
  });

  it('lists a topic before the pictures of its subjects', () => {
    const result = fallbackCandidates(smallCatalog(), 'klima', { limit: 20 });
    assert.equal(result.kind, 'rubrik');
    assert.deepEqual(titles(result), ['klima 1', 'klima 2', 'klima 3', 'klima 4', 'Windpark 1']);
  });

  it('delivers everything an article image needs, plus alt text', () => {
    const [first] = fallbackCandidates(smallCatalog(), 'windkraft').candidates;
    assert.deepEqual(Object.keys(first), ['url', 'credit', 'license', 'sourceUrl', 'alt', 'width', 'height', 'title', 'fallback']);
    assert.equal(first.alt, 'Symbolbild Windpark 1');
    assert.equal(first.fallback, 'motiv:windpark');
  });

  it('respects the limit (default 5)', () => {
    assert.equal(fallbackCandidates(smallCatalog(), 'bundestag').candidates.length, 5);
    assert.equal(fallbackCandidates(smallCatalog(), 'bundestag', { limit: 1 }).candidates.length, 1);
  });

  it('skips pictures already used – by URL, file page, title or another thumbnail size', () => {
    const c = smallCatalog();
    const [b1, b2] = c.subjects.bundestag.images;
    const p1 = c.topics.politik[0];
    const result = fallbackCandidates(c, 'bundestag', {
      exclude: [b1.url.replace('/1280px-', '/960px-'), b2.sourceUrl, p1.title, '', 'https://example.com/fremd.jpg'],
      limit: 20,
    });
    assert.deepEqual(titles(result), ['politik 2', 'politik 3', 'politik 4', 'Kanzleramt 1']);
    assert.equal(result.excluded, 3);
  });

  it('returns an empty list when everything is taken', () => {
    const c = smallCatalog();
    const all = [...c.subjects.windpark.images, ...c.topics.klima].map((e) => e.url);
    const result = fallbackCandidates(c, 'windpark', { exclude: all });
    assert.deepEqual(result.candidates, []);
    assert.equal(result.excluded, 5);
  });

  it('rotates each group reproducibly with the seed', () => {
    const c = smallCatalog();
    const first = (seed) => fallbackCandidates(c, 'politik', { seed, limit: 1 }).candidates[0].title;
    assert.equal(first('politik-a'), first('politik-a'));
    const firsts = new Set(Array.from({ length: 40 }, (_, i) => first(`politik-artikel-${i}`)));
    assert.deepEqual([...firsts].sort(), c.topics.politik.map((e) => e.title).sort());
    const rotated = fallbackCandidates(c, 'bundestag', { seed: 'x', limit: 20 });
    assert.deepEqual(rotated.candidates.slice(0, 2).map((x) => x.fallback), ['motiv:bundestag', 'motiv:bundestag']);
    assert.equal(rotated.candidates.length, 7);
  });

  it('rejects unknown keys', () => {
    assert.throws(() => fallbackCandidates(smallCatalog(), 'mondlandung'), (e) => e instanceof UnknownFallbackError && /--list-fallbacks/.test(e.message));
  });

  it('hands out every picture of a topic of the real catalogue once before running dry', () => {
    const subjects = Object.values(CATALOG.subjects).filter((s) => s.topic === 'politik');
    const total = CATALOG.topics.politik.length + subjects.reduce((n, s) => n + s.images.length, 0);
    const used = [];
    for (;;) {
      const [pick] = fallbackCandidates(CATALOG, 'politik', { exclude: used, seed: `politik-${used.length}` }).candidates;
      if (!pick) break;
      assert.ok(!used.includes(pick.url), pick.title);
      used.push(pick.url);
    }
    assert.equal(used.length, total);
    assert.ok(total >= 15, `${total} Bilder für politik`);
  });
});

describe('exclusion helpers', () => {
  it('splits --exclude lists without breaking commas inside file names', () => {
    const a = 'https://commons.wikimedia.org/wiki/File:Reichstag,_Berlin-Mitte,_170402,_ako.jpg';
    const b = 'https://upload.wikimedia.org/wikipedia/commons/thumb/f/fd/X%2C_Y.jpg/1280px-X%2C_Y.jpg';
    assert.deepEqual(splitExcludeList(`${a},${b}`), [a, b]);
    assert.deepEqual(splitExcludeList(`${a}, ${b} File:Z, Q.jpg`), [a, b, 'File:Z, Q.jpg']);
    assert.deepEqual(splitExcludeList(`${a}\n${b}\n`), [a, b]);
    assert.deepEqual(splitExcludeList(''), []);
    assert.equal(commonsFileKey(splitExcludeList(`${a},${b}`)[0]), 'Reichstag, Berlin-Mitte, 170402, ako.jpg');
  });

  it('collects the pictures used in section files, briefs and folders', () => {
    const { dir, cleanup } = tempDir();
    try {
      const ki = makeSection('ki');
      const region = makeSection('region', { start: 10 });
      writeFileSync(path.join(dir, 'ki.json'), JSON.stringify(ki));
      writeFileSync(path.join(dir, 'region.json'), JSON.stringify(region));
      writeFileSync(path.join(dir, 'kaputt.json'), '{');
      writeFileSync(path.join(dir, 'notiz.txt'), 'kein JSON');
      const all = usedImagesIn(dir);
      const expected = [...ki.items, ...region.items].filter((i) => i.image).flatMap((i) => [i.image.url, i.image.sourceUrl]);
      assert.deepEqual(all.refs.sort(), expected.sort());
      assert.equal(all.warnings.length, 1);
      assert.match(all.warnings[0], /kaputt\.json übersprungen/);

      const brief = { schema: 2, sections: [ki] };
      writeFileSync(path.join(dir, 'brief.json'), JSON.stringify(brief));
      const fromBrief = usedImagesIn(path.join(dir, 'brief.json'));
      assert.equal(fromBrief.refs.length, 2);

      const missing = usedImagesIn(path.join(dir, 'gibt-es-nicht'));
      assert.deepEqual(missing.refs, []);
      assert.match(missing.warnings[0], /nicht gefunden – nichts ausgeschlossen/);
    } finally {
      cleanup();
    }
  });
});

/** A fake Commons API for file lookups: answers `titles=` requests from `files`. */
function fakeCommons(files, { normalized = [], redirects = [], status = 200 } = {}) {
  const calls = [];
  const fn = async (url) => {
    calls.push(url);
    const titles = new URL(url).searchParams.get('titles').split('|');
    const renamed = new Map([...normalized, ...redirects].map((s) => [s.from, s.to]));
    const pages = {};
    titles.forEach((requested, i) => {
      let title = requested;
      while (renamed.has(title)) title = renamed.get(title);
      const file = files.get(title);
      pages[file ? 1000 + i : -1 - i] = file
        ? {
            pageid: 1000 + i,
            ns: 6,
            title,
            imageinfo: [
              {
                width: 4000,
                height: 2666,
                thumburl: `${file.url}?utm_source=commons.wikimedia.org`,
                thumbwidth: file.width ?? 1280,
                thumbheight: file.height ?? 853,
                url: file.url.replace(/\/thumb\/(.+)\/1280px-.*$/, '/$1'),
                descriptionurl: file.sourceUrl,
                extmetadata: {
                  Artist: { value: `<a href="//commons.wikimedia.org/wiki/User:X">${file.credit ?? ''}</a>` },
                  LicenseShortName: { value: file.license },
                  Restrictions: { value: file.restrictions ?? '' },
                },
              },
            ],
          }
        : { ns: 6, title, missing: '' };
    });
    return {
      status,
      ok: status >= 200 && status < 300,
      headers: { get: () => null },
      json: async () => ({ batchcomplete: '', query: { normalized, redirects, pages } }),
    };
  };
  fn.calls = calls;
  return fn;
}

const filesOf = (catalog) => new Map(catalogEntries(catalog).map(({ entry: e }) => [e.title, { ...e }]));

describe('verifyFallbackCatalog', () => {
  it('asks for the catalogue files in batches with the search metadata', async () => {
    const c = smallCatalog();
    const fetchImpl = fakeCommons(filesOf(c));
    const { checked, problems } = await verifyFallbackCatalog(c, { fetchImpl, retryDelayMs: 0 });
    assert.equal(checked, 44);
    assert.deepEqual(problems, []);
    assert.equal(fetchImpl.calls.length, 3);
    const params = new URL(fetchImpl.calls[0]).searchParams;
    assert.equal(params.get('titles').split('|').length, 20);
    assert.equal(params.get('iiurlwidth'), '1280');
    assert.equal(params.get('redirects'), '1');
    assert.equal(buildFileInfoUrl(['File:A.jpg']), fetchImpl.calls[0].replace(/titles=[^&]+/, 'titles=File%3AA.jpg'));
  });

  it('reports deleted, renamed, relicensed and restricted files', async () => {
    const c = smallCatalog();
    const files = filesOf(c);
    files.delete('File:ki 1.jpg');
    files.get('File:ki 2.jpg').license = 'CC BY-NC 2.0';
    files.get('File:ki 3.jpg').credit = 'Jemand Anderes';
    files.get('File:ki 4.jpg').restrictions = 'personality';
    const moved = files.get('File:Windpark 1.jpg');
    files.set('File:Windpark neu.jpg', { ...moved, url: moved.url.replace(/Windpark_1/g, 'Windpark_neu') });
    files.delete('File:Windpark 1.jpg');
    const fetchImpl = fakeCommons(files, { redirects: [{ from: 'File:Windpark 1.jpg', to: 'File:Windpark neu.jpg' }] });
    const { problems } = await verifyFallbackCatalog(c, { fetchImpl, retryDelayMs: 0 });
    const text = problems.join('\n');
    assert.match(text, /rubrik ki #1 \(File:ki 1\.jpg\): Datei nicht \(mehr\) auf Commons/);
    assert.match(text, /rubrik ki #2 \(File:ki 2\.jpg\): nicht mehr verwendbar \(Lizenz\)/);
    assert.match(text, /rubrik ki #3 \(File:ki 3\.jpg\): Urheber ist jetzt „Jemand Anderes“/);
    assert.match(text, /rubrik ki #4 \(File:ki 4\.jpg\): Persönlichkeitsrechte-Hinweis/);
    assert.match(text, /motiv windpark #1 \(File:Windpark 1\.jpg\): Datei heißt jetzt File:Windpark neu\.jpg/);
    assert.match(text, /motiv windpark #1 .*Bild-URL ist jetzt https:\/\/upload\.wikimedia\.org\/.*Windpark_neu/);
    assert.equal(problems.length, 6);
  });

  it('throws CommonsError when Commons is unreachable', async () => {
    const fetchImpl = fakeCommons(new Map(), { status: 503 });
    await assert.rejects(verifyFallbackCatalog(smallCatalog(), { fetchImpl, retryDelayMs: 0 }), CommonsError);
  });
});

describe('find-image CLI (fallback mode)', () => {
  const capture = () => {
    const lines = { out: [], err: [] };
    return { lines, out: (s) => lines.out.push(s), err: (s) => lines.err.push(s) };
  };
  const withCatalog = async (fn) => {
    const { dir, cleanup } = tempDir();
    try {
      const file = path.join(dir, 'katalog.json');
      writeFileSync(file, JSON.stringify(smallCatalog()));
      return await fn(file, dir);
    } finally {
      cleanup();
    }
  };
  const noFetch = async () => {
    throw new Error('Fallback braucht kein Netz');
  };

  it('prints catalogue pictures without touching the network', async () => {
    await withCatalog(async (catalogPath) => {
      const io = capture();
      const code = await main(['--fallback', 'Reichstag', '--limit', '3'], { ...io, catalogPath, fetchImpl: noFetch });
      assert.equal(code, 0);
      const parsed = JSON.parse(io.lines.out.join('\n'));
      assert.deepEqual(parsed.map((p) => p.title), ['File:Bundestag 1.jpg', 'File:Bundestag 2.jpg', 'File:politik 1.jpg']);
      assert.equal(io.lines.err.join('\n'), '3 Katalogbilder für Motiv „bundestag“ (Rubrik politik)');
    });
  });

  it('honours --exclude, --exclude-from and --seed', async () => {
    await withCatalog(async (catalogPath, dir) => {
      const c = smallCatalog();
      const used = makeSection('politik');
      used.items[0].image = { ...c.subjects.bundestag.images[0] };
      delete used.items[0].image.title;
      mkdirSync(path.join(dir, 'sections'));
      writeFileSync(path.join(dir, 'sections', 'politik.json'), JSON.stringify(used));
      const io = capture();
      const code = await main(
        ['--fallback=bundestag', '--exclude', `${c.subjects.bundestag.images[1].url},${c.topics.politik[0].sourceUrl}`,
          '--exclude-from', path.join(dir, 'sections'), '--seed', 'politik-umfrage', '--limit=20'],
        { ...io, catalogPath },
      );
      assert.equal(code, 0);
      const parsed = JSON.parse(io.lines.out.join('\n'));
      assert.equal(parsed.length, 4);
      assert.ok(parsed.every((p) => p.fallback !== 'motiv:bundestag'));
      assert.match(io.lines.err.join('\n'), /\(3 schon vergeben\)/);
    });
  });

  it('says so when no picture is left', async () => {
    await withCatalog(async (catalogPath) => {
      const c = smallCatalog();
      const io = capture();
      const all = [...c.subjects.windpark.images, ...c.topics.klima].map((e) => e.url).join(',');
      assert.equal(await main(['--fallback', 'windpark', '--exclude', all], { ...io, catalogPath }), 0);
      assert.equal(io.lines.out.join(''), '[]');
      assert.match(io.lines.err.join('\n'), /Kein freies Katalogbild mehr/);
    });
  });

  it('lists the catalogue', async () => {
    const io = capture();
    assert.equal(await main(['--list-fallbacks'], io), 0);
    const text = io.lines.out.join('\n');
    assert.match(text, /^Bildkatalog \(geprüft 2026-10-09\): \d+ freie Commons-Bilder\./);
    assert.match(text, /🏛️ politik – Rubrik, \d+ Bilder/);
    assert.match(text, /^ {2}bundestag – .*auch: reichstag/m);
  });

  it('verifies the catalogue against (fake) Commons', async () => {
    await withCatalog(async (catalogPath) => {
      const ok = capture();
      assert.equal(await main(['--verify-fallbacks'], { ...ok, catalogPath, fetchImpl: fakeCommons(filesOf(smallCatalog())), retryDelayMs: 0 }), 0);
      assert.match(ok.lines.out.join('\n'), /OK – alle 44 Katalogbilder/);
      const files = filesOf(smallCatalog());
      files.delete('File:sport 1.jpg');
      const bad = capture();
      assert.equal(await main(['--verify-fallbacks', '--catalog', catalogPath], { ...bad, fetchImpl: fakeCommons(files), retryDelayMs: 0 }), 1);
      assert.match(bad.lines.err.join('\n'), /Problem: rubrik sport #1/);
      assert.match(bad.lines.out.join('\n'), /FEHLER – 1 Problem\(e\) bei 44 Katalogbildern/);
      const offline = capture();
      assert.equal(await main(['--verify-fallbacks'], { ...offline, catalogPath, fetchImpl: fakeCommons(files, { status: 500 }), retryDelayMs: 0 }), 1);
      assert.match(offline.lines.err.join('\n'), /FEHLER: Commons antwortet mit HTTP 500/);
    });
  });

  it('exits 2 for unknown subjects and wrong option combinations, 1 for a broken catalogue', async () => {
    await withCatalog(async (catalogPath, dir) => {
      const unknown = capture();
      assert.equal(await main(['--fallback', 'mondlandung'], { ...unknown, catalogPath }), 2);
      assert.match(unknown.lines.err.join('\n'), /„mondlandung“ ist weder ein Motiv noch eine Rubrik/);
      for (const argv of [
        ['--fallback'],
        ['--fallback', '--seed', 'x'],
        ['--seed', 'x', 'Reichstag'],
        ['--exclude', 'https://upload.wikimedia.org/x.jpg', 'Reichstag'],
        ['Reichstag', '--fallback', 'bundestag'],
        ['--list-fallbacks', '--verify-fallbacks'],
        ['--fallback', 'bundestag', '--limit', '0'],
      ]) {
        assert.equal(await main(argv, { ...capture(), catalogPath }), 2, argv.join(' '));
      }
      writeFileSync(path.join(dir, 'kaputt.json'), '[]');
      const broken = capture();
      assert.equal(await main(['--fallback', 'politik', '--catalog', path.join(dir, 'kaputt.json')], broken), 1);
      assert.match(broken.lines.err.join('\n'), /FEHLER: Bildkatalog .* ist ungültig/);
    });
  });

  it('documents the fallback options in --help', async () => {
    const io = capture();
    assert.equal(await main(['--help'], io), 0);
    const help = io.lines.out.join('\n');
    for (const flag of ['--fallback', '--seed', '--exclude', '--exclude-from', '--list-fallbacks', '--verify-fallbacks', '--catalog']) {
      assert.ok(help.includes(flag), flag);
    }
  });
});
