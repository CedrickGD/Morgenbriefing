/**
 * Tests for routine/find-image.mjs. No network: `fixtures/commons-search-leipzig-hbf.json` is a
 * real response of the Commons API for "Leipzig Hauptbahnhof" (recorded 2026-10-05, the unused
 * `responsiveUrls`/`thumbattribs` fields removed); everything else uses a fake `fetch`.
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, it } from 'node:test';

import {
  API_URL,
  CommonsError,
  MIN_WIDTH,
  USER_AGENT,
  buildSearchUrl,
  canonicalImageUrl,
  findImages,
  main,
  normalizeLicense,
  parseCandidates,
  stripHtml,
} from '../find-image.mjs';
import { IMAGE_SOURCE_PREFIX, IMAGE_URL_PREFIX, isFreeLicense, licenseNeedsCredit } from '../validate-brief.mjs';
import { FIXTURES_DIR } from './fixtures.mjs';

const RECORDED = JSON.parse(readFileSync(path.join(FIXTURES_DIR, 'commons-search-leipzig-hbf.json'), 'utf8'));

/** A Commons API page as `generator=search` + `prop=imageinfo` returns it (format 1). */
function page(id, overrides = {}) {
  const { meta = {}, info = {}, ...rest } = overrides;
  const name = rest.title ?? `File:Bild ${id}.jpg`;
  const file = name.replace(/^File:/, '').replace(/ /g, '_');
  const metaValue = (value) => ({ value, source: 'commons-desc-page' });
  const extmetadata = Object.fromEntries(
    Object.entries({
      Artist: '<a href="//commons.wikimedia.org/wiki/User:Erika" title="User:Erika">Erika Mustermann</a>',
      LicenseShortName: 'CC BY-SA 4.0',
      ImageDescription: `Beschreibung ${id}`,
      Restrictions: '',
      ...meta,
    })
      .filter(([, v]) => v !== undefined)
      .map(([k, v]) => [k, metaValue(v)]),
  );
  return {
    pageid: id,
    ns: 6,
    title: name,
    index: id,
    imagerepository: 'local',
    imageinfo: [
      {
        width: 4000,
        height: 3000,
        thumburl: `https://thumb.wikimedia.org/wikipedia/commons/thumb/a/ab/${file}/1280px-${file}?utm_source=commons.wikimedia.org&utm_campaign=imageinfo&utm_content=thumbnail`,
        thumbwidth: 1280,
        thumbheight: 960,
        url: `https://upload.wikimedia.org/wikipedia/commons/a/ab/${file}?utm_source=commons.wikimedia.org`,
        descriptionurl: `https://commons.wikimedia.org/wiki/File:${file}`,
        extmetadata,
        ...info,
      },
    ],
    ...rest,
  };
}

const response = (...pages) => ({ batchcomplete: '', query: { pages: Object.fromEntries(pages.map((p) => [p.pageid, p])) } });

describe('buildSearchUrl', () => {
  it('asks the Commons API for bitmap files with license metadata', () => {
    const url = new URL(buildSearchUrl('Semperoper Dresden', 5));
    assert.equal(`${url.origin}${url.pathname}`, API_URL);
    const p = url.searchParams;
    assert.equal(p.get('action'), 'query');
    assert.equal(p.get('generator'), 'search');
    assert.equal(p.get('gsrnamespace'), '6');
    assert.equal(p.get('gsrsearch'), 'Semperoper Dresden filetype:bitmap');
    assert.equal(p.get('prop'), 'imageinfo');
    assert.equal(p.get('iiprop'), 'url|size|extmetadata');
    assert.equal(p.get('iiurlwidth'), '1280');
    assert.equal(p.get('format'), 'json');
    assert.equal(p.get('origin'), '*');
    assert.equal(p.get('gsrlimit'), '20');
    assert.equal(new URL(buildSearchUrl('x', 20)).searchParams.get('gsrlimit'), '50');
  });
});

