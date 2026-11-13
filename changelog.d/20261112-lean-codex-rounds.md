### Changed — Lean local Codex rounds

Local Codex now runs once before each push: on the whole branch before the first push, and on only the fix before a
fix push. It used to run twice on the whole branch every time. PRs are kept smaller. The Codex review on GitHub, on
the exact head commit, is still what allows a merge. This saves Codex quota, which ran out twice on 2026-11-12.
