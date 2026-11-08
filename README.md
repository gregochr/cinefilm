# CineFilm

A private, single-user movie server for a self-ripped film library — "my own cinema". Browse, resume and watch
films in Safari on a Mac or iPhone, at home or away, with the lights dimmed by
[CineLight](https://github.com/gregochr/plex-webhooks) when a film starts.

- **Backend:** Java 25, Spring Boot 4 (`backend/`)
- **Frontend:** React 19 + Vite (`frontend/`)
- **Design:** the UX handover in `docs/design/handover/`
- **Plan and roadmap:** `docs/engineering/foundation-plan.md`

## Running locally

The dev profile needs PostgreSQL 17 on localhost (one-off setup, see `docs/engineering/dev-and-testing.md`):

```bash
brew install postgresql@17 && brew services start postgresql@17
scripts/dev-db.sh            # creates the `cinefilm` role and database (idempotent)
```

```bash
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # API on :8080, sample library
cd frontend && npm install && npm run dev                              # UI on :5173
```

Password: `CINEFILM_PASSWORD` (dev fallback `changeme`). The database defaults to
`jdbc:postgresql://localhost:5432/cinefilm` as `cinefilm`/`cinefilm`; override with `CINEFILM_DB_URL`,
`CINEFILM_DB_USER` and `CINEFILM_DB_PASSWORD`. The prod profile has no defaults and fails to start without them.
