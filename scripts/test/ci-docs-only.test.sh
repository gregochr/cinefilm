#!/bin/bash
# Tests scripts/ci-docs-only.sh against throwaway git repositories.

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=/dev/null
. "$HERE/helpers.sh"
SCRIPT="$HERE/../ci-docs-only.sh"

REPO=$(mktemp -d)
trap 'rm -rf "$REPO"' EXIT
git -C "$REPO" init -q
git -C "$REPO" config user.email t@example.com
git -C "$REPO" config user.name t
git -C "$REPO" config commit.gpgsign false
mkdir -p "$REPO/backend" "$REPO/docs"
printf 'class Foo {\n  int a;\n  int b;\n  int c;\n}\n' > "$REPO/backend/Foo.java"
echo "# notes" > "$REPO/docs/a.md"
git -C "$REPO" add -A
git -C "$REPO" commit -q -m base
BASE=$(git -C "$REPO" rev-parse HEAD)

# classify: commits whatever the caller changed, runs the script on BASE..HEAD, then resets to BASE.
classify() {
    git -C "$REPO" add -A
    git -C "$REPO" commit -q --allow-empty -m change
    OUT=$(cd "$REPO" && bash "$SCRIPT" "$BASE" "$(git rev-parse HEAD)" 2>/dev/null)
    git -C "$REPO" reset -q --hard "$BASE"
    git -C "$REPO" clean -qfd
}

echo "docs only"
echo "more" >> "$REPO/docs/a.md"
mkdir -p "$REPO/changelog.d"
echo "### x" > "$REPO/changelog.d/x.md"
classify
assert_eq "docs and markdown changes are docs-only" "docs_only=true" "$OUT"

echo "code present"
echo "// x" >> "$REPO/backend/Foo.java"
echo "more" >> "$REPO/docs/a.md"
classify
assert_eq "a code change alongside docs runs everything" "docs_only=false" "$OUT"

echo "workflow files are code"
mkdir -p "$REPO/.github/workflows"
echo "name: x" > "$REPO/.github/workflows/x.yml"
classify
assert_eq "workflow changes run everything" "docs_only=false" "$OUT"

echo "rename of code into docs"
git -C "$REPO" mv backend/Foo.java docs/foo.md
classify
assert_eq "moving a Java file to docs/*.md is code" "docs_only=false" "$OUT"

echo "deleting code"
git -C "$REPO" rm -q backend/Foo.java
classify
assert_eq "deleting a Java file is code" "docs_only=false" "$OUT"

echo "empty diff"
classify
assert_eq "an empty diff runs everything" "docs_only=false" "$OUT"

echo "bad input"
assert_eq "missing arguments run everything" "docs_only=false" "$(bash "$SCRIPT" 2>/dev/null)"
assert_eq "unknown commits run everything" "docs_only=false" "$(cd "$REPO" && bash "$SCRIPT" deadbeef cafebabe 2>/dev/null)"

finish_tests