describe('helpers', () => {
  it('strips HTML and decodes entities', () => {
    assert.equal(
      stripHtml('<bdi><a href="//commons.wikimedia.org/wiki/User:X" title="User:X">Ralf&nbsp;Roletschek</a></bdi> (<a href="/t">talk</a>)'),
      'Ralf Roletschek (talk)',
    );
    assert.equal(stripHtml('A &amp; B &#8211; C &#x263A;<br/>D'), 'A & B – C ☺ D');
    assert.equal(stripHtml(undefined), '');
  });

  it('moves thumbnails to upload.wikimedia.org and drops tracking parameters', () => {
    assert.equal(
      canonicalImageUrl('https://thumb.wikimedia.org/wikipedia/commons/thumb/8/87/A%2C_B.jpg/1280px-A%2C_B.jpg?utm_source=commons.wikimedia.org&utm_campaign=imageinfo'),
      'https://upload.wikimedia.org/wikipedia/commons/thumb/8/87/A%2C_B.jpg/1280px-A%2C_B.jpg',
    );
    assert.equal(
      canonicalImageUrl('https://upload.wikimedia.org/wikipedia/commons/1/14/X.jpg?utm_content=original'),
      'https://upload.wikimedia.org/wikipedia/commons/1/14/X.jpg',
    );
    assert.equal(canonicalImageUrl('https://example.com/x.jpg'), null);
    assert.equal(canonicalImageUrl('kein link'), null);
  });

  it('normalizes multi-version licenses to the first version', () => {
    assert.equal(normalizeLicense('CC BY-SA 3.0,2.5,2.0,1.0'), 'CC BY-SA 3.0');
    assert.equal(normalizeLicense('CC BY 2.0'), 'CC BY 2.0');
    assert.equal(normalizeLicense(' Public  domain '), 'Public domain');
  });
});

describe('parseCandidates (recorded API response)', () => {
  const { candidates, rejected } = parseCandidates(RECORDED, { limit: 20 });

  it('keeps only freely licensed files and says why the others were dropped', () => {
    assert.equal(candidates.length, 16);
    assert.deepEqual(rejected, { Lizenz: 4 });
    for (const c of candidates) {
      assert.ok(isFreeLicense(c.license), c.license);
      if (licenseNeedsCredit(c.license)) assert.ok(c.credit.length > 0, c.title);
      assert.ok(!/[<>]/.test(c.credit), c.credit);
    }
  });

  it('returns validator-ready URLs on upload.wikimedia.org without tracking', () => {
    for (const c of candidates) {
      assert.ok(c.url.startsWith(IMAGE_URL_PREFIX), c.url);
      assert.ok(c.sourceUrl.startsWith(IMAGE_SOURCE_PREFIX), c.sourceUrl);
      assert.ok(!c.url.includes('utm_'), c.url);
    }
  });

  it('describes the delivered thumbnail and lists landscape pictures first', () => {
    const [first] = candidates;
    assert.deepEqual(first, {
      url: 'https://upload.wikimedia.org/wikipedia/commons/thumb/8/84/L01_542_Bf_Leipzig_Hbf%2C_ex_Stw_1.jpg/1280px-L01_542_Bf_Leipzig_Hbf%2C_ex_Stw_1.jpg',
      credit: 'Falk2',
      license: 'CC BY-SA 4.0',
      sourceUrl: 'https://commons.wikimedia.org/wiki/File:L01_542_Bf_Leipzig_Hbf,_ex_Stw_1.jpg',
      width: 1280,
      height: 853,
      title: 'File:L01 542 Bf Leipzig Hbf, ex Stw 1.jpg',
      description: first.description,
    });
    assert.ok(first.description.startsWith('Beim Umbau der Gleise 20 bis 23'));
    assert.ok(candidates.every((c) => c.width > c.height && c.width >= 1000));
  });

  it('respects the limit', () => {
    assert.equal(parseCandidates(RECORDED, { limit: 3 }).candidates.length, 3);
    assert.equal(parseCandidates(RECORDED).candidates.length, 5);
  });
});

