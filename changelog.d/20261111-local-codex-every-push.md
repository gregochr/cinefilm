### Process — local Codex review before every push

The local Codex review (`CLAUDE.md` step 2, `dev-and-testing.md`) now runs before **every** push, including each
push of fixes for PR findings, not only before the first commit. It reviews the whole branch
(`codex review --base origin/main`) and stops only after two runs in a row find nothing new, since Codex does not
find the same things on every run. Fix rounds that skipped it cost PR #105 four Codex rounds.
