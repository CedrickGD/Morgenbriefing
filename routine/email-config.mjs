#!/usr/bin/env node
/**
 * Reads and validates `config/email.json` from the `briefs` data branch – the switch and the
 * recipient list of the daily e-mail (contract: docs/brief-format.md, "E-Mail-Briefing").
 * Zero dependencies, Node >= 18, ESM.
 *
 *   node routine/email-config.mjs <pfad/zu/email.json>
 *
 *   {
 *     "schema": 1,
 *     "enabled": true,
 *     "recipients": ["du@example.com"],
 *     "updatedAt": "2026-10-06T21:14:03+02:00"
 *   }
 *
 * On success it prints exactly one line of JSON to stdout, `{"enabled":true,"recipients":[…]}`,
 * and exits with 0. The e-mail routine sends to these addresses and to nothing else, so this
 * script is deliberately strict about what counts as an address: plain ASCII `name@domain.tld`,
 * no display names, no lists, no whitespace inside. Addresses are trimmed and lower-cased;
 * duplicates (after that) are dropped with a note on stderr.
 *
 * Exit codes: 0 = valid, 1 = invalid or unreadable (German message on stderr), 2 = usage error.
 */
import { JsonFileError, isMainModule, parseOffsetDateTime, readJsonFile } from './validate-brief.mjs';

/** The only config schema this script understands. */
export const EMAIL_CONFIG_SCHEMA = 1;
/** At most this many recipients (one message each). */
export const MAX_RECIPIENTS = 10;
/** RFC 5321 limits. */
export const MAX_ADDRESS_LENGTH = 254;
export const MAX_LOCAL_PART_LENGTH = 64;

const CONFIG_KEYS = new Set(['schema', 'enabled', 'recipients', 'updatedAt']);

// Local part: the characters every mail provider accepts (and Android's EMAIL_ADDRESS pattern
// allows), dot-separated – no leading, trailing or doubled dots.
const LOCAL_PART_RE = /^[a-z0-9_%+-]+(?:\.[a-z0-9_%+-]+)*$/;
// One DNS label: letters, digits, inner hyphens, at most 63 characters.
const LABEL_RE = /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/;
// Top-level domain: letters only, or an IDN in punycode (xn--…).
const TLD_RE = /^(?:[a-z]{2,63}|xn--[a-z0-9-]{1,59})$/;

