#!/bin/bash
# Tests for scripts/lib/release-lib.sh: version parsing and the rollback decision. Pure, no docker.

set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=/dev/null
. "${HERE}/helpers.sh"
# shellcheck source=/dev/null
. "${HERE}/../lib/release-lib.sh"

for v in v0.1.0 v1.2.3 v10.20.30; do
    if is_valid_version "$v"; then ok "valid: $v"; else bad "valid: $v"; fi
done
for v in "" 0.1.0 v1 v1.2 v1.2.3.4 v01.2.3 v1.2.3-rc1 "v1.2.3 " vx.y.z; do
    if is_valid_version "$v"; then bad "invalid accepted: '$v'"; else ok "invalid rejected: '$v'"; fi
done

assert_eq "patch bump" "v1.2.4" "$(next_version v1.2.3 patch)"
assert_eq "minor bump resets patch" "v1.3.0" "$(next_version v1.2.3 minor)"
assert_eq "major bump resets the rest" "v2.0.0" "$(next_version v1.2.3 major)"
assert_eq "no previous tag starts at v0.0.1" "v0.0.1" "$(next_version "" patch)"
assert_eq "no previous tag, minor" "v0.1.0" "$(next_version "" minor)"
assert_eq "two-digit parts" "v1.10.0" "$(next_version v1.9.9 minor)"
if next_version v1.2.3 bogus >/dev/null; then bad "unknown kind accepted"; else ok "unknown kind rejected"; fi
if next_version garbage patch >/dev/null; then bad "garbage version accepted"; else ok "garbage version rejected"; fi

if version_gt v0.1.1 v0.1.0; then ok "patch is newer"; else bad "patch is newer"; fi
if version_gt v0.10.0 v0.9.0; then ok "0.10 is newer than 0.9 (numeric, not lexical)"; else bad "0.10 vs 0.9"; fi
if version_gt v0.1.0 v0.1.0; then bad "equal is not newer"; else ok "equal is not newer"; fi
if version_gt v0.1.0 v0.2.0; then bad "older is not newer"; else ok "older is not newer"; fi

assert_eq "pass: no action" "none" "$(rollback_decision 0 true)"
assert_eq "pass with enforce off: no action" "none" "$(rollback_decision 0 "")"
assert_eq "broken + enforce true: roll back" "rollback" "$(rollback_decision 1 true)"
assert_eq "broken + enforce unset: report only" "report-only" "$(rollback_decision 1 "")"
assert_eq "broken + enforce false: report only" "report-only" "$(rollback_decision 1 false)"
assert_eq "broken + enforce 'TRUE' is not true" "report-only" "$(rollback_decision 1 TRUE)"
assert_eq "inconclusive never rolls back" "report-only" "$(rollback_decision 3 true)"
assert_eq "misconfigured never rolls back" "report-only" "$(rollback_decision 4 true)"
assert_eq "unknown exit never rolls back" "report-only" "$(rollback_decision 137 true)"

finish_tests
