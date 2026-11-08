// Global coverage floor for the whole run, checked after `vitest run --coverage` (see `test:coverage`).
//
// Why a script: Vitest 4 treats `coverage.thresholds.perFile` as all-or-nothing, so the per-file floor
// (95% lines / 90% branches, in vite.config.js) and a floor on the combined total cannot both be
// expressed there. goldenhour (Vitest 5) can; CineFilm is on Vitest 4.1.
//
// The floors are the figures measured on 2026-11-08, rounded down to a whole percent.
// Ratchet rule: raise them when coverage rises; never lower them without the owner's say-so.
import { readFileSync } from 'node:fs';

const FLOOR = { lines: 99, statements: 99, functions: 98, branches: 97 };

const summaryPath = new URL('../coverage/coverage-summary.json', import.meta.url);
let total;
try {
  total = JSON.parse(readFileSync(summaryPath, 'utf8')).total;
} catch (error) {
  console.error(`Cannot read ${summaryPath.pathname}: run \`vitest run --coverage\` first.`);
  console.error(String(error));
  process.exit(1);
}

const failures = Object.entries(FLOOR).filter(([metric, floor]) => total[metric].pct < floor);
for (const [metric, floor] of failures) {
  console.error(`Global ${metric} coverage ${total[metric].pct}% is below the ${floor}% floor.`);
}
if (failures.length > 0) process.exit(1);

console.log(
  `Global coverage floor met: ${Object.entries(FLOOR)
    .map(([metric, floor]) => `${metric} ${total[metric].pct}% (floor ${floor}%)`)
    .join(', ')}.`,
);
