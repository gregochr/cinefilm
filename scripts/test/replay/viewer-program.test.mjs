// scripts/replay/viewer.mjs run as the program it is, in a child process: the way it starts is what no other test sees
// (a module that waits on itself ends the program with exit code 13, whatever its functions do when they are called).
// Safe anywhere: the child's HOME is a temporary folder, so the real ~/cinefilm-viewer is never touched, and the lock
// in it names this test's own process, so the program refuses at the lock before it could start Chrome.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { after, describe, test } from 'node:test';

const VIEWER = fileURLToPath(new URL('../../replay/viewer.mjs', import.meta.url));
const tmp = mkdtempSync(join(tmpdir(), 'viewer-program-'));
after(() => rmSync(tmp, { recursive: true, force: true }));
let counter = 0;
// A site no program can reach (the discard port on this machine): were the lock's refusal ever to fail, the child would
// find nothing at it, and could not touch the real site.
const NOWHERE = 'http://127.0.0.1:9';

// Runs the program with a home of its own, with a time limit of its own: a program that hangs fails this test, and is
// killed, rather than eating the gate's budget.
function program(args, { locked }) {
  const home = join(tmp, `home-${counter += 1}`);
  mkdirSync(home);
  if (locked) {
    mkdirSync(join(home, 'cinefilm-viewer'));
    writeFileSync(join(home, 'cinefilm-viewer', 'lock'), String(process.pid));
  }
  const result = spawnSync(process.execPath, [VIEWER, ...args], {
    env: { ...process.env, HOME: home }, encoding: 'utf8', input: '', timeout: 10000, killSignal: 'SIGKILL',
  });
  return { ...result, home };
}

describe('viewer.mjs as a program', () => {
  for (const [name, args, line] of [
    ['--film', ['--film', '1', '--site', NOWHERE], /The run failed: another viewer is running/],
    ['--sign-in', ['--sign-in', '--site', NOWHERE], /The sign-in failed: another viewer is running/],
  ]) {
    test(`${name}, with another viewer holding the lock, refuses at the lock with exit 1 and starts nothing`, () => {
      const result = program(args, { locked: true });
      assert.equal(result.signal, null, 'not killed: it did not hang');
      assert.equal(result.status, 1, `exit code (stderr: ${result.stderr})`);
      assert.match(result.stdout, line);
      assert.match(result.stdout, new RegExp(`\\(process ${process.pid}\\)`));
      assert.deepEqual(readdirSync(join(result.home, 'cinefilm-viewer')), ['lock']);
      assert.deepEqual(readdirSync(result.home), ['cinefilm-viewer']);
    });
  }

  test('a refused option stops at once with exit 1 and its refusal, and makes nothing', () => {
    const result = program(['--film', '0'], { locked: false });
    assert.equal(result.signal, null);
    assert.equal(result.status, 1);
    assert.match(result.stderr, /--film must be a whole number from 1 to 999999999/);
    assert.deepEqual(readdirSync(result.home), []);
  });
});
