import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { afterEach, beforeEach, describe, it } from 'node:test';

import {
  FOOTER_TEXT,
  HTML_BUDGET_BYTES,
  PLACES,
  SUBJECT_MAX,
  USER_AGENT,
  buildForecastUrl,
  buildSubject,
  buildWeather,
  clothingHint,
  conditionOf,
  escapeHtml,
  fetchWeather,
  formatTemp,
  germanDate,
  main,
  newsSections,
  renderEmail,
  safeHttpsUrl,
  weatherHeadline,
} from '../render-email.mjs';
import { TOPICS } from '../validate-brief.mjs';
import { FIXTURES_DIR, ROUTINE_DIR, SAMPLE_BRIEF, clone, makeBriefV2, tempDir } from './fixtures.mjs';

const SCRIPT = path.join(ROUTINE_DIR, 'render-email.mjs');
const WEATHER_FILE = path.join(FIXTURES_DIR, 'open-meteo-weather.json');
const RAW_WEATHER = JSON.parse(readFileSync(WEATHER_FILE, 'utf8'));
const SAMPLE = JSON.parse(readFileSync(SAMPLE_BRIEF, 'utf8'));
/** 05:20 in Berlin on the day after the demo brief – the fixture covers 2026-10-05 and -06. */
const MORNING = Date.parse('2026-10-06T05:20:00+02:00');
const EVIL = '<script>alert("x")</script>';

const weatherAt = (nowMs = MORNING, raw = RAW_WEATHER) => buildWeather(clone(raw), nowMs);
const render = (brief = SAMPLE, weather = weatherAt(), nowMs = MORNING) => renderEmail({ brief, weather, nowMs });

/** All values of `attr="…"` in the HTML. */
function attributeValues(html, attr) {
  return [...html.matchAll(new RegExp(`\\s${attr}="([^"]*)"`, 'g'))].map((m) => m[1].replace(/&amp;/g, '&'));
}

/** Sets hourly values of the Leipzig forecast for the hours 10–18 of `date`. */
function withLeipzigHours(raw, date, values) {
  const copy = clone(raw);
  const h = copy.day.hourly;
  h.time.forEach((time, i) => {
    const hour = Number(time.slice(11, 13));
    if (time.startsWith(date) && hour >= 10 && hour <= 18) {
      for (const [field, value] of Object.entries(values)) h[field][i] = typeof value === 'function' ? value(hour, h[field][i]) : value;
    }
  });
  return copy;
}

describe('helpers', () => {
  it('escapes HTML special characters', () => {
    assert.equal(escapeHtml(`<a href="x" onclick='y'>&</a>`), '&lt;a href=&quot;x&quot; onclick=&#39;y&#39;&gt;&amp;&lt;/a&gt;');
  });

  it('accepts only absolute https URLs', () => {
    assert.equal(safeHttpsUrl('https://www.tagesschau.de/a?b=1&c=2'), 'https://www.tagesschau.de/a?b=1&c=2');
    for (const bad of ['http://example.com/', 'javascript:alert(1)', 'data:text/html,x', '//example.com', 'https://localhost/', 'https://user:pw@example.com/', 'https://exa mple.com', '', null]) {
      assert.equal(safeHttpsUrl(bad), null, String(bad));
    }
  });

  it('formats temperatures and dates like the app', () => {
    assert.equal(formatTemp(9.4), '9°');
    assert.equal(formatTemp(2.5), '3°');
    assert.equal(formatTemp(-2.5), '-3°');
    assert.equal(formatTemp(-0.4), '0°');
    assert.deepEqual(germanDate('2026-10-06'), { long: 'Dienstag, 6. Oktober 2026', short: 'Di, 6.10.' });
    assert.deepEqual(germanDate('2027-03-01'), { long: 'Montag, 1. März 2027', short: 'Mo, 1.3.' });
  });

  it('maps WMO codes like the app', () => {
    assert.equal(conditionOf(0).emoji(true), '☀️');
    assert.equal(conditionOf(0).emoji(false), '🌙');
    assert.equal(conditionOf(45).key, 'FOG');
    assert.equal(conditionOf(63).wet, true);
    assert.equal(conditionOf(73).wintry, true);
    assert.equal(conditionOf(999).key, 'OVERCAST');
  });

  it('builds a keyless Open-Meteo request in Berlin time', () => {
    const url = new URL(buildForecastUrl(PLACES.home));
    assert.equal(url.origin + url.pathname, 'https://api.open-meteo.com/v1/forecast');
    assert.equal(url.searchParams.get('latitude'), '51.5928');
    assert.equal(url.searchParams.get('longitude'), '12.5853');
    assert.equal(url.searchParams.get('timezone'), 'Europe/Berlin');
    assert.match(url.searchParams.get('hourly'), /precipitation_probability/);
  });
});

