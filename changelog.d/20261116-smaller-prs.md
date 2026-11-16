### Changed — Smaller pull requests, shorter review rounds

A pull request is now 1,500 changed lines or fewer, tests included; the target was half of phase 4a's size, about
4,000 lines. A review round with nothing but nits is fixed and pushed without another review, except on a docs-only
PR, and a later phase may be built while the one before waits on CI or Codex when the two touch different files. No
check is dropped: the gates, the fresh reviewer and, on a code PR, Codex's clean review of the exact head commit
still gate the merge.
