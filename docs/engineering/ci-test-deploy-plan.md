# CineFilm — testing standards, CI, mutation testing and deployment plan

Written 2026-11-08 by the planner (docs only; no code). Owner's brief: make CineFilm's testing standards, mutation
testing, CI and deployment **like goldenhour's**. Sources read: goldenhour (`.github/workflows/*`,
`.github/dependabot.yml`, `Dockerfile`s, `docker-compose.yml`, `Makefile`, `DEVOPS.md`, `release.sh`, `scripts/`,
`backend/pom.xml`, `frontend/package.json`, `vite.config.js`, `stryker.config.mjs`, `playwright.smoke.config.js`,
`docs/engineering/reference/dev-and-testing.md`, `test-improvement-standards.md`, `frontend-test-standards.md`,
`CLAUDE.md`), CineLight (`plex-webhooks/docs/engineering/test-standards.md`, `dev-and-testing.md`,
`reboot-plan.md`, its workflows), and CineFilm's own `CLAUDE.md`, `AGENTS.md`, `foundation-plan.md`, `backend/`,
`frontend/` and `.github/workflows/`.

Every phase below is one PR, built by a Sonnet subagent in its own worktree under the owner rule in `CLAUDE.md`
(Opus/Fable plan and review, Sonnet codes, `changelog.d/` entry per PR, squash-merge only on green CI on the exact
head commit). The planner writes the docs listed in each phase; Sonnet writes everything else.

---

## 1. goldenhour at a glance (check I understood it)

**Where it runs.** Production is **dockermacmini**, the Ubuntu Mac mini. Evidence: `backup-verify.yml` and
`prod-health.yml` say "`self-hosted`, which is dockermacmini — the production host"; `scripts/backup-postgres.sh`
says Docker moved "onto the Ubuntu host (dockermacmini)" around 2026-03-16; `release.sh` prints "Post-deploy health
check (on dockermacmini)". So **CineFilm will share a host with goldenhour production.**

**Topology on that host.** `~/goldenhour/docker-compose.yml` runs three containers: `goldenhour-db`
(`postgres:17-alpine`, data bind-mounted at `/home/gregochr/goldenhour-data/postgres`, **host port 5432
published**), `goldenhour-backend` (`ghcr.io/gregochr/goldenhour-backend:latest`, **host port 8082**, `env_file
./backend/.env`, prod profile, logs at `/home/gregochr/goldenhour-data/logs`, `stop_grace_period: 40s`, healthcheck
on `/actuator/health/container`) and `goldenhour-frontend` (nginx with the bind-mounted `nginx.conf`, **host port
80**). Public access is a **Cloudflare tunnel** (`cloudflared` systemd service) to `app.photocast.online`; the host
is also on **Tailscale** (`100.76.73.16`).

**PR CI (`ci.yml`, every PR against any base, push to `main`, and `workflow_call` from deploy).**
- `changes`: docs-only detector. Required jobs always *run and report*; only their steps are skipped for a
  docs-only diff (a `paths-ignore` would leave required checks unreported and the PR blocked). Fail-safe: any doubt
  runs everything.
- `Backend — Build, Test & Coverage`: `./mvnw clean verify` (Checkstyle at `validate`; tests incl. Testcontainers
  Postgres integration classes; JaCoCo **95% line / 90% branch per class** with measured per-class overrides each
  carrying an XML comment naming the unreachable line, plus a bundle floor at the measured total 99.3/95.8;
  SpotBugs `effort Max`, `threshold High` with FindSecBugs at `verify`). On PRs: **diff-cover ≥ 90% of changed
  `src/main` lines** (pinned `diff-cover==10.0.0`, `--src-roots backend/src/main/java`). Uploads JaCoCo and SpotBugs
  reports.
- `Frontend — Lint, Test & Build`: `npm ci`, ESLint `--max-warnings 0`, `vitest run --coverage` with global
  thresholds (99/98/98/95) **and 95% line / 90% branch per file**, diff-cover ≥ 90% on changed `frontend/src`
  lines (with the `SF:` path rewrite), `scripts/npm-audit.sh frontend high`, build, entry-closure check, `nginx -t`
  and a log-redaction canary. `timeout-minutes: 20`.
- `Shell — ShellCheck` (pinned `koalaman/shellcheck:v0.11.0`, `-o check-unassigned-uppercase`, including the SSH
  script extracted from `deploy.yml`) and `Shell — script tests` (`scripts/test/*.test.sh` against stub
  docker/git/psql).
- `E2E — Playwright smoke` (required): real backend jar (`-Plocal-dev`, fresh H2 with a seeded fixture, dead
  outbound proxy) behind the production nginx.conf, Chromium only, Playwright pinned 1.64.1, `ubuntu-24.04`.