describe('parseCandidates (edge cases)', () => {
  it('drops non-free, trademarked, logo and uncredited files', () => {
    const { candidates, rejected } = parseCandidates(
      response(
        page(1, { meta: { LicenseShortName: 'CC BY-NC 2.0' } }),
        page(2, { meta: { NonFree: 'true' } }),
        page(3, { meta: { Restrictions: 'trademarked' } }),
        page(4, { title: 'File:Tagesschau Logo 2026.png' }),
        page(5, { meta: { Artist: '<span>Unknown author</span>' } }),
        page(6, { meta: { Artist: undefined } }),
        page(7),
      ),
    );
    assert.deepEqual(candidates.map((c) => c.title), ['File:Bild 7.jpg']);
    assert.deepEqual(rejected, { Lizenz: 1, 'nicht frei': 1, 'Logo/Marke': 2, 'kein Urheber': 2 });
  });

  it('accepts public-domain files without author', () => {
    const { candidates } = parseCandidates(response(page(1, { meta: { LicenseShortName: 'Public domain', Artist: 'Unknown author' } })));
    assert.equal(candidates.length, 1);
    assert.equal(candidates[0].credit, '');
    assert.equal(candidates[0].license, 'Public domain');
  });

  it('uses the real size of originals smaller than the thumbnail and drops tiny ones', () => {
    const small = page(1, {
      info: { width: 820, height: 533, thumbwidth: 1280, thumbheight: 832, thumburl: 'https://upload.wikimedia.org/wikipedia/commons/1/14/Klein.jpg?utm_source=x' },
    });
    const tiny = page(2, { info: { width: MIN_WIDTH - 1, height: 400, thumbwidth: 1280, thumbheight: 800 } });
    const { candidates, rejected } = parseCandidates(response(small, tiny));
    assert.equal(candidates.length, 1);
    assert.equal(candidates[0].url, 'https://upload.wikimedia.org/wikipedia/commons/1/14/Klein.jpg');
    assert.deepEqual([candidates[0].width, candidates[0].height], [820, 533]);
    assert.deepEqual(rejected, { 'zu klein': 1 });
  });

  it('prefers wide landscape pictures, then keeps the search order', () => {
    const portrait = page(1, { info: { thumbwidth: 1280, thumbheight: 1700 } });
    const narrow = page(2, { info: { width: 900, height: 600, thumbwidth: 900, thumbheight: 600 } });
    const wide = page(3);
    const wide2 = page(4);
    const { candidates } = parseCandidates(response(portrait, narrow, wide2, wide));
    assert.deepEqual(candidates.map((c) => c.title), ['File:Bild 3.jpg', 'File:Bild 4.jpg', 'File:Bild 2.jpg', 'File:Bild 1.jpg']);
  });

  it('normalizes licenses and credits', () => {
    const { candidates } = parseCandidates(
      response(page(1, { meta: { LicenseShortName: 'CC BY-SA 3.0,2.5,2.0,1.0', Artist: 'A &amp; B' } })),
    );
    assert.equal(candidates[0].license, 'CC BY-SA 3.0');
    assert.equal(candidates[0].credit, 'A & B');
  });

  it('understands formatversion=2 arrays and empty results', () => {
    assert.equal(parseCandidates({ query: { pages: [page(1)] } }).candidates.length, 1);
    assert.deepEqual(parseCandidates({ batchcomplete: true }), { candidates: [], rejected: {} });
  });

  it('turns API errors into CommonsError', () => {
    assert.throws(() => parseCandidates({ error: { code: 'maxlag', info: 'Waiting' } }), (e) => e instanceof CommonsError && /maxlag/.test(e.message));
  });
});

/** Fake fetch answering with the given responses in order; records the calls. */
function fakeFetch(...answers) {
  const calls = [];
  const fn = async (url, init) => {
    calls.push({ url, init });
    const answer = answers[Math.min(calls.length - 1, answers.length - 1)];
    if (answer instanceof Error) throw answer;
    const { status = 200, body = RECORDED, headers = {} } = answer;
    return {
      status,
      ok: status >= 200 && status < 300,
      headers: { get: (name) => headers[name.toLowerCase()] ?? null },
      json: async () => {
        if (typeof body === 'string') throw new SyntaxError('Unexpected token');
        return body;
      },
    };
  };
  fn.calls = calls;
  return fn;
}

