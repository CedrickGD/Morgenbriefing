/**
 * Consistency checks for routine/PROMPT.md: every topic has a table row and a Quellenkarte entry
 * with 4–8 reachable sources, no reachable source is on the "nicht abrufbar" list, the template
 * placeholders are explained, and every script option the prompt uses exists.
 */
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, it } from 'node:test';

import { TOPICS } from '../validate-brief.mjs';
import { ROUTINE_DIR } from './fixtures.mjs';

const PROMPT = readFileSync(path.join(ROUTINE_DIR, 'PROMPT.md'), 'utf8').replace(/\r\n/g, '\n');

/** The text of a `## …` / `### …` section up to the next heading of the same or a higher level. */
function section(title) {
  const start = PROMPT.indexOf(title);
  assert.notEqual(start, -1, `Abschnitt "${title}" fehlt`);
  const level = /^#+/.exec(title)[0].length;
  const rest = PROMPT.slice(start + title.length);
  const next = rest.search(new RegExp(`\\n#{1,${level}} `));
  return next === -1 ? rest : rest.slice(0, next);
}

/** Quellenkarte entry per topic id: the bullet text, joined into one line. */
function sourceMap() {
  const entries = new Map();
  const text = section('### Quellenkarte');
  const re = /^- \*\*`([a-z]+)`\*\*: ([\s\S]*?)(?=\n- \*\*`|\n\n|(?![\s\S]))/gm;
  for (const m of text.matchAll(re)) entries.set(m[1], m[2].replace(/\s*\n\s*/g, ' '));
  return entries;
}

/** Host of a Quellenkarte item ("https://www.x.de/a (…)" or "x.de (Lauf)"), without "www.". */
function hostOf(item) {
  const token = item.trim().split(/\s+/)[0];
  const host = token.startsWith('https://') ? new URL(token).hostname : token.replace(/[.,;]$/, '');
  return host.replace(/^www\./, '');
}

/** Domains on the "NICHT ABRUFBAR" list of the template. */
function blockedDomains() {
  const start = PROMPT.indexOf('NICHT ABRUFBAR – gar nicht erst versuchen');
  const end = PROMPT.indexOf('Treffer dieser Seiten', start);
  assert.ok(start !== -1 && end !== -1, 'Liste NICHT ABRUFBAR fehlt');
  return new Set(PROMPT.slice(start, end).match(/\b[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:de|com|org|ch|at|net)\b/g));
}

describe('PROMPT.md', () => {
  it('has a table row and a Quellenkarte entry for every topic', () => {
    const entries = sourceMap();
    for (const topic of TOPICS) {
      assert.match(PROMPT, new RegExp(`^\\| \`${topic.id}\` \\| ${topic.title.replace('&', '\\&')} \\| ${topic.emoji} \\|`, 'm'), topic.id);
      assert.ok(entries.has(topic.id), `Quellenkarte ohne ${topic.id}`);
    }
    assert.deepEqual([...entries.keys()], TOPICS.map((t) => t.id), 'Quellenkarte in Katalog-Reihenfolge');
  });

  it('lists 4–8 reachable sources per topic, none of them blocked', () => {
    const blocked = blockedDomains();
    assert.ok(blocked.has('tagesschau.de') && blocked.has('dw.com') && blocked.has('kicker.de'));
    for (const [id, entry] of sourceMap()) {
      const reachable = entry.split(/ Ungetestet: /)[0];
      const items = reachable.split(' · ');
      assert.ok(items.length >= 4 && items.length <= 8, `${id}: ${items.length} Quellen`);
      for (const item of items) {
        const host = hostOf(item);
        assert.match(host, /^[a-z0-9.-]+\.[a-z]+$/, `${id}: "${item}"`);
        for (const b of blocked) {
          assert.ok(host !== b && !host.endsWith(`.${b}`), `${id}: ${host} steht auf NICHT ABRUFBAR`);
        }
      }
    }
  });

  it('explains every placeholder of the templates', () => {
    const used = new Set(PROMPT.match(/\{\{[A-Z_]+\}\}/g));
    for (const name of ['{{ID}}', '{{TITEL}}', '{{EMOJI}}', '{{SCHWERPUNKT}}', '{{QUELLEN}}', '{{HEUTE}}',
      '{{JETZT}}', '{{FRIST}}', '{{ERSTE_ANTWORT}}']) {
      assert.ok(used.has(name), `${name} wird nicht benutzt`);
      used.delete(name);
    }
    assert.deepEqual([...used], [], 'unbekannte Platzhalter');
    const instructions = section('## Schritt 2');
    for (const name of ['{{ID}}', '{{QUELLEN}}', '{{FRIST}}', '{{ERSTE_ANTWORT}}']) {
      assert.ok(instructions.includes(`\`${name}\``), `Schritt 2 erklärt ${name} nicht`);
    }
  });

  it('uses only options the scripts know', () => {
    const calls = [...PROMPT.matchAll(/node routine\/([a-z-]+)\.mjs([^\n`]*)/g)];
    assert.ok(calls.length >= 10);
    const help = new Map();
    for (const [, script, args] of calls) {
      if (!help.has(script)) {
        const run = spawnSync(process.execPath, [path.join(ROUTINE_DIR, `${script}.mjs`), '--help'], { encoding: 'utf8' });
        assert.equal(run.status, 0, `${script} --help`);
        help.set(script, run.stdout);
      }
      for (const flag of args.match(/--[a-z][a-z-]*/g) ?? []) {
        assert.ok(help.get(script).includes(flag), `${script}.mjs kennt ${flag} nicht`);
      }
    }
  });

  it('publishes to the target branch and keeps the 04:50 budget', () => {
    assert.match(PROMPT, /node routine\/publish-brief\.mjs --print-branch/);
    assert.match(PROMPT, /--data-dir \/tmp\/briefs --max-age-hours 36 --branch <ZWEIG>/);
    assert.match(PROMPT, /git -C \/tmp\/briefs push origin <ZWEIG>\n/);
    assert.match(PROMPT, /## Manueller Testlauf/);
    assert.match(PROMPT, /spätestens um \*\*04:50\*\*/);
    assert.doesNotMatch(PROMPT, /Zügig arbeiten/);
  });
});
