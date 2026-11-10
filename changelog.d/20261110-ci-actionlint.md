### Changed — CI runs actionlint on every PR

The Shell job now runs a pinned actionlint (1.7.12) over the GitHub Actions workflows, which also shellchecks their `run:` blocks. `.github/actionlint.yaml` declares the production self-hosted runner label. It exists because actionlint found a bare `! grep -q` under `set -e` that could never fail, which review had missed.