- Separate workflows: `a11y.yml` (axe via Playwright, **advisory**, its own file so it can't block a release),
  `codeql.yml` (Java + JS on every PR, weekly full), `security-scan.yml` (OWASP dependency-check, `failBuildOnCVSS
  7`, NVD DB refreshed daily on `main` only and cached; PRs scan only when dependency inputs change and never
  download), `zap.yml` (weekly, on the self-hosted box, against a throwaway stack restored from a prod DB snapshot,
  with a Python gate and known-rules files), `pitest.yml` (weekly, 5 shards, `mutationThreshold 60`), `stryker.yml`
  (weekly, 10 shards over `src/utils`, per-shard floors 94–97, incremental state carried in artifacts),
  `real-api-smoke.yml` and `prompt-regression.yml` (Anthropic-specific), `ci-failure-issue.yml` (reusable: one open
  issue per failing unattended workflow, then comments), `dependabot-auto-merge.yml` (minor/patch `gh pr merge
  --auto --squash`, relying on branch protection's required checks), `dependabot.yml` (Maven + npm weekly Monday,
  vitest and stryker groups).
- Rules carried in comments: required checks never on the self-hosted runner (a dead runner *queues* forever and
  blocks merging); notifiers fire on `failure` **or** `cancelled`; pin tool versions; gate on exit codes.

**Release and deploy (`release.sh` → tag `v*` → `deploy.yml`).** The owner runs `release.sh` (sessions never
tag). It promotes `changelog.d/` into `CHANGELOG.md` through an auto-merged docs-only PR, tags, pushes and watches
the deploy. `deploy.yml`: (1) calls `ci.yml`; (2) builds and pushes both images to **GHCR** (`:latest` and
`:vX.Y.Z`) on `ubuntu-latest` with `GITHUB_TOKEN`; (3) a `deploy` job on **`runs-on: self-hosted`** (the runner
lives on dockermacmini) uses `appleboy/ssh-action` with `secrets.DEPLOY_SSH_KEY` to SSH to `100.76.73.16` (the same
box over Tailscale). The remote script: refuses to deploy over a rollback pin (`docker-compose.override.yml`),
`git fetch --tags` in the host's clone, extracts the tag's `scripts/`, runs `pre-release-backup.sh` (pg_dump +
running image **digests** + Flyway version + outgoing config, never overwriting an existing rollback point),
writes the tag's `nginx.conf` and `docker-compose.yml`, refuses to start Postgres on an empty PGDATA, `pull` + `up
-d --no-deps`, then `prod-health.sh --deploy --classify` and `post-deploy-smoke.sh` (exit contract: 0 pass, 1
release broken, 3 infra/inconclusive, 4 smoke misconfigured). Only exit 1 with the repo variable
`SMOKE_ENFORCE=true` triggers `auto-rollback.sh` (pins previous digests in `docker-compose.override.yml`, restores
old config, re-verifies; **never touches the database**). `record-deployed-images.sh` records digests on success.
`rollback.sh` is the manual Plan B (restores the DB too). A failed deploy files an issue. Concurrency group
`prod-deploy`, queued, never cancelled.

**Operations.** `goldenhour-backup.timer` (systemd, 02:00, `Persistent=true`) runs `backup-postgres.sh` (7 daily +
4 weekly, optional off-box rsync, success marker). `backup-verify.yml` (08:00 UTC, self-hosted) restores the newest
dump into a scratch Postgres and counts rows, checks the off-box copy, and an **off-box runner watchdog** on
`ubuntu-latest` fails if the self-hosted job never got picked up. `prod-health.yml` (07:00 UTC) runs
`prod-health.sh --daily` on-host (compose, cloudflared, Tailscale, app answers 401 from outside, **reboot-required
and pending apt updates fail the run**) plus an external `curl` probe and the same watchdog.

**Test standards.** Backend: `test-improvement-standards.md` (three questions; no `any()` in `verify()` or knowable
`when()`; no `lenient()`; boundary/below/above for every threshold; no `@SpringBootTest` where a unit test will
do; fixed clocks pinned to a date that cannot be today; never restore a mutation with `git checkout --`).
Frontend: `frontend-test-standards.md` (role queries for anything interactive, no `container.querySelector`, no
`toBeTruthy`, assert values and negatives, degrade paths get named tests, a11y is assertions, keyboard tests fire
at `document.activeElement`, no snapshots, no sleeps, settle async actions). `dev-and-testing.md`: the build
ladder, the "no Docker on the dev Mac" rule (Testcontainers classes are CI-only; the local gate carries
`-Dtest='!**/integration/**'`), UI review cadence (build → tests → adversarial review → fix → re-verify → commit;
reviewers read-only), ratchet rule (raise floors, never lower without the owner).

---

## 2. Audit: CineFilm today against those standards

### Backend (`backend/pom.xml`, 17 test files, ~2,100 lines)

| # | Gap | goldenhour / CineLight standard |
|---|---|---|
| B1 | JaCoCo has only a **bundle** rule (90% line, 80% branch). No per-class rule. | 95% line / 90% branch **per class**, bundle at the measured total, per-class overrides only for a named unreachable line. |
| B2 | JaCoCo **excludes** `CineFilmApplication*` and `seed/**` from measurement altogether. | "No class is excluded from checking altogether." `DevSeed` already has `DevSeedTest`; the application class can be excluded from PIT, not from JaCoCo (or covered by a context-load test). |
| B3 | No SpotBugs / FindSecBugs. | `spotbugs-maven-plugin` 4.10.5.0, `effort Max`, `threshold High`, FindSecBugs 1.14.0, `spotbugs-exclude.xml`, bound to `verify`. |
| B4 | No OWASP dependency-check plugin or suppressions file. | `dependency-check-maven` 13.0.0, `failBuildOnCVSS 7`, run only from `security-scan.yml`. |
| B5 | No PIT plugin, no `junit-platform-launcher`. | `pitest-maven` 1.30.1 + `pitest-junit5-plugin` 1.2.3, `pit.targetClasses` property, `mutationThreshold`. |
| B6 | No `spring-boot-starter-actuator`, no `git-commit-id-maven-plugin`. | Health groups (`/actuator/health/container` = db + ping) for Docker healthchecks and deploy smoke; `/actuator/info` `git.commit.id` for the "running build matches the tag" probe. |
| B7 | Checkstyle is "Google-style-lite", **no Javadoc mandates**, no `Indentation`, no suppressions file. | goldenhour requires Javadoc on every public type/method/constructor in `src/main` (`MissingJavadocType`/`MissingJavadocMethod`, tests exempt via `checkstyle-suppressions.xml`), 4-space `Indentation`. (Owner decision D4.) |
| B8 | H2 is `runtime` scope: it would ship in a production image. | H2 is `test` scope; production runs Postgres (CineFilm: phase 1, dev on Postgres too). |
| B9 | No Testcontainers; Flyway is only ever exercised on H2 in `MODE=PostgreSQL`. | `IntegrationTestBase` boots Postgres 17 Testcontainers with Flyway; CI-only. |
| B10 | Surefire has no `forkCount`, no tag filtering (`excludedGroups`). | Tags gate what runs where (`real-api`, `prompt-regression`). CineFilm will need `real-api` for TMDB in phase 2 of the product. |
| B11 | **Clock pinned to today.** `TestData.NOW = 2026-11-08T12:00:00Z` is the date the suite was written: goldenhour's smell #6 exactly (a test that ignores the injected clock passes on that day). | Pin to a past or far-future year. |
| B12 | **API tests run on the real clock.** `HomeApiTest`, `FilmsApiTest`, `PlaybackApiTest`, `StreamApiTest`, `PersonApiTest`, `CollectionApiTest` use `Instant.now()`, and `ClockConfig` is `Clock.systemDefaultZone()` with no test override, so "recently added (30 days)", "not watched in years" and the Monday weekly-theme rollover depend on the day and the machine's time zone. The container will be UTC unless told otherwise. | A fixed `Clock` bean in the test profile; an explicit application zone (D21). |
| B13 | Every API test is a full `@SpringBootTest` (`ApiTestBase`, `AuthApiTest`, `DevSeedTest`, `SessionCookieTest` on a random port). No `@WebMvcTest` slices, no Mockito unit tests of `HomeService`, `PlaybackService`, `PreferencesService`, `FilmService`, `LibraryLoader`, `StreamService`, `CollectionService`, `PersonService`: they are tested only through HTTP. | Smell #5. It also matters for PIT: goldenhour excludes full-context tests from PIT, so the services need unit tests of their own to be killable cheaply. Keep the MockMvc tests (they are the API contract); add service unit tests rather than converting. |
| B14 | Existence assertions where a value is knowable: `jsonPath("$.error").exists()` in `AuthApiTest`. | "Assert the value, not that a value exists." |
| B15 | `AuthApiTest.protectedRequests()` lists routes by hand; a new controller route not added to it passes CI unprotected-by-test. | goldenhour's drift-test pattern (`PitExclusionDriftTest`): add a test that enumerates `RequestMappingHandlerMapping` and fails when an `/api/**` route other than `/api/session` is missing from the list. Serves `AGENTS.md` rule 5. |
| B16 | No test-standards doc, no `dev-and-testing.md`, no build ladder. | Both repos have them. |

What is already good: test names state the rule (`progressAtTheMarkerMakesItWatchedEvenBelowNinetyTwoPercent`),
boundary tests at the watched threshold and the five-year rule, no `lenient()`, no `any()`, no `Thread.sleep`, no
`@Disabled`, Flyway actually runs in tests, `lombok.addLombokGeneratedAnnotation = true` is set, the 401 and
CSRF-header cases are centralised.

### Frontend (`frontend/`, 5 test files, ~760 lines, ~45 source files)

| # | Gap | Standard |
|---|---|---|
| F1 | **No coverage at all**: no `@vitest/coverage-v8`, no `test:coverage` script, no thresholds. | Global floor at the measured figure plus **95% line / 90% branch per file**, `include: src/**/*.{js,jsx}` so untested files count as 0%. |
| F2 | Many files have no test of their own (likely below a per-file floor): `components/Scrubber.jsx`, `PlayerMenus.jsx`, `PlayerOverlays.jsx`, `Controls.jsx`, `Header.jsx`, `TabBar.jsx`, `SignInCard.jsx`, `FilmRow.jsx`, `Poster.jsx`, `Art.jsx`, `CollectionCard.jsx`, `StateMessages.jsx`, `Skeleton.jsx`, `useSlashFocus.js`, `api/fixtureServer.js`, `state/useDebouncedValue.js`, `usePeopleAndCollections.js`, `useResource.js`, `player/time.js`. Some are reached through `screens.test.jsx`; the per-file run will say which. | A new file arrives with its tests. |
| F3 | One `container.querySelector('video')` in `screens.test.jsx`. | Never `container.querySelector`; give the element a role or test-id. |
| F4 | No Playwright E2E, no axe a11y scan. Primary client is **Safari**, so WebKit matters more than it does for goldenhour. | `E2E — Playwright smoke` required; axe scan. |
| F5 | No Stryker. | Weekly Stryker with per-run floor. |
| F6 | No `npm audit` in CI. | `scripts/npm-audit.sh frontend high` in the frontend job. |
| F7 | No frontend test-standards doc. CineFilm's house style differs from goldenhour's and must be written down, not overwritten: it mocks at the **fetch** boundary (`src/test/mockApi.jsx` `installFetch`/`respond`) rather than `vi.mock` of API modules, and uses `userEvent` (21) more than `fireEvent` (10). | Codify the existing conventions. |

### CI (`.github/workflows/backend.yml`, `frontend.yml`)

| # | Gap |
|---|---|
| C1 | **`paths:` filters on both workflows.** This is the trap goldenhour's `ci.yml` header warns about: if these checks are ever required, a PR touching only the other side leaves them unreported and blocked. Replace with one `ci.yml` and the docs-only detector. |
| C2 | No diff-cover, no coverage artifacts, no `concurrency`, no `timeout-minutes`, no `workflow_call` (so a deploy cannot reuse CI). |
| C3 | No `ci-failure-issue.yml`, no Dependabot config, no auto-merge, no security, mutation, E2E or a11y workflows. |
| C4 | No branch protection is possible today (see D1): the repo is **private** (`gh repo view`: `PRIVATE`). goldenhour is public. CineLight hit the same wall (`reboot-plan.md`: "private on a free plan, so `master` cannot have required checks"), and built its own auto-merge gate. |

### Constraints from the repo being private (affects several phases; decision D1)

- **CodeQL code scanning is not available** for a private repository on a personal account (it needs GitHub Code
  Security on an organisation plan). goldenhour's `codeql.yml` cannot simply be copied.
- **No branch protection / required checks** on GitHub Free for a private repo; `gh pr merge --auto` cannot gate on
  CI. The merge rule in `CLAUDE.md` (`--match-head-commit` after reading the checks) is then the only gate, and
  Dependabot auto-merge must use CineLight's `workflow_run` design, not goldenhour's.
- **Hosted Actions minutes are metered** (2,000/month on Free, 3,000 on Pro). Budget: a full PR run (backend + frontend
  + E2E + shell) ≈ 12–15 min, daily NVD refresh ≈ 3 min, weekly PIT ≈ 15 min, weekly Stryker ≈ 20 min. Comfortable
  for one developer, tight with many Dependabot PRs. Never use hosted macOS runners (10× multiplier).
- **GHCR packages are private**, so the host must authenticate to pull (solved in phase 8 by logging in inside the
  deploy job with `GITHUB_TOKEN`).

If the owner makes the repo public, CodeQL, branch protection, free minutes and goldenhour's auto-merge all apply
unchanged. Nothing in the repo is secret (secrets are env vars), but the design handover and plans would be public.

---

## 3. Cross-cutting decisions this plan recommends

**PostgreSQL 17 everywhere that matters (owner decision, 2026-11-08: settled).** Production, local dev and the
migration tests run on Postgres, like goldenhour; it lands in **phase 1**, before any gate or test work, so every
later phase builds on the final database. Why it was the right call, for the record: H2's on-disk format changes
between versions (a Dependabot bump could strand the file holding the watch history); goldenhour's backup, verify,
pre-release-dump and PGDATA-guard toolchain is Postgres-shaped and copies over almost unchanged; WAL crash recovery
on a 2012 machine with no UPS; and product phases 3–4 add concurrent writes and years of imported history.

**Test database strategy (goldenhour's split, one improvement).** The dev Mac has **no Docker** (`which docker`: not
found), the same as goldenhour's, so Testcontainers cannot run locally:
- **Migrations and repositories are proven on Postgres 17 Testcontainers** in an `integration` package of
  `IntegrationTestBase` subclasses, which run in CI's backend job (ubuntu runners have Docker). The local gate
  carries `-Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false`, exactly as goldenhour's does.
- **The default unit/MockMvc suite stays on in-memory H2** (`MODE=PostgreSQL`, Flyway on) for speed and because it
  needs no Docker. Rule: **migrations are written for Postgres and never contorted for H2.** The day one cannot
  run on H2, the test profile switches to goldenhour's `ddl-auto=create-drop` with Flyway off, and Testcontainers
  becomes the only migration proof (it already is the authoritative one).
- **Improvement on goldenhour: local dev runs real Postgres** (Homebrew `postgresql@17` as a brew service, no
  Docker), with Flyway on, so `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` applies every migration to a
  real Postgres before CI does. goldenhour's local profile is H2 with Flyway off and cannot do this.

**One container, Spring serves the SPA (recommended).** The foundation plan already says so. goldenhour's two-image
nginx + backend split exists for a public site (CSP, caching, Cloudflare). For a single-user server whose heaviest
traffic is video byte-range streaming that must go through Spring anyway (AGENTS.md rule 6: only paths known to the
database), an nginx hop adds a second image, a config to `nginx -t`, and proxy-buffering tuning for multi-GB files,
for no gain. The SPA build is copied into `static/` at package time; Spring serves `index.html` for client routes.

**Remote access: Tailscale, not Cloudflare (recommended).** The host is already on Tailscale (goldenhour deploys
through `100.76.73.16`). `tailscale serve` gives `https://<host>.<tailnet>.ts.net` with a real certificate, which
satisfies "HTTPS behind the VPN", the `Secure` cookie and iPhone Safari. Cloudflare's tunnel/CDN terms are hostile
to serving video, and CineFilm must not be on the public internet. Consequence: Spring must honour
`X-Forwarded-Proto` from the local proxy (`server.forward-headers-strategy=native`, trusting 127.0.0.1 only) so the
session cookie gets `Secure`.

**Names, ports and paths on the shared host (avoid every goldenhour clash).**

| Thing | goldenhour uses | CineFilm uses |
|---|---|---|
| Host ports | 80 (frontend), 8082 (backend), 5432 (db) | app `127.0.0.1:8090 → 8080` (behind `tailscale serve`); **db publishes no port** |
| Containers | `goldenhour-db/-backend/-frontend`, `pentest-*`, `backup-verify-db` | `cinefilm-db`, `cinefilm`, later `cinefilm-worker`; scratch `cinefilm-backup-verify-db` |
| Compose project | `~/goldenhour` | `~/cinefilm`, with `name: cinefilm` set in the file |
| Data | `/home/gregochr/goldenhour-data/{postgres,logs}` | `/home/gregochr/cinefilm-data/{postgres,logs,cache,transcode}` |
| Backups | `/home/gregochr/goldenhour-backups` | `/home/gregochr/cinefilm-backups` |
| systemd | `goldenhour-backup.{service,timer}` 02:00 | `cinefilm-backup.{service,timer}` 03:00 |
| Runner | `actions.runner.gregochr-goldenhour.*`, `runs-on: self-hosted` | a **second** runner registered to `gregochr/cinefilm` in `~/actions-runner-cinefilm`, label `cinefilm-prod`; `runs-on: [self-hosted, cinefilm-prod]` |
| Images | `ghcr.io/gregochr/goldenhour-{backend,frontend}` | `ghcr.io/gregochr/cinefilm`, later `ghcr.io/gregochr/cinefilm-worker` |
| Deploy concurrency | `prod-deploy` | `cinefilm-prod-deploy` |
| Self-hosted schedules | ZAP Mon 04:00 UTC (a whole pentest stack on the box), prod-health 07:00, backup-verify 08:00 | prod-health 07:15, backup-verify 08:30; never schedule heavy CineFilm work at Mon 04:00 |
| Media | — | `/mnt/workhorse-films` (cifs, read-only) → container `/media:ro` |

Host-level checks (reboot required, pending apt updates, Tailscale up) are **already** done every morning by
goldenhour's `prod-health.sh` on the same box. CineFilm's health script must not repeat them (two issues per
incident); it checks only CineFilm's containers, its `tailscale serve`, the media mount and its disk.

---

## 4. Phases

Each phase lists: **Copy** (goldenhour paths to mirror), **Adapt**, **Leave out**, **Gates**, **Owner provides**,
**Docs** (written by the planner in the same PR).

### Phase 1 — PostgreSQL and Testcontainers (settled by the owner)

**Copy**
- `goldenhour/backend/src/test/java/com/gregochr/goldenhour/integration/IntegrationTestBase.java` minus the WireMock
  / Anthropic parts: `@SpringBootTest`, `@ActiveProfiles({"test", "integration-test"})`, `@Testcontainers`, a static
  `@Container PostgreSQLContainer` on `postgres:17-alpine`, `@DynamicPropertySource` setting the datasource and
  `spring.flyway.enabled=true`, and `@DirtiesContext(classMode = AFTER_CLASS)` **with its whole Javadoc** (the
  per-class container restart hands out a new port; without the annotation a cached context keeps the old one, a
  real goldenhour CI failure).
- pom dependencies from `goldenhour/backend/pom.xml`: `org.postgresql:postgresql` (runtime),
  `org.flywaydb:flyway-database-postgresql`, `spring-boot-testcontainers`, `org.testcontainers:testcontainers-junit-jupiter`,
  `org.testcontainers:testcontainers-postgresql` (test; versions from the Boot BOM). H2 moves to **`test` scope**
  (goldenhour's comment: test scope is what keeps it out of the production image).
- The `docker-compose.yml` `goldenhour-db` service shape (for phase 8: `postgres:17-alpine`, bind-mounted data,
  `pg_isready` healthcheck, log rotation, `depends_on: condition: service_healthy`) — written down now in
  `operations.md`'s outline, built in phase 8.

**Adapt**
- Profiles: `application.properties` keeps shared settings only (no datasource URL). `application-dev.properties`:
  `jdbc:postgresql://localhost:5432/cinefilm`, user/password from `CINEFILM_DB_USER`/`CINEFILM_DB_PASSWORD` with
  dev fallbacks `cinefilm`/`cinefilm`, DevSeed on an empty database as today. New `application-prod.properties`:
  datasource from `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` (set by compose, no fallback: a missing value must fail
  start-up), Flyway on, `ddl-auto=validate`. `src/test/resources/application-test.properties` stays on H2
  `MODE=PostgreSQL` (see §3). Hikari pool small (`maximum-pool-size=5`): one user, a shared 2012 host.
- `V1__schema.sql`: review it on Postgres (types such as `TIMESTAMP WITH TIME ZONE` for instants, `BIGINT`
  identities, `TEXT` vs `VARCHAR`, boolean defaults) and fix anything that only H2 accepted. Phase 1A is merged but
  nothing is deployed, so **editing V1 in place is allowed this once** (no database anywhere holds it except
  throwaway dev files); from this PR on, migrations are append-only.
- Integration tests (`src/test/java/gregochr/cinefilm/integration/`, CI-only):
  `FlywayMigrationIntegrationTest` (all migrations apply to an empty Postgres 17 and Hibernate `validate` passes);
  `LibraryQueriesIntegrationTest` (DevSeed's library, then every custom repository query and `LibraryLoader` path —
  browse, sort ignoring "The", search across title/actor/director, case-insensitivity, which differs between H2 and
  Postgres collations — returns the same results the H2 tests assert); `PlaybackStateIntegrationTest` (progress
  upsert and watched transitions persist on Postgres). Name the class suffixes so `!**/integration/**` excludes
  them all.
- Migration policy written into `dev-and-testing.md`: **expand, then contract** (add columns nullable or with a
  default; drop only in a later release) because phase 8's automatic rollback restores images, never the database,
  so the previous image must start against the new schema.
- Gates text: the CLAUDE.md backend gate becomes `./mvnw clean verify --batch-mode --no-transfer-progress
  -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false`, with goldenhour's note that migrations are proven
  before merge only by CI's backend job (and, for CineFilm, by a local dev run on Homebrew Postgres).
- CI: the existing `backend.yml` already runs on `ubuntu-latest` with Docker, so the integration classes run there
  unchanged; add `timeout-minutes: 20`.
- Optional helper `scripts/dev-db.sh` (idempotent `createuser`/`createdb` for the dev role) so a fresh Mac or a
  Sonnet worktree gets the dev database in one command; ShellChecked from phase 8.

**Leave out**: goldenhour's `local-dev` Maven profile and `src/local` source roots (CineFilm's dev profile uses
real Postgres, so H2 never needs runtime scope), `LocalH2*` clean-up beans, `migrate-pgdata-path.sh`, the WireMock
Anthropic configuration.

**Gates**: local gate (with the exclusion) exit 0; CI `Backend` job green **with the integration classes
running** (the PR summary quotes their test counts from the surefire report, so "0 tests ran" cannot pass as
green); the owner (or the planner, read-only) starts the app once on the dev profile against Homebrew Postgres
and signs in.

**Owner provides**: `brew install postgresql@17 && brew services start postgresql@17` on the dev Mac (Sonnet can't
install system software), then `scripts/dev-db.sh` or `createuser cinefilm -P` / `createdb -O cinefilm cinefilm`.
Production DB password: generated at phase 8 into the host's `.env`.

**Docs**: `foundation-plan.md` stack row ("PostgreSQL 17 (production and dev), H2 in-memory for the fast test
suite; Flyway owns the schema") and the dev line; `AGENTS.md` deliberate decision "H2 in phase 1" → "H2 for the
fast test suite only; migrations target Postgres"; `CLAUDE.md` gate line; a first `dev-and-testing.md` with the
no-Docker rule and the migration policy (phase 2 extends it).

### Phase 2 — Backend test standards and gates

**Copy**
- `goldenhour/docs/engineering/test-improvement-standards.md` → CineFilm `docs/engineering/test-standards.md`
  (backend half), using CineLight's `plex-webhooks/docs/engineering/test-standards.md` as the shape (it is the
  already-adapted, shorter version: three questions, smells 1–6, tags table, process, mutation-checking, what not to
  do).
- `goldenhour/backend/pom.xml`: the `spotbugs-maven-plugin` block (FindSecBugs inside), the JaCoCo `check` rules
  structure (default per-class rule with `<excludes>` for overridden classes, one `<rule>` per override with the
  XML reason comment, bundle rule), `git-commit-id-maven-plugin` block (`failOnNoGitDirectory=false`, `offline`),
  surefire `forkCount 1C`, `excludedGroups ${surefire.excludedGroups}`, the `--add-opens` argLine.
- `goldenhour/backend/spotbugs-exclude.xml` as a starting point (prune to what fires).
- `goldenhour/backend/checkstyle.xml` + `checkstyle-suppressions.xml` *if D4 = adopt Javadoc*.

**Adapt**
- JaCoCo: default rule **95% line / 90% branch per CLASS**; bundle rule at the measured total rounded down to one
  decimal place; remove the `seed/**` exclusion (DevSeed is tested); keep `CineFilmApplication` measured (a
  `contextLoads`-style test already exists via the API tests) or give it a per-class override with the reason.
  Every class below the default either gets tests or (only for a truly unreachable line) its own `<rule>` at the
  measured figure with the line and reason in a comment. Sonnet measures; the planner reviews every override.
- Add `spring-boot-starter-actuator` now (health groups and info are needed by phases 7–9); expose only `health`
  and `info`; define a `container` health group = `db,ping`; `/actuator/**` other than those stays behind auth or
  unexposed. Update `AuthApiTest`/route drift test accordingly.
- Tests: fix B11 (`TestData.NOW` → a past year, e.g. `2024-03-13T12:00:00Z`, and assert the weekly-theme cases
  still mean what they meant), B12 (a `@TestConfiguration` fixed `Clock` in the test profile, at a non-today
  instant in an explicit zone; replace `Instant.now()` in fixtures with offsets from it), B13 (unit tests for the
  eight services listed, MockMvc tests kept), B14, B15 (route drift test).
- `surefire.excludedGroups` property = `real-api` (empty today, ready for TMDB).

**Leave out**: prompt-regression tags/exclusions, `PitExclusionDriftTest`'s prompt-regression half, solar-utils and
JitPack repository, Anthropic SDK/OkHttp pins, WireMock (add when TMDB arrives), the `src/local` source roots.

**Gates**: `cd backend && ./mvnw clean verify --batch-mode --no-transfer-progress` exit 0 (now includes per-class
JaCoCo and SpotBugs).

**Owner provides**: D4 (Javadoc), D5 (thresholds), D21 (zone).

**Docs**: `docs/engineering/test-standards.md` (backend half), `docs/engineering/dev-and-testing.md` (CineFilm's:
the build ladder `compile → one class → checkstyle:check → clean verify`, gate on exit codes, the ratchet rule,
how to read JaCoCo, mutation-checking without `git checkout --`, and goldenhour's "UI Work — Review Cadence"
section adapted: reviewers read-only, browser verification with the dev seed), `CLAUDE.md` table rows pointing at
both, `AGENTS.md` rule 5 extended ("…and meet `docs/engineering/test-standards.md`; coverage floors only go up").

### Phase 3 — Frontend test standards and gates

**Copy**
- `goldenhour/docs/engineering/frontend-test-standards.md` → frontend half of CineFilm's `test-standards.md`:
  philosophy, queries (roles required for interactive elements, dialogs, headings), assertions, UI boundaries,
  degrade paths, a11y as assertions, structure, keyboard tests at `document.activeElement`, the "what not to do"
  list including the React 19 never-settling-promise and outliving-timer traps, "a commit is not its effects",
  "never put the click inside the wait".
- `goldenhour/frontend/vite.config.js` `test.coverage` block (provider v8, `include`, `exclude` of `src/test/**`,
  tests and `src/main.jsx`, reporters `text-summary, json-summary, lcov`, `reportOnFailure`, global thresholds,
  the per-file glob entry) and the `exclude: ['**/node_modules/**', '**/e2e/**', '**/.stryker-tmp/**']`.
- `package.json` script `test:coverage: vitest run --coverage`; `@vitest/coverage-v8` pinned to the **exact**
  installed vitest version (CineFilm is on vitest 4.1.x; goldenhour is on 5 — do not copy its number).
- `goldenhour/scripts/npm-audit.sh` (transport-error retry, never retries a finding).

**Adapt**
- Thresholds: per file **95% lines / 90% branches**; global at the measured figure rounded down. Backfill tests for
  every file in F2 that misses the per-file floor (this is the bulk of the PR; if it is too large, split into 2a
  components / 2b player+state, keeping the floor off until 2b — D6).
- Codify CineFilm's conventions, don't import goldenhour's: mock at the fetch boundary with `src/test/mockApi.jsx`
  (no `vi.mock` of `src/api`, no MSW); `userEvent` is the default for interaction; fixtures in
  `src/test/fixtures/*.json` named for their shape; the handover's a11y contract (§7 of the handover, `AGENTS.md`
  rule 2) is asserted through roles: `aria-pressed`, `menuitemradio` + `aria-checked`, `role="slider"` with
  `aria-valuetext`, `role="status"`.
