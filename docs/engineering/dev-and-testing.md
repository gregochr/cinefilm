# Development and testing

How to run CineFilm locally, what the test suites are, and the rules for changing the database. The plan for the
CI, test and deploy series is `ci-test-deploy-plan.md`; later phases extend this file.

## Local PostgreSQL (dev profile)

Dev and production run on PostgreSQL 17. The dev Mac uses Homebrew, not Docker:

```bash
brew install postgresql@17 && brew services start postgresql@17
scripts/dev-db.sh        # idempotent: creates role `cinefilm` (password `cinefilm`) and database `cinefilm`
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
curl -i localhost:8080/api/session
```

`postgresql@17` is keg-only, so put it on the PATH once: `export PATH="/opt/homebrew/opt/postgresql@17/bin:$PATH"`.
Without the helper script: `createuser cinefilm -P` then `createdb -O cinefilm cinefilm`.

Configuration comes from the environment, never from committed files:

| Variable | Dev default | Prod |
|---|---|---|
| `CINEFILM_DB_URL` | `jdbc:postgresql://localhost:5432/cinefilm` | required |
| `CINEFILM_DB_USER` | `cinefilm` | required |
| `CINEFILM_DB_PASSWORD` | `cinefilm` | required |
| `CINEFILM_LIBRARY_ROOT` | `/media` | the films folder (read-only mount); media paths are stored relative to it |
| `CINEFILM_CACHE_DIR` | `$TMPDIR/cinefilm-cache` | `/app/cache` by default (the deploy mounts the writable cache there; checked by `scripts/test/config-agreement.test.sh`); generated WebVTT subtitles live here |
| `TMDB_READ_TOKEN` | empty (TMDB off) | the owner's TMDB Read Access Token (sent as a Bearer header, never logged) |
| `OMDB_API_KEY` | empty (ratings off) | the owner's OMDb API key (IMDb and Rotten Tomatoes ratings; sent as a query parameter, so the URL is never logged) |

The prod profile has no fallbacks for the database values, so a missing one fails start-up. The library values have
defaults; `ffprobe` and `ffmpeg` must be on the PATH (`brew install ffmpeg` on a Mac) for a real scan. The dev profile applies every Flyway migration
to the real database and seeds the sample library when the database has no films. To start over:
`dropdb cinefilm && scripts/dev-db.sh`.

## Test suites

- **Fast suite (default).** Unit and MockMvc tests on in-memory H2 with `MODE=PostgreSQL` and Flyway on. H2 is
  `test` scope only: it is not on the dev or prod classpath. Rule: migrations are written for PostgreSQL and never
  contorted for H2. The day one cannot run on H2, switch the test profile to `ddl-auto=create-drop` with Flyway off;
  Testcontainers then remains the only migration proof (it already is the authoritative one).
- **Integration suite** (`gregochr.cinefilm.integration`, classes extending `IntegrationTestBase`). A Testcontainers
  `postgres:17-alpine`, Flyway migrations on an empty database, Hibernate `ddl-auto=validate`. Covers the migrations,
  the library queries on the dev seed, playback state persistence and the library scan's V2 columns, queries and
  bulk edition move.
- **Library scan tests.** ffprobe parsing runs on recorded-shape JSON in `src/test/resources/ffprobe`; the TMDB client
  and matcher run on responses recorded from the live API in `src/test/resources/tmdb` (`TmdbFixtures` serves them;
  an unrecorded search answers with no results). To record more, call the API once with the token from your
  environment, keep the JSON, and never write the token down. `RealToolsTest` runs the real `ffprobe`/`ffmpeg` and
  skips itself when they are not on the PATH (CI installs ffmpeg). Tests that scan use the shared
  `${java.io.tmpdir}/cinefilm-test-library` folder (`cinefilm.library.root` in the test profile) and clean it.

**No Docker on the dev Mac**, so Testcontainers cannot run locally. The local gate excludes the package:

```bash
cd backend && ./mvnw clean verify --batch-mode --no-transfer-progress -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false
```

CI's backend job (`ubuntu-latest`, Docker available) runs plain `./mvnw clean verify`, which includes the
integration classes, and prints their test counts so "0 tests ran" cannot pass as green. Migrations are therefore
proven before merge only by CI, plus a local `dev`-profile run on Homebrew Postgres.

