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

The prod profile has no fallbacks, so a missing value fails start-up. The dev profile applies every Flyway migration
to the real database and seeds the sample library when the database has no films. To start over:
`dropdb cinefilm && scripts/dev-db.sh`.

## Test suites

- **Fast suite (default).** Unit and MockMvc tests on in-memory H2 with `MODE=PostgreSQL` and Flyway on. H2 is
  `test` scope only: it is not on the dev or prod classpath. Rule: migrations are written for PostgreSQL and never
  contorted for H2. The day one cannot run on H2, switch the test profile to `ddl-auto=create-drop` with Flyway off;
  Testcontainers then remains the only migration proof (it already is the authoritative one).
- **Integration suite** (`gregochr.cinefilm.integration`, classes extending `IntegrationTestBase`). A Testcontainers
  `postgres:17-alpine`, Flyway migrations on an empty database, Hibernate `ddl-auto=validate`. Covers the migrations,
  the library queries on the dev seed and playback state persistence.

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