- Fix F3 (`video` gets a test-id or is queried via its accessible name).

**Leave out**: Tailwind/PropTypes/`data-testid`-everywhere conventions (CineFilm deliberately uses plain CSS and no
PropTypes), `check:entry` (Leaflet entry closure), the timezone section beyond "pin `TZ` if a test formats dates",
the 20 s `testTimeout` essay (keep Vitest's default until a measured need).

**Gates**: `cd frontend && npm run lint && npm run test:coverage && npm audit --audit-level=high && npm run build`
(the CLAUDE.md gate line changes to this).

**Owner provides**: D5, D6.

**Docs**: frontend half of `test-standards.md`; frontend section of `dev-and-testing.md` (local diff-cover recipe
with the `SF:` rewrite); `CLAUDE.md` gate line.

### Phase 4 — CI parity

**Copy**
- `goldenhour/.github/workflows/ci.yml`: `on` (push main, unfiltered `pull_request`, `workflow_call`),
  `concurrency`, the `changes` docs-only job (keep the comments; simplify the regex to `^docs/|\.md$` — CineFilm
  has no `landing/`), the backend job (checkout `fetch-depth: 0`, setup-java 25 temurin maven cache, `clean
  verify`, diff-cover 90% with `--src-roots backend/src/main/java` and the step summary, JaCoCo + SpotBugs
  artifacts, the docs-only echo step), the frontend job (`timeout-minutes: 20`, lint, `test:coverage`, diff-cover
  with the `SF:frontend/` rewrite, `scripts/npm-audit.sh frontend high`, build).
- `goldenhour/.github/workflows/ci-failure-issue.yml` verbatim.
- `goldenhour/.github/dependabot.yml`, plus a `github-actions` ecosystem entry (CineLight has one) and the
  `vitest` group.
- Dependabot auto-merge: **if private (D1)**, copy CineLight's
  `plex-webhooks/.github/workflows/dependabot-auto-merge.yml` (label on the PR event; merge on `workflow_run` of
  `CI` only when every check on the head commit succeeded and CI ran against current `main`; squash pinned to the
  head commit; re-dispatch CI on `main`); **if public**, goldenhour's 41-line version plus branch protection.

**Adapt**
- Job names are stable strings: `Backend — Build, Test & Coverage`, `Frontend — Lint, Test & Build` (later `E2E —
  Playwright smoke`, `Shell — ShellCheck`, `Shell — script tests`). The auto-merge workflow reads them.
- Node: keep **24** (CineFilm's current CI; npm 11 ships with it, which `npm-audit.sh` otherwise installs). Java 25.
- Delete `backend.yml` and `frontend.yml` (C1).

**Leave out**: `nginx -t`, the nginx log-redaction canary, the landing checks, entry closure, the shellcheck and
shell-test jobs (no scripts yet: they arrive in phase 8), the E2E job (phase 7).

**Gates**: the workflow itself on the PR (both jobs green, diff-cover shows real percentages in the summary, a
docs-only PR shows both checks green in seconds).

**Owner provides**: D1; if public: enable "Allow auto-merge" and branch protection on `main` requiring the two
checks; if private: enable Dependabot alerts/security updates in Settings.

**Docs**: `dev-and-testing.md` "CI workflows" section (CineLight's format: one subsection per workflow).

### Phase 5 — Mutation testing (PIT + Stryker)

**Copy**
- PIT plugin block from `goldenhour/backend/pom.xml` (`pitest-maven` 1.30.1, `pitest-junit5-plugin` 1.2.3,
  `targetClasses ${pit.targetClasses}`, `parseSurefireConfig=false`, `timeoutConstant`, `threads 4`, HTML+XML,
  the `jvmArgs`), plus `junit-platform-launcher` test dependency.
- Workflow shape from **CineLight's** `plex-webhooks/.github/workflows/pitest.yml` (one job, not sharded: CineFilm
  is ~55 classes), with goldenhour's summary-extraction step, report artifact and `notify-failure` on `failure`
  **or** `cancelled`.
- `goldenhour/frontend/stryker.config.mjs` (vitest runner, `pool: 'forks'`, `breakFloor()` from `STRYKER_BREAK`,
  `ignoreStatic`, `StringLiteral` excluded, incremental file, reporters, `.stryker-tmp`) and
  `goldenhour/.github/workflows/stryker.yml` **without the shard matrix** (one job; keep the incremental-artifact
  fetch from the last completed `main` runs, the monthly forced run, the summary step and notify on
  failure/cancelled). `@stryker-mutator/core` and `vitest-runner` pinned to the same exact version, Dependabot
  `stryker` group.

**Adapt**
- PIT targets: `gregochr.cinefilm.*` minus `config.*`, `seed.*`, `*Dtos*`, `*Controller` and
  `CineFilmApplication` (controllers are covered by MockMvc and are poor mutation targets). Because CineFilm's
  rules are mostly tested through `ApiTestBase`, run the first measurement **with** the full-context tests
  included (one cached context, H2, no async startup work, so goldenhour's "unbounded runtime" reason likely does
  not apply); if the run exceeds ~20 min, exclude `*ApiTest` and rely on phase 2's service unit tests. Threshold =
  first measured score rounded down. Weekly Monday 06:30 UTC.
- Stryker `mutate`: the non-JSX logic, `src/player/*.js`, `src/api/*.js`, `src/state/*.js`,
  `src/components/placeholder.js`, `src/components/useSlashFocus.js` (exclude `src/api/fixtureServer.js` if it is
  dev-only). `inPlace` only if a test reads files outside `frontend/` (none does today: use the default sandbox).
  Floor = first full forced run rounded down, Wednesday 03:30 UTC.
- Exclude PIT's and Stryker's reports from git (`backend/target` already; add `frontend/reports/`,
  `frontend/.stryker-tmp/` to `.gitignore`).

**Leave out**: PIT's 5-shard `plan` job and Stryker's 10-shard matrix and coverage assertion (add shards only if a
single run approaches its timeout), prompt-regression exclusions, `PitExclusionDriftTest`.

**Gates**: the normal gates; one `workflow_dispatch` run of each workflow on the branch, green, with the scores in
the summary (the PR records them).

**Owner provides**: D16.

**Docs**: `dev-and-testing.md` "Mutation testing" (goldenhour's two sections, shortened: never run in place over
uncommitted work, interrupt Stryker with SIGINT only, ratchet rule).

### Phase 6 — Security scanning

**Copy**
- `goldenhour/.github/workflows/security-scan.yml` nearly verbatim (daily NVD refresh on `main` only,
  restore/save split cache, PR scans only on dependency-input changes with `-DautoUpdate=false`, prune superseded
  caches, report artifact, notify on unattended failure). `dependency-check-maven` 13.0.0 in the pom with
  `failBuildOnCVSS 7`, `backend/dependency-check-suppressions.xml`.
- CodeQL: **only if public (D1)**, copy `goldenhour/.github/workflows/codeql.yml` and
  `.github/codeql/codeql-config.yml` (drop the landing/docs paths). **If private**, replace with a Semgrep CE job
  (`semgrep scan --config p/java --config p/react --error`, pinned image) in `ci.yml` or its own workflow — FindSecBugs
  already covers much of CodeQL's Java ground.

**Adapt**: path regex in the detector to CineFilm's (`backend/pom.xml`, the suppressions file, `backend/mvnw`,
`backend/.mvn/`, the workflow). Schedule 05:45 UTC.

**Leave out**: ZAP for now (it needs a running stack; see phase 9).

**Gates**: one dispatch on `main` after merge to seed the NVD cache, then a PR touching `pom.xml` scans from cache.

**Owner provides**: an **`NVD_API_KEY`** repository secret (free from nvd.nist.gov; CineLight's was rejected by NVD,
so test it). D1 for CodeQL vs Semgrep.

**Docs**: `dev-and-testing.md` OWASP and static-analysis rows.

### Phase 7 — Packaging, E2E smoke and accessibility

**Copy**
- `goldenhour/backend/Dockerfile` structure: multi-stage, BuildKit Maven cache mount, non-root user (uid 1001),
  `HEALTHCHECK` on `/actuator/health/container`, `APP_VERSION` and `GIT_COMMIT_*` build args.
- `goldenhour/frontend/playwright.smoke.config.js` and `frontend/scripts/e2e-smoke-backend.sh` (both servers started
  by Playwright so local and CI recipes match; fixed ports; workers 2; retries 0; traces on failure; logs to
  `test-results/e2e-logs`), the `e2e` job from `ci.yml` (pinned `ubuntu-24.04`, Playwright browser cache keyed on
  the exact version, `install-deps`, artifacts on failure, the "backend log must contain the seeded marker"
  assertion).
- `goldenhour/frontend/playwright.a11y.config.js`, `scripts/a11y-summary*.mjs`, `@axe-core/playwright` 4.14.0,
  `@playwright/test` pinned exact (1.64.1).

**Adapt**
- One image: stage 1 `node:24-slim` builds the SPA; stage 2 `eclipse-temurin:25-jdk` builds the jar with the SPA
  copied into `classes/static`; runtime `eclipse-temurin:25-jre` (**Ubuntu/noble, not Alpine**: product phase 2 adds
  `ffprobe`, product phase 3's worker needs `i965-va-driver`/VAAPI, both simplest on Ubuntu; linux/amd64 only — both Macs
  are Intel). A Spring `index.html` fallback for client routes that never shadows `/api/**` or `/actuator/**`, and
  a MockMvc test for it. `server.forward-headers-strategy=native`.
- Build the image in CI on PRs (no push) so a broken Dockerfile fails before a release: a `Docker — image builds`
  step inside the backend job or its own job.
- E2E runs the **packaged jar** (SPA inside, the production shape) with the `dev` profile seed on a fresh Postgres
  (a `services: postgres:17-alpine` container in the `e2e` job, the production engine),
  `CINEFILM_PASSWORD` set to a value generated in the job, and a **tiny generated test clip** for
  `cinefilm.sample-video` (D15). Browsers: **WebKit and Chromium** (D8). Specs: sign-in (wrong password, right
  password, session survives reload), Home rows render from the seed, Library search/sort/filter, Film detail with
  editions, Person, Collection, the player opens with the right `<video>` source and the stream answers 206 to a
  range request (no decode assertion: Playwright's Linux WebKit/Chromium lack H.264), progress is reported on
  pause, sign-out.
- a11y: axe on every screen, serious/critical fail. **Recommended blocking** inside the `e2e` job from day one (D7):
  CineFilm has no backlog, unlike goldenhour when it made the scan advisory. If advisory, keep it in its own
  workflow as goldenhour does (a red advisory job inside `ci.yml` would block releases via `workflow_call`).

**Leave out**: nginx mode (`E2E_WEB=nginx`, `e2e-smoke-nginx.sh`), the Turnstile build arg, the root
`goldenhour/Dockerfile` (stale H2 leftover), the "never built a briefing" log assertion, `vite-plugin-pwa`.

**Gates**: the phase 1–6 gates, plus `cd backend && ./mvnw -Dmaven.test.skip=true package` (with the SPA) and
`cd frontend && npm run test:e2e:smoke` locally (preview mode, against the Homebrew dev Postgres, no Docker), and `E2E — Playwright smoke` green
in CI.

**Owner provides**: D7, D8, D9, D15.

**Docs**: `dev-and-testing.md` "The E2E smoke gate"; `foundation-plan.md` (packaging pulled forward from product
phase 5).

### Phase 8 — Deploy to dockermacmini (GHCR, runner, smoke, rollback, release)

**Copy**
- `goldenhour/.github/workflows/deploy.yml` (tag `v*` → `uses: ./.github/workflows/ci.yml` → build and push to
  GHCR with `:latest` and `:vX.Y.Z` and commit build args → `deploy` job, queued concurrency, smoke-configuration
  summary step, `notify-failure`).
- `goldenhour/scripts/pre-release-backup.sh`, `lib/release-manifest.sh`, `auto-rollback.sh`, `rollback.sh`,
  `record-deployed-images.sh`, `post-deploy-smoke.sh`, `prod-health.sh` (the `--deploy --classify` half), the exit
  code contract (0/1/3/4), the override-pin guard, `scripts/test/helpers.sh`, `run-all.sh` and the matching
  `*.test.sh` files, `manifest-field-parity.test.sh`.
- `goldenhour/release.sh` and `scripts/promote-changelog.sh` (CineFilm uses `changelog.d/` the same way; create
  `CHANGELOG.md` with an `[Unreleased]` heading).
- `ci.yml` jobs `Shell — ShellCheck` (pinned v0.11.0, `-o check-unassigned-uppercase`) and `Shell — script tests`.

**Adapt**
- **No SSH.** goldenhour's self-hosted runner SSHes to its own Tailscale IP with `DEPLOY_SSH_KEY`. CineFilm's
  runner is on the host too, so the job runs `scripts/deploy.sh` directly: `actions/checkout` of the tag into the
  runner workspace (no host git clone or credentials for a private repo), then the script copies the tag's
  `docker-compose.yml` and `scripts/` into `~/cinefilm`. This removes the deploy key, `appleboy/ssh-action`, the
  `envs:` forwarding and its character-mangling caveat, and `extract-deploy-script.sh` (the logic is a normal
  ShellChecked script).
- **GHCR auth**: the deploy job runs `docker/login-action` with `GITHUB_TOKEN` (`packages: read`) before `pull`, so
  no long-lived PAT on the host. Pinned digests stay local for rollback.
- `docker-compose.yml`: `name: cinefilm`; `cinefilm-db` (no `ports:`, bind mount
  `/home/gregochr/cinefilm-data/postgres`, `pg_isready` healthcheck, json-file log rotation); `cinefilm`
  (`127.0.0.1:8090:8080`, `env_file: ./.env` holding `CINEFILM_PASSWORD` and `POSTGRES_PASSWORD`, later
  `TMDB_READ_TOKEN`; `SPRING_PROFILES_ACTIVE=prod`; `TZ`; `mem_limit` and `-XX:MaxRAMPercentage` so a 2012 mini shared
  with goldenhour cannot be starved (D20); the media mount in long syntax `type: bind, source:
  /mnt/workhorse-films, target: /media, read_only: true, bind: { propagation: rslave }` so a share that mounts after
  the container starts still appears inside it; cache and logs volumes; `stop_grace_period`; healthcheck). A
  commented `cinefilm-worker` service shows the product-phase-3 shape (`devices: /dev/dri`, `group_add: [render]`,
  `LIBVA_DRIVER_NAME=i965`, `/media:ro`, transcode volume) so the deploy script's service list is a variable, not
  hard-coded.
- Guards in `deploy.sh` before `up`: the override-pin guard; the PGDATA-has-`PG_VERSION` guard; a **media guard**:
  `mountpoint -q /mnt/workhorse-films` and a sentinel file `/mnt/workhorse-films/.cinefilm-library` present —
  missing is a warning on deploy (exit 3 class, never a rollback), because the app is still useful without it, but
  it is recorded in the summary.
- `post-deploy-smoke.sh` probes via `http://127.0.0.1:8090` and the Tailscale HTTPS name: `GET /` is 200 HTML with
  hashed assets all 200; `/actuator/health/container` UP; `GET /api/session` 401 `{"authenticated":false}`; `POST
  /api/session` without `X-CineFilm` is 403; `/actuator/info` `git.commit.id` matches the tag; logged-in probes
  (sign in with `CINEFILM_PASSWORD` read from `~/cinefilm/.env` on the host — no smoke secret needed — then `GET
  /api/home` 200). Cloudflare-edge classification removed.
- `SMOKE_ENFORCE` repository variable as in goldenhour; recommend report-only for the first release, then `true`
  (D13).
- `release.sh`: if private (no auto-merge), the changelog promotion PR is merged by the script with `gh pr merge
  --squash --match-head-commit` after its checks pass, instead of `--auto`.

**Leave out**: `appleboy/ssh-action`, `DEPLOY_SSH_KEY`, the hard-coded `100.76.73.16`, nginx.conf delivery and its
rollback, `cloudflared` checks, the second (frontend) image, `migrate-pgdata-path.sh`, `diagnose-stale-forecast.sh`,
`dev-seed-locations.sh`, `release-landing.sh`, `SMOKE_DEPLOY_PASSWORD`/`SMOKE_LOGIN_REQUIRED` (replaced by reading
the host's `.env`), goldenhour's `Makefile` (stale: it waits on a `sunset` Postgres user) and the legacy macOS
sections of `DEVOPS.md`.

**Gates**: all previous; `Shell — ShellCheck` and `Shell — script tests` green; a dry run of `deploy.sh` against
stubs in the shell tests; first real deploy by the owner tagging `v0.1.0` with `release.sh`.

**Owner provides** (one-off host setup, recorded in the runbook):
1. On dockermacmini: register a **new** self-hosted runner for `gregochr/cinefilm` in `~/actions-runner-cinefilm`
   with label `cinefilm-prod`, as a systemd service (`./svc.sh install`), user in the `docker` group; make sure it
   restarts after reboot (goldenhour notes its runner does not always).
2. SMB on workhorse: macOS File Sharing for the film disk, a dedicated read-only user. On dockermacmini: `cifs-utils`,
   `/mnt/workhorse-films`, a credentials file `/root/.smb-workhorse` (mode 600), an `/etc/fstab` line
   `//workhorse.local/Films /mnt/workhorse-films cifs ro,credentials=/root/.smb-workhorse,vers=3.0,uid=1001,gid=1001,_netdev,nofail,x-systemd.automount 0 0`
   (uid/gid = the container user); create `.cinefilm-library` at the share root.
3. `~/cinefilm/.env` with `CINEFILM_PASSWORD` and `POSTGRES_PASSWORD` (mode 600), and
   `/home/gregochr/cinefilm-data/{postgres,logs,cache,transcode}`.
4. `tailscale serve --bg --https=<port> http://127.0.0.1:8090` and the chosen hostname (D10, D11); MagicDNS/HTTPS
   certificates enabled in the tailnet.
5. Repository variable `SMOKE_ENFORCE`; Actions permission for `GITHUB_TOKEN` to read packages (default).
6. RAM size of dockermacmini and free disk for cache/transcode (D20).

**Docs**: `docs/engineering/operations.md` (CineFilm's DEVOPS: ports, paths, start/stop, logs, release, rollback
Plan A/B, the host setup list above, how to pin and unpin), `foundation-plan.md` deployment section.

### Phase 9 — Backups, backup verification, production health (and optional ZAP)

**Copy**
- `goldenhour/scripts/backup-postgres.sh`, `goldenhour-backup.service`, `goldenhour-backup.timer` (renamed
  `cinefilm-*`, 03:00, `Persistent=true`, `RandomizedDelaySec`, explicit `BACKUP_DIR`, `TimeoutStartSec`).
- `goldenhour/.github/workflows/backup-verify.yml` (restore newest dump into `cinefilm-backup-verify-db`, count rows
  in `film`, `edition`, `playback_state` — the real table names, read from `V1__schema.sql`; query the real
  database, not `pg_isready`; off-box status check; **runner watchdog on `ubuntu-latest`**; two notifiers).
- `goldenhour/.github/workflows/prod-health.yml` and the `--daily` mode of `prod-health.sh`.

**Adapt**
- Off-box copy: rsync over SSH to **workhorse's internal disk** (not the film USB disk), e.g.
  `gregochr@workhorse.local:/Users/gregochr/cinefilm-backups/` with a dedicated key (D14).
- CineFilm's daily health checks: compose services healthy, `tailscale serve` status lists the CineFilm proxy, app
  answers `401` on `/api/session` via the Tailscale name, media mount + sentinel present (a **failure** daily, a
  warning at deploy), disk free under the data and transcode volumes above a threshold. **Not** reboot/apt/Tailscale
  daemon checks: goldenhour's job already fails on those for this host.
- The `external` job cannot curl CineFilm from GitHub (it is tailnet-only), so it is the runner watchdog alone.
- Optional ZAP (D19): a weekly **baseline** (passive) scan on `ubuntu-latest` against the phase 7 E2E stack (packaged
  jar, dev seed, signed-in cookie via a header rule), advisory, with goldenhour's `.github/scripts/zap_gate.py` and
  known-rules files. Not goldenhour's self-hosted, prod-snapshot design: CineFilm's seed is a realistic library and
  running a pentest stack on the shared 2012 box is not worth it.

**Leave out**: the cloudflared check, the external `curl` probe, reboot/apt checks, the prod-snapshot pentest
stack.

**Gates**: shell tests for the new scripts; one dispatch of `backup-verify.yml` and `prod-health.yml` green after
the owner installs the timer.

**Owner provides**: install the systemd units (`sudo cp ... && systemctl enable --now cinefilm-backup.timer`), an
SSH key from dockermacmini to workhorse for the off-box rsync, D14, D19.

**Docs**: `operations.md` backup/restore section.

### Later, with the product phases (not in this series)

- **Product phase 2 (TMDB)**: `@Tag("real-api")` TMDB client tests excluded by default; copy CineLight's
  `real-api-smoke.yml` (weekly, fails if 0 tests ran), secret `TMDB_READ_TOKEN`; WireMock or `MockRestServiceServer`
  for the deterministic tests. The **scanner must treat a missing mount or missing sentinel as "library
  unavailable", never as "every film deleted"** — a named test.
- **Product phase 3 (workers)**: `ghcr.io/gregochr/cinefilm-worker` built in `deploy.yml`; the dockermacmini worker
  joins the compose service list; the workhorse worker is a launchd service running the worker jar, deployed
  either by a second self-hosted runner on workhorse (macOS, label `cinefilm-workhorse`) or by an `scp` + `launchctl
  kickstart` step from the dockermacmini runner over the LAN (decide then). Transcode output volume and disk sizing.

---

## 5. Things goldenhour does that CineFilm should NOT copy

| goldenhour | Why not here |
|---|---|
| `prompt-regression.yml`, `real-api-smoke.yml` (Anthropic batch), `PitExclusionDriftTest`'s prompt half, cost ledgers | Anthropic-specific; CineFilm calls no LLM. A TMDB real-API smoke comes later on CineLight's simpler pattern. |
| PIT 5 shards and Stryker 10 shards with coverage-assertion `plan` jobs | Sized for 10,500 and 18,900 mutants. CineFilm is ~55 classes and ~45 files: one job each. |
| Two images (nginx + backend), `nginx -t`, the log-redaction canary, CSP via nginx | One Spring container serves the SPA (see §3). |
| Cloudflare tunnel, `cloudflared` checks, Turnstile, the external curl probe | CineFilm is tailnet-only. |
| `appleboy/ssh-action` to the runner's own Tailscale IP, `DEPLOY_SSH_KEY`, a host-side git clone | The runner is on the host; run the script directly from the checked-out tag. |
| ZAP against a restored production snapshot on the self-hosted box | Heavy on a shared 2012 machine; CineFilm's seed library suffices for a baseline scan on a hosted runner. |
| Host reboot/apt checks in CineFilm's prod-health | Already done daily for this very host by goldenhour; duplicating files two issues per incident. |
| `landing/`, `release-landing.sh`, "gate values are published" to `landing/engineering.html`, `check:entry` | No marketing site, no Leaflet. |
| Root `Dockerfile`, `Makefile`, legacy macOS sections of `DEVOPS.md` | Stale in goldenhour itself. |
| `fireEvent` and `vi.mock`-the-API-module house style, Tailwind, PropTypes, test-id-everywhere | CineFilm's own conventions are `userEvent`, a fetch-level mock and plain CSS; keep them. |
| H2 `create-drop` with Flyway off for the default test profile, and an H2 local-dev profile | CineFilm keeps Flyway on for its H2 suite while migrations stay portable, and develops against real Postgres. |
| Node 22 and the npm-11-install workaround's rationale | CineFilm is on Node 24 (npm 11 built in); keep the script for its retry logic. |
| goldenhour's `dependabot-auto-merge.yml` (if CineFilm stays private) | It relies on branch protection, which a private Free repo cannot have; use CineLight's. |

---

## 6. Decisions for the owner

**Decided 2026-11-08.** The owner chose: D1 **private** (Semgrep instead of CodeQL, CineLight-style auto-merge),
D2 **Homebrew `postgresql@17`** for local dev (the owner installs it), D3 goldenhour's split (Testcontainers in CI
only), D10 **Tailscale serve**. The owner asked for CI and deployment "like goldenhour", so every other row takes the
recommendation below. Still open: D11 (whether tailnet 443 is free, checked during phase 8), D20 (dockermacmini's
RAM and disk, needed for phase 8) and D22 (the owner creates an NVD API key before phase 6).

| # | Decision | Recommendation |
|---|---|---|
| D1 | Keep `gregochr/cinefilm` **private** or make it **public**? Private means no CodeQL (use Semgrep), no branch protection (merge rule is process-only; CineLight-style auto-merge), metered minutes, private GHCR images. | Public if you're comfortable publishing the handover and plans; otherwise private with the substitutes above. |
| D2 | ~~PostgreSQL 17~~ | **Settled by the owner (2026-11-08)**: phase 1. Remaining sub-decision: local dev on Homebrew `postgresql@17` (recommended) rather than keeping an H2 dev fallback. |
| D3 | Test DB strategy: H2 fast suite + Postgres Testcontainers in CI only (local gate excludes `integration`), or install Docker/Colima on the dev Mac so Testcontainers runs locally too? | goldenhour's split; no Docker on the Mac. |
| D4 | Adopt goldenhour's **Javadoc mandate** (public types/methods/constructors in `src/main`) and `Indentation` in Checkstyle? | Adopt (parity, and the API will be reused by Apple TV/native clients). |
| D5 | Coverage floors: backend 95/90 per class + bundle at measured; frontend 95/90 per file + global at measured; diff-cover 90% on both; ratchet up only. | Yes, exactly goldenhour's numbers. |
| D6 | Frontend backfill in one PR, or split 2a/2b with the per-file floor switched on in 2b? | Decide after Sonnet's first coverage measurement; default one PR. |
| D7 | a11y scan **blocking** (inside `e2e`) or **advisory** (own workflow)? | Blocking from day one. |
| D8 | Playwright browsers: WebKit + Chromium, or Chromium only like goldenhour? | WebKit + Chromium (Safari is the primary client). |
| D9 | One container with Spring serving the SPA, or goldenhour's nginx + backend pair? | One container. |
| D10 | Remote access: Tailscale `serve` (HTTPS on the tailnet) or something else? | Tailscale serve. |
| D11 | Host port and URL: `127.0.0.1:8090` behind `tailscale serve` on HTTPS port 443 (is 443 on the tailnet name free?) or another port; the hostname to use. | 8090 + 443 if free. |
| D12 | Deploy mechanism: CineFilm's own self-hosted runner on dockermacmini running the script directly (no SSH), or goldenhour's SSH-from-runner? | Own runner, no SSH. |
| D13 | `SMOKE_ENFORCE` (automatic image rollback on a broken release) on from the first release or after a report-only release? | Report-only for `v0.1.0`, then `true`. |
| D14 | Backup retention and off-box destination. | goldenhour's 7 daily + 4 weekly; rsync to workhorse's internal disk. |
| D15 | Commit a tiny self-generated test clip (ffmpeg test pattern, a few KB) for E2E, or skip player E2E? | Commit it. |
| D16 | Mutation floors: set at the first measured score rounded down? Include the MockMvc/`ApiTestBase` tests in PIT? | Yes to both, revisit if PIT exceeds ~20 min. |
| D17 | Release versioning and first tag (`v0.1.0`), and who runs `release.sh`. | You run it; sessions never tag (already the rule). |
| D18 | Allow Dependabot minor/patch auto-merge? | Yes (after phase 1, H2 bumps can no longer touch real data). |
| D19 | Run a weekly advisory ZAP baseline on a hosted runner (phase 9), or skip ZAP? | Optional; low value for a tailnet-only single-user app. Skip until product phase 3 adds more surface. |
| D20 | dockermacmini's RAM and free disk: sets `mem_limit`/`MaxRAMPercentage` for CineFilm (and later the worker) so goldenhour isn't starved, and the cache/transcode budget. | Tell me the numbers; I'll size it in phase 8. |
| D21 | The application time zone for "this week's theme", "recently added" and "not watched in years" (today `Clock.systemDefaultZone()`; the container would be UTC). | `Europe/London`, set explicitly in config and the container `TZ`. |
| D22 | NVD API key: will you create one (needed for the OWASP scan)? | Yes; free. |

## 7. Order and sizing summary

| Phase | PR | Depends on | Size |
|---|---|---|---|
| 1 | PostgreSQL (prod/dev profiles, H2 to test scope, V1 checked on Postgres) + Testcontainers `IntegrationTestBase` | — | M |
| 2 | Backend standards + gates (per-class JaCoCo, SpotBugs, actuator, clock and service-test fixes) | 1 | M |
| 3 | Frontend standards + coverage floors + backfill | — (parallel with 1–2) | M–L |
| 4 | CI parity (`ci.yml`, diff-cover, Dependabot, failure issues) | 2, 3 | S |
| 5 | PIT + Stryker weekly | 2, 3, 4 | S |
| 6 | OWASP (+ CodeQL or Semgrep) | 4 | S |
| 7 | Docker image, SPA served by Spring, Playwright smoke (Postgres service) + axe | 1, 4 | L |
| 8 | Deploy (compose with `cinefilm-db`), pre-release dump, smoke, auto-rollback, release, shell CI | 7 | L |
| 9 | Nightly `pg_dump` backups, backup-verify, prod-health (+ optional ZAP) | 8 | M |
