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