Name integration classes `*IntegrationTest` and keep them under `integration/`: the exclusion is by package. Always
extend `IntegrationTestBase`; its Javadoc explains why `@DirtiesContext(AFTER_CLASS)` must stay.

## Migration policy: expand, then contract

Migrations in `backend/src/main/resources/db/migration` are **append-only** from the PostgreSQL switch onwards
(`V1__schema.sql` was edited in place once, because nothing was deployed). Never edit an applied migration; add a new
`V<n>__<what>.sql`.

Every migration must be **expand-only** in the release that introduces it:

- add columns nullable or with a default, add tables and indexes;
- do not drop, rename or tighten (NOT NULL, narrower type) anything the previous release still uses.

Contract (drop the old column, add the constraint) in a **later** release, once no running image reads the old shape.

Why: the automatic rollback planned for deployment restores the previous *image*, never the database. The previous
image must therefore start and work against the new schema.

## The build ladder (backend)

`./mvnw clean verify` runs Checkstyle (validate), compile, tests with JaCoCo, the JaCoCo per-class and bundle check,
then SpotBugs with FindSecBugs. Climb the ladder from cheap to expensive and stop at the first red rung:

```bash
cd backend
./mvnw compile -q                                    # signatures and imports, seconds
./mvnw test -Dtest=PlaybackServiceTest -q            # one class: the inner loop
./mvnw checkstyle:check@validate                     # Javadoc, indentation, line length, ~10 s
./mvnw clean verify --batch-mode --no-transfer-progress -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false
```

The last line is the local gate and the only one that proves a change; the earlier rungs are for speed. Do not skip
Checkstyle or SpotBugs (`-Dcheckstyle.skip`, `-Dspotbugs.skip`) in anything you report as done.

**Vitest workers (frontend).** Locally Vitest uses half the cores (`maxWorkers: '50%'` in `frontend/vite.config.js`),
because several sessions often run gates at once and an oversubscribed machine makes screen tests hit their 5 s
timeout. CI keeps Vitest's default. `VITEST_MAX_WORKERS=<number>` (for example `2`, or `8` for every logical core) overrides it; Vitest parses it as an
integer, so a percentage string is not understood there. Do not raise
`testTimeout` to cope with load.

**Gate on exit codes, never on a grep of output.** `$?` after a pipeline is the status of the last command in it
(usually `grep`, which is almost always 0), and with `-q` Checkstyle's violation lines are not printed at all, so a
grep for `ERROR` finds nothing and looks clean. Run Maven by itself, read its exit status as its own statement, and
only then look at the output: redirect to a file, `echo "exit=$?"`, then read the file.

## What the backend gates enforce

| Gate | Rule | Where |
|---|---|---|
| Checkstyle (validate) | Javadoc on every public type, method and constructor in `src/main` (tests exempt; bean getters/setters and `@Override` exempt); 4-space indentation; 120 columns; no unused imports | `backend/config/checkstyle/` |
| JaCoCo per class | **95% line and 90% branch for every class**, measured on the unit and MockMvc suite | `backend/pom.xml`, `check` execution |
| JaCoCo bundle | a floor at the measured total (figures in `test-standards.md`) | same |
| SpotBugs + FindSecBugs | effort Max, threshold High; any finding fails the build. Suppressions only in `backend/spotbugs-exclude.xml`, each with a reason | `backend/pom.xml`, `spotbugs-check` |

**How to read JaCoCo.** After a run open `backend/target/site/jacoco/index.html` (class table; sort by Missed
Instructions) or `backend/target/site/jacoco/jacoco.csv`. A failure reads `Rule violated for class
gregochr.cinefilm.x.Y: lines covered ratio is 0.85, but expected minimum is 0.95`: open that class's page, red lines
are missed lines and yellow lines are a branch not taken. Fix it with a test of the behaviour on that line, not by
lowering the number. The integration package does not run locally, so the figures here are the **minimum** CI sees:
the integration tests only add coverage.

**Ratchet rule.** Floors only go up. When coverage rises, raise the bundle floor in the same PR; never lower a
floor, never add an exclusion and never add a per-class override to fit a change. A per-class override exists only
for a line that truly cannot run (for example the `NoSuchAlgorithmException` branch after a SHA-256 lookup), is
written as its own `<rule>` at the measured figure and carries an XML comment naming the line and the reason.
Anything else gets a test. Lowering anything needs the owner.

**Time.** Production code reads the time only through the injected `java.time.Clock` (`ClockConfig`; zone from
`cinefilm.time-zone`, `Europe/London`). Tests run on `TestData.CLOCK`; see `test-standards.md`.

