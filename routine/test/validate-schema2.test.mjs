import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import path from 'node:path';
import { describe, it } from 'node:test';

import {
  LIMITS,
  TOPICS,
  analyzeBody,
  countImages,
  countItems,
  describe as describeDoc,
  detectKind,
  expectedReadingMinutes,
  isFreeLicense,
  isSlug,
  licenseNeedsCredit,
  quotedPassages,
  sentenceCount,
  validateBrief,
  validateDocument,
  validateSection,
} from '../validate-brief.mjs';
import { ROUTINE_DIR, bodyWithWords, clone, makeBriefV2, makeImage, makeSection, tempDir } from './fixtures.mjs';

const VALIDATE = path.join(ROUTINE_DIR, 'validate-brief.mjs');

function expectError(result, where, pattern) {
  const hits = result.errors.filter((e) => e.startsWith(`${where}:`));
  assert.ok(hits.length >= 1, `expected an error at ${where}, got: ${JSON.stringify(result.errors, null, 1)}`);
  if (pattern) assert.ok(hits.some((h) => pattern.test(h)), `no error at ${where} matches ${pattern}: ${hits}`);
  return hits[0];
}

function expectWarning(result, where, pattern) {
  const hits = result.warnings.filter((w) => w.startsWith(`${where}:`) && (!pattern || pattern.test(w)));
  assert.ok(hits.length >= 1, `expected a warning at ${where} ${pattern ?? ''}, got: ${JSON.stringify(result.warnings, null, 1)}`);
}

const ITEM = 'sections[0].items[0]';

describe('schema 2: primitives', () => {
  it('knows the topic catalogue of the app', () => {
    assert.deepEqual(
      TOPICS.map((t) => t.id),
      ['ki', 'wirtschaft', 'politik', 'welt', 'region', 'sport', 'wissenschaft', 'gesundheit', 'klima', 'entertainment'],
    );
    assert.deepEqual(TOPICS.find((t) => t.id === 'region'), { id: 'region', title: 'Leipzig & Region', emoji: '📍' });
  });

  it('accepts only free licenses', () => {
    for (const ok of ['CC0', 'cc0 1.0', 'Public domain', 'public-domain', 'PD-US', 'PD-old-70', 'CC BY 2.0',
      'CC BY-SA 4.0', 'cc-by-sa-3.0', 'CC BY-SA 3.0 de', 'CC BY 3.0 igo', ' CC  BY 4.0 ']) {
      assert.ok(isFreeLicense(ok), ok);
    }
    for (const bad of ['CC BY-NC 2.0', 'CC BY-ND 4.0', 'CC BY-NC-SA 3.0', 'GFDL 1.2', 'FAL', 'Fair use',
      'All rights reserved', 'CC BY', 'PD', '', undefined, 'CC BY-SA 3.0,2.5']) {
      assert.ok(!isFreeLicense(bad), String(bad));
    }
  });

  it('needs credit for CC BY / BY-SA only', () => {
    assert.equal(licenseNeedsCredit('CC0'), false);
    assert.equal(licenseNeedsCredit('Public domain'), false);
    assert.equal(licenseNeedsCredit('PD-self'), false);
    assert.equal(licenseNeedsCredit('CC BY 4.0'), true);
    assert.equal(licenseNeedsCredit('CC BY-SA 3.0 de'), true);
  });

  it('accepts lowercase slugs as ids', () => {
    assert.ok(isSlug('ki-openai-chips-2026'));
    assert.ok(isSlug('a1'));
    for (const bad of ['KI-News', 'ki_news', 'ki news', '-ki', 'ki-', 'ki--news', '', 'ü-bahn', 'a'.repeat(81)]) {
      assert.ok(!isSlug(bad), bad);
    }
  });

  it('computes reading minutes as round(words / 200), at least 1', () => {
    assert.equal(expectedReadingMinutes(50), 1);
    assert.equal(expectedReadingMinutes(299), 1);
    assert.equal(expectedReadingMinutes(300), 2);
    assert.equal(expectedReadingMinutes(700), 4);
  });

  it('counts sentences without stumbling over abbreviations', () => {
    assert.equal(sentenceCount('Der Euro fällt. Urlaub wird teurer.'), 2);
    assert.equal(sentenceCount('Die Telekom spart z. B. 1,1 Mrd. Euro bis 2027.'), 1);
    assert.equal(sentenceCount('Der 1. FC Lok spielt am 5. Oktober. Das Stadion ist voll!'), 2);
    assert.equal(sentenceCount('Eins. Zwei. Drei.'), 3);
  });

  it('finds quoted passages in German and English quotes', () => {
    assert.deepEqual(quotedPassages('Er sagte: „Das ist gut.“ Sie: »Na ja«, dann "okay" und “fine”.'), [
      'Das ist gut.',
      'Na ja',
      'fine',
      'okay',
    ]);
  });

  it('detects single sections', () => {
    assert.equal(detectKind(makeSection('ki')), 'section');
    assert.equal(detectKind(makeBriefV2()), 'brief');
  });
});

