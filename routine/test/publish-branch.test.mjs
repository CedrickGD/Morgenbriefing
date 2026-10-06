import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { afterEach, beforeEach, describe, it } from 'node:test';

import {
  BRANCH_ENV,
  DEFAULT_BRANCH,
  PublishError,
  branchProblem,
  checkoutBranch,
  publishBrief,
  resolveBranch,
} from '../publish-brief.mjs';
import { ROUTINE_DIR, makeBriefV2, tempDir } from './fixtures.mjs';

const PUBLISH = path.join(ROUTINE_DIR, 'publish-brief.mjs');
const HAS_GIT = spawnSync('git', ['--version']).status === 0;

/** The current environment without MB_BRIEFS_BRANCH, plus `extra`. */
function envWith(extra = {}) {
  const env = { ...process.env };
  delete env[BRANCH_ENV];
  return { ...env, ...extra };
}

/** `git init` with HEAD on the (unborn) `branch` – like `git switch --orphan` in the routine. */
function initCheckout(dir, branch) {
  for (const args of [['init', '-q'], ['symbolic-ref', 'HEAD', `refs/heads/${branch}`]]) {
    const result = spawnSync('git', ['-C', dir, ...args], { encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
  }
}

describe('target branch', () => {
  it('defaults to briefs; --branch wins over MB_BRIEFS_BRANCH', () => {
    assert.equal(DEFAULT_BRANCH, 'briefs');
    assert.equal(BRANCH_ENV, 'MB_BRIEFS_BRANCH');
    assert.deepEqual(resolveBranch({ env: {} }), { branch: 'briefs', from: 'Standard', test: false });
    assert.deepEqual(resolveBranch({ env: { MB_BRIEFS_BRANCH: '  ' } }).branch, 'briefs');
    assert.deepEqual(resolveBranch({ env: { MB_BRIEFS_BRANCH: ' briefs-test ' } }), {
      branch: 'briefs-test',
      from: 'MB_BRIEFS_BRANCH',
      test: true,
    });
    assert.deepEqual(resolveBranch({ branch: 'briefs', env: { MB_BRIEFS_BRANCH: 'briefs-test' } }), {
      branch: 'briefs',
      from: '--branch',
      test: false,
    });
  });

  it('accepts only briefs and briefs-<name>', () => {
    for (const ok of ['briefs', 'briefs-test', 'briefs-test-2', 'briefs-2026-10-06']) {
      assert.equal(branchProblem(ok), null, ok);
    }
    for (const bad of ['main', 'master', 'Briefs', 'briefs-', 'briefs--x', 'briefs-Test', 'briefs/test',
      'claude/briefs', 'briefs test', '', '-briefs', 'briefs-ä', undefined]) {
      assert.notEqual(branchProblem(bad), null, String(bad));
    }
    assert.throws(() => resolveBranch({ branch: 'main', env: {} }), PublishError);
    assert.throws(() => resolveBranch({ env: { MB_BRIEFS_BRANCH: 'main' } }), /MB_BRIEFS_BRANCH: "main"/);
  });
});

describe('publishBrief with a target branch', () => {
  let dir;
  let cleanup;
  beforeEach(() => {
    ({ dir, cleanup } = tempDir());
  });
  afterEach(() => cleanup());

  it('publishes into a plain directory (no git checkout) for any allowed branch', () => {
    assert.deepEqual(checkoutBranch(dir), { checkout: false, branch: null });
    const result = publishBrief({ brief: makeBriefV2(), dataDir: dir, branch: 'briefs-test' });
    assert.equal(result.branch, 'briefs-test');
    assert.ok(existsSync(path.join(dir, 'briefs', '2026-10-06.json')));
  });

  it('refuses names other than briefs / briefs-<name> and writes nothing', () => {
    assert.throws(
      () => publishBrief({ brief: makeBriefV2(), dataDir: dir, branch: 'main' }),
      (error) => error instanceof PublishError && /kein erlaubter Daten-Branch/.test(error.message),
    );
    assert.deepEqual(readdirSync(dir), []);
  });

  it('needs the checkout to be on the target branch', { skip: !HAS_GIT && 'git fehlt' }, () => {
    initCheckout(dir, 'briefs-test');
    assert.deepEqual(checkoutBranch(dir), { checkout: true, branch: 'briefs-test' });
    assert.throws(
      () => publishBrief({ brief: makeBriefV2(), dataDir: dir, branch: 'briefs' }),
      (error) => error instanceof PublishError && /steht auf Branch "briefs-test", Ziel ist "briefs"/.test(error.message),
    );
    assert.deepEqual(readdirSync(dir), ['.git'], 'nothing written');

    const result = publishBrief({ brief: makeBriefV2(), dataDir: dir, branch: 'briefs-test' });
    assert.equal(result.branch, 'briefs-test');
    assert.ok(existsSync(path.join(dir, 'latest.json')));
  });

  it('refuses a checkout that is on no branch', { skip: !HAS_GIT && 'git fehlt' }, () => {
    writeFileSync(path.join(dir, '.git'), 'gitdir: ./gibt-es-nicht\n');
    assert.deepEqual(checkoutBranch(dir), { checkout: true, branch: null });
    assert.throws(() => publishBrief({ brief: makeBriefV2(), dataDir: dir, branch: 'briefs' }), /auf keinem Branch/);
  });

  it('does not check the checkout when no branch is given (library use)', () => {
    writeFileSync(path.join(dir, '.git'), 'gitdir: ./gibt-es-nicht\n');
    const result = publishBrief({ brief: makeBriefV2(), dataDir: dir });
    assert.equal(result.branch, null);
  });
});

describe('publish CLI with a target branch', () => {
  const run = (args, env = envWith()) => spawnSync(process.execPath, [PUBLISH, ...args], { encoding: 'utf8', env });

  it('--print-branch prints the resolved branch', () => {
    assert.equal(run(['--print-branch']).stdout, 'briefs\n');
    assert.equal(run(['--print-branch'], envWith({ MB_BRIEFS_BRANCH: 'briefs-test' })).stdout, 'briefs-test\n');
    assert.equal(run(['--print-branch', '--branch', 'briefs-probe']).stdout, 'briefs-probe\n');
    assert.equal(run(['--print-branch', '--branch=briefs'], envWith({ MB_BRIEFS_BRANCH: 'briefs-test' })).stdout, 'briefs\n');

    const bad = run(['--print-branch', '--branch', 'main']);
    assert.equal(bad.status, 2);
    assert.equal(bad.stdout, '');
    assert.match(bad.stderr, /kein erlaubter Daten-Branch/);
    assert.equal(run(['--print-branch'], envWith({ MB_BRIEFS_BRANCH: 'feature/x' })).status, 2);
  });

  it('names the branch and flags a test run', () => {
    const { dir, cleanup } = tempDir();
    try {
      const data = path.join(dir, 'data');
      mkdirSync(data);
      const briefFile = path.join(dir, 'brief.json');
      writeFileSync(briefFile, JSON.stringify(makeBriefV2()));

      const normal = run(['--brief', briefFile, '--data-dir', data, '--dry-run']);
      assert.equal(normal.status, 0, normal.stderr);
      assert.match(normal.stdout, /^Ziel: .* · Branch briefs {2}\(Probelauf/m);
      assert.doesNotMatch(normal.stdout, /Testlauf/);

      const test = run(['--brief', briefFile, '--data-dir', data], envWith({ MB_BRIEFS_BRANCH: 'briefs-test' }));
      assert.equal(test.status, 0, test.stderr);
      assert.match(test.stdout, /· Branch briefs-test$/m);
      assert.match(test.stdout, /Testlauf: Ziel ist "briefs-test" \(MB_BRIEFS_BRANCH\)/);

      const bad = run(['--brief', briefFile, '--data-dir', data, '--branch', 'main']);
      assert.equal(bad.status, 2);
    } finally {
      cleanup();
    }
  });

  it('exits 1 if the checkout is on another branch', { skip: !HAS_GIT && 'git fehlt' }, () => {
    const { dir, cleanup } = tempDir();
    try {
      const data = path.join(dir, 'data');
      mkdirSync(data);
      initCheckout(data, 'briefs');
      const briefFile = path.join(dir, 'brief.json');
      writeFileSync(briefFile, JSON.stringify(makeBriefV2()));
      const result = run(['--brief', briefFile, '--data-dir', data, '--branch', 'briefs-test']);
      assert.equal(result.status, 1);
      assert.match(result.stderr, /steht auf Branch "briefs", Ziel ist "briefs-test"/);
      assert.deepEqual(readdirSync(data), ['.git']);
    } finally {
      cleanup();
    }
  });
});