**Actuator.** Only `/actuator/health` and `/actuator/info` are exposed, and both answer a signed-out caller.
`/actuator/health/container` (database and ping, status only) is the probe for the Docker healthcheck and the deploy
smoke. `/actuator/info` carries `git.commit.id.full` from `git.properties`, written at build time by
`git-commit-id-maven-plugin` (the build never fails for lack of a `.git` directory, but the info is then empty).

## Mutation-checking a test without destroying work

Break the production code on purpose to prove a test can fail. Never restore with `git checkout --`,
`git restore` or `git stash`: they revert to HEAD and silently delete any uncommitted change in the file, usually the
very change under test. Back the file up with `cp` and restore with `cp`, or commit first, or mutate in a separate
worktree, and confirm with `git diff` that the file is exactly as you started.

## Mutation testing

Coverage says a line ran; mutation testing says a test would notice if it were wrong. Two weekly workflows mutate the
code and report how many mutants the tests kill. Neither is a PR gate. Each has a score floor and a red run opens a
`ci-failure` issue (`ci-failure-issue.yml`).

| | Backend (PIT) | Frontend (Stryker) |
|---|---|---|
| Workflow | `pitest.yml`, Mondays 06:30 UTC | `stryker.yml`, Wednesdays 03:30 UTC |
| Mutated | `gregochr.cinefilm.*` minus `config.*`, `seed.*`, `*Dtos*`, `*Controller`, `CineFilmApplication` | `src/player/*.js`, `src/api/*.js` (not `fixtureServer.js`), `src/state/*.js`, `src/components/placeholder.js`, `src/components/useSlashFocus.js` |
| Tests that kill | Unit, MockMvc/`@WebMvcTest` and `ApiTestBase` (H2) tests; the Testcontainers `integration` package is excluded (same locally and in CI so scores compare) | The whole Vitest suite (per-test coverage), `pool: 'forks'` |
| Floor | `mutationThreshold` 98 in `backend/pom.xml` (98.19%, 1196 of 1218, after the Plex import) | `STRYKER_BREAK` 95 in `stryker.yml` (`thresholds.break`) |
| First measured (2026-11-08) | 96.25% (282 of 293 mutants killed) | 92.22% (498 of 540) |
| After the first new tests | 97.95% (287 of 293) | 95.56% (516 of 540) |
| Runtime (one job, no shards) | about 5 minutes on a laptop and on the runner | about 10 minutes on a laptop, 28 minutes on the runner (3-minute dry run, then mutants) |
| First runner run (2026-11-08) | 97.95%, same as local | 96.30% (4 mutants time out on the runner and count as killed, so the score varies a little with load; the floor has room) |
| Report artifact | `pitest-report` (`pit-reports/`) | `stryker-report` (`mutation.html`) and `stryker-incremental` |

Run them locally (neither needs Docker):

```bash
cd backend && ./mvnw test-compile org.pitest:pitest-maven:mutationCoverage    # report: backend/target/pit-reports/index.html
cd frontend && npm run test:mutation                                          # report: frontend/reports/mutation/mutation.html
```

Rules:

- **Floors only ratchet up.** A floor is the measured score rounded down. When a run scores a whole point higher,
  raise the floor in the same PR; never lower one without the owner's say-so. A new class or file should arrive with
  tests that kill its mutants, not with a lower floor.
- **Never switch Stryker to `inPlace`** (it would mutate your working tree and a killed run leaves it mutated). The
  default sandbox copies `frontend/` into `frontend/.stryker-tmp/sandbox-*` and mutates the copy. Interrupt a run
  with SIGINT (Ctrl-C), not SIGKILL, so it cleans up. `frontend/reports/` and `.stryker-tmp/` are git-ignored.
- **The whole suite has to pass from that copy**, or the dry run fails before any mutant is tested. The copy holds
  only `frontend/`, so a relative import that climbs out of it, or a path built on `process.cwd()`, leads nowhere
  there. The two tests that read a table the backend suite reads too
  (`backend/src/test/resources/shared/edition-validation-cases.json`, `docs/design/search/vectors.json`) load it
  with `readRepoJson('<path from the repo root>')` from `frontend/src/test/repoFile.js`, which walks up from the
  copy to the repository; that is also why `tempDirName` stays inside the repo. ESLint fails a test that names a
  `../…/backend/` or `../…/docs/` path. No PR check runs Stryker: after adding a test that reads a file outside
  `frontend/`, run `npx stryker run --dryRunOnly` (a few minutes, no mutants).
