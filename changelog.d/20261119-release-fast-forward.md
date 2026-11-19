### Changed — release.sh fast-forwards a local main that is only behind origin

`scripts/release.sh` used to stop when local `main` differed from `origin/main`. A local main that is only behind is now fast-forwarded (`git merge --ff-only`) and released at the new head, saving the manual `git pull`. A main that is ahead of or has diverged from origin is still refused, and the dirty-tree and on-main checks still run first. Tests cover behind, ahead, diverged and behind-with-a-dirty-tree.
