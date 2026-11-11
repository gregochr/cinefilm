### Fixed — The search speed tests no longer stop the mutation run

Stryker's first step, the plain test run, still stopped on the two search speed tests: its instrumentation makes the
search roughly 8 times slower on the exact query and roughly 30 times slower on the fuzzy one, past the 30 ms bound, and
the run ends on any failing test. Under Stryker (it sets `STRYKER_MUTATOR_WORKER`) the bound is now 600 ms, which still
catches an O(n²); `npm test` and CI keep 30 ms. The tests stay in the mutation run, and what Stryker mutates and its
score floor are unchanged.