- **Stryker's incremental file** (`reports/stryker-incremental.json`) lets a run re-test only what changed. It cannot
  see changes to `setup.js`, `vite.config.js` or dependencies, so the workflow forces a full run on the first
  scheduled run of each month; force one yourself with `npx stryker run --force`. The workflow keeps the file as an
  artifact between runs (not `actions/cache`, which evicts after 7 days).
- **`@stryker-mutator/core` and `vitest-runner` are pinned to the same exact version**; Dependabot bumps them as one
  `stryker` group.
- **PIT's mutators are the defaults; Stryker excludes `StringLiteral` and ignores static mutants** (module-level
  tables re-run every covering test and string literals are mostly labels).
- If PIT approaches 20 minutes, first exclude `*ApiTest` and rely on the service unit tests, then shard by package as
  goldenhour's `pitest.yml` does.

### Known survivors (2026-11-08), not worth a test

- `WatchedRule.isWatched`, `threshold >= 0` to `> 0`: equivalent, the threshold is never 0 (a marker is positive and
  the 92% fallback of a positive runtime is at least 1).
- `SecurityConfig` and `CsrfHeaderFilter` `setCharacterEncoding(UTF-8)`: JSON responses carry UTF-8 anyway in the
  mock layer; the visible header is unchanged.
- `SecurityConfig.securityFilterChain` and `unauthorizedJson` null returns: PIT credits them to tests that never
  exercise the returned object; the security rules themselves (401, 403, route drift) are pinned by
  `RouteSecurityDriftTest`, `CsrfHeaderFilterTest` and the controller tests.
- `BrowseRules.matchesSearch`, `q == null` conditional (line 72): not understood yet; the null-query path is tested
  (`BrowseRulesTest.searchMatchesTitleActorAndDirectorCaseInsensitively`). Look at it when the floor next moves.
- Frontend: React effect dependency arrays (`[]` to `["Stryker was here"]`) and `n + 1` to `n - 1` in tick counters
  are equivalent behaviour, and the `autohide`/`exitReport`/`usePlayback` ref-update effects are covered only through
  the rendered player. Killing those needs timing tests that pin implementation, not behaviour.

## UI work: review cadence

Adapted from goldenhour. Every code commit that touches the UI is built, tested, adversarially reviewed against the
working tree, fixed and re-verified before it is committed, in that order. A green suite, clean lint and a good
build all pass over real defects (a token that silently resolves to nothing, an escape hatch that vanishes when a
fetch fails), so review after green is where the defects are.

- **Reviewers are read-only.** A reviewer that mutates source to probe a test, then tidies with `git checkout --`,
  deletes unstaged work it never knew about. Tell review agents to read only; anything that must mutate runs in its
  own worktree.
- **Verify in the browser, do not assert.** Run the backend with the `dev` profile against Homebrew Postgres (it
  seeds the sample library) and the frontend with `npm run dev`, sign in with the dev password and look at the
  screen. State which claims were seen and which were only tested; where the browser cannot reach (a state the seed
  does not produce), say so.
- **Report what was not examined** as plainly as what was.

## Poor-network check (by hand)

A train is not reproducible, so a change to playback is checked on a deliberately bad link before it is released
(`network-resilience-review.md`, item 13). In Chrome, add three custom profiles under DevTools, Network, throttling:

| Profile | Download | Upload | Latency |
|---|---|---|---|
| Train, bad | 300 kbit/s | 150 kbit/s | 150 ms |
| Train, typical | 500 kbit/s | 250 kbit/s | 150 ms |
| Train, good | 800 kbit/s | 400 kbit/s | 150 ms |

On each profile, with a film on Auto:

1. Play from the start, and again from a resume point. Note the time to the first frame.
2. Watch two minutes, then seek forward ten minutes. Note every stall.
3. Switch the throttle to Offline for 30 seconds and back, then for 3 minutes and back. "Reconnecting…" must clear
   by itself and playback must carry on from the same place, without a reload and without falling back to the
   original file.
4. Open Admin, Playback health: the viewing is there, and its start-up time, stalls and reconnects match what you
   saw (`playback-health-plan.md`).

