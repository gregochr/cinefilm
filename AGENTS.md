# AGENTS.md — review rules for CineFilm

Reviewers (human, Codex, Claude) check every PR against these.

1. **Spec fidelity.** UI changes match `docs/design/handover/` (screens, copy, the numbered decisions). Flag any
   copy that differs from the handover without a note in the PR.
2. **Accessibility (§7 of the handover).** Real elements with labels; icon-only buttons have `aria-label`;
   segmented controls use `aria-pressed`; player menus use `menuitemradio` + `aria-checked`; the scrubber is a
   `role="slider"` with `aria-valuetext` in words; status messages are `role="status"`; touch targets ≥ 44px; no
   focus outline removed without the focus ring.
3. **Layering.** No JSX or DOM access in `frontend/src/api` or `frontend/src/state`. No business rules (watched
   threshold, resume rewind, theme choice) duplicated between backend and frontend unless the plan says so.
4. **API contract.** Changes to request/response shapes update `docs/engineering/foundation-plan.md` in the same PR.
5. **Tests.** New behaviour has tests and meets `docs/engineering/test-standards.md`; coverage floors only go up. Backend rules and every service have unit tests; endpoints have MockMvc tests including the
   401 and missing-CSRF-header cases.
6. **Security.** No secrets in code, logs or responses. File streaming only serves paths known to the database,
   never a path from the request.
7. **Changelog.** Every PR adds one `changelog.d/YYYYMMDD-<slug>.md` file per change.

Deliberate decisions (don't flag): plain CSS on tokens instead of Tailwind; plain JavaScript in the frontend; H2 for the fast test suite only (migrations target PostgreSQL);
Admin has only the Library scan and Films needing attention panels until the later phases.
