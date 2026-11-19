#!/bin/bash
# Tag a commit on main as a release and push the tag, which triggers the Deploy workflow (.github/workflows/deploy.yml).
# The OWNER runs this; sessions never tag.
#
# Usage: scripts/release.sh [vX.Y.Z]
#   No version: prints the commits since the last tag and offers 1) patch 2) minor 3) major (Enter takes patch).
#
# Checks: on main, clean tree, then origin/main is fetched: a local main only BEHIND origin/main is fast-forwarded
# (--ff-only) and released at the new head; one AHEAD of or DIVERGED from origin/main is refused. Version valid, newer
# than the latest tag, tag not already present. Then tags, pushes the tag, and watches the Deploy run (needs the gh CLI).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
. "${SCRIPT_DIR}/lib/release-lib.sh"

die() { echo "release: $*" >&2; exit 1; }

VERSION="${1:-}"

[[ "$(git rev-parse --abbrev-ref HEAD)" = "main" ]] || die "must be on main"
[[ -z "$(git status --porcelain)" ]] || die "working tree is not clean"
git fetch origin main --tags --quiet
LOCAL="$(git rev-parse HEAD)"
REMOTE="$(git rev-parse origin/main)"
if [[ "$LOCAL" != "$REMOTE" ]]; then
    if git merge-base --is-ancestor HEAD origin/main; then
        BEHIND="$(git rev-list --count HEAD..origin/main)"
        echo "Local main is ${BEHIND} commits behind origin/main; fast-forwarding."
        git merge --ff-only --quiet origin/main || die "could not fast-forward local main to origin/main"
    else
        die "local main differs from origin/main: it has commits not on origin/main (push them through a PR first)"
    fi
fi

LAST="$(git tag --list 'v*' | sort -V | tail -n 1)"
echo "Last release: ${LAST:-none}"
echo "Commits since:"
if [[ -n "$LAST" ]]; then git log --oneline "${LAST}..HEAD"; else git log --oneline -n 20; fi
echo ""

if [[ -z "$VERSION" ]]; then
    echo "1) $(next_version "$LAST" patch)  2) $(next_version "$LAST" minor)  3) $(next_version "$LAST" major)"
    read -r -p "Release which? [1] " choice
    case "${choice:-1}" in
        1) VERSION="$(next_version "$LAST" patch)" ;;
        2) VERSION="$(next_version "$LAST" minor)" ;;
        3) VERSION="$(next_version "$LAST" major)" ;;
        *) die "choose 1, 2 or 3" ;;
    esac
fi

is_valid_version "$VERSION" || die "'${VERSION}' is not a version like v0.1.0"
if [[ -n "$LAST" ]]; then
    version_gt "$VERSION" "$LAST" || die "${VERSION} is not newer than ${LAST}"
fi
git rev-parse -q --verify "refs/tags/${VERSION}" >/dev/null && die "tag ${VERSION} already exists"

echo "Tagging ${VERSION} at $(git rev-parse --short HEAD) and pushing."
git tag -a "$VERSION" -m "Release ${VERSION}"
if ! git push origin "$VERSION"; then
    git tag -d "$VERSION" >/dev/null
    die "pushing ${VERSION} failed; the local tag was removed, nothing was released"
fi

if command -v gh >/dev/null 2>&1; then
    echo "Waiting for the Deploy run..."
    sleep 10
    RUN_ID="$(gh run list --workflow deploy.yml --branch "$VERSION" --limit 1 --json databaseId --jq '.[0].databaseId' || true)"
    if [[ -n "$RUN_ID" ]]; then
        if gh run watch "$RUN_ID" --exit-status; then
            echo "Deployed ${VERSION}."
        else
            die "the deploy for ${VERSION} failed (gh run view ${RUN_ID} --log-failed)"
        fi
    else
        echo "Could not find the Deploy run; check the Actions tab."
    fi
fi
