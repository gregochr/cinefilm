### Fixed — CI no longer fails at random on `grep -q` after a pipe

The Docker image checks piped `caddy list-modules` and `unzip -p` straight into `grep -q`. grep exits on its first match, the producer dies of SIGPIPE ("broken pipe"), and `pipefail` failed the step depending on timing. Both checks now capture the output first and grep that, with the same match rules.

The proxy step's "entrypoint never prints the token" check was a bare `! grep -q`, which `set -e` ignores, so it could never fail; it is now an explicit `if grep -q …; then exit 1; fi`.
