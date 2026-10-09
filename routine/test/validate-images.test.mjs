/**
 * The picture policy of validate-brief.mjs: `--require-images` (every schema-2 article needs a
 * picture), the same Commons file twice in a brief or across sections, and `commonsFileKey`.
 */
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import path from 'node:path';
import { describe, it } from 'node:test';

import {
  commonsFileKey,
  crossSectionImageWarnings,
  imageKey,
  validateBrief,
  validateSection,
} from '../validate-brief.mjs';
import { ROUTINE_DIR, clone, makeBrief, makeBriefV2, makeImage, makeSection, tempDir } from './fixtures.mjs';

const VALIDATE = path.join(ROUTINE_DIR, 'validate-brief.mjs');
const NOW = Date.parse('2026-10-06T05:00:00+02:00');

/** Gives every article of a section or brief its own picture. */
function withAllImages(doc) {
  const sections = Array.isArray(doc.sections) ? doc.sections : [doc];
  let n = 100;
  for (const s of sections) for (const item of s.items) item.image = makeImage(n++);
  return doc;
}

describe('commonsFileKey', () => {
  it('names the Commons file behind thumbnails, originals, file pages and titles', () => {
    const name = 'Reichstag, Berlin-Mitte (2017).jpg';
    for (const value of [
      'https://upload.wikimedia.org/wikipedia/commons/thumb/f/fd/Reichstag%2C_Berlin-Mitte_%282017%29.jpg/1280px-Reichstag%2C_Berlin-Mitte_%282017%29.jpg',
      'https://upload.wikimedia.org/wikipedia/commons/thumb/f/fd/Reichstag%2C_Berlin-Mitte_%282017%29.jpg/960px-Reichstag%2C_Berlin-Mitte_%282017%29.jpg',
      'https://upload.wikimedia.org/wikipedia/commons/f/fd/Reichstag%2C_Berlin-Mitte_%282017%29.jpg',
      'https://thumb.wikimedia.org/wikipedia/commons/thumb/f/fd/Reichstag%2C_Berlin-Mitte_%282017%29.jpg/1280px-x.jpg?utm_source=y',
      'https://commons.wikimedia.org/wiki/File:Reichstag,_Berlin-Mitte_(2017).jpg',
      'https://commons.wikimedia.org/wiki/File:Reichstag%2C_Berlin-Mitte_%282017%29.jpg#Lizenz',
      'https://commons.m.wikimedia.org/wiki/Datei:Reichstag,_Berlin-Mitte_(2017).jpg',
      'https://commons.wikimedia.org/w/index.php?title=File:Reichstag,_Berlin-Mitte_(2017).jpg',
      'File:Reichstag, Berlin-Mitte (2017).jpg',
      'Datei:reichstag,_Berlin-Mitte_(2017).jpg',
    ]) {
      assert.equal(commonsFileKey(value), name, value);
    }
  });

  it('returns null for anything else', () => {
    for (const value of ['https://example.com/a.jpg', 'https://commons.wikimedia.org/wiki/Category:Berlin',
      'https://upload.wikimedia.org/favicon.ico', 'kein link', '', undefined, 42]) {
      assert.equal(commonsFileKey(value), null, String(value));
    }
    assert.equal(imageKey('https://example.com/a.jpg?x=1'), 'example.com/a.jpg?x=1');
  });
});

describe('--require-images', () => {
  it('leaves articles without picture valid by default (old briefs)', () => {
    const brief = makeBriefV2();
    assert.deepEqual(validateBrief(brief, { maxAgeHours: 36 }), { errors: [], warnings: [] });
    assert.deepEqual(validateSection(makeSection('ki'), { now: NOW }), { errors: [], warnings: [] });
  });

  it('makes every article without picture an error', () => {
    const brief = makeBriefV2();
    const { errors, warnings } = validateBrief(brief, { maxAgeHours: 36, requireImages: true });
    assert.deepEqual(warnings, []);
    assert.equal(errors.length, 6, errors.join('\n'));
    assert.match(errors[0], /^sections\[0\]\.items\[0\]\.image: Artikel "ki-meldung-1" hat kein Bild – mit --require-images braucht jeder Artikel eins/);
    assert.ok(errors.every((e) => /\.image: Artikel "[a-z0-9-]+" hat kein Bild/.test(e)));

    const section = makeSection('ki');
    const result = validateSection(section, { now: NOW, requireImages: true });
    assert.deepEqual(result.errors.map((e) => e.split(':')[0]), ['items[0].image', 'items[2].image']);
    assert.match(result.errors[0], /node routine\/find-image\.mjs --fallback/);
  });

  it('is satisfied when every article has a picture', () => {
    assert.deepEqual(validateBrief(withAllImages(makeBriefV2()), { maxAgeHours: 36, requireImages: true }), { errors: [], warnings: [] });
    assert.deepEqual(validateSection(withAllImages(makeSection('ki')), { now: NOW, requireImages: true }), { errors: [], warnings: [] });
  });

  it('still checks the picture itself, and ignores schema 1', () => {
    const section = withAllImages(makeSection('ki'));
    section.items[1].image.license = 'CC BY-NC 2.0';
    const result = validateSection(section, { now: NOW, requireImages: true });
    assert.equal(result.errors.length, 1);
    assert.match(result.errors[0], /^items\[1\]\.image\.license/);
    assert.deepEqual(validateBrief(makeBrief(), { requireImages: true }).errors, []);
  });
});

