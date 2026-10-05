import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import path from 'node:path';
import { afterEach, beforeEach, describe, it } from 'node:test';

import {
  MAX_RECIPIENTS,
  emailAddressProblem,
  isValidEmailAddress,
  main,
  normalizeAddress,
  readEmailConfig,
  validateEmailConfig,
} from '../email-config.mjs';
import { ROUTINE_DIR, tempDir } from './fixtures.mjs';

const SCRIPT = path.join(ROUTINE_DIR, 'email-config.mjs');
const BOM = String.fromCharCode(0xfeff);

function config(overrides = {}) {
  return {
    schema: 1,
    enabled: true,
    recipients: ['du@example.com'],
    updatedAt: '2026-10-06T21:14:03+02:00',
    ...overrides,
  };
}

describe('email addresses', () => {
  it('accepts plain addresses', () => {
    for (const address of [
      'du@example.com',
      'vorname.nachname+news@gmail.com',
      'x_y-z@sub.example.co.uk',
      'a%b@example.de',
      '1234@web.de',
      'info@xn--mller-kva.de',
      'kurz@a.io',
    ]) {
      assert.equal(emailAddressProblem(address), null, address);
      assert.ok(isValidEmailAddress(address));
    }
  });

  it('rejects everything that is not a single plain address', () => {
    for (const address of [
      '',
      'ohne-at.de',
      'zwei@@example.com',
      'a@b@example.com',
      '@example.com',
      'du@',
      'du@localhost',
      'Name <du@example.com>',
      'du@example.com, wer@example.com',
      'du@example.com;wer@example.com',
      'du@example.com\nbcc: wer@example.com',
      'du @example.com',
      '"du"@example.com',
      '.du@example.com',
      'du.@example.com',
      'd..u@example.com',
      'du@-example.com',
      'du@example-.com',
      'du@example..com',
      'du@example.c',
      'du@example.123',
      'du@[192.168.0.1]',
      'jürgen@example.com',
      'du@müller.de',
      `${'a'.repeat(65)}@example.com`,
      `du@${'a'.repeat(250)}.com`,
    ]) {
      assert.notEqual(emailAddressProblem(address), null, JSON.stringify(address));
      assert.equal(isValidEmailAddress(address), false);
    }
  });

  it('normalizes by trimming and lower-casing', () => {
    assert.equal(normalizeAddress('  Du@Example.COM '), 'du@example.com');
    assert.equal(normalizeAddress(42), 42);
  });
});

describe('validateEmailConfig', () => {
  it('accepts the default config', () => {
    const result = validateEmailConfig(config());
    assert.deepEqual(result.errors, []);
    assert.deepEqual(result.warnings, []);
    assert.deepEqual(result.config, { enabled: true, recipients: ['du@example.com'] });
  });

  it('accepts a switched-off config and an empty recipient list', () => {
    assert.deepEqual(validateEmailConfig(config({ enabled: false })).config, {
      enabled: false,
      recipients: ['du@example.com'],
    });
    assert.deepEqual(validateEmailConfig(config({ recipients: [] })).config, { enabled: true, recipients: [] });
  });

  it('allows up to ten recipients, not more', () => {
    const ten = Array.from({ length: MAX_RECIPIENTS }, (_, i) => `person${i}@example.com`);
    assert.equal(validateEmailConfig(config({ recipients: ten })).config.recipients.length, 10);
    const eleven = [...ten, 'elf@example.com'];
    const result = validateEmailConfig(config({ recipients: eleven }));
    assert.equal(result.config, null);
    assert.match(result.errors.join('\n'), /11 Einträge – höchstens 10/);
  });

  it('normalizes recipients and drops duplicates with a warning', () => {
    const result = validateEmailConfig(config({ recipients: [' Du@Example.com', 'du@example.com', 'Wer@Example.com'] }));
    assert.deepEqual(result.errors, []);
    assert.deepEqual(result.config.recipients, ['du@example.com', 'wer@example.com']);
    assert.equal(result.warnings.length, 3);
    assert.match(result.warnings.join('\n'), /doppelt/);
  });

  it('rejects an invalid address anywhere in the list (nothing is sent then)', () => {
    const result = validateEmailConfig(config({ recipients: ['du@example.com', 'Chef <chef@example.com>'] }));
    assert.equal(result.config, null);
    assert.match(result.errors[0], /^Empfänger 2/);
  });

  it('requires schema 1, a boolean switch and a recipient list', () => {
    const cases = [
      [{ schema: undefined }, /"schema" fehlt/],
      [{ schema: 2 }, /nur Schema 1/],
      [{ schema: '1' }, /nur Schema 1/],
      [{ enabled: undefined }, /"enabled" fehlt/],
      [{ enabled: 'ja' }, /true oder false/],
      [{ enabled: 1 }, /true oder false/],
      [{ recipients: undefined }, /"recipients" fehlt/],
      [{ recipients: 'du@example.com' }, /Liste/],
      [{ recipients: [42] }, /kein Text/],
      [{ updatedAt: '2026-10-06 21:14' }, /"updatedAt"/],
      [{ updatedAt: '2026-10-06T21:14:03' }, /"updatedAt"/],
    ];
    for (const [overrides, pattern] of cases) {
      const doc = config(overrides);
      for (const [key, value] of Object.entries(overrides)) if (value === undefined) delete doc[key];
      const result = validateEmailConfig(doc);
      assert.equal(result.config, null, JSON.stringify(overrides));
      assert.match(result.errors.join('\n'), pattern, JSON.stringify(overrides));
    }
  });

  it('treats updatedAt as optional and ignores unknown fields with a warning', () => {
    const doc = config({ note: 'x' });
    delete doc.updatedAt;
    const result = validateEmailConfig(doc);
    assert.deepEqual(result.config, { enabled: true, recipients: ['du@example.com'] });
    assert.match(result.warnings.join('\n'), /Unbekannte Felder.*note/);
  });

  it('rejects non-objects', () => {
    for (const doc of [null, [], 'text', 1]) {
      assert.equal(validateEmailConfig(doc).config, null);
    }
  });
});

