#!/bin/bash
# Tests scripts/dependabot-commits-trusted.sh with fixtures shaped like the pulls/{n}/commits API response
# (trimmed to sha, author.login and commit.verification).

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=/dev/null
. "$HERE/helpers.sh"
SCRIPT="$HERE/../dependabot-commits-trusted.sh"

# commit SHA AUTHOR_JSON VERIFIED_JSON REASON -> one commit object
commit() {
    printf '{"sha":"%s","author":%s,"commit":{"verification":{"verified":%s,"reason":"%s"}}}' "$1" "$2" "$3" "$4"
}
BOT='{"login":"dependabot[bot]"}'
HUMAN='{"login":"mallory"}'
GOOD1=$(commit 1111111111aaaa "$BOT" true valid)
GOOD2=$(commit 2222222222bbbb "$BOT" true valid)
GOOD3=$(commit 3333333333cccc "$BOT" true valid)
FOREIGN=$(commit 9999999999ffff "$HUMAN" true valid)

# run INPUT: sets CODE and OUT (stdout and stderr)
run() {
    OUT=$(printf '%s' "$1" | bash "$SCRIPT" 2>&1)
    CODE=$?
}

# passes DESCRIPTION INPUT
passes() {
    run "$2"
    assert_eq "$1: exits 0" "0" "$CODE"
    assert_eq "$1: prints nothing" "" "$OUT"
}

# fails DESCRIPTION INPUT EXPECTED_TEXT
fails() {
    run "$2"
    if [ "$CODE" -ne 0 ]; then ok "$1: exits non-zero"; else bad "$1: exited 0"; fi
    assert_contains "$1: message" "$OUT" "$3"
}

echo "trusted"
passes "one verified Dependabot commit" "[$GOOD1]"
passes "several verified Dependabot commits" "[$GOOD1,$GOOD2,$GOOD3]"

echo "untrusted"
fails "unsigned Dependabot commit" "[$(commit 4444444444dddd "$BOT" false unsigned)]" \
    "commit 4444444444 (author dependabot[bot], signature verified: false, reason: unsigned)"
fails "verified commit by another author, first" "[$FOREIGN,$GOOD1,$GOOD2]" \
    "commit 9999999999 (author mallory, signature verified: true, reason: valid)"
fails "verified commit by another author, middle" "[$GOOD1,$FOREIGN,$GOOD2]" "commit 9999999999 (author mallory"
fails "verified commit by another author, last" "[$GOOD1,$GOOD2,$FOREIGN]" "commit 9999999999 (author mallory"
fails "author is null" "[$(commit 5555555555eeee null true valid)]" \
    "commit 5555555555 (author none, signature verified: true, reason: valid)"
fails "verified false with a reason" "[$(commit 6666666666aaaa "$BOT" false gpgverify_unavailable)]" \
    "reason: gpgverify_unavailable"
fails "the first offender is the one named" "[$GOOD1,$FOREIGN,$(commit 7777777777abab "$BOT" false unsigned)]" \
    "commit 9999999999"
fails "verified is the string true, not a boolean" "[$(commit 8888888888abab "$BOT" '"true"' valid)]" \
    "commit 8888888888"

echo "fail closed"
fails "empty array" "[]" "no commits"
fails "empty stdin" "" "no commits"
fails "malformed JSON" '[{"sha":' "unreadable commit list"
fails "an error object instead of commits" '{"message":"Not Found"}' "commit"
fails "an array of strings" '["abc"]' "commit"

echo "pagination shapes"
fails "pages as concatenated arrays, bad commit on page two" "[$GOOD1,$GOOD2]
[$GOOD3,$FOREIGN]" "commit 9999999999 (author mallory"
passes "pages as concatenated arrays, all trusted" "[$GOOD1,$GOOD2]
[$GOOD3]"
fails "pages as an array of arrays (--slurp), bad commit on page two" "[[$GOOD1],[$FOREIGN]]" "commit 9999999999"
passes "pages as an array of arrays (--slurp), all trusted" "[[$GOOD1],[$GOOD2]]"

finish_tests