function isPlainObject(value) {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** Trimmed, lower-cased form of an address as stored in the config. */
export function normalizeAddress(value) {
  return typeof value === 'string' ? value.trim().toLowerCase() : value;
}

/**
 * Returns `null` if `address` (already normalized) is a usable plain e-mail address, otherwise a
 * German reason. Rejects display names (`Name <a@b.de>`), lists (`a@b.de, c@d.de`), quoted
 * local parts, IP literals and non-ASCII characters.
 */
export function emailAddressProblem(address) {
  if (typeof address !== 'string' || address === '') return 'ist leer';
  if (address.length > MAX_ADDRESS_LENGTH) return `ist länger als ${MAX_ADDRESS_LENGTH} Zeichen`;
  if (/[\s,;<>()[\]"\\]/.test(address)) {
    return 'darf nur eine einzelne Adresse enthalten (keine Leerzeichen, Kommas, Namen oder spitzen Klammern)';
  }
  if (/[^\x21-\x7e]/.test(address)) return 'enthält Umlaute oder Sonderzeichen (Domains mit Umlauten bitte als xn--…)';
  const at = address.indexOf('@');
  if (at < 0) return 'enthält kein @';
  if (address.indexOf('@', at + 1) >= 0) return 'enthält mehr als ein @';
  const local = address.slice(0, at);
  const domain = address.slice(at + 1);
  if (local === '') return 'hat nichts vor dem @';
  if (local.length > MAX_LOCAL_PART_LENGTH) return `hat vor dem @ mehr als ${MAX_LOCAL_PART_LENGTH} Zeichen`;
  if (!LOCAL_PART_RE.test(local)) {
    return 'hat vor dem @ ungültige Zeichen (erlaubt: a–z, 0–9 und . _ % + -, keine Punkte am Rand oder doppelt)';
  }
  if (domain === '') return 'hat keine Domain nach dem @';
  const labels = domain.split('.');
  if (labels.length < 2) return 'braucht eine Domain mit Endung (z. B. gmail.com)';
  if (!labels.every((label) => LABEL_RE.test(label))) return `hat keine gültige Domain („${domain}“)`;
  if (!TLD_RE.test(labels[labels.length - 1])) return `hat keine gültige Domain-Endung („${domain}“)`;
  return null;
}

/** True for plain addresses the routine may send to (see `emailAddressProblem`). */
export function isValidEmailAddress(address) {
  return emailAddressProblem(address) === null;
}

/**
 * Validates a parsed `config/email.json`.
 *
 * @param {unknown} doc
 * @returns {{ config: { enabled: boolean, recipients: string[] } | null, errors: string[], warnings: string[] }}
 *   `config` is set only when there are no errors; `recipients` are normalized and unique.
 */
export function validateEmailConfig(doc) {
  const errors = [];
  const warnings = [];
  if (!isPlainObject(doc)) {
    return { config: null, errors: ['Die Datei muss ein JSON-Objekt { "schema", "enabled", "recipients" } sein.'], warnings };
  }

  if (doc.schema === undefined) {
    errors.push(`"schema" fehlt (erwartet: ${EMAIL_CONFIG_SCHEMA}).`);
  } else if (doc.schema !== EMAIL_CONFIG_SCHEMA) {
    errors.push(`"schema" ist ${JSON.stringify(doc.schema)} – dieses Skript kennt nur Schema ${EMAIL_CONFIG_SCHEMA}.`);
  }

  if (doc.enabled === undefined) {
    errors.push('"enabled" fehlt (true oder false).');
  } else if (typeof doc.enabled !== 'boolean') {
    errors.push(`"enabled" muss true oder false sein (ist ${JSON.stringify(doc.enabled)}).`);
  }

  const recipients = [];
  if (doc.recipients === undefined) {
    errors.push('"recipients" fehlt (Liste von E-Mail-Adressen, höchstens 10).');
  } else if (!Array.isArray(doc.recipients)) {
    errors.push('"recipients" muss eine Liste von E-Mail-Adressen sein.');
  } else {
    if (doc.recipients.length > MAX_RECIPIENTS) {
      errors.push(`"recipients" hat ${doc.recipients.length} Einträge – höchstens ${MAX_RECIPIENTS} sind erlaubt.`);
    }
    const seen = new Set();
    doc.recipients.forEach((raw, i) => {
      const where = `Empfänger ${i + 1}`;
      if (typeof raw !== 'string') {
        errors.push(`${where} ist kein Text, sondern ${JSON.stringify(raw)}.`);
        return;
      }
      const address = normalizeAddress(raw);
      const problem = emailAddressProblem(address);
      if (problem !== null) {
        errors.push(`${where} („${raw}“) ${problem}.`);
        return;
      }
      if (address !== raw) warnings.push(`${where} („${raw}“) wurde zu „${address}“ vereinheitlicht.`);
      if (seen.has(address)) {
        warnings.push(`${where} („${address}“) steht doppelt in der Liste und bekommt nur eine E-Mail.`);
        return;
      }
      seen.add(address);
      recipients.push(address);
    });
  }

  if (doc.updatedAt !== undefined && parseOffsetDateTime(doc.updatedAt) === null) {
    errors.push(`"updatedAt" muss ein ISO-8601-Zeitpunkt mit Zeitzone sein, z. B. 2026-10-06T21:14:03+02:00 (ist ${JSON.stringify(doc.updatedAt)}).`);
  }

  const unknown = Object.keys(doc).filter((k) => !CONFIG_KEYS.has(k));
  if (unknown.length > 0) warnings.push(`Unbekannte Felder werden ignoriert: ${unknown.join(', ')}.`);

  if (errors.length > 0) return { config: null, errors, warnings };
  return { config: { enabled: doc.enabled, recipients }, errors, warnings };
}

/**
 * Reads and validates a config file. Missing, unreadable and broken files count as errors.
 *
 * @param {string} file
 * @returns {{ config: { enabled: boolean, recipients: string[] } | null, errors: string[], warnings: string[] }}
 */
export function readEmailConfig(file) {
  let parsed;
  try {
    parsed = readJsonFile(file);
  } catch (error) {
    if (error instanceof JsonFileError) return { config: null, errors: [error.message], warnings: [] };
    throw error;
  }
  const result = validateEmailConfig(parsed.doc);
  return { ...result, warnings: [...parsed.warnings, ...result.warnings] };
}

const USAGE = `Prüft die E-Mail-Einstellungen (config/email.json vom Branch briefs).

Aufruf:
  node routine/email-config.mjs <pfad/zu/email.json>

Ausgabe (stdout), wenn alles stimmt – genau eine Zeile:
  {"enabled":true,"recipients":["du@example.com"]}

Regeln: "schema" 1, "enabled" true/false, "recipients" 0–${MAX_RECIPIENTS} einzelne E-Mail-Adressen,
"updatedAt" (optional) ISO-8601 mit Zeitzone. Adressen werden getrimmt und kleingeschrieben,
doppelte nur einmal ausgegeben.

Exit-Code: 0 = gültig, 1 = ungültig oder nicht lesbar, 2 = falscher Aufruf.`;

/**
 * Runs the CLI and returns the exit code (does not call `process.exit`).
 *
 * @param {string[]} argv arguments without `node` and the script path
 */
export function main(argv = process.argv.slice(2), out = console.log, err = console.error) {
  if (argv.includes('-h') || argv.includes('--help')) {
    out(USAGE);
    return 0;
  }
  const unknown = argv.find((a) => a.startsWith('-') && a !== '-');
  if (unknown !== undefined) {
    err(`Unbekannte Option: ${unknown}\n\n${USAGE}`);
    return 2;
  }
  if (argv.length !== 1) {
    err(`${argv.length === 0 ? 'Keine Datei angegeben.' : 'Bitte genau eine Datei angeben.'}\n\n${USAGE}`);
    return 2;
  }
  const file = argv[0];
  const { config, errors, warnings } = readEmailConfig(file);
  for (const w of warnings) err(`Hinweis: ${w}`);
  if (config === null) {
    err(`FEHLER: E-Mail-Einstellungen ${file} sind ungültig – es wird nichts verschickt.`);
    for (const e of errors) err(`  Fehler: ${e}`);
    return 1;
  }
  out(JSON.stringify(config));
  return 0;
}

if (isMainModule(import.meta.url)) {
  process.exitCode = main();
}
