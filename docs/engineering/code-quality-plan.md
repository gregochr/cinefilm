# CineFilm — code quality gates and feedback loop plan

Written 2026-11-10 by the planner (docs only; no code). Owner's brief: the test gates are in place; add code-quality
checks on both sides, with a feedback loop so findings are fixed when they are spotted. Owner chose: **SonarCloud and
CodeQL as PR checks, ArchUnit on the backend, knip on the frontend**, and asked for a one-off baseline first.

Every phase is one PR, built by a Sonnet subagent in its own worktree under the owner rule in `CLAUDE.md`. Repo
settings (rulesets, secrets, code-scanning setup) are not code: the planner changes them with `gh` and records them
here and in `dev-and-testing.md`.

---

## 1. What changed on 2026-11-10 (context a later session needs)

- **The repo is public.** The free Actions quota for private repos ran out on the 10th; public repos are not metered
  (goldenhour is public for the same reason). SonarCloud and CodeQL are free for the same reason.
- **`main` is protected**: required checks `Backend — Build, Test & Coverage`, `Frontend — Lint, Test & Build`,
  `Shell — ShellCheck & script tests`; no force-push, no deletion; not enforced for admins; no review required.
- **Auto-merge is allowed** on the repo. With required checks it merges on green CI; it does **not** wait for Codex,
  so the planner still reads Codex on the head commit before enabling it on a PR.
