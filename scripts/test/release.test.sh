#!/bin/bash
# Tests for scripts/release.sh against throwaway git repos: a bare "origin", a working clone, and a stub `gh`.
# Every rejected release must leave NO tag locally and NO tag on origin.

set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
# shellcheck source=/dev/null
. "${HERE}/helpers.sh"

ROOT="$(mktemp -d)"
trap 'rm -rf "$ROOT"' EXIT
export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@example.com GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@example.com
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null

# new_world: origin.git (bare), work (clone on main with one commit pushed), bin/ with stub gh and sleep.
new_world() {
    W="$(mktemp -d "$ROOT/w.XXXXXX")"
    git init -q --bare -b main "$W/origin.git"
    git clone -q "$W/origin.git" "$W/work" 2>/dev/null
    (cd "$W/work" && git checkout -q -b main 2>/dev/null; echo a > a; git add a; git commit -q -m one; git push -q origin main)
    mkdir -p "$W/bin"
    printf '#!/bin/bash\nexit 0\n' > "$W/bin/sleep"
    # gh: `run list` prints a run id; `run watch` exits per $W/gh_watch_rc (default 0).
    cat > "$W/bin/gh" <<'EOS'
#!/bin/bash
case "$2" in
    list) echo 4242 ;;
    watch) exit "$(cat "$(dirname "$0")/../gh_watch_rc" 2>/dev/null || echo 0)" ;;
esac
EOS
    chmod +x "$W/bin/sleep" "$W/bin/gh"
}

release() { # VERSION: runs release.sh in the work clone; OUT, RC
    OUT="$(cd "$W/work" && PATH="$W/bin:$PATH" bash "$REPO/scripts/release.sh" "$@" 2>&1)"
    RC=$?
}
local_tags() { git -C "$W/work" tag --list | tr '\n' ' '; }
remote_tags() { git -C "$W/origin.git" tag --list | tr '\n' ' '; }

assert_rejected() { # DESCRIPTION: non-zero exit and no tag anywhere
    if [ "$RC" -ne 0 ]; then ok "$1: rejected"; else bad "$1: expected a non-zero exit"; fi
    assert_eq "$1: no local tag" "" "$(local_tags)"
    assert_eq "$1: no tag on origin" "" "$(remote_tags)"
}

new_world; echo dirty > "$W/work/a"
release v0.1.0
assert_rejected "dirty tree"
assert_contains "dirty tree says why" "$OUT" "not clean"

new_world; echo untracked > "$W/work/new-file"
release v0.1.0
assert_rejected "untracked file"

new_world; git -C "$W/work" checkout -q -b feature
release v0.1.0
assert_rejected "not on main"
assert_contains "not on main says why" "$OUT" "must be on main"

new_world; (cd "$W/work" && echo b > b && git add b && git commit -q -m local-only)
release v0.1.0
assert_rejected "main ahead of origin"

new_world
git clone -q "$W/origin.git" "$W/other" 2>/dev/null
(cd "$W/other" && echo c > c && git add c && git commit -q -m remote-only && git push -q origin main)
release v0.1.0
assert_rejected "main behind origin"

for v in 1.0.0 v1.0 v1.0.0-rc1 vx "v1.0.0 "; do
    new_world
    release "$v"
    assert_rejected "invalid version '$v'"
done

new_world; git -C "$W/work" tag -a v0.1.0 -m old; git -C "$W/work" push -q origin v0.1.0
before_local="$(local_tags)"; before_remote="$(remote_tags)"
release v0.1.0
if [ "$RC" -ne 0 ]; then ok "existing tag: rejected"; else bad "existing tag: expected non-zero"; fi
assert_eq "existing tag: local tags unchanged" "$before_local" "$(local_tags)"
assert_eq "existing tag: origin tags unchanged" "$before_remote" "$(remote_tags)"

new_world; git -C "$W/work" tag -a v0.2.0 -m old; git -C "$W/work" push -q origin v0.2.0
release v0.1.0
if [ "$RC" -ne 0 ]; then ok "older than the latest tag: rejected"; else bad "older version: expected non-zero"; fi
assert_eq "older version: no extra tag on origin" "v0.2.0 " "$(remote_tags)"

# --- a valid release: exactly one annotated tag, created and pushed --------------------------------------------------------
new_world
release v0.1.0
assert_eq "valid release: exit 0" "0" "$RC"
assert_eq "valid release: exactly one local tag" "v0.1.0 " "$(local_tags)"
assert_eq "valid release: exactly one tag on origin" "v0.1.0 " "$(remote_tags)"
assert_eq "the tag is annotated" "tag" "$(git -C "$W/work" cat-file -t refs/tags/v0.1.0)"
assert_eq "the tag points at main's head" "$(git -C "$W/work" rev-parse main)" "$(git -C "$W/origin.git" rev-parse 'v0.1.0^{commit}')"
assert_contains "it reports the deploy" "$OUT" "Deployed v0.1.0"

new_world; git -C "$W/work" tag -a v0.1.0 -m old; git -C "$W/work" push -q origin v0.1.0
release v0.1.1
assert_eq "a newer version after an existing tag: exit 0" "0" "$RC"
assert_eq "origin has both tags" "v0.1.0 v0.1.1 " "$(remote_tags)"

# --- regression: no tag and a long history (>20 commits) must not die of SIGPIPE under pipefail -----------------------------
new_world
(cd "$W/work" && for i in $(seq 1 25); do echo "$i" > "f$i"; git add "f$i"; git commit -q -m "commit $i"; done && git push -q origin main)
release v0.1.0
assert_eq "long history, no tag: exit 0" "0" "$RC"
assert_contains "long history, no tag: prints the commit list" "$OUT" "commit 25"
assert_contains "long history, no tag: reaches the tagging step" "$OUT" "Tagging v0.1.0"
assert_eq "long history, no tag: tag pushed" "v0.1.0 " "$(remote_tags)"

# --- failures after tagging ----------------------------------------------------------------------------------------------------
new_world
printf '#!/bin/sh\nexit 1\n' > "$W/origin.git/hooks/pre-receive"; chmod +x "$W/origin.git/hooks/pre-receive"
git -C "$W/work" config receive.denyCurrentBranch ignore
release v0.1.0
if [ "$RC" -ne 0 ]; then ok "failed push: non-zero exit"; else bad "failed push: expected non-zero"; fi
assert_eq "failed push: nothing on origin" "" "$(remote_tags)"
assert_eq "failed push: the local tag is removed again" "" "$(local_tags)"

new_world; echo 1 > "$W/gh_watch_rc"
release v0.1.0
if [ "$RC" -ne 0 ]; then ok "failed deploy watch: non-zero exit"; else bad "failed deploy watch: expected non-zero"; fi
assert_contains "failed deploy watch says the deploy failed" "$OUT" "failed"
assert_eq "the tag was pushed before the watch failed" "v0.1.0 " "$(remote_tags)"

finish_tests
