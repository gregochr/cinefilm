### Changed — The build now fails on test-standard violations that reviewers used to catch

Checkstyle fails `lenient()` stubs, `@Disabled`, and real-clock reads (no-argument `Instant.now()` and friends,
`Clock.systemUTC()`, `Clock.systemDefaultZone()`, `ZoneId.systemDefault()`, `System.currentTimeMillis()`) in test
sources, and the same real-clock calls in `src/main`, where time must come from the injected `Clock`. ESLint fails
`.skip`/`.only`/`.todo`, `toBeTruthy()`, snapshots, `vi.mock` of anything under `src/api`, `await new Promise(r =>
setTimeout(r, n))` sleeps, and class-name reads in frontend tests. The two existing offenders were fixed in their tests
(`ServerSettingsServiceTest` stubs the row where it is read; the stale-hls.js test waits with `vi.dynamicImportSettled()`
instead of a 20 ms sleep, and `SearchSheet.test.jsx` matches the id it asserts). `test-standards.md` marks each rule as
enforced. No production behaviour changed.

The rules also catch the forms that went round them: `test.concurrent.skip`, `test.each(rows).skip`, `.skipIf` and
`.runIf` in tests, the fully qualified `@org.junit.jupiter.api.Disabled`, and static imports of the real-clock methods.

ESLint now fails any `setTimeout(...)` call in a frontend test, not only the resolver form. The four tests that used one
(latency, native engine, health report, throughput probe) now release their late answers and chunks from the test.
