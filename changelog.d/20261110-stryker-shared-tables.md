### Fixed — Mutation testing finds the tables the backend tests share

Stryker's first step, the plain test run, stopped once the Edit editions panel and the search matching had merged. Two
tests read a table that the backend tests read too (the edition validation cases and the search vectors) through a
path that only works from `frontend/`, and Stryker runs the tests from a copy of `frontend/`. Both now load it with
`readRepoJson` (`frontend/src/test/repoFile.js`), which finds the repository from the copy as well, and ESLint fails a
test that reaches into `backend/` or `docs/` with a relative path. Each table is still one file, and what Stryker
mutates and its score floor are unchanged. That first step still stops on the two search speed tests, which run far
slower under Stryker's instrumentation; that is a separate change.