describe('the same picture twice', () => {
  it('warns inside one brief, also when the thumbnail size differs', () => {
    const brief = withAllImages(makeBriefV2());
    const again = clone(brief.sections[0].items[0].image);
    again.url = again.url.replace('/1280px-', '/960px-');
    brief.sections[2].items[1].image = again;
    const { errors, warnings } = validateBrief(brief, { requireImages: true });
    assert.deepEqual(errors, []);
    assert.deepEqual(warnings, [
      'sections[2].items[1].image.url: dasselbe Bild hat schon sections[0].items[0].image – jedes Bild nur einmal im Briefing',
    ]);
  });

  it('warns across sections checked together', () => {
    const seen = new Map();
    const ki = withAllImages(makeSection('ki'));
    const region = withAllImages(makeSection('region', { start: 10 }));
    region.items.forEach((item, i) => {
      item.image = clone(makeImage(200 + i));
    });
    region.items[2].image = clone(ki.items[1].image);
    assert.deepEqual(crossSectionImageWarnings(ki, 'ki.json', seen), []);
    assert.deepEqual(crossSectionImageWarnings(region, 'region.json', seen), [
      'items[2].image.url: dasselbe Bild hat schon ki.json items[1] – jedes Bild nur einmal im Briefing',
    ]);
    assert.equal(seen.size, 5, 'the repeated picture keeps its first location');
    assert.equal(seen.get(imageKey(ki.items[1].image.url)), 'ki.json items[1]');
    assert.deepEqual(crossSectionImageWarnings({ items: 'kaputt' }, 'x.json', seen), []);
    assert.deepEqual(crossSectionImageWarnings(null, 'x.json', seen), []);
  });
});

describe('validate-brief CLI: pictures', () => {
  const run = (...args) => spawnSync(process.execPath, [VALIDATE, ...args], { encoding: 'utf8' });

  it('documents --require-images', () => {
    const help = run('--help');
    assert.equal(help.status, 0);
    assert.match(help.stdout, /--require-images +Jeder Artikel \(Schema 2\) braucht ein Bild/);
  });

  it('fails sections and briefs without pictures only with --require-images', () => {
    const { dir, cleanup } = tempDir();
    try {
      const ki = path.join(dir, 'ki.json');
      writeFileSync(ki, JSON.stringify(makeSection('ki')));
      const brief = path.join(dir, 'brief.json');
      writeFileSync(brief, JSON.stringify(makeBriefV2()));

      assert.equal(run('--section', ki, '--now', '2026-10-06T05:00:00+02:00').status, 0);
      assert.equal(run(brief).status, 0);

      const section = run('--section', ki, '--require-images', '--now', '2026-10-06T05:00:00+02:00');
      assert.equal(section.status, 1);
      assert.match(section.stdout, /^FEHLER .*ki\.json – Rubrik ki · 3 Artikel \(2 Top, 1 Mehr\) · 1 Bild · 2 Fehler$/m);
      assert.match(section.stdout, /Fehler: {2}items\[0\]\.image: Artikel "ki-meldung-1" hat kein Bild/);

      const full = run(brief, '--require-images', '--max-age-hours', '36');
      assert.equal(full.status, 1);
      assert.match(full.stdout, /6 Fehler/);
    } finally {
      cleanup();
    }
  });

  it('reports a picture used in two section files', () => {
    const { dir, cleanup } = tempDir();
    try {
      const ki = withAllImages(makeSection('ki'));
      const region = withAllImages(makeSection('region', { start: 10 }));
      region.items.forEach((item, i) => {
        item.image = makeImage(300 + i);
      });
      region.items[0].image = clone(ki.items[2].image);
      writeFileSync(path.join(dir, 'ki.json'), JSON.stringify(ki));
      writeFileSync(path.join(dir, 'region.json'), JSON.stringify(region));
      const result = run('--section', path.join(dir, 'ki.json'), path.join(dir, 'region.json'), '--require-images',
        '--now', '2026-10-06T05:00:00+02:00');
      assert.equal(result.status, 0, result.stdout);
      assert.match(result.stdout, /^OK .*region\.json – Rubrik region · 3 Artikel \(2 Top, 1 Mehr\) · 3 Bilder · 1 Hinweis\(e\)$/m);
      assert.match(result.stdout, /Hinweis: items\[0\]\.image\.url: dasselbe Bild hat schon ki\.json items\[2\]/);
      assert.match(result.stdout, /Alle 2 Dateien sind gültig\./);
    } finally {
      cleanup();
    }
  });
});