describe('buildWeather', () => {
  it('builds home (current + next hours) and the Leipzig day window', () => {
    const w = weatherAt();
    assert.deepEqual(w.problems, []);
    // `current` in the fixture is from 23:15 the evening before → the 05:00 forecast is used.
    assert.equal(w.home.current.temp, 8.7);
    assert.equal(w.home.current.code, 45);
    assert.deepEqual(w.home.nextHours.map((p) => p.hour), [6, 7, 8, 9]);
    assert.equal(w.home.sunrise, '07:18');
    assert.equal(w.day.date, '2026-10-06');
    assert.equal(w.day.isTomorrow, false);
    assert.deepEqual(w.day.hours.map((p) => p.hour), [10, 11, 12, 13, 14, 15, 16, 17, 18]);
    assert.equal(formatTemp(w.day.minTemp), '13°');
    assert.equal(formatTemp(w.day.maxTemp), '22°');
    assert.equal(w.day.maxPop, 0);
  });

  it('uses fresh current values', () => {
    const raw = clone(RAW_WEATHER);
    Object.assign(raw.home.current, { time: '2026-10-06T05:15', temperature_2m: 7.4, apparent_temperature: 5.1, weather_code: 3 });
    const w = buildWeather(raw, MORNING);
    assert.equal(w.home.current.temp, 7.4);
    assert.equal(w.home.current.feels, 5.1);
    assert.equal(w.home.current.code, 3);
  });

  it('switches to tomorrow after the window and reports unusable parts', () => {
    const evening = Date.parse('2026-10-05T20:00:00+02:00');
    assert.equal(weatherAt(evening).day.isTomorrow, true);
    assert.equal(weatherAt(evening).day.date, '2026-10-06');

    const late = buildWeather(RAW_WEATHER, Date.parse('2026-10-06T20:00:00+02:00'));
    assert.equal(late.day, null); // the fixture has no 2026-10-07
    assert.match(late.problems.join(), /keine Vorhersage für den 2026-10-07/);

    const empty = buildWeather({ home: {}, day: 'kaputt' }, MORNING);
    assert.equal(empty.home, null);
    assert.equal(empty.day, null);
    assert.equal(empty.problems.length, 2);
  });
});

