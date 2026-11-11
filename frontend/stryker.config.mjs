// Mutation testing for the non-JSX client logic (weekly, never a PR gate): .github/workflows/stryker.yml.
// Run locally with `npm run test:mutation`. Notes and scores: docs/engineering/dev-and-testing.md.

// The score floor. The weekly workflow sets STRYKER_BREAK; unset (the default, every local run) there is
// no floor, so exploring does not fail. Set but not a whole number 0-100, it throws, so a typo cannot
// quietly switch the floor off.
function breakFloor() {
  const raw = process.env.STRYKER_BREAK;
  if (raw === undefined || raw === '') return null;
  const value = Number(raw);
  if (!/^\d+$/.test(raw.trim()) || !Number.isInteger(value) || value < 0 || value > 100) {
    throw new Error(`STRYKER_BREAK must be a whole number from 0 to 100, got "${raw}"`);
  }
  return value;
}

/** @type {import('@stryker-mutator/api/core').PartialStrykerOptions} */
export default {
  testRunner: 'vitest',
  // 'forks', not the runner's default 'threads': the suite may pin process.env, which a worker thread
  // (with a copy of it) might not honour. Do not set coverageAnalysis: the vitest runner ignores it and
  // always uses per-test coverage.
  vitest: { configFile: 'vite.config.js', pool: 'forks' },
  // The non-JSX logic. fixtureServer.js is the dev-only fake API (no production path imports it).
  mutate: [
    'src/player/*.js',
    'src/api/*.js',
    '!src/api/fixtureServer.js',
    'src/state/*.js',
    'src/components/placeholder.js',
    'src/components/useSlashFocus.js',
    '!src/**/*.test.js',
  ],
  // Deliberate trade-offs: module-level tables are static mutants that re-run every covering test, and
  // string literals are mostly user-facing labels (low value, high cost).
  ignoreStatic: true,
  mutator: { excludedMutations: ['StringLiteral'] },
  timeoutMS: 30000,
  timeoutFactor: 2,
  // The initial test run (the dry run) has a limit of its own, 5 minutes by default. It took 1 minute 27 seconds for 938
  // tests on the runner on 2026-11-10; with 1,500 tests it takes 3.5 to 5 minutes on a loaded laptop.
  dryRunTimeoutMinutes: 15,
  concurrency: 4,
  incremental: true,
  incrementalFile: 'reports/stryker-incremental.json',
  reporters: ['clear-text', 'progress', 'html', 'json'],
  htmlReporter: { fileName: 'reports/mutation/mutation.html' },
  jsonReporter: { fileName: 'reports/mutation/mutation.json' },
  // Ratchet: the floor is the first measured score rounded down. Raise it when the score rises; never
  // lower it without the owner's say-so.
  thresholds: { high: 80, low: 60, break: breakFloor() },
  tempDirName: '.stryker-tmp',
};
