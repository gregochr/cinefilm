### Docs — Codex reviews each change locally before its PR opens

The planner now runs `codex review` in Sonnet's worktree once the gates are green, and fixes what it finds before
committing, so fewer PR findings each cost another CI run on dockermacmini. Sonnet's brief also carries a short
self-check drawn from Codex's past findings: the test rules, the `AGENTS.md` items, and races between requests.