- DevTools throttles the page's own requests (the hls.js segments included) and only while DevTools is open. It
  does not reach Safari: use Network Link Conditioner there (macOS: Additional Tools for Xcode; iPhone: Settings,
  Developer).

## Local Codex review before the PR

Every PR costs a 10–15 minute CI run on dockermacmini, and every Codex finding on the PR costs another. Most of
those findings can be had locally first: the same reviewer, reading the same `AGENTS.md`, on the laptop.

- **When:** after Sonnet's gates are green and the planner's own read of the diff is done, before the commit. Any
  code change, UI or not.
- **How:** the planner runs it in Sonnet's worktree, from the repo root (Codex reads `AGENTS.md` there by itself):
  `codex review --uncommitted` for work not yet committed, or `codex review --base origin/main` once it is. Run it
  in the background with its output sent to a scratch file; it takes about two minutes. The log is long (every
  command Codex ran); its verdict and findings are the last block, after the final `codex` line. The CLI is
  signed in on the owner's laptop (`codex login status`).
- **Then:** send each finding to Sonnet, or reject it with a reason, as for a PR finding. Sonnet fixes, re-runs the
  gates, and the planner runs Codex again. Stop when a run finds nothing new, then commit and open the PR.
- **Still request the PR review.** The PR's Codex run is the merge gate (`CLAUDE.md` step 6); the local run only
  makes it likely to be clean the first time.

### Self-check in every Sonnet brief

Codex's past PR findings (#9–#46) fall into three groups. Put this list in the brief so Sonnet checks its own diff
before reporting done:

- **Rules already in `test-standards.md`:** no `container.querySelector`, `parentElement`/`firstChild` walking or
  class-name queries; `userEvent`, not `fireEvent.click`; keys go to the focused element; no `Thread.sleep`; no
  `any()` in `verify()`; no `verify(..., never())` on repositories. Lint enforces the ones it can.
- **`AGENTS.md` items:** a changed request or response shape updates `foundation-plan.md` and the HTTP contract
  tests; every new script has tests; every new interactive element has a 44px target.
