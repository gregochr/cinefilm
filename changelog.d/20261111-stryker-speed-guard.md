### Fixed — The search speed tests no longer stop the mutation run

Stryker's first step, the plain test run, still stopped on the two search speed tests: its instrumentation makes the
search far slower (the fuzzy query takes 2 ms plain, 57 ms instrumented on its own and over a second in the full dry
run), past the 30 ms bound, and the run ends on any failing test. Under Stryker (it sets `STRYKER_MUTATOR_WORKER`) each
query now runs once instead of five times against a bound of 10 s, which only catches a search that has become orders of
magnitude slower; `npm test` and CI keep 30 ms, median of 5. The tests stay in the mutation run, and what Stryker mutates
and its score floor are unchanged.
