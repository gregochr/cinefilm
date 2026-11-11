### Fixed — Stryker's first step gets more time on a slow or busy machine

Stryker ends its first step, the plain test run, after 5 minutes by default. With 1,483 tests it takes 3.5 to 5 minutes
on a loaded laptop (1 minute 27 seconds for 938 tests on the runner on 2026-11-10), so a busy machine could end it with
"Initial test run timed out". The limit is now 15 minutes (`dryRunTimeoutMinutes` in `stryker.config.mjs`); what Stryker
mutates and its score floor are unchanged.