- **Async state:** for each request the diff starts, what happens on a second click, an undo, or a newer request
  while the first is in flight, and on cancel? Most of the logic findings (#39, #42) were races of this kind.

## CI workflows

One workflow runs the checks (`.github/workflows/ci.yml`, name `CI`); the others support it. The job names below are
stable strings: branch protection and `dependabot-auto-merge.yml` both read them. Do not rename them.

**What GitHub enforces (since 2026-11-10).** The repo is public, which is why Actions minutes, CodeQL and SonarQube
Cloud are free; a private repo on the free plan has a monthly Actions quota and stops starting jobs when it runs out.

- **Branch protection on `main`:** the `Backend`, `Frontend` and `Shell` checks are required; force-pushes and
  deletion are blocked; no review is required; admins are not bound. Auto-merge is allowed on the repo, and with
  these required checks it merges on green CI. It does not wait for Codex: read Codex on the head commit before
  enabling it, and still merge by hand with `gh pr merge --match-head-commit` otherwise (`CLAUDE.md`).
- **Ruleset "CodeQL: no new high-severity alerts"** on the default branch: a PR that adds a CodeQL security alert
  of `high` or above (or an `error`) cannot merge. It is a ruleset rather than a required check so a docs-only PR is
  never left waiting. Proven on 2026-11-10 with a throwaway path-injection PR (#56, closed unmerged).

### CodeQL (GitHub default setup, no workflow file)

GitHub-managed: `java-kotlin`, `javascript-typescript` and `actions`, extended query suite, on every PR, every push
to `main`, and weekly. Results are in the repo's Security tab and as the `CodeQL` check on a PR. There is no
`codeql.yml`; change the setup in the repo's code-scanning settings (or the `code-scanning/default-setup` API).

- **A real finding is fixed in the PR that raised it.** A false positive is dismissed in GitHub with a written
  reason (never suppressed in code), by the planner, and named in the PR description.
- **Baseline, 2026-11-10:** 20 alerts on the first scan, 0 open after PR #54. Dismissed with reasons: the disabled
  Spring CSRF (replaced by `auth/CsrfHeaderFilter`), path injection in `ArtworkController` and
  `HlsSessionController` (inputs are pattern-checked and file names rebuilt server-side), the array index in
  `CutList.startMs` (range-checked in `SessionManager.segment` and again in `CutList.checkSegment`, which CodeQL
  does not follow), and a relative command in a test.
- **Third-party actions are pinned to commit SHAs** with the version in a trailing comment; Dependabot's
  `github-actions` updates move both. First-party `actions/*` stay on tags.

### `ci.yml` — CI

Triggers: push to `main`, every pull request (any base), `workflow_call` (for a later deploy or mutation workflow to
reuse), `workflow_dispatch` (the auto-merge workflow builds `main` after a `GITHUB_TOKEN` merge). Concurrency
cancels superseded runs of the same ref.

| Job | Name | What it runs |
|---|---|---|
| `changes` | `Detect docs-only change` | Classifies the PR diff. |
| `backend` | `Backend — Build, Test & Coverage` | `./mvnw clean verify` in full (Checkstyle, tests incl. Testcontainers integration, JaCoCo per class and bundle, SpotBugs + FindSecBugs), then the integration test count, then on PRs diff-cover at 90% of changed `backend/src/main` lines (JaCoCo XML). Uploads the JaCoCo and SpotBugs reports. 20 min timeout. |
| `shell` | `Shell — ShellCheck & script tests` | Pinned ShellCheck v0.11.0 (`-o check-unassigned-uppercase`) over `scripts/*.sh`, `scripts/lib/*.sh` and `scripts/test/*.sh`, then `bash scripts/test/run-all.sh`: the tests stub `npx`/`sleep` on PATH to prove `npm-audit.sh` (clean passes, findings fail without retry, transport errors retry with 15s/30s backoff, exhausted retries fail) and run `ci-docs-only.sh` against throwaway git repos. Run both locally the same way. Also runs a pinned actionlint (`rhysd/actionlint:1.7.12`, which shellchecks `run:` blocks too) over `.github/workflows/`: run `actionlint` locally before pushing a workflow change; `.github/actionlint.yaml` lists the self-hosted runner label (`cinefilm-prod`). |
| `frontend` | `Frontend — Lint, Test & Build` | `npm ci`, `npm run lint`, `npm run format:check` (Prettier), `npm run test:coverage` (95/90 per file plus the global floor), on PRs diff-cover at 90% of changed `frontend/src` lines (lcov, with the `SF:` prefix rewrite), `scripts/npm-audit.sh frontend high`, `npm run build`. 20 min timeout. |
| `docker` | `Docker — image builds` | Builds the production `Dockerfile` (SPA, jar, ffmpeg runtime) without pushing, then checks it runs as uid 1001 with `ffmpeg`, `ffprobe` and `vainfo` present. The Mac has no Docker, so this is the only proof the image builds. 20 min timeout. |
| `sonar` | `SonarQube Cloud — Analysis` | Needs `backend` and `frontend`. Downloads their artifacts (`backend-sonar-inputs`: classes, test classes and dependency jars; `jacoco-report`; `frontend-lcov`), rewrites the lcov `SF:` paths, and runs the pinned `sonarqube-scan-action` with `sonar.qualitygate.wait=true`, so the job fails when the quality gate on **new code** fails. Config: `sonar-project.properties` (backend, frontend, `scripts/`, Dockerfiles, `deploy/`, `.github/`). **Advisory, not a required check** (see `code-quality-plan.md` phase 4). Skips and reports success when `SONAR_TOKEN` is empty: fork PRs, and `deploy.yml`'s reuse of this workflow. 15 min timeout. |

**SonarQube Cloud** project `gregochr_cinefilm`, organization `gregochr`, Automatic Analysis off. `SONAR_TOKEN` is an
Actions secret and a Dependabot secret (the owner's personal token; when it expires the job fails at the scan step).
Results are public at `https://sonarcloud.io/project/overview?id=gregochr_cinefilm` and the same API serves
measures without a token. The separate `SonarCloud Code Analysis` check on a PR is the Sonar GitHub App's
decoration of the same analysis. A finding in new code is fixed in the PR. An accepted finding is recorded in
`sonar-project.properties` (`sonar.issue.ignore.multicriteria`: one entry per finding, exactly one rule and one
file, a dated reason in the comment, no wildcards), so the decision is reviewed in a PR; sessions cannot sign in to
the Sonar UI. Never `NOSONAR` or `@SuppressWarnings`.

**Read issues by impact, not by the legacy severity.** The dashboard groups issues by software quality (Security,
Reliability, Maintainability) with an impact severity; the API's older `severities` field rates the same issues
differently (an issue can be `MAJOR` there and `BLOCKER` by impact). Query `impactSeverities` and
`impactSoftwareQualities` on `api/issues/search`. The dashboard's ratings are for the whole code base; only the
quality gate judges new code.

**Docs-only path.** There are deliberately no `paths:` filters: a workflow filtered out by path never reports, and a
check that never reports cannot be satisfied. Instead both jobs always run; when every changed file is `docs/**` or
`*.md` (changelog entries included) `scripts/ci-docs-only.sh` (diffing with `--no-renames`, so moving code into `docs/x.md` still counts as code) outputs `docs_only=true` and every real step is skipped by a
step-level `if:`, leaving one echo step, so both checks go green in seconds. It is fail-safe: anything but a
non-empty all-docs pull request diff (push, dispatch, call, empty diff, any other path including
`.github/workflows/**`) runs everything.

**diff-cover** reads the same coverage files as the local gate, so you can reproduce it before pushing (the
percentages must be real numbers; "No lines with coverage information" on a diff that changes covered code means
the path mapping is wrong):

```bash
pip install diff-cover==10.0.0
git fetch origin main
diff-cover backend/target/site/jacoco/jacoco.xml --src-roots backend/src/main/java --compare-branch=origin/main --fail-under=90
sed 's#^SF:#SF:frontend/#' frontend/coverage/lcov.info > /tmp/lcov-root.info
diff-cover /tmp/lcov-root.info --compare-branch=origin/main --fail-under=90
```

**Frontend audit.** `scripts/npm-audit.sh frontend high` is `npm audit --audit-level=high` run under npm 11 with
retries on registry transport errors only (never on a finding). Run it locally the same way.

### `ci-failure-issue.yml` — CI failure issue

A reusable workflow (`workflow_call`, input `workflow-name`) that opens one `ci-failure` issue per failing
unattended workflow and comments on it for later failures. `pitest.yml` and `stryker.yml` call it from a final
`notify-failure` job that runs when the mutation job fails or is cancelled (a timeout ends as `cancelled`).

### `pitest.yml` and `stryker.yml` — weekly mutation testing

Scheduled (Monday 06:30 UTC and Wednesday 03:30 UTC) and `workflow_dispatch`; one job each, on `ubuntu-latest`. They
do not call `ci.yml` and are not PR gates. Each writes its score to the run summary and uploads its HTML report. See
"Mutation testing" above.

### `dependabot-auto-merge.yml` — Dependabot auto-merge

Copied from CineLight. Job `label` (on the PR event) adds `automerge-ok` to minor and patch Dependabot PRs and removes
it from majors. Job `merge` (on `workflow_run` of `CI`) squash-merges only when the label is present, the head has not
moved, all three required check-runs above succeeded, no other check-run on the head failed, and CI ran against the
current `main`; the merge is pinned to the head commit and then dispatches `ci.yml` on `main`. Major updates are never
merged automatically.

**Trust never rests on the event's actor** (`github.actor`, `workflow_run.actor`): that names whoever triggered the
event, which a fork can arrange to be Dependabot. `label` runs only for a PR authored by Dependabot from a branch in
this repo. `merge`'s job-level `if:` (same repo, `dependabot/` branch) is only a prefilter; the script re-reads the PR
from the API and requires a Dependabot author and, through `scripts/dependabot-commits-trusted.sh`, that every commit
is authored by `dependabot[bot]` with a verified signature. That script fails closed (no commits, bad JSON, an API
error) and is tested by `scripts/test/dependabot-commits-trusted.test.sh`. The `merge` job checks out the default
branch only, never the PR head, so the script judging a PR always comes from `main`. Not yet exercised end to end:
the first minor or patch Dependabot PR after 2026-11-10 is the proof.

### `dependabot.yml`

Weekly (Monday): Maven (`/backend`), npm (`/frontend`, with `vitest` and `@vitest/*` grouped, and `@stryker-mutator/*` grouped, so each family bumps together)
and GitHub Actions.

**ESLint is held at 9** (`ignore` for major updates of `eslint` and `@eslint/js`): `eslint-plugin-jsx-a11y` (our
accessibility gate) and `eslint-plugin-react` do not declare ESLint 10 in `peerDependencies`, so `npm ci` fails with
ERESOLVE, and we do not use `--legacy-peer-deps` or `overrides`. Remove the `ignore` when both plugins support 10
(checked 2026-11-10: 6.10.2 and 7.37.5 cap at 9).