describe('weather texts', () => {
  it('writes a friendly headline', () => {
    assert.equal(weatherHeadline(weatherAt(), MORNING), 'Guten Morgen! Nebel zum Start, dann Sonne satt bei bis zu 22° in Leipzig.');
    assert.equal(weatherHeadline({ home: null, day: null }, MORNING), 'Guten Morgen! Hier ist dein Überblick für heute.');
    const rainy = buildWeather(withLeipzigHours(RAW_WEATHER, '2026-10-06', { precipitation_probability: (h) => (h >= 15 ? 80 : 10), weather_code: (h, c) => (h >= 15 ? 61 : c) }), MORNING);
    assert.equal(weatherHeadline(rainy, MORNING), 'Guten Morgen! Nebel zum Start, dann ab 15 Uhr Regen, bis 22° in Leipzig.');
    const clearStart = clone(RAW_WEATHER);
    clearStart.home.hourly.weather_code = clearStart.home.hourly.weather_code.map(() => 3);
    assert.equal(weatherHeadline(buildWeather(clearStart, MORNING), MORNING), 'Guten Morgen! Sonne satt bei bis zu 22° in Leipzig.');
    const evening = Date.parse('2026-10-05T20:00:00+02:00');
    assert.equal(weatherHeadline(weatherAt(evening), evening), 'Guten Abend! Morgen in Leipzig: Sonne satt bei bis zu 22°.');
  });

  it('gives a clothing hint from simple rules', () => {
    assert.deepEqual(clothingHint(weatherAt()), {
      emoji: '🧥',
      text: 'Morgens Jacke oder Fleece, ab 15 Uhr reicht ein T-Shirt. Kein Schirm nötig.',
    });

    const rain = buildWeather(withLeipzigHours(RAW_WEATHER, '2026-10-06', { precipitation_probability: (h) => (h >= 14 ? 70 : 0) }), MORNING);
    assert.match(clothingHint(rain).text, /Schirm einpacken – ab 14 Uhr wird's nass\./);

    const storm = buildWeather(withLeipzigHours(RAW_WEATHER, '2026-10-06', { precipitation_probability: 70, wind_gusts_10m: 55 }), MORNING);
    assert.match(clothingHint(storm).text, /Regenjacke statt Schirm – zu windig\./);

    const winter = clone(RAW_WEATHER);
    winter.home.hourly.apparent_temperature = winter.home.hourly.apparent_temperature.map(() => -3);
    winter.day.hourly.apparent_temperature = winter.day.hourly.apparent_temperature.map(() => -1);
    winter.day.hourly.temperature_2m = winter.day.hourly.temperature_2m.map(() => 0.5);
    winter.day.hourly.weather_code = winter.day.hourly.weather_code.map(() => 71);
    const cold = clothingHint(buildWeather(winter, MORNING));
    assert.equal(cold.emoji, '🧤');
    assert.match(cold.text, /^Den ganzen Tag Winterjacke, Mütze und Handschuhe\./);
    assert.match(cold.text, /Feste Schuhe an – es kann glatt sein\./);

    const sunny = clone(RAW_WEATHER);
    sunny.day.daily.uv_index_max = [6, 6];
    assert.match(clothingHint(buildWeather(sunny, MORNING)).text, /Sonnencreme nicht vergessen\.$/);
  });
});

describe('subject', () => {
  it('has weather, count and date and stays short', () => {
    const w = weatherAt();
    assert.equal(buildSubject({ date: '2026-10-06', itemCount: 21, weather: w }), '🌫️ 9° in Bad Düben · 21 Meldungen · Di, 6.10.');
    assert.equal(buildSubject({ date: '2026-10-06', itemCount: 1, weather: { home: null, day: null } }), '☀️ Dein Morgenbriefing · 1 Meldung · Di, 6.10.');
    assert.equal(buildSubject({ date: '2026-10-06', itemCount: 5, weather: { home: null, day: w.day } }), '☀️ 13° → 22° in Leipzig · 5 Meldungen · Di, 6.10.');
    for (const count of [0, 1, 9, 80, 1000]) {
      assert.ok(Array.from(buildSubject({ date: '2026-12-31', itemCount: count, weather: w })).length <= SUBJECT_MAX);
    }
  });
});

describe('renderEmail with the demo brief', () => {
  const email = render();

  it('renders subject, preheader, HTML and plain text', () => {
    assert.equal(email.subject, '🌫️ 9° in Bad Düben · 12 Meldungen · Mo, 5.10.');
    assert.ok(Array.from(email.subject).length <= SUBJECT_MAX);
    assert.ok(email.html.startsWith('<!DOCTYPE html>'));
    assert.match(email.html, /<html lang="de"/);
    assert.ok(email.html.includes(`${escapeHtml(SAMPLE.teaser)}&zwnj;`), 'preheader carries the teaser');
    assert.match(email.html, /<title>🌫️ 9° in Bad Düben/);
    assert.ok(email.text.length > 1000);
    assert.equal(email.stats.items, 12);
    assert.equal(email.stats.sections, 4);
    assert.equal(email.stats.fullArticles, 8);
    assert.equal(email.stats.shortened, false);
  });

  it('contains date line, headline, weather and clothing hint', () => {
    for (const part of [
      'Morgenbriefing · Montag, 5. Oktober 2026',
      'Guten Morgen! Nebel zum Start, dann Sonne satt bei bis zu 22° in Leipzig.',
      'Vor der Tür · Bad Düben',
      'Leipzig · 10–18 Uhr',
      '13° → 22°',
      'Anziehen:',
      'Sonnenaufgang 07:18',
    ]) {
      assert.ok(email.html.includes(escapeHtml(part)), part);
    }
    assert.match(email.text, /^MORGENBRIEFING · MONTAG, 5\. OKTOBER 2026$/m);
    assert.match(email.text, /^VOR DER TÜR · BAD DÜBEN$/m);
    assert.match(email.text, /^LEIPZIG · 10–18 UHR$/m);
    assert.match(email.text, /^18 Uhr {2}☀️ 21° {2}Regen 0.%$/m);
  });

  it('lists every section in catalogue order with top articles and the further headlines', () => {
    const order = ['🤖 KI &amp; Tech', '📈 Wirtschaft &amp; Märkte', '🏛️ Politik', '📍 Leipzig &amp; Region'].map((t) => email.html.indexOf(t));
    assert.ok(order.every((i) => i > 0));
    assert.deepEqual([...order].sort((a, b) => a - b), order);
    for (const section of SAMPLE.sections) {
      for (const item of section.items) {
        assert.ok(email.html.includes(escapeHtml(item.headline)), item.headline);
        assert.ok(email.text.includes(item.headline), item.headline);
        if (item.tier === 'top') {
          assert.ok(email.html.includes(escapeHtml(item.summary)), item.summary);
          assert.ok(email.html.includes(`href="${escapeHtml(new URL(item.url).href)}"`), item.url);
          assert.ok(email.text.includes(`Quelle: ${item.source} – ${new URL(item.url).href}`));
        }
      }
    }
    assert.match(email.html, /Mehr in der App:<\/b> Gemini gratis bald nur noch mit Flash-Lite/);
  });

  it('only links https and loads pictures only from upload.wikimedia.org, with alt texts', () => {
    const hrefs = attributeValues(email.html, 'href');
    assert.ok(hrefs.length >= 10);
    for (const href of hrefs) assert.ok(href.startsWith('https://'), href);
    const images = [...email.html.matchAll(/<img\b[^>]*>/g)].map((m) => m[0]);
    assert.equal(images.length, 5);
    for (const img of images) {
      assert.match(img, /\ssrc="https:\/\/upload\.wikimedia\.org\//);
      assert.match(img, /\salt="[^"]{5,}"/);
    }
    assert.match(email.html, /Bild: <a href="https:\/\/commons\.wikimedia\.org\/wiki\/File:[^"]+"[^>]*>Nishkid64 · Public domain · Wikimedia Commons<\/a>/);
  });

  it('has the footer with how to unsubscribe and the attributions', () => {
    assert.ok(email.html.includes(escapeHtml(FOOTER_TEXT)));
    assert.ok(email.text.includes(FOOTER_TEXT));
    assert.match(email.html, /Open-Meteo\.com<\/a> \(CC BY 4\.0\)/);
  });

  it('is e-mail safe: tables, inline styles, max 600 px, no scripts, short lines', () => {
    assert.match(email.html, /max-width:600px/);
    assert.match(email.html, /<meta name="color-scheme" content="light dark">/);
    assert.doesNotMatch(email.html, /<script|<link|<form|<iframe|<video|position:\s*(?:absolute|fixed)/i);
    assert.doesNotMatch(email.html, /background:#fff(?:fff)?\b/i, 'no pure white backgrounds');
    assert.ok(Buffer.byteLength(email.html) < HTML_BUDGET_BYTES);
    for (const line of email.html.split('\n')) assert.ok(line.length < 1500, line.slice(0, 80));
  });
});

describe('renderEmail safety', () => {
  function hostileBrief() {
    const brief = clone(SAMPLE);
    brief.teaser = `Teaser ${EVIL}`;
    const [ki] = brief.sections;
    ki.items[0].headline = `Schlagzeile ${EVIL}`;
    ki.items[0].summary = `Vorspann "mit" <b>HTML</b> & ${EVIL}`;
    ki.items[0].source = `Quelle ${EVIL}`;
    ki.items[1].image.alt = `Alt " onerror="alert(1)`;
    ki.items[1].image.credit = `<img src=x onerror=alert(1)>`;
    ki.items[2].headline = `Mehr ${EVIL}`;
    brief.sections.push({
      id: 'unbekannt',
      title: `Rubrik ${EVIL}`,
      emoji: '🧪',
      items: [
        { ...clone(ki.items[0]), id: 'u-1', headline: 'Link mit javascript', url: 'javascript:alert(1)', image: null },
        { ...clone(ki.items[0]), id: 'u-2', headline: 'Link mit http', url: 'http://example.com/a', image: null },
        {
          ...clone(ki.items[1]),
          id: 'u-3',
          headline: 'Fremdes Bild',
          image: { ...ki.items[1].image, url: 'https://evil.example/tracker.gif' },
        },
      ],
    });
    return brief;
  }

  it('escapes every text from the brief', () => {
    const { html, text, preheader } = render(hostileBrief());
    assert.doesNotMatch(html, /<script/i);
    assert.doesNotMatch(html, /<img src=x/i);
    assert.doesNotMatch(html, /<b>HTML<\/b>/);
    assert.ok(html.includes('&lt;script&gt;alert(&quot;x&quot;)&lt;/script&gt;'));
    assert.ok(html.includes('Vorspann &quot;mit&quot; &lt;b&gt;HTML&lt;/b&gt; &amp;'));
    assert.ok(html.includes('alt="Alt &quot; onerror=&quot;alert(1)"'));
    assert.ok(html.includes('Rubrik &lt;script&gt;'));
    // The plain-text part carries the text as is (it is never interpreted as HTML).
    assert.ok(text.includes(`Schlagzeile ${EVIL}`));
    assert.equal(preheader, `Teaser ${EVIL}`);
  });

  it('drops non-https links and pictures from other hosts', () => {
    const { html } = render(hostileBrief());
    for (const href of attributeValues(html, 'href')) assert.ok(href.startsWith('https://'), href);
    for (const src of attributeValues(html, 'src')) assert.ok(src.startsWith('https://upload.wikimedia.org/'), src);
    assert.doesNotMatch(html, /javascript:|http:\/\/example|evil\.example/);
    assert.ok(html.includes('Link mit javascript'));
    assert.ok(html.includes('Fremdes Bild'));
  });

  it('sorts sections into catalogue order and drops empty ones', () => {
    const brief = clone(SAMPLE);
    brief.sections.reverse();
    brief.sections.push({ id: 'sport', title: 'Sport', emoji: '⚽', items: [] });
    assert.deepEqual(newsSections(brief).map((s) => s.id), ['ki', 'wirtschaft', 'politik', 'region']);
  });

  it('renders schema-1 briefs (all items as full entries)', () => {
    const v1 = {
      schema: 1,
      date: '2026-10-05',
      generatedAt: '2026-10-05T05:07:12+02:00',
      teaser: 'Kurz.',
      sections: [{ id: 'ki', title: 'KI & Tech', emoji: '🤖', items: [{ headline: 'Alt', summary: 'S', source: 'Q', url: 'https://example.org/a', importance: 5 }] }],
    };
    const email = render(v1);
    assert.equal(email.stats.fullArticles, 1);
    assert.doesNotMatch(email.html, /ganzer Artikel in der App/);
  });
});

describe('renderEmail without weather', () => {
  it('omits the weather block gracefully', () => {
    const email = renderEmail({ brief: SAMPLE, weather: null, nowMs: MORNING });
    assert.equal(email.subject, '☀️ Dein Morgenbriefing · 12 Meldungen · Mo, 5.10.');
    assert.doesNotMatch(email.html, /Vor der Tür|Anziehen|10–18 Uhr/);
    assert.match(email.html, /Guten Morgen! Hier ist dein Überblick für heute\./);
    assert.ok(email.html.includes(escapeHtml(FOOTER_TEXT)));
  });
});

describe('renderEmail size budget', () => {
  it('keeps a full ten-topic brief below the Gmail clipping limit', () => {
    const brief = makeBriefV2('2026-10-06', ['ki', 'wirtschaft', 'region']);
    brief.sections = TOPICS.map((topic, s) => {
      const base = makeBriefV2('2026-10-06', ['ki']).sections[0];
      const top = [0, 1, 2].map((i) => ({
        ...clone(base.items[1]),
        id: `${topic.id}-top-${i}`,
        headline: 'Ü'.repeat(120),
        summary: 'Ö'.repeat(300),
        url: `https://www.example.org/${topic.id}/${'pfad-'.repeat(30)}${i}`,
        image: { ...clone(base.items[1].image), url: `https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/${'Bild_'.repeat(20)}${s}${i}.jpg/1280px-${'Bild_'.repeat(20)}${s}${i}.jpg`, alt: 'Ä'.repeat(200), credit: 'C'.repeat(200) },
      }));
      const more = [0, 1, 2, 3, 4].map((i) => ({ ...clone(base.items[2]), id: `${topic.id}-more-${i}`, headline: `${'M'.repeat(110)} ${i}` }));
      return { id: topic.id, title: topic.title, emoji: topic.emoji, items: [...top, ...more] };
    });
    const email = render(brief);
    assert.equal(email.stats.items, 80);
    assert.ok(email.stats.htmlBytes <= HTML_BUDGET_BYTES, `${email.stats.htmlBytes} bytes`);
    for (const line of email.html.split('\n')) assert.ok(line.length < 2000);
  });

  it('shortens an oversized brief instead of letting Gmail clip the footer', () => {
    const base = makeBriefV2('2026-10-06', ['ki']).sections[0];
    const sections = Array.from({ length: 40 }, (_, s) => ({
      id: `extra-${s}`,
      title: `Rubrik ${s}`,
      emoji: '📰',
      items: [0, 1, 2].map((i) => ({ ...clone(base.items[1]), id: `x-${s}-${i}`, summary: 'S'.repeat(300), headline: `Schlagzeile ${s}-${i}` })),
    }));
    const email = render({ ...makeBriefV2('2026-10-06'), sections });
    assert.equal(email.stats.shortened, true);
    assert.ok(email.stats.htmlBytes <= HTML_BUDGET_BYTES, `${email.stats.htmlBytes} bytes`);
    assert.ok(email.html.includes('Schlagzeile 39-2'), 'shortened articles stay as headlines');
    assert.ok(email.html.includes(escapeHtml(FOOTER_TEXT)));
  });
});

describe('fetchWeather', () => {
  it('calls Open-Meteo for both places with a descriptive User-Agent', async () => {
    const calls = [];
    const fetchImpl = async (url, init) => {
      calls.push({ url, ua: init.headers['User-Agent'] });
      const lat = new URL(url).searchParams.get('latitude');
      return { ok: true, status: 200, json: async () => (lat === '51.5928' ? RAW_WEATHER.home : RAW_WEATHER.day) };
    };
    const raw = await fetchWeather({ fetchImpl });
    assert.equal(calls.length, 2);
    assert.ok(calls.every((c) => c.ua === USER_AGENT && /Morgenbriefing/.test(c.ua)));
    assert.deepEqual(raw, { home: RAW_WEATHER.home, day: RAW_WEATHER.day, problems: [] });
  });

  it('retries once, keeps the place that worked and explains the other', async () => {
    const calls = { home: 0, day: 0 };
    const fetchImpl = async (url) => {
      const isHome = new URL(url).searchParams.get('latitude') === '51.5928';
      calls[isHome ? 'home' : 'day']++;
      return isHome ? { ok: true, status: 200, json: async () => RAW_WEATHER.home } : { ok: false, status: 503 };
    };
    const raw = await fetchWeather({ fetchImpl, retryDelayMs: 0 });
    assert.deepEqual(calls, { home: 1, day: 2 });
    assert.equal(raw.day, null);
    assert.deepEqual(raw.problems, ['Open-Meteo (Leipzig): HTTP 503']);
    const weather = buildWeather(raw, MORNING);
    assert.notEqual(weather.home, null);
    assert.equal(weather.day, null);
    assert.deepEqual(weather.problems, []);
  });

  it('gives up with a German message when nothing can be fetched', async () => {
    const fetchImpl = async () => ({ ok: false, status: 404 });
    await assert.rejects(fetchWeather({ fetchImpl, retryDelayMs: 0 }), /Open-Meteo \(Bad Düben\): HTTP 404; Open-Meteo \(Leipzig\): HTTP 404/);
  });
});

describe('render-email CLI', () => {
  let dir;
  let cleanup;
  const run = (...args) => spawnSync(process.execPath, [SCRIPT, ...args], { encoding: 'utf8' });
  const read = (name) => readFileSync(path.join(dir, 'out', name), 'utf8');

  beforeEach(() => {
    ({ dir, cleanup } = tempDir());
  });
  afterEach(() => cleanup());

  it('writes email.html, email.txt and subject.txt', () => {
    const out = path.join(dir, 'out');
    const result = run('--brief', SAMPLE_BRIEF, '--out-dir', out, '--weather-file', WEATHER_FILE, '--now', '2026-10-06T05:20:00+02:00');
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /^OK {6}.* – E-Mail zum Briefing 2026-10-05$/m);
    assert.match(result.stdout, /Betreff: 🌫️ 9° in Bad Düben · 12 Meldungen · Mo, 5\.10\./);
    assert.equal(read('subject.txt'), '🌫️ 9° in Bad Düben · 12 Meldungen · Mo, 5.10.\n');
    assert.ok(read('email.html').includes(escapeHtml(FOOTER_TEXT)));
    assert.ok(read('email.txt').includes(FOOTER_TEXT));
  });

  it('works without weather', () => {
    const result = run('--brief', SAMPLE_BRIEF, '--out-dir', path.join(dir, 'out'), '--no-weather');
    assert.equal(result.status, 0, result.stderr);
    assert.match(read('subject.txt'), /^☀️ Dein Morgenbriefing · 12 Meldungen/);
  });

  it('falls back to no weather when Open-Meteo is unreachable', async () => {
    const lines = [];
    const errors = [];
    const fetchImpl = async () => {
      throw new TypeError('fetch failed');
    };
    const code = await main(['--brief', SAMPLE_BRIEF, '--out-dir', path.join(dir, 'out')], {
      out: (l) => lines.push(l),
      err: (l) => errors.push(l),
      fetchImpl,
      retryDelayMs: 0,
    });
    assert.equal(code, 0);
    assert.match(errors.join('\n'), /Hinweis: Wetter nicht verfügbar – Open-Meteo .*nicht erreichbar.*ohne Wetterblock/);
    assert.match(lines.join('\n'), /ohne Wetter \(nicht erreichbar\)/);
    assert.doesNotMatch(read('email.html'), /Vor der Tür/);
  });

  it('refuses a brief from another day with exit code 3', () => {
    const result = run('--brief', SAMPLE_BRIEF, '--out-dir', path.join(dir, 'out'), '--no-weather', '--expect-date', '2026-10-06');
    assert.equal(result.status, 3);
    assert.match(result.stderr, /Briefing ist vom 2026-10-05, nicht vom 2026-10-06/);
    assert.equal(existsSync(path.join(dir, 'out')), false);
  });

  it('rejects unusable input and bad calls', () => {
    const notBrief = path.join(dir, 'x.json');
    writeFileSync(notBrief, '{"days": []}');
    assert.equal(run('--brief', notBrief, '--out-dir', path.join(dir, 'out'), '--no-weather').status, 1);
    assert.equal(run('--brief', path.join(dir, 'fehlt.json'), '--out-dir', path.join(dir, 'out'), '--no-weather').status, 1);
    assert.equal(run('--brief', SAMPLE_BRIEF, '--out-dir', path.join(dir, 'out'), '--weather-file', path.join(dir, 'fehlt.json')).status, 1);
    assert.equal(run('--out-dir', path.join(dir, 'out')).status, 2);
    assert.equal(run('--brief', SAMPLE_BRIEF).status, 2);
    assert.equal(run('--brief', SAMPLE_BRIEF, '--out-dir', dir, '--now', '2026-10-06 05:20').status, 2);
    assert.equal(run('--brief', SAMPLE_BRIEF, '--out-dir', dir, '--expect-date', 'heute').status, 2);
    assert.equal(run('--brief', SAMPLE_BRIEF, '--out-dir', dir, '--no-weather', '--weather-file', WEATHER_FILE).status, 2);
    assert.equal(run('--brief', SAMPLE_BRIEF, '--out-dir', dir, '--bogus').status, 2);
    const help = run('--help');
    assert.equal(help.status, 0);
    assert.match(help.stdout, /Aufruf:/);
  });
});
