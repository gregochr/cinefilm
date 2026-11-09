### Fixed — release.sh no longer exits silently on a long history

With no previous tag and more than 20 commits, `git log --oneline | head -20` died of SIGPIPE under `pipefail` and `release.sh` exited without a message or a tag; it now uses `git log -n 20`. `dev-db.sh` and `pre-release-backup.sh` no longer pipe into `grep -q` (same hazard). A regression test covers a tagless 25-commit history.
