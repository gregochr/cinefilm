### Changed — review process

Codex now reviews only the PR, to save its quota (owner, 2026-11-13). Before every push, the gates pass, the
planner's new test-class check prints nothing, and a fresh read-only Claude reviewer finds nothing.
All of one Codex review's findings go into one fix push. `@codex review` is requested only after CI is green. A
docs-only PR's fix pushes don't ask Codex again, and a docs-only move of `main` doesn't need a branch update.
The brief self-check now says every service or component needs its own unit test class, and every controller needs a
MockMvc test. PR #129 shipped a service with only a full-application test, which the review missed
(`docs/engineering/dev-and-testing.md`).