describe('findImages', () => {
  it('identifies itself with a descriptive User-Agent', async () => {
    const fetchImpl = fakeFetch({});
    const { candidates } = await findImages('Leipzig Hauptbahnhof', { fetchImpl, retryDelayMs: 0 });
    assert.equal(candidates.length, 5);
    assert.equal(fetchImpl.calls.length, 1);
    assert.equal(fetchImpl.calls[0].init.headers['User-Agent'], USER_AGENT);
    assert.match(USER_AGENT, /^MorgenbriefingRoutine\/1\.4 \(https:\/\/github\.com\/CedrickGD\/Morgenbriefing\)$/);
    assert.match(fetchImpl.calls[0].url, /gsrsearch=Leipzig\+Hauptbahnhof\+filetype%3Abitmap/);
  });

  it('retries rate limits, server errors and network failures', async () => {
    const fetchImpl = fakeFetch({ status: 429, headers: { 'retry-after': '0' } }, new TypeError('fetch failed'), {});
    const { candidates } = await findImages('x', { fetchImpl, retryDelayMs: 0 });
    assert.equal(candidates.length, 5);
    assert.equal(fetchImpl.calls.length, 3);
  });

  it('gives up after three attempts', async () => {
    const fetchImpl = fakeFetch({ status: 503 });
    await assert.rejects(findImages('x', { fetchImpl, retryDelayMs: 0 }), (e) => e instanceof CommonsError && /HTTP 503.*3 Versuchen/.test(e.message));
    assert.equal(fetchImpl.calls.length, 3);
  });

  it('does not retry client errors or broken JSON', async () => {
    const notFound = fakeFetch({ status: 404 });
    await assert.rejects(findImages('x', { fetchImpl: notFound, retryDelayMs: 0 }), /HTTP 404/);
    assert.equal(notFound.calls.length, 1);
    await assert.rejects(findImages('x', { fetchImpl: fakeFetch({ body: '<html>' }), retryDelayMs: 0 }), /kein gültiges JSON/);
  });
});

describe('find-image CLI', () => {
  const capture = () => {
    const lines = { out: [], err: [] };
    return { lines, out: (s) => lines.out.push(s), err: (s) => lines.err.push(s) };
  };

  it('prints the candidates as JSON on stdout and a summary on stderr', async () => {
    const io = capture();
    const code = await main(['Leipzig', 'Hauptbahnhof', '--limit', '2'], { ...io, fetchImpl: fakeFetch({}), retryDelayMs: 0 });
    assert.equal(code, 0);
    const parsed = JSON.parse(io.lines.out.join('\n'));
    assert.equal(parsed.length, 2);
    assert.deepEqual(Object.keys(parsed[0]), ['url', 'credit', 'license', 'sourceUrl', 'width', 'height', 'title', 'description']);
    assert.match(io.lines.err.join('\n'), /2 Kandidaten für „Leipzig Hauptbahnhof“ \(verworfen: 4× Lizenz\)/);
  });

  it('prints an empty list when nothing fits', async () => {
    const io = capture();
    const code = await main(['xyz'], { ...io, fetchImpl: fakeFetch({ body: { batchcomplete: '' } }), retryDelayMs: 0 });
    assert.equal(code, 0);
    assert.equal(io.lines.out.join(''), '[]');
  });

  it('exits 1 when Commons fails and 2 on usage errors', async () => {
    const io = capture();
    assert.equal(await main(['x'], { ...io, fetchImpl: fakeFetch({ status: 500 }), retryDelayMs: 0 }), 1);
    assert.match(io.lines.err.join('\n'), /FEHLER: Commons antwortet mit HTTP 500/);
    for (const argv of [[], ['x', '--limit', '0'], ['x', '--limit', '21'], ['x', '--limit', 'viele'], ['x', '--wat']]) {
      assert.equal(await main(argv, { ...capture(), fetchImpl: fakeFetch({}) }), 2, argv.join(' '));
    }
    assert.equal(await main(['--help'], capture()), 0);
  });
});
