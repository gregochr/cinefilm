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
5. **Wait for CI (and Codex, if enabled on this repo), fix what they find** through Sonnet, push again.
6. **Merge (squash) only when CI is green** on the exact head commit:
   `gh pr merge --squash --match-head-commit <sha>`.
7. Multi-phase plans repeat 2–6 per phase, each branched from the updated `main`.

Never done by a session: creating or deleting tags, pushing to `main`, force-pushing a shared branch.

---

## Gates (gate on exit codes, never on a grep of output)

```bash
cd backend && ./mvnw clean verify --batch-mode --no-transfer-progress   # CI backend job
cd frontend && npm run lint && npm test && npm run build                # CI frontend job
```

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