describe('schema 2: article bodies', () => {
  it('accepts paragraphs, ## subheadings and - bullet lists', () => {
    const body = `${bodyWithWords(260)}\n\n- erster Punkt\n- zweiter Punkt`;
    const a = analyzeBody(body);
    assert.deepEqual(a.errors, []);
    assert.deepEqual(a.warnings, []);
    assert.equal(a.words, 264);
    assert.equal(a.paragraphs, 3);
  });

  it('rejects HTML, links, images and URLs', () => {
    assert.match(analyzeBody('Text mit <b>Fett</b>.').errors.join(), /HTML/);
    assert.match(analyzeBody('Siehe [hier](https://x.de).').errors.join(), /Link/);
    assert.match(analyzeBody('Bild ![x](https://x.de/a.png)').errors.join(), /Bild/);
    assert.match(analyzeBody('Mehr unter https://www.heise.de dazu.').errors.join(), /URL/);
    assert.match(analyzeBody('Mehr unter www.heise.de dazu.').errors.join(), /URL/);
    assert.deepEqual(analyzeBody('Die Inflation liegt bei < 2 Prozent, laut heise.de.').errors, []);
  });

  it('allows only ## subheadings on their own block', () => {
    assert.match(analyzeBody('# Titel\n\nText.').errors.join(), /## Titel/);
    assert.match(analyzeBody('### Titel\n\nText.').errors.join(), /## Titel/);
    assert.match(analyzeBody('Text.\n## Titel').errors.join(), /eigenen Block/);
    assert.match(analyzeBody('## Titel\nText.').warnings.join(), /Leerzeile/);
    assert.match(analyzeBody('Text.\n\n## Am Ende').warnings.join(), /endet/);
  });

  it('warns about line breaks inside paragraphs and other bullet styles', () => {
    assert.match(analyzeBody('Zeile eins\nZeile zwei').warnings.join(), /Zeilenumbruch/);
    assert.match(analyzeBody('Liste:\n* eins\n* zwei').warnings.join(), /„- “/);
    assert.match(analyzeBody('Liste:\n- eins\n- zwei').warnings.join(), /Leerzeile/);
    assert.match(analyzeBody('Das ist **wichtig**.').warnings.join(), /Fett/);
  });

  it('rejects quotes of 15 words or more and warns about several long quotes', () => {
    const q14 = Array.from({ length: 14 }, (_, i) => `wort${i}`).join(' ');
    const q15 = `${q14} wort14`;
    assert.deepEqual(analyzeBody(`Sie sagte: „${q14}“.`).errors, []);
    assert.match(analyzeBody(`Sie sagte: „${q15}“.`).errors.join(), /15 Wörtern/);
    assert.match(analyzeBody('„Eins zwei drei vier fünf.“ Und „sechs sieben acht neun zehn“.').warnings.join(), /höchstens ein kurzes Zitat/);
  });
});

describe('schema 2: briefs', () => {
  it('accepts a well-formed brief without warnings', () => {
    const brief = makeBriefV2();
    assert.deepEqual(validateBrief(brief), { errors: [], warnings: [] });
    assert.deepEqual(validateBrief(brief, { maxAgeHours: 36 }), { errors: [], warnings: [] });
    assert.equal(countItems(brief), 9);
    assert.equal(countImages(brief), 3);
    assert.equal(describeDoc('brief', brief), 'Briefing 2026-10-06 · 3 Rubriken · 9 Artikel · 3 Bilder');
  });

  it('accepts schema 2 and still schema 1, nothing else', () => {
    const brief = makeBriefV2();
    brief.schema = 3;
    let result = validateBrief(brief);
    assert.match(expectError(result, 'schema'), /1 oder 2/);
    assert.equal(result.errors.length, 1, 'the items are still checked with the schema-2 rules');
    delete brief.schema;
    result = validateBrief(brief);
    expectError(result, 'schema', /fehlt/);
    assert.equal(result.errors.length, 1);
  });

  it('requires unique slug ids', () => {
    const brief = makeBriefV2();
    delete brief.sections[0].items[0].id;
    brief.sections[0].items[1].id = 'KI Meldung';
    brief.sections[1].items[0].id = brief.sections[0].items[2].id;
    const result = validateBrief(brief);
    expectError(result, `${ITEM}.id`, /fehlt/);
    expectError(result, 'sections[0].items[1].id', /Slug/);
    expectError(result, 'sections[1].items[0].id', /doppelt/);
  });

  it('checks tiers: 1–3 top, at most 5 more, top first', () => {
    const brief = makeBriefV2();
    brief.sections[0].items[0].tier = 'featured';
    expectError(validateBrief(brief), `${ITEM}.tier`, /"top" oder "more"/);

    const order = makeBriefV2();
    order.sections[0].items.reverse(); // more, top, top
    expectError(validateBrief(order), 'sections[0].items[1].tier', /vor allen "more"/);

    const noTop = makeBriefV2();
    noTop.sections[0].items = [noTop.sections[0].items[2]];
    expectError(validateBrief(noTop), 'sections[0].items', /0 "top"/);

    const tooMany = makeBriefV2();
    const s = tooMany.sections[0];
    const extraTop = clone(s.items[0]);
    extraTop.id = 'ki-extra-top';
    extraTop.url = 'https://www.zeit.de/extra-top';
    extraTop.sources = [{ name: 'ZEIT', url: extraTop.url }, { name: 'SZ', url: 'https://www.sueddeutsche.de/x' }];
    extraTop.source = 'ZEIT';
    extraTop.headline = 'Noch eine wichtige Meldung';
    s.items.splice(2, 0, extraTop, { ...clone(extraTop), id: 'ki-extra-top-2', url: 'https://www.zeit.de/t2', headline: 'Zweite', sources: [{ name: 'ZEIT', url: 'https://www.zeit.de/t2' }, { name: 'SZ', url: 'https://www.sueddeutsche.de/y' }] });
    expectError(validateBrief(tooMany), 'sections[0].items', /4 "top"/);

    const manyMore = makeBriefV2();
    const more = manyMore.sections[0].items[2];
    for (let i = 0; i < 5; i++) {
      const copy = clone(more);
      copy.id = `ki-mehr-${i}`;
      copy.url = `https://www.mdr.de/mehr-${i}`;
      copy.sources = [{ name: 'MDR', url: copy.url }];
      copy.source = 'MDR';
      copy.headline = `Weitere Meldung ${i}`;
      manyMore.sections[0].items.push(copy);
    }
    expectError(validateBrief(manyMore), 'sections[0].items', /6 "more"/);
  });

  it('rejects empty sections', () => {
    const brief = makeBriefV2();
    brief.sections[1].items = [];
    expectError(validateBrief(brief), 'sections[1].items', /weglassen/);
  });

  it('enforces body length per tier', () => {
    const { min: topMin, max: topMax } = LIMITS.bodyWords.top;
    const { min: moreMin, max: moreMax } = LIMITS.bodyWords.more;
    const brief = makeBriefV2();
    const [top, , more] = brief.sections[0].items;
    const fix = (item, words) => {
      item.body = bodyWithWords(words);
      item.readingMinutes = expectedReadingMinutes(words);
    };
    fix(top, topMin);
    fix(more, moreMin);
    assert.deepEqual(validateBrief(brief).errors, []);
    fix(top, topMax);
    fix(more, moreMax);
    assert.deepEqual(validateBrief(brief).errors, []);
    fix(top, topMin - 1);
    fix(more, moreMax + 1);
    let result = validateBrief(brief);
    expectError(result, `${ITEM}.body`, new RegExp(`${topMin - 1} Wörter`));
    expectError(result, 'sections[0].items[2].body', new RegExp(`${moreMax + 1} Wörter`));
    fix(top, topMax + 1);
    fix(more, moreMin - 1);
    result = validateBrief(brief);
    expectError(result, `${ITEM}.body`, /250–700/);
    expectError(result, 'sections[0].items[2].body', /120–400/);
    delete top.body;
    expectError(validateBrief(brief), `${ITEM}.body`, /fehlt/);
  });

  it('reports body format errors with the item path', () => {
    const brief = makeBriefV2();
    brief.sections[0].items[0].body += '\n\nQuelle: https://www.tagesschau.de/x';
    expectError(validateBrief(brief), `${ITEM}.body`, /URL/);
  });

  it('limits the summary to 300 characters and warns about long leads', () => {
    const brief = makeBriefV2();
    brief.sections[0].items[0].summary = 'x'.repeat(LIMITS.summaryV2 + 1);
    expectError(validateBrief(brief), `${ITEM}.summary`, /301 Zeichen/);
    brief.sections[0].items[0].summary = 'Eins ist passiert. Zwei auch. Drei sowieso.';
    expectWarning(validateBrief(brief), `${ITEM}.summary`, /3 Sätze/);
  });

  it('requires enough sources and the main url among them', () => {
    const brief = makeBriefV2();
    const top = brief.sections[0].items[0];
    top.sources = [top.sources[0]];
    expectError(validateBrief(brief), `${ITEM}.sources`, /mindestens 2/);

    const other = makeBriefV2();
    other.sections[0].items[0].url = 'https://www.spiegel.de/anderer-artikel';
    expectError(validateBrief(other), `${ITEM}.url`, /eine der Adressen/);

    const bad = makeBriefV2();
    bad.sections[0].items[0].sources[1] = { name: '', url: 'http://www.zeit.de/x' };
    const result = validateBrief(bad);
    expectError(result, `${ITEM}.sources[1].name`);
    expectError(result, `${ITEM}.sources[1].url`, /https/);

    const missing = makeBriefV2();
    delete missing.sections[0].items[2].sources;
    expectError(validateBrief(missing), 'sections[0].items[2].sources', /Liste/);
  });

  it('counts duplicate sources once and warns about one-outlet articles', () => {
    const brief = makeBriefV2();
    const top = brief.sections[0].items[0];
    top.sources[1] = { ...top.sources[0], url: `${top.sources[0].url}#kommentare` };
    let result = validateBrief(brief);
    expectWarning(result, `${ITEM}.sources[1].url`, /dieselbe Quelle/);
    expectError(result, `${ITEM}.sources`, /1 Quelle/);

    const sameHost = makeBriefV2();
    sameHost.sections[0].items[0].sources[1] = { name: 'tagesschau', url: 'https://www.tagesschau.de/ausland/anderes-100.html' };
    result = validateBrief(sameHost);
    assert.deepEqual(result.errors, []);
    expectWarning(result, `${ITEM}.sources`, /tagesschau\.de/);
  });

  it('warns when source and sources disagree on the name', () => {
    const brief = makeBriefV2();
    brief.sections[0].items[0].source = 'ARD';
    expectWarning(validateBrief(brief), `${ITEM}.source`, /"tagesschau"/);
  });

  it('checks readingMinutes', () => {
    const brief = makeBriefV2();
    brief.sections[0].items[0].readingMinutes = 0;
    expectError(validateBrief(brief), `${ITEM}.readingMinutes`, /für 300 Wörter: 2/);
    brief.sections[0].items[0].readingMinutes = 3; // off by one: fine
    assert.deepEqual(validateBrief(brief), { errors: [], warnings: [] });
    brief.sections[0].items[0].readingMinutes = 4;
    expectWarning(validateBrief(brief), `${ITEM}.readingMinutes`, /passt 2/);
    delete brief.sections[0].items[0].readingMinutes;
    expectError(validateBrief(brief), `${ITEM}.readingMinutes`);
  });

  it('validates images: Commons only, free license, credit, source page', () => {
    const brief = makeBriefV2();
    const img = brief.sections[0].items[1].image; // item 2 has an image
    const at = 'sections[0].items[1].image';
    img.url = 'https://www.tagesschau.de/bild.jpg';
    img.license = 'CC BY-NC 2.0';
    img.sourceUrl = 'https://de.wikipedia.org/wiki/Datei:Bild.jpg';
    delete img.alt;
    let result = validateBrief(brief);
    expectError(result, `${at}.url`, /upload\.wikimedia\.org/);
    expectError(result, `${at}.license`, /keine erlaubte freie Lizenz/);
    expectError(result, `${at}.sourceUrl`, /commons\.wikimedia\.org/);
    expectWarning(result, `${at}.alt`);

    const credit = makeBriefV2();
    delete credit.sections[0].items[1].image.credit;
    expectError(validateBrief(credit), `${at}.credit`, /fehlt/);
    credit.sections[0].items[1].image.license = 'CC0';
    assert.deepEqual(validateBrief(credit).errors, []);
    credit.sections[0].items[1].image.license = 'Public domain';
    assert.deepEqual(validateBrief(credit).errors, []);

    const html = makeBriefV2();
    html.sections[0].items[1].image.credit = '<a href="//commons.wikimedia.org/wiki/User:X">X</a>';
    expectError(validateBrief(html), `${at}.credit`, /HTML/);

    const notObject = makeBriefV2();
    notObject.sections[0].items[1].image = 'https://upload.wikimedia.org/x.jpg';
    expectError(validateBrief(notObject), at, /null oder ein Objekt/);

    const reused = makeBriefV2();
    reused.sections[1].items[1].image = makeImage(2); // same picture as sections[0].items[1]
    expectWarning(validateBrief(reused), 'sections[1].items[1].image.url', /dasselbe Bild/);
  });

  it('accepts a missing or null image', () => {
    const brief = makeBriefV2();
    delete brief.sections[0].items[1].image;
    brief.sections[1].items[1].image = null;
    assert.deepEqual(validateBrief(brief), { errors: [], warnings: [] });
  });

  it('checks importance like schema 1', () => {
    const brief = makeBriefV2();
    brief.sections[0].items[0].importance = 11;
    expectError(validateBrief(brief), `${ITEM}.importance`);
  });

  it('warns about unknown topics, catalogue deviations and order', () => {
    const brief = makeBriefV2();
    brief.sections[0].id = 'mode';
    brief.sections[1].title = 'Wirtschaft';
    brief.sections[1].emoji = '💶';
    let result = validateBrief(brief);
    assert.deepEqual(result.errors, []);
    expectWarning(result, 'sections[0].id', /Katalog/);
    expectWarning(result, 'sections[1].title', /Wirtschaft & Märkte/);
    expectWarning(result, 'sections[1].emoji', /📈/);

    const order = makeBriefV2();
    order.sections.reverse(); // region, wirtschaft, ki
    result = validateBrief(order);
    assert.deepEqual(result.errors, []);
    expectWarning(result, 'sections[1].id', /Katalog-Reihenfolge/);
  });

  it('reports unknown fields as typos', () => {
    const brief = makeBriefV2();
    brief.sections[0].items[0].imgae = null;
    brief.sections[0].items[1].image.autor = 'X';
    const result = validateBrief(brief);
    expectWarning(result, ITEM, /"imgae"/);
    expectWarning(result, 'sections[0].items[1].image', /"autor"/);
  });

  it('applies the freshness policy to schema 2 too', () => {
    const brief = makeBriefV2();
    brief.sections[2].items[0].publishedAt = '2026-10-04T17:00:00+02:00';
    assert.deepEqual(validateBrief(brief).errors, []);
    expectError(validateBrief(brief, { maxAgeHours: 36 }), 'sections[2].items[0].publishedAt', /36\.1 h/);
  });
});

describe('sections (--section)', () => {
  const now = Date.parse('2026-10-06T03:07:12Z'); // 05:07:12 Berlin

  it('accepts a valid section', () => {
    const section = makeSection('ki');
    assert.deepEqual(validateSection(section, { maxAgeHours: 36, now }), { errors: [], warnings: [] });
    assert.equal(describeDoc('section', section), 'Rubrik ki · 3 Artikel (2 Top, 1 Mehr) · 1 Bild');
  });

  it('reports paths relative to the section', () => {
    const section = makeSection('ki');
    section.items[0].tier = 'oben';
    delete section.title;
    const result = validateSection(section, { now });
    expectError(result, 'items[0].tier');
    expectError(result, 'title');
  });

  it('checks freshness against now', () => {
    const section = makeSection('ki');
    section.items[2].publishedAt = '2026-10-04T15:00:00+02:00'; // 38 h before "now"
    expectError(validateSection(section, { maxAgeHours: 36, now }), 'items[2].publishedAt', /bezogen auf jetzt/);
    section.items[2].publishedAt = '2026-10-06T09:00:00+02:00'; // ~4 h in the future
    expectError(validateSection(section, { now }), 'items[2].publishedAt', /nach jetzt/);
  });

  it('rejects empty sections, whole briefs and non-objects', () => {
    expectError(validateSection({ id: 'sport', title: 'Sport', emoji: '⚽', items: [] }, { now }), 'items', /weglassen/);
    assert.match(validateSection(makeBriefV2(), { now }).errors[0], /ohne --section/);
    assert.equal(validateSection([], { now }).errors.length, 1);
  });

  it('is detected by validateDocument and needs --section', () => {
    const result = validateDocument(makeSection('ki'));
    assert.equal(result.kind, 'section');
    assert.match(result.errors[0], /--section/);
    assert.deepEqual(validateDocument(makeSection('ki'), { section: true, now }).errors, []);
  });

  describe('CLI', () => {
    const run = (...args) =>
      spawnSync(process.execPath, [VALIDATE, ...args], { encoding: 'utf8', env: { ...process.env, GITHUB_ACTIONS: '' } });

    it('validates section files and warns about mismatching file names', () => {
      const { dir, cleanup } = tempDir();
      try {
        const good = path.join(dir, 'ki.json');
        const misnamed = path.join(dir, 'tech.json');
        writeFileSync(good, JSON.stringify(makeSection('ki')));
        writeFileSync(misnamed, JSON.stringify(makeSection('ki')));
        const result = run('--section', good, misnamed, '--max-age-hours', '36', '--now', '2026-10-06T05:07:12+02:00');
        assert.equal(result.status, 0, result.stdout + result.stderr);
        assert.match(result.stdout, /^OK .*ki\.json – Rubrik ki · 3 Artikel \(2 Top, 1 Mehr\) · 1 Bild$/m);
        assert.match(result.stdout, /Dateiname "tech\.json" passt nicht zur Rubrik "ki"/);
      } finally {
        cleanup();
      }
    });

    it('exits 1 for invalid sections and when a section is checked as a brief', () => {
      const { dir, cleanup } = tempDir();
      try {
        const file = path.join(dir, 'ki.json');
        const section = makeSection('ki');
        section.items[0].body = 'Viel zu kurz.';
        writeFileSync(file, JSON.stringify(section));
        const bad = run('--section', file, '--now', '2026-10-06T05:07:12+02:00');
        assert.equal(bad.status, 1);
        assert.match(bad.stdout, /items\[0\]\.body: hat 3 Wörter/);

        writeFileSync(file, JSON.stringify(makeSection('ki')));
        const asBrief = run(file);
        assert.equal(asBrief.status, 1);
        assert.match(asBrief.stdout, /mit --section prüfen/);
      } finally {
        cleanup();
      }
    });

    it('rejects --now without --section and invalid --now values', () => {
      assert.equal(run('x.json', '--now', '2026-10-06T05:07:12+02:00').status, 2);
      assert.equal(run('--section', 'x.json', '--now', 'gestern').status, 2);
    });

    it('describes schema-2 briefs with articles and images', () => {
      const { dir, cleanup } = tempDir();
      try {
        const file = path.join(dir, 'brief.json');
        writeFileSync(file, JSON.stringify(makeBriefV2()));
        const result = run(file, '--max-age-hours', '36');
        assert.equal(result.status, 0, result.stdout);
        assert.match(result.stdout, /Briefing 2026-10-06 · 3 Rubriken · 9 Artikel · 3 Bilder/);
      } finally {
        cleanup();
      }
    });
  });
});
