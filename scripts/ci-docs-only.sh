#!/bin/bash
# ============================================================================
# Classifies a pull request diff for ci.yml's "Detect docs-only change" job.
#
# Usage: ci-docs-only.sh BASE_SHA HEAD_SHA   -> prints docs_only=true|false
#
# docs_only=true only for a non-empty diff in which EVERY path is documentation
# (anything under docs/, or *.md anywhere). Anything else, or any error, is
# false, so the full suite runs.
#
# --no-renames is load-bearing: by default `git diff --name-only` reports a
# rename as its destination only, so moving backend/Foo.java to docs/foo.md
# would list just docs/foo.md and look docs-only. With --no-renames the diff is
# a delete plus an add, so the code path on the source side is listed too.
# ============================================================================
set -uo pipefail

BASE="${1:-}"
HEAD="${2:-}"
if [ -z "$BASE" ] || [ -z "$HEAD" ]; then
    echo "usage: ci-docs-only.sh BASE_SHA HEAD_SHA" >&2
    echo "docs_only=false"
    exit 0
fi

if ! CHANGED=$(git diff --name-only --no-renames "$BASE" "$HEAD"); then
    echo "git diff failed - running the full suite rather than guessing." >&2
    echo "docs_only=false"
    exit 0
fi

if [ -z "$CHANGED" ]; then
    echo "Empty diff - running the full suite rather than guessing." >&2
    echo "docs_only=false"
    exit 0
fi

echo "Changed files:" >&2
printf '%s\n' "$CHANGED" | sed 's/^/  /' >&2

NON_DOC=$(echo "$CHANGED" | grep -vE '(^docs/|\.md$)' || true)
if [ -z "$NON_DOC" ]; then
    echo "Documentation only - the suite proves nothing here, skipping it." >&2
    echo "docs_only=true"
else
    echo "Non-documentation paths present:" >&2
    printf '%s\n' "$NON_DOC" | sed 's/^/  /' >&2
    echo "docs_only=false"
fi
