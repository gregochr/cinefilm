# CineFilm

A private, single-user movie server for a self-ripped film library — "my own cinema". Browse, resume and watch
films in Safari on a Mac or iPhone, at home or away, with the lights dimmed by
[CineLight](https://github.com/gregochr/plex-webhooks) when a film starts.

- **Backend:** Java 25, Spring Boot 4 (`backend/`)
- **Frontend:** React 19 + Vite (`frontend/`)
- **Design:** the UX handover in `docs/design/handover/`
- **Plan and roadmap:** `docs/engineering/foundation-plan.md`

## Running locally

```bash
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # API on :8080, sample library
cd frontend && npm install && npm run dev                              # UI on :5173
```

Password: `CINEFILM_PASSWORD` (dev fallback `changeme`).