describe('email-config CLI', () => {
  let dir;
  let cleanup;
  const write = (name, content) => {
    const file = path.join(dir, name);
    writeFileSync(file, typeof content === 'string' ? content : JSON.stringify(content), 'utf8');
    return file;
  };
  const run = (...args) => spawnSync(process.execPath, [SCRIPT, ...args], { encoding: 'utf8' });

  beforeEach(() => {
    ({ dir, cleanup } = tempDir());
  });
  afterEach(() => cleanup());

  it('prints exactly one JSON line for a valid config', () => {
    const result = run(write('email.json', config({ recipients: ['Du@Example.com'] })));
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.stdout, '{"enabled":true,"recipients":["du@example.com"]}\n');
    assert.match(result.stderr, /Hinweis: .*vereinheitlicht/);
  });

  it('tolerates a BOM', () => {
    const result = run(write('email.json', `${BOM}${JSON.stringify(config())}`));
    assert.equal(result.status, 0, result.stderr);
    assert.deepEqual(JSON.parse(result.stdout), { enabled: true, recipients: ['du@example.com'] });
  });

  it('fails with a German message for invalid, broken and missing files', () => {
    const invalid = run(write('email.json', config({ enabled: 'ja' })));
    assert.equal(invalid.status, 1);
    assert.equal(invalid.stdout, '');
    assert.match(invalid.stderr, /FEHLER: E-Mail-Einstellungen .* ungültig – es wird nichts verschickt/);

    const broken = run(write('broken.json', '{ "schema": 1,'));
    assert.equal(broken.status, 1);
    assert.match(broken.stderr, /kein gültiges JSON/);

    const missing = run(path.join(dir, 'gibt-es-nicht.json'));
    assert.equal(missing.status, 1);
    assert.match(missing.stderr, /Datei nicht gefunden/);
  });

  it('reports usage errors with exit code 2 and shows help', () => {
    assert.equal(run().status, 2);
    assert.equal(run('a.json', 'b.json').status, 2);
    assert.equal(run('--verbose', 'a.json').status, 2);
    const help = run('--help');
    assert.equal(help.status, 0);
    assert.match(help.stdout, /Aufruf:/);
  });

  it('main() returns the exit code without exiting', () => {
    const lines = [];
    const file = write('email.json', config());
    assert.equal(main([file], (l) => lines.push(l), () => {}), 0);
    assert.deepEqual(lines, ['{"enabled":true,"recipients":["du@example.com"]}']);
    assert.deepEqual(readEmailConfig(file).config, { enabled: true, recipients: ['du@example.com'] });
  });
});
