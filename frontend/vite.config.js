import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Local runs use half the cores: several sessions' gates often run at once, and a default-sized pool per run
// pushed the load high enough for screen tests to hit their 5 s timeout. CI keeps Vitest's default.
// Vitest itself reads VITEST_MAX_WORKERS and lets it override this value, so no code is needed for the override.
// It parses the variable with parseInt: use a number (VITEST_MAX_WORKERS=2); '100%' would become 100 workers.
const maxWorkers = process.env.CI ? undefined : '50%';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { '/api': 'http://localhost:8080' },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.js'],
    css: false,
    maxWorkers,
    exclude: ['**/node_modules/**', '**/e2e/**', '**/.stryker-tmp/**'],
    // Coverage gate (CI runs `npm run test:coverage`). Two layers, mirroring goldenhour:
    //  1. Per file: every file in src/** must reach 95% lines and 90% branches on its own, so a new file
    //     has to arrive with tests. No file is excluded from checking altogether (see `exclude` below
    //     for the three things that are not product code).
    //  2. Global floor on the whole run (lines / statements / functions / branches, measured and rounded
    //     down): Vitest 4 applies `perFile` to every threshold, so the global floor cannot live here.
    //     `scripts/check-coverage-floor.mjs` enforces it from coverage-summary.json right after the run.
    // `include` (not the removed `all`) makes files that no test imports count as 0%.
    // Raise these when coverage rises; never lower them without the owner's say-so.
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{js,jsx}'],
      exclude: [
        'src/test/**', // test setup, fixtures and helpers, not product code
        'src/**/*.test.{js,jsx}', // the tests themselves
        'src/main.jsx', // bootstrap that mounts <App/> into the page; nothing to unit-test
      ],
      // lcov feeds the changed-lines check (diff-cover); see docs/engineering/dev-and-testing.md.
      reporter: ['text-summary', 'json-summary', 'lcov'],
      reportOnFailure: true,
      thresholds: { perFile: true, lines: 95, branches: 90 },
    },
  },
});
