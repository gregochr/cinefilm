# CLAUDE.md — CineFilm

CineFilm is a private, single-user, Plex-style movie server ("my own cinema"). Backend: Java 25, Spring Boot 4.1.2,
Maven (`backend/mvnw`). Frontend: React 19 + Vite (`frontend/`). Companion to CineLight
(`~/IdeaProjects/plex-webhooks`), which dims the Hue lights when a film starts.

## ⚠️ Keep this file small

Loaded into every session and subagent. Add a line only for something a session must know before it opens any file.
Everything else goes in `docs/engineering/`:

| Topic | File |
|---|---|
| Architecture, domain model, API contract, phases and roadmap | `docs/engineering/foundation-plan.md` |
| The UX spec (visual language, screens, behaviour, decisions) | `docs/design/handover/README.md` |
| Screen targets and exact values | `docs/design/handover/screens/*.png`, `docs/design/handover/reference-markup/*.dc.html` |
| Test standards (backend and frontend), coverage floors | `docs/engineering/test-standards.md` |
| Dev setup, build ladder, gates, review cadence | `docs/engineering/dev-and-testing.md` |
| Production host setup, release, rollback (dockermacmini, Tailscale, SMB media) | `docs/engineering/operations.md` |
| Code review rules | `AGENTS.md` |

---

## ⚠️ How every code change is made (owner rule)

**Opus and Fable plan and review; Sonnet writes all the code.** A planning session never edits source, tests, build
files or workflows itself. Docs (this file, `docs/`, `AGENTS.md`) may be written by the planner.

1. **Plan, then review the plan adversarially**, sized to the risk. A multi-phase change gets a plan doc in
   `docs/engineering/`.
2. **Delegate the build to a Sonnet subagent** (Agent tool, `model: "sonnet"`, its own worktree). The brief carries
   the plan, the files, the tests to write, the gates below and the `changelog.d/` entry. Sonnet runs the gates
   until green; the planner reviews the diff read-only and sends fixes back to Sonnet.
3. **Commit** on a feature branch (conventional commit, changelog entry).
4. **Push and open a ready PR** (not a draft) against `main`.
5. **Wait for CI and Codex, fix what they find** through Sonnet, push again. Codex reviews a PR when it opens and
   on an `@codex review` comment, not on later pushes: after a fix push, comment `@codex review`. 👀 means running
   (never merge on it), 👍 is a clean review, comments are findings. Until Codex is enabled on this repo, CI alone
   gates.
6. **Merge (squash) when CI is green and Codex has given 👍 on the exact head commit**, re-reading both just
   before: `gh pr merge --squash --match-head-commit <sha>`. **The owner does not review PRs** (owner, 2026-11-08):
   the planner merges each phase itself once these gates pass, without asking.
7. Multi-phase plans repeat 2–6 per phase, each branched from the updated `main`.

Never done by a session: creating or deleting tags, pushing to `main`, force-pushing a shared branch.

---

## Gates (gate on exit codes, never on a grep of output)

```bash
cd backend && ./mvnw clean verify --batch-mode --no-transfer-progress -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false   # local backend gate (no Docker here)
cd frontend && npm run lint && npm run test:coverage && ../scripts/npm-audit.sh . high && npm run build   # CI frontend job (95/90 per file)
```

CI (`.github/workflows/ci.yml`) also requires, on PRs, 90% of changed lines covered on both sides (diff-cover; see
`dev-and-testing.md` "CI workflows"). Required checks: `Backend — Build, Test & Coverage`, `Frontend — Lint, Test & Build`, `Shell — ShellCheck & script tests`.

The Mac has no Docker, so the local backend gate excludes the `integration` package (Testcontainers on Postgres 17).
CI's backend job runs the full `./mvnw clean verify` including those tests, and is the only merge proof for
migrations; locally, also boot the `dev` profile against Homebrew Postgres (`docs/engineering/dev-and-testing.md`).

## Cross-cutting invariants

- **The handover is the spec.** Match `docs/design/handover/` for layout, copy and behaviour; its numbered
  decisions are settled. Don't port the `.dc.html` template format: build idiomatic React.
- **Amber is rare** (primary action, progress fills, current-nav underline, eyebrow). Never remove a focus outline
  without the `--cf-focus-ring` replacement.
- **Data and UI stay separable:** `frontend/src/api` and `frontend/src/state` contain no JSX; Apple TV and a native
  app will reuse the API and that logic.
- **Single user, one password** (`CINEFILM_PASSWORD`, dev fallback `changeme`). Secrets never reach the browser or
  the repo.
- **Fast and quiet:** no autoplay, no moving carousels, static skeletons, lazy images with reserved aspect ratios.