- **Prettier is a CI step** (`npm run format:check`, PR #52).
- **CodeQL default setup is on** (extended suite; `java-kotlin`, `javascript-typescript`, `actions`; PRs, pushes to
  `main`, weekly). It is GitHub-managed: there is no `codeql.yml`.

## 2. Baseline (measured on `c0ee2cb`, 2026-11-10)

Local scans changed nothing in the tree. Tools: PMD/CPD 7.17.0, knip 6.44.2, eslint-plugin-sonarjs 4.2.2, jscpd 5.4.3.

| Area | Measure | Result |
|---|---|---|
| Backend size | main / test | 183 classes, 12,825 lines / 149 classes, 24,637 lines |
| Backend PMD quickstart | violations | 78 in 39 files; 31 are `GuardLogStatement` (style noise). Likely real: `PreserveStackTrace` 6, `ReturnEmptyCollectionRatherThanNull` 2, `EmptyCatchBlock` 1 (`ratings/RatingsBudget.java:48`) |
| Backend duplication | CPD, 100 tokens | 0 |
| Backend complexity | cyclomatic > 10 / cognitive > 15 | 10 methods (max 27, `SessionService.create`) / 6 methods (max 24, `PlexFilmWriter.apply`) |
| Backend layering (import grep, heuristic) | | package-by-feature, 15 packages. 1 controller uses a repository directly (`plex/ArtworkController` → `film.FilmRepository`). 0 entities import Spring web. Nothing imports a controller. `config` imports no feature package. One dependency cycle spans 9 packages (film, person, collection, playback, transcode, library, tmdb, ratings, plex); `home`, `stream`, `subtitle`, `seed`, `auth`, `config` are outside it |
| Frontend knip | raw / with minimal config | 15 / 1. Real: unused export `BACKDROP_SIZE_PHONE` (`src/api/images.js`). The rest are constants used only in their own file, and a JSDoc-only `@stryker-mutator/api` import |
| Frontend maintainability probe | sonarjs recommended + complexity rules | 58 findings (45 product, 13 test). Cognitive complexity > 15: 7. Functions > 150 lines: `PlayerView` 477, `FilmDetail` 216, `useStream` 170. `no-nested-conditional` (18, JSX ternaries) is noise |
| Frontend duplication | jscpd, 70 tokens, product only | 3 clones, 0.55% (two between `LibraryCollections.jsx` and `LibraryFilms.jsx`) |
| Frontend bundle | gzip | app 111 kB, hls.js 180 kB (own chunk), CSS 9 kB; no route-level splitting |
| CodeQL (GitHub, extended) | open alerts | 20: 5 high, 15 medium (below) |
| SonarCloud | | not measured: needs the owner's account (phase 4) |

**CodeQL alerts.** High: `java/path-injection` at `plex/ArtworkController.java:34` and
`transcode/HlsSessionController.java:56`; `java/spring-disabled-csrf-protection` at `auth/SecurityConfig.java:21`;
`java/improper-validation-of-array-index` at `transcode/CutList.java:114`; `java/comparison-with-wider-type` at
`transcode/Mp4SampleTableReader.java:255`. Medium: `actions/missing-workflow-permissions` ×5 (all `ci.yml`),
`actions/unpinned-tag` ×9 (`ci.yml`, `deploy.yml`, `dependabot-auto-merge.yml`), `java/relative-path-command` ×1 (a
test).

The PMD and ESLint probes are **not** being adopted as gates: SonarCloud covers the same ground (complexity,
duplication, smells) on new code only. Their numbers predict the shape of SonarCloud's backlog.

## 3. The feedback loop

1. **PR gate (all four tools).** A finding fails a check on the PR. Auto-fix wakes the session, Sonnet fixes, the
   planner re-reads CI and Codex and merges. No new machinery.
2. **Backlog.** What the baseline found in existing code is fixed once, in the phase that turns the gate on (CodeQL,
   knip, ArchUnit) or in a sized follow-up (SonarCloud, after its first scan).
3. **Drift in untouched code** (new CodeQL queries, new Sonar rules): the weekly CodeQL scan and Sonar's `main`
   analysis raise alerts nobody is paged for. Phase 5 adds a weekly Claude routine that reads them and opens a fix PR.

## 4. Phases

### Phase 1 — CodeQL: clear the baseline, then gate

- **Triage each of the 5 high alerts before changing code.** For each: is the tainted value attacker-controlled
  after authentication, and is it already constrained? Fix real ones (canonicalise and check the resolved path stays
  under its root; bounds-check the index; widen the loop variable). `spring-disabled-csrf-protection` is expected to
  be a false positive because `auth/` has its own CSRF filter: confirm by reading it and its tests, then dismiss
  the alert in GitHub with that reason rather than suppressing in code.
- **Workflows:** add a top-level least-privilege `permissions:` block to `ci.yml` (jobs that need more already
  declare it; check `dependabot-auto-merge.yml` and `deploy.yml` still work). Pin third-party actions to commit SHAs
  with the version in a trailing comment; confirm `.github/dependabot.yml` has the `github-actions` ecosystem so the
  pins are kept current. First-party `actions/*` may stay on tags only if CodeQL stops flagging them; otherwise pin.
- **Tests:** a regression test per real fix (path traversal attempts return 400/404; malformed cut list; the
  wider-type loop bound). Existing coverage floors and diff-cover apply.
- **Gate (planner, after the fix PR merges and the alert count is 0):** a repository ruleset on `main` with the
  code-scanning rule for CodeQL, blocking on security alerts `high` or above and on `errors`. A ruleset, not a
  required status check, so a docs-only PR (which CodeQL may not scan) is never left waiting on a check that will
  not report. Verify with a throwaway PR that adds an obvious path injection: it must be blocked.
- **Docs (planner):** `dev-and-testing.md` "CI workflows" gains CodeQL, the ruleset, and the section 1 facts above.
- **Risk:** pinning actions touches `deploy.yml`, which only runs on a tag, so a bad pin there would first show at
  release time. The phase must run `actionlint` (or equivalent) over every workflow and resolve each pinned SHA
  against its tag, and say so in the PR.

### Phase 2 — knip (frontend)

- `frontend/knip.json`: `ignoreExportsUsedInFile: true`, `ignoreDependencies: ["@stryker-mutator/api"]`. Remove
  `BACKDROP_SIZE_PHONE` if the handover does not call for it (check `docs/design/handover/` first; if it is a
  planned value, use it or leave a tracked exception with the reason).
- `knip` as an exact-pinned devDependency (Dependabot keeps it current), script `"knip": "knip"`, and a CI step after
  `Format check (Prettier)` in the frontend job. Add `npm run knip` to the frontend gate line in `CLAUDE.md`.
- **Done when:** `npm run knip` exits 0 with that config, and a PR adding an unused file or dependency fails.

### Phase 3 — ArchUnit (backend)

Rules as ordinary JUnit 5 tests in `backend/src/test/java/gregochr/cinefilm/architecture/`, run by the existing
`verify`. Each rule encodes something the baseline showed is already true, or nearly:

1. Classes annotated `@RestController` do not depend on Spring Data repositories. One violation today: route
   `ArtworkController` through a service in `plex` (or `film`), with tests.
2. `@Entity` classes do not depend on `org.springframework.web..` or `org.springframework.http..`.
3. No class depends on a `@RestController` or `@ControllerAdvice` class.
4. `config` depends on no feature package.
5. Nothing depends on `home`, `seed`, `stream` or `subtitle` (leaf packages). Sonnet verifies each with the real
   class graph first; a package that fails is dropped from the rule and reported, not forced.
6. Top-level packages are free of cycles, as a **frozen** rule (`FreezingArchRule`, violation store committed under
   `backend/src/test/resources/archunit/`): the existing 9-package cycle is recorded and may not grow. Shrinking it
   is not part of this plan.

- **Build:** `archunit-junit5` test-scoped, newest release; the phase must first prove it reads Java 25 class files
  (if not, stop and report: do not lower the bytecode level). Exclude the architecture tests from PIT
  (`excludedTestClasses`): they kill no mutants and cost time. JaCoCo is unaffected (test scope).
- **Risk:** the frozen store makes a rule pass silently if the store is regenerated. The freeze must be configured
  with `freeze.store.default.allowStoreUpdate=false` in CI's `archunit.properties`, so only a deliberate local run
  can rewrite it, and a changed store file is visible in the diff.

### Phase 4 — SonarCloud (both sides) — blocked on the owner

**Owner steps:** sign in at sonarcloud.io with GitHub, import `gregochr/cinefilm`, turn **off** "Automatic
Analysis", and add the token as the Actions secret `SONAR_TOKEN` **and** as a Dependabot secret of the same name.

- **CI-based analysis**, because Java analysis needs compiled classes and the gate should see real coverage. A
  `sonar` job in `ci.yml` after `backend` and `frontend`, fed by artifacts (`backend/target/classes`, the JaCoCo
  XML, `frontend/coverage/lcov.info`), with a root `sonar-project.properties` (sources `backend/src/main/java` and
  `frontend/src`; tests declared separately; fixtures and `src/test/**` excluded from duplication).
- The job follows `ci.yml`'s docs-only pattern (steps skipped, job reports) and **skips with a clear message when
  `SONAR_TOKEN` is empty** (fork PRs) instead of failing.
- **Quality gate:** SonarCloud's default "Sonar way" on **new code** (no new issues, new-code coverage and
  duplication thresholds). The scan step waits for the gate (`sonar.qualitygate.wait=true`) so the job's exit code is
  the gate.
