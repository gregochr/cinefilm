#!/bin/bash
# deploy/docker-compose.yml must be valid YAML. The v0.2.0 deploy failed before touching anything because a
# `${VAR:?message}` guard held a colon-space inside an unquoted plain scalar. Checked from the committed file only.
# The needles are literal compose placeholders, never meant to expand here.
# shellcheck disable=SC2016

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
# shellcheck source=scripts/test/helpers.sh
. "${HERE}/helpers.sh"

COMPOSE="$REPO/deploy/docker-compose.yml"

# 1. Every `${VAR:?message}` guard used as an environment value is double-quoted whole, so its message can never
#    break the scalar; and no message holds a colon-space.
unquoted="$(grep -nE '^\s+[A-Za-z_0-9]+: \$\{[A-Za-z_0-9]+:\?' "$COMPOSE" || true)"
assert_eq "no ':?' guard is an unquoted environment value" "" "$unquoted"
colon_space="$(grep -nE '\$\{[A-Za-z_0-9]+:\?[^}]*: [^}]*\}' "$COMPOSE" || true)"
assert_eq "no ':?' guard message contains a colon-space" "" "$colon_space"

# 2. The file parses as YAML (PyYAML; skipped, loudly, where it is unavailable).
if python3 -c 'import yaml' 2> /dev/null; then
    if python3 -c 'import sys, yaml; yaml.safe_load(open(sys.argv[1]))' "$COMPOSE" 2> /dev/null; then
        ok "deploy/docker-compose.yml parses as YAML"
    else
        bad "deploy/docker-compose.yml does not parse as YAML"
    fi
else
    echo "  skip YAML parse (python3 yaml module not installed)"
fi

finish_tests