- **Required check:** not at first. Run it advisory for a week of PRs, review false positives, tune the rule
  profile in SonarCloud, then the planner adds the job to `main`'s required checks and to `CLAUDE.md`.
- **Backlog:** after the first `main` analysis the planner sizes it (expect the shape in section 2: about 6–10
  complex backend methods, three oversized frontend functions, the Library screens' duplication) and proposes a
  burn-down PR series to the owner. Not pre-approved here.

### Phase 5 — weekly drift routine

A scheduled Claude routine, weekly after the CodeQL scan: read open code-scanning alerts on `main` and (once phase 4
is live) new SonarCloud issues on `main`; if any, open one fix PR through the normal rule (Sonnet codes, gates, PR,
merge on green CI and Codex). If none, do nothing. Designed and proposed to the owner after phases 1–4, when the
real alert rate is known; a routine that fires on an empty queue every week is not worth having.

## 5. Order and dependencies

1 → 2 → 3 are independent and can run in any order; 1 goes first because it carries the only high-severity
findings. 4 waits for the owner's SonarCloud steps and can slot in whenever they are done. 5 is last.

## 6. Open points

- **`ArtworkController` path injection vs rule 1 of phase 3** touch the same class: phase 1 fixes the path handling
  only; phase 3 moves the repository access. Do not merge the two.
- **Codex and auto-merge:** auto-merge does not wait for Codex. Unchanged rule: read Codex on the head commit first.
- **Admin bypass on `main`** is still on (owner's call).
