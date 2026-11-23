#!/bin/bash
# Tests scripts/train-feed-logger.py against scripts/test/train-feed-stand-in.py: a feed and a small site on
# loopback ports. Nothing here touches www.ombord.info or the real default site; every run names its own --base.

set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=/dev/null
. "${HERE}/helpers.sh"
LOGGER="${HERE}/../train-feed-logger.py"
STAND_IN="${HERE}/train-feed-stand-in.py"

export PYTHONDONTWRITEBYTECODE=1
TMP="$(mktemp -d)"
PIDS=()
cleanup() {
    if [ "${#PIDS[@]}" -gt 0 ]; then
        kill "${PIDS[@]}" 2>/dev/null
        wait "${PIDS[@]}" 2>/dev/null
    fi
    rm -rf "$TMP"
}
trap cleanup EXIT
trap 'exit 1' INT TERM HUP

# serve NAME [stand-in options]: starts a stand-in on a free loopback port and sets PORT.
serve() {
    local name="$1"
    shift
    local file="$TMP/$name.port" tries=0
    python3 "$STAND_IN" "$file" "$@" &
    PIDS+=("$!")
    while [ ! -s "$file" ] && [ "$tries" -lt 100 ]; do
        sleep 0.1
        tries=$((tries + 1))
    done
    PORT=$(<"$file")
}

# fresh_home: a new empty HOME for one run (the log's place is fixed under HOME, so this is how a run gets its own).
# Sets RUN_HOME and LOGDIR, where the logger will put its log.
fresh_home() {
    RUN_HOME=$(mktemp -d "$TMP/home.XXXXXX")
    LOGDIR="$RUN_HOME/train-feed-logger/logs"
}

# run_logger ARGS: runs the logger with no terminal. Sets OUT, RC, LOG (its log file), LOGDIR and RUN_HOME.
run_logger() {
    fresh_home
    OUT=$(HOME="$RUN_HOME" "$LOGGER" --no-wifi --no-laptop --no-awake "$@" </dev/null 2>&1)
    RC=$?
    LOG=""
    for LOG in "$LOGDIR"/*.jsonl; do break; done
}

# start_bg NAME ARGS: like run_logger, but in the background so a long run overlaps the other cases.
# Read it with collect NAME.
start_bg() {
    local name="$1"
    shift
    mkdir -p "$TMP/$name.home"
    (
        HOME="$TMP/$name.home" "$LOGGER" --no-wifi --no-laptop --no-awake "$@" </dev/null >"$TMP/$name.out" 2>&1
        echo $? >"$TMP/$name.rc"
    ) &
    PIDS+=("$!")
}

# collect NAME: waits for a start_bg run (at most a minute) and sets OUT, RC, LOG and LOGDIR as run_logger does.
collect() {
    local name="$1" tries=0
    while [ ! -f "$TMP/$name.rc" ] && [ "$tries" -lt 600 ]; do
        sleep 0.1
        tries=$((tries + 1))
    done
    OUT=$(<"$TMP/$name.out")
    RC=$(cat "$TMP/$name.rc" 2>/dev/null || echo "never finished")
    LOGDIR="$TMP/$name.home/train-feed-logger/logs"
    for LOG in "$LOGDIR"/*.jsonl; do break; done
}

# jv FILE EXPRESSION: the JSON of a Python expression over L, the log's lines as a list of dicts.
jv() {
    python3 -B -c '
import json, sys
L = [json.loads(x) for x in open(sys.argv[1]) if x.strip()]
try:
    print(json.dumps(eval(sys.argv[2])))
except Exception as error:
    print("ERROR %r" % (error,))
' "$1" "$2"
}

# count_lines TEXT NEEDLE: how many lines of TEXT contain NEEDLE
count_lines() {
    printf '%s\n' "$1" | grep -cF -- "$2"
}

# count_files DIRECTORY: how many entries it holds
count_files() {
    local n=0 f
    for f in "$1"/*; do
        if [ -e "$f" ]; then n=$((n + 1)); fi
    done
    echo "$n"
}

# mode_of PATH: its permission bits in octal, e.g. 700
mode_of() {
    python3 -B -c 'import os, sys; print(format(os.stat(sys.argv[1]).st_mode & 0o777, "o"))' "$1"
}

ALL_PARTS='["position", "connectivity", "users", "user", "system"]'
SCRIPT_BYTES=262144
INDEX_TEXT="parts: position connectivity users user system"

echo "starting the stand-ins and the long runs"
serve ok
P=$PORT
if [ -n "$P" ]; then ok "the stand-in started"; else bad "the stand-in did not start"; fi
BASE="http://127.0.0.1:$P/api/jsonp/"
FREE=$(python3 -B -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')

# Seven rounds, a full check every third round, two addresses; the first has a part that answers 404 and
# the internet check goes to a port with nothing listening.
serve lra --missing user --request-log "$TMP/lra.log"
LRA="http://127.0.0.1:$PORT/api/jsonp/"
serve lrb --request-log "$TMP/lrb.log"
LRB="http://127.0.0.1:$PORT/api/jsonp/"
start_bg longrun --base "$LRA" --base "$LRB" --probe "http://127.0.0.1:$FREE/generate_204" \
    --interval 1 --rounds 7 --retry-every 3 --timeout 3

# Three rounds; the first address stops answering after its first position request.
serve dying --die-after 1
FIRST="http://127.0.0.1:$PORT/api/jsonp/"
serve second
SECOND="http://127.0.0.1:$PORT/api/jsonp/"
start_bg failover --base "$FIRST" --base "$SECOND" --probe off --interval 1 --rounds 3 --timeout 3

echo "ordinary feed, two rounds"
run_logger --base "$BASE" --probe "http://127.0.0.1:$P/generate_204" --interval 1 --rounds 2 --timeout 3
assert_eq "exits 0" 0 "$RC"
assert_eq "both lines say the feed is answering" 2 "$(count_lines "$OUT" "feed answering (http)")"
assert_eq "log is start, two rounds, stop" '["start", "round", "round", "stop"]' \
    "$(jv "$LOG" '[x.get("event", "round") for x in L]')"
assert_eq "round 1 holds all five parts" "$ALL_PARTS" \
    "$(jv "$LOG" 'sorted(L[1]["feed"], key=["position", "connectivity", "users", "user", "system"].index)')"
assert_eq "the position answer was read with the name given" true "$(jv "$LOG" 'L[1]["first"]["position"]["named"]')"
assert_eq "records the address that answered" '"127.0.0.1"' "$(jv "$LOG" 'L[1]["ip"]')"
assert_eq "an ms figure for each part" true \
    "$(jv "$LOG" 'sorted(L[1]["ms"]) == sorted(L[1]["feed"]) and all(isinstance(v, int) for v in L[1]["ms"].values())')"
assert_eq "a numeric probe ms" true "$(jv "$LOG" 'isinstance(L[1]["probe"]["ms"], int)')"
assert_eq "the users answer is kept whole" '{"version": "1.6", "total": "93", "online": "38"}' \
    "$(jv "$LOG" 'L[1]["feed"]["users"]')"
assert_eq "the feed's index is recorded in round 1" "\"$INDEX_TEXT\"" "$(jv "$LOG" 'L[1]["index"]')"
assert_eq "and only then" false "$(jv "$LOG" '"index" in L[2]')"
assert_eq "round 1 asked the one address" "[\"$BASE\"]" "$(jv "$LOG" 'L[1]["asked"]')"
assert_eq "and round 2 too: the log says what was asked every round" "[\"$BASE\"]" "$(jv "$LOG" 'L[2]["asked"]')"
assert_eq "round 2 says how the address stands" "{\"$BASE\": \"ok\"}" "$(jv "$LOG" 'L[2]["bases"]')"
assert_eq "no speed sample without a terminal" false "$(jv "$LOG" '"speed" in L[1]')"
assert_eq "start record: version 3" 3 "$(jv "$LOG" 'L[0]["version"]')"
assert_eq "start record: no speed site" null "$(jv "$LOG" 'L[0]["speed_site"]')"
assert_eq "start record: nobody said the class (no terminal)" '[null, null]' \
    "$(jv "$LOG" '[L[0]["travel_class"], L[0]["travel_class_from"]]')"
assert_eq "start record: --no-laptop leaves the device out, and the round has no laptop" '[false, false]' \
    "$(jv "$LOG" '["device" in L[0], "laptop" in L[1]]')"
assert_eq "start record: the timeout" 3.0 "$(jv "$LOG" 'L[0]["timeout"]')"
REAL_CURL=$(/usr/bin/curl --version | head -n 1 | cut -d ' ' -f 2 | grep -o '^[0-9.]*')  # not a suffix such as -DEV
assert_eq "start record: the curl version, as this machine's curl reports it" "\"$REAL_CURL\"" "$(jv "$LOG" 'L[0]["curl"]')"
assert_contains "the line shows users" "$OUT" "38 of 93 online"
assert_contains "the line shows each link" "$OUT" "links: lte -62, disconnected 0, lte -35"
assert_contains "the line shows the internet check" "$OUT" "internet "
assert_not_contains "a 204 needs no note" "$OUT" "(answered"
assert_not_contains "no speed in the line" "$OUT" "speed"

echo "the class travelled"
run_logger --base "$BASE" --probe off --rounds 1 --class first
assert_eq "--class first: exits 0" 0 "$RC"
assert_eq "--class first: the start line says first, from the option" '["first", "option"]' \
    "$(jv "$LOG" '[L[0]["travel_class"], L[0]["travel_class_from"]]')"
run_logger --base "$BASE" --probe off --rounds 1 --class standard
assert_eq "--class standard: the start line says standard, from the option" '["standard", "option"]' \
    "$(jv "$LOG" '[L[0]["travel_class"], L[0]["travel_class_from"]]')"
fresh_home
OUT=$(HOME="$RUN_HOME" "$LOGGER" --no-wifi --no-laptop --no-awake --base "$BASE" --probe off --rounds 1 \
    --class business </dev/null 2>&1)
assert_eq "--class business is refused" 2 "$?"
assert_contains "and named" "$OUT" "--class"
assert_eq "nothing was created for it" 0 "$(count_files "$RUN_HOME")"
assert_file_lacks "the logger never uses a shell" "$LOGGER" "shell=True"

echo "an internet check that answers 200, as a sign-in page does"
run_logger --base "$BASE" --probe "http://127.0.0.1:$P/generate_200" --rounds 1
assert_eq "exits 0" 0 "$RC"
assert_contains "the line says what answered" "$OUT" " ms (answered 200)"
assert_eq "the record keeps the code" 200 "$(jv "$LOG" 'L[1]["probe"]["code"]')"
assert_eq "a small body leaves no mark" false "$(jv "$LOG" '"body" in L[1]["probe"]')"

echo "an internet check whose answer is big"
for path in generate_big generate_big_nolength; do
    run_logger --base "$BASE" --probe "http://127.0.0.1:$P/$path" --rounds 1
    assert_eq "$path: exits 0" 0 "$RC"
    assert_eq "$path: the status was received and the body not read" '["body", "code", "ms"]' \
        "$(jv "$LOG" 'sorted(L[1]["probe"])')"
    assert_eq "$path: code 200, not read" '[200, "not read"]' \
        "$(jv "$LOG" '[L[1]["probe"]["code"], L[1]["probe"]["body"]]')"
    assert_eq "$path: a numeric ms" true "$(jv "$LOG" 'isinstance(L[1]["probe"]["ms"], int)')"
    assert_contains "$path: the line says what answered" "$OUT" " ms (answered 200)"
done
run_logger --base "$BASE" --probe "http://127.0.0.1:$P/generate_404_big" --rounds 1
assert_eq "a 404 with a big body is a failure, not a working check" '{"ms": null, "why": "answered 404"}' \
    "$(jv "$LOG" 'L[1]["probe"]')"
assert_contains "and the line says so" "$OUT" "internet answered 404"
run_logger --base "$BASE" --probe "file:///etc/hostname" --rounds 1
assert_eq "a probe address that is not http or https is not asked" \
    '{"ms": null, "why": "only http and https are asked"}' "$(jv "$LOG" 'L[1]["probe"]')"

echo "default log directory, and who may read the log"
HOME_DIR="$TMP/home"
mkdir -p "$HOME_DIR"
OUT=$(umask 022; HOME="$HOME_DIR" "$LOGGER" --no-wifi --no-laptop --no-awake --base "$BASE" --probe off --rounds 1 </dev/null 2>&1)
assert_eq "exits 0" 0 "$?"
FOUND=0
HOME_LOG=""
for f in "$HOME_DIR"/train-feed-logger/logs/train-feed-*.jsonl; do
    if [ -f "$f" ]; then
        FOUND=$((FOUND + 1))
        HOME_LOG="$f"
    fi
done
assert_eq "one log under HOME/train-feed-logger/logs" 1 "$FOUND"
assert_contains "the banner names that directory" "$OUT" "$HOME_DIR/train-feed-logger/logs/train-feed-"
assert_file_missing "no logs directory beside the script" "$HERE/../logs"
assert_file_missing "no logs directory beside the test" "$HERE/logs"
assert_eq "the folder it made is for the owner alone" 700 "$(mode_of "$HOME_DIR/train-feed-logger/logs")"
assert_eq "the log is for the owner alone" 600 "$(mode_of "$HOME_LOG")"
fresh_home
mkdir -p "$LOGDIR"
chmod 755 "$LOGDIR"
HOME="$RUN_HOME" "$LOGGER" --no-wifi --no-laptop --no-awake --base "$BASE" --probe off --rounds 1 >/dev/null 2>&1 </dev/null
assert_eq "an existing folder keeps its mode" 755 "$(mode_of "$LOGDIR")"
assert_eq "and the log went into it" 1 "$(count_files "$LOGDIR")"

echo "the log's place cannot be chosen, and a run from the repository stays out of it"
fresh_home
OUT=$(HOME="$RUN_HOME" "$LOGGER" --no-wifi --no-laptop --no-awake --log-dir "$TMP/elsewhere" --base "$BASE" --probe off \
    --rounds 1 </dev/null 2>&1)
assert_eq "--log-dir is refused" 2 "$?"
assert_contains "and named" "$OUT" "--log-dir"
assert_file_missing "nothing was made where it pointed" "$TMP/elsewhere"
assert_eq "nor under HOME" 0 "$(count_files "$RUN_HOME")"
REPO="$(cd "$HERE/../.." && pwd)"
if git -C "$REPO" rev-parse --git-dir >/dev/null 2>&1; then
    # --ignored, because .gitignore hides train-feed-*.jsonl: a log written into the checkout would not show otherwise.
    BEFORE=$(git -C "$REPO" status --porcelain --ignored)
    fresh_home
    OUT=$(cd "$REPO" && HOME="$RUN_HOME" "$LOGGER" --no-wifi --no-laptop --no-awake --base "$BASE" --probe off --rounds 1 \
        </dev/null 2>&1)
    assert_eq "a run started inside the repository exits 0" 0 "$?"
    assert_eq "its log is under HOME" 1 "$(count_files "$LOGDIR")"
    assert_eq "and the repository's status is unchanged" "$BEFORE" "$(git -C "$REPO" status --porcelain --ignored)"
    assert_file_missing "no logs folder in the repository root" "$REPO/train-feed-logger"
else
    echo "  SKIP the repository case: this is not a checkout"
fi

echo "an answer without the function's name"
run_logger --base "http://127.0.0.1:$P/anon/api/jsonp/" --probe off --rounds 1
assert_eq "exits 0" 0 "$RC"
assert_contains "still answering" "$OUT" "feed answering (http)"
assert_eq "the name was not honoured" false "$(jv "$LOG" 'L[1]["first"]["position"]["named"]')"
assert_eq "the data was still read" true "$(jv "$LOG" '"latitude" in L[1]["feed"]["position"]')"

echo "a sign-in page where the feed should be"
run_logger --base "http://127.0.0.1:$P/html/api/jsonp/" --probe off --rounds 1
assert_eq "exits 0" 0 "$RC"
assert_contains "says why" "$OUT" "feed not answering (http: answered, but not with data)"
assert_eq "no feed in the round" false "$(jv "$LOG" '"feed" in L[1]')"
assert_eq "keeps what the address said" '"<html><body>Sign in to the train Wi-Fi</body></html>"' \
    "$(jv "$LOG" 'L[1]["bases_text"]["http://127.0.0.1:'"$P"'/html/api/jsonp/"]')"
assert_eq "no probe when it is off" false "$(jv "$LOG" '"probe" in L[1]')"

echo "a position that answers 404"
serve m404 --missing position
run_logger --base "http://127.0.0.1:$PORT/api/jsonp/" --probe off --rounds 1
assert_eq "exits 0" 0 "$RC"
assert_contains "says why" "$OUT" "feed not answering (http: answered 404)"

echo "another part that answers a web page"
serve htmlpart --html-part user
run_logger --base "http://127.0.0.1:$PORT/api/jsonp/" --probe off --rounds 1
assert_eq "exits 0" 0 "$RC"
assert_eq "the part is in errs, with what it said" \
    '{"user": "answered, but not with data", "user_text": "<html><body>Sign in to the train Wi-Fi</body></html>"}' \
    "$(jv "$LOG" 'L[1]["errs"]')"
assert_eq "the other parts were read" '["position", "connectivity", "users", "system"]' "$(jv "$LOG" 'list(L[1]["feed"])')"

echo "an answer too big to read"
run_logger --base "http://127.0.0.1:$P/big/api/jsonp/" --probe off --rounds 1 --timeout 5
assert_eq "a body of 49,900 bytes, just under the 50,000 limit, is read as data" true \
    "$(jv "$LOG" 'len(L[1]["feed"]["position"]["pad"]) > 49500')"
assert_contains "and the feed answers" "$OUT" "feed answering (http)"
run_logger --base "http://127.0.0.1:$P/huge/api/jsonp/" --probe off --rounds 1 --timeout 5
assert_contains "over the limit, with a Content-Length" "$OUT" "feed not answering (http: answer too large)"
run_logger --base "http://127.0.0.1:$P/chunked/api/jsonp/" --probe off --rounds 1 --timeout 5
assert_contains "over the limit, without a Content-Length" "$OUT" "feed not answering (http: answer too large)"

echo "an index too big to read"
run_logger --base "http://127.0.0.1:$P/bigindex/api/jsonp/" --probe off --rounds 1 --timeout 5
assert_contains "the parts answered" "$OUT" "feed answering (http)"
assert_eq "the index is refused, and says why" '"answer too large"' "$(jv "$LOG" 'L[1]["index"]')"

echo "a site page too big to read"
serve bigpage --site bigpage --request-log "$TMP/bigpage.log"
SITE_B="http://127.0.0.1:$PORT"
run_logger --base "http://127.0.0.1:$PORT/api/jsonp/" --probe off --speed-site "$SITE_B" --rounds 1 --timeout 5
assert_eq "the sample says why, and nothing else" '{"why": "answer too large"}' "$(jv "$LOG" 'L[1]["speed"]')"
assert_eq "the page was asked for" 1 "$(grep -cx 'GET /' "$TMP/bigpage.log")"
assert_file_lacks "no download was attempted" "$TMP/bigpage.log" "/assets/"

echo "a slow feed with a short --timeout"
run_logger --base "http://127.0.0.1:$P/slow/api/jsonp/" --probe off --rounds 1 --timeout 1
assert_eq "exits 0" 0 "$RC"
assert_contains "timed out" "$OUT" "feed not answering (http: timed out)"
assert_eq "recorded against the address" '"timed out"' \
    "$(jv "$LOG" 'L[1]["bases"]["http://127.0.0.1:'"$P"'/slow/api/jsonp/"]')"

echo "nothing listening"
run_logger --base "http://127.0.0.1:$FREE/api/jsonp/" --probe off --rounds 1
assert_eq "exits 0" 0 "$RC"
assert_contains "could not connect" "$OUT" "feed not answering (http: could not connect)"

echo "limits on the options"
# refuse OPTION VALUE: exits 2, names the option, and writes no log.
refuse() {
    fresh_home
    OUT=$(HOME="$RUN_HOME" "$LOGGER" --no-wifi --no-laptop --no-awake --base "$BASE" --probe off --rounds 1 "$1" "$2" \
        </dev/null 2>&1)
    RC=$?
    assert_eq "$1 $2 is refused" 2 "$RC"
    assert_contains "$1 $2: the message names the option" "$OUT" "$1 must"
    assert_eq "$1 $2: nothing was created, no log folder either" 0 "$(count_files "$RUN_HOME")"
}
refuse --timeout 0
refuse --timeout -1
refuse --speed-timeout 0
refuse --interval 0.99
refuse --rounds -1
refuse --retry-every 0
run_logger --base "http://127.0.0.1:$FREE/api/jsonp/" --probe off --rounds 1 --timeout 0.001 --speed-timeout 0.001
assert_eq "the smallest timeouts are accepted" 0 "$RC"
assert_eq "and the round was still logged" 1 "$(jv "$LOG" 'len([x for x in L if x.get("round")])')"

echo "an untrusted certificate"
if command -v openssl >/dev/null 2>&1 \
    && openssl req -x509 -newkey rsa:2048 -nodes -keyout "$TMP/key.pem" -out "$TMP/cert.pem" -days 1 \
        -subj "/CN=127.0.0.1" >/dev/null 2>&1; then
    serve tls --cert "$TMP/cert.pem" --key "$TMP/key.pem" --request-log "$TMP/tls.log" \
        --connection-log "$TMP/tls.conn"
    run_logger --base "https://127.0.0.1:$PORT/api/jsonp/" --probe off --interval 1 --rounds 3 --retry-every 2 \
        --timeout 5
    assert_eq "exits 0" 0 "$RC"
    assert_contains "answering, with the certificate named" "$OUT" "feed answering (https, certificate not trusted)"
    assert_eq "tls untrusted in all three rounds, the full check in round 3 included" \
        '["untrusted", "untrusted", "untrusted"]' "$(jv "$LOG" '[x["tls"] for x in L[1:4]]')"
    assert_eq "the data was read" true "$(jv "$LOG" '"latitude" in L[1]["feed"]["position"]')"
    # Connections, counted at the stand-in (a refused handshake never reaches a request, so GET lines miss it).
    # With --retry-every 2 the full checks are rounds 1 and 3, each asking position without -k first:
    #   round 1: position refused (1), then read with -k (1); the four other parts follow the mark (4);
    #            the index, once (1)                                                                   = 7
    #   round 2: not a full check: position and the four other parts, all with -k                       = 5
    #   round 3: a full check: position refused again (1), read with -k (1), the four parts (4);
    #            no index, it was read in round 1                                                       = 6
    # 18 in all. If a full check asked a marked address with -k, the mark would be dropped on that read, round 3
    # would lose its tls and count 9 (position, then each part refused and read again).
    assert_eq "connections: 7, 5 and 6" 18 "$(grep -c '' "$TMP/tls.conn")"
    assert_eq "and all but the two refused ones (rounds 1 and 3) reached a request" 16 "$(grep -c '^GET' "$TMP/tls.log")"
else
    echo "  SKIP the certificate case: openssl is missing or could not make a certificate"
fi

echo "failover to the second address"
collect failover
assert_eq "exits 0" 0 "$RC"
assert_eq "every round has a feed" 3 "$(count_lines "$OUT" "feed answering (http)")"
assert_eq "the base moves from the first to the second" "[\"$FIRST\", \"$SECOND\", \"$SECOND\"]" \
    "$(jv "$LOG" '[x["base"] for x in L if x.get("round")]')"
assert_eq "every round says what it asked" "[[\"$FIRST\", \"$SECOND\"], [\"$FIRST\", \"$SECOND\"], [\"$SECOND\"]]" \
    "$(jv "$LOG" '[x["asked"] for x in L if x.get("round")]')"
assert_eq "round 2 says why the first address failed" "{\"$FIRST\": \"empty answer\", \"$SECOND\": \"ok\"}" \
    "$(jv "$LOG" 'L[2]["bases"]')"
assert_eq "round 1 had both answering" "{\"$FIRST\": \"ok\", \"$SECOND\": \"ok\"}" "$(jv "$LOG" 'L[1]["bases"]')"

echo "full checks every third round, and parts that keep failing"
collect longrun
assert_eq "exits 0" 0 "$RC"
assert_eq "seven rounds, each with every part that answers" true \
    "$(jv "$LOG" 'len(L) == 9 and all(sorted(x["feed"]) == ["connectivity", "position", "system", "users"] for x in L[1:8])')"
assert_eq "the 404 is in errs in rounds 1 to 4 and 7" '[1, 2, 3, 4, 7]' \
    "$(jv "$LOG" '[x["round"] for x in L[1:8] if x.get("errs", {}).get("user") == "answered 404"]')"
assert_eq "it is skipped in rounds 5 and 6" '[5, 6]' "$(jv "$LOG" '[x["round"] for x in L[1:8] if "skipped" in x]')"
assert_eq "and the skipped list names it" true "$(jv "$LOG" 'all(x["skipped"] == ["user"] for x in L[5:7])')"
assert_eq "the stand-in was asked for it five times, not seven" 5 "$(grep -c 'GET /api/jsonp/user/' "$TMP/lra.log")"
assert_eq "both addresses asked in rounds 1, 4 and 7 only" '[2, 1, 1, 2, 1, 1, 2]' \
    "$(jv "$LOG" '[len(x["asked"]) for x in L[1:8]]')"
assert_eq "the second address was asked for position three times" 3 "$(grep -c 'GET /api/jsonp/position/' "$TMP/lrb.log")"
assert_eq "and for nothing else" 3 "$(grep -c '' "$TMP/lrb.log")"
assert_eq "a failing internet check is recorded" '{"ms": null, "why": "could not connect"}' "$(jv "$LOG" 'L[1]["probe"]')"
assert_contains "and the line says why" "$OUT" "internet could not connect"
run_logger --base "$BASE" --base "http://127.0.0.1:$P/anon/api/jsonp/" --probe off --interval 1 --rounds 2 --retry-every 1
assert_eq "--retry-every 1 makes every round a full check" true "$(jv "$LOG" 'all(len(x["asked"]) == 2 for x in L[1:3])')"

# stop_with SIGNAL [logger options]: runs the logger until its first round, sends the signal and waits at most
# STOP_WITHIN seconds (5) for it to end (then kills it, which fails the case). Sets RC, OUT and LOG.
STOP_WITHIN=5
stop_with() {
    local sig="$1" pid watchdog tries=0
    shift
    fresh_home
    HOME="$RUN_HOME" "$LOGGER" --no-wifi --no-laptop --no-awake --base "$BASE" --probe off --interval 1 "$@" \
        </dev/null >"$TMP/stop.out" 2>&1 &
    pid=$!
    while ! grep -qs '"round":1' "$LOGDIR"/*.jsonl && [ "$tries" -lt 100 ]; do
        sleep 0.1
        tries=$((tries + 1))
    done
    kill "-$sig" "$pid"
    (
        sleep "$STOP_WITHIN"
        kill -9 "$pid"
    ) >/dev/null 2>&1 &
    watchdog=$!
    wait "$pid"
    RC=$?
    kill "$watchdog" 2>/dev/null
    wait "$watchdog" 2>/dev/null
    OUT=$(<"$TMP/stop.out")
    for LOG in "$LOGDIR"/*.jsonl; do break; done
}

echo "stopping"
for sig in INT HUP TERM; do
    if [ "$sig" = INT ]; then
        stop_with "$sig" --rounds 0
    else
        stop_with "$sig"
    fi
    assert_eq "SIG$sig: exits 0, and not by being killed after 5 s" 0 "$RC"
    assert_eq "SIG$sig: the last line is the stop event" '"stop"' "$(jv "$LOG" 'L[-1]["event"]')"
    assert_eq "SIG$sig: it counted the round" true "$(jv "$LOG" 'L[-1]["rounds"] >= 1')"
    assert_contains "SIG$sig: says it stopped" "$OUT" "Stopped after"
done

echo "stopping in the middle of a long wait"
STOP_WITHIN=3
stop_with TERM --interval 30
STOP_WITHIN=5
assert_eq "it ends within 3 s of the signal, not after the 30 s interval" 0 "$RC"
assert_eq "and writes the stop line" '"stop"' "$(jv "$LOG" 'L[-1]["event"]')"

echo "the window going away"
fresh_home
HOME="$RUN_HOME" "$LOGGER" --no-wifi --no-laptop --no-awake --base "$BASE" --probe off --interval 1 --rounds 3 \
    </dev/null 2>"$TMP/pipe.err" | head -n 1 >/dev/null
assert_eq "it still exits 0" 0 "${PIPESTATUS[0]}"
for LOG in "$LOGDIR"/*.jsonl; do break; done
assert_eq "the log holds start, three rounds and stop" '["start", "round", "round", "round", "stop"]' \
    "$(jv "$LOG" '[x.get("event", "round") for x in L]')"
assert_eq "nothing was written to stderr" "" "$(<"$TMP/pipe.err")"

echo "a machine without system_profiler or caffeinate"
if [ "$(uname)" = Linux ]; then
    fresh_home
    OUT=$(HOME="$RUN_HOME" "$LOGGER" --base "$BASE" --probe off --rounds 1 </dev/null 2>&1)
    RC=$?
    for LOG in "$LOGDIR"/*.jsonl; do break; done
    assert_eq "exits 0" 0 "$RC"
    assert_eq "no wifi reading" false "$(jv "$LOG" '"wifi" in L[1]')"
    assert_eq "no device, and no laptop in the round: the tools are not there" '[null, false]' \
        "$(jv "$LOG" '[L[0]["device"], "laptop" in L[1]]')"
    assert_eq "the feed was read" true "$(jv "$LOG" '"feed" in L[1]')"
else
    echo "  SKIP the no-Wi-Fi-tools case: on a Mac it would read this machine's real Wi-Fi"
fi

echo "terminal safety"
serve esc --escape
run_logger --base "http://127.0.0.1:$PORT/api/jsonp/" --probe off --rounds 1
assert_eq "exits 0" 0 "$RC"
assert_not_contains "no ESC byte is printed" "$OUT" $'\033'
assert_contains "a question mark takes its place" "$OUT" "at ?[31m52.1, "
assert_eq "the log keeps the raw value" '"\u001b[31m52.1"' "$(jv "$LOG" 'L[1]["feed"]["position"]["latitude"]')"

echo "speed sample"
SITE="http://127.0.0.1:$P"
run_logger --base "$BASE" --probe "$SITE/generate_204" --speed-site "$SITE" --interval 1 --rounds 2
assert_eq "exits 0" 0 "$RC"
assert_eq "downloaded exactly 256 KB" "$SCRIPT_BYTES" "$(jv "$LOG" 'L[1]["speed"]["bytes"]')"
assert_eq "a first-byte figure" true \
    "$(jv "$LOG" 'isinstance(L[1]["speed"]["first_byte_ms"], int) and isinstance(L[1]["speed"]["ms"], int)')"
assert_eq "a speed in kbit/s" true "$(jv "$LOG" '0 < L[1]["speed"]["kbps"] <= 41943')"
assert_eq "no why when it worked" false "$(jv "$LOG" '"why" in L[1]["speed"]')"
assert_eq "names the script, in the first round" "\"$SITE/assets/index-test.js\"" "$(jv "$LOG" 'L[1]["speed_from"]')"
assert_eq "not repeated in the second" false "$(jv "$LOG" '"speed_from" in L[2]')"
assert_eq "sampled again in the second" "$SCRIPT_BYTES" "$(jv "$LOG" 'L[2]["speed"]["bytes"]')"
assert_eq "start record names the site" "\"$SITE\"" "$(jv "$LOG" 'L[0]["speed_site"]')"
LINE=$(printf '%s\n' "$OUT" | grep -F "feed answering" | head -1)
MBIT=$(jv "$LOG" '"%.1f" % (L[1]["speed"]["kbps"] / 1000)' | tr -d '"')
assert_contains "the line shows the speed" "$LINE" "speed $MBIT Mbit/s"
assert_contains "the banner says what is downloaded" "$OUT" "first 256 KB of a script from $SITE"

echo "speed sample that stalls"
serve trickle --site trickle
SITE_T="http://127.0.0.1:$PORT"
run_logger --base "http://127.0.0.1:$PORT/api/jsonp/" --probe off --speed-site "$SITE_T" --speed-timeout 1 --rounds 1
assert_eq "exits 0" 0 "$RC"
assert_eq "why: timed out" '"timed out"' "$(jv "$LOG" 'L[1]["speed"]["why"]')"
assert_eq "some bytes arrived, not all" true "$(jv "$LOG" '0 < L[1]["speed"]["bytes"] < 262144')"
assert_contains "the line says why" "$OUT" "speed: timed out"

echo "a site with no script"
serve noscript --site noscript --request-log "$TMP/noscript.log"
SITE_N="http://127.0.0.1:$PORT"
run_logger --base "http://127.0.0.1:$PORT/api/jsonp/" --probe off --speed-site "$SITE_N" --interval 1 --rounds 2
assert_eq "exits 0" 0 "$RC"
assert_eq "the round records why" '{"why": "page names no script"}' "$(jv "$LOG" 'L[1]["speed"]')"
assert_eq "no speed_from" false "$(jv "$LOG" '"speed_from" in L[1]')"
assert_contains "the line says why" "$OUT" "speed: page names no script"
assert_eq "tried the page again in round 2" 2 "$(grep -cx 'GET /' "$TMP/noscript.log")"
assert_file_lacks "no download was attempted" "$TMP/noscript.log" "/assets/"

echo "a script that has gone since the page was read"
serve gone --site brokenscript --request-log "$TMP/gone.log"
SITE_G="http://127.0.0.1:$PORT"
run_logger --base "http://127.0.0.1:$PORT/api/jsonp/" --probe off --speed-site "$SITE_G" --interval 1 --rounds 2 --retry-every 1
assert_eq "exits 0, with the smallest --retry-every" 0 "$RC"
assert_eq "round 1: the script answered 404" '"answered 404"' "$(jv "$LOG" 'L[1]["speed"]["why"]')"
assert_eq "round 2: the same" '"answered 404"' "$(jv "$LOG" 'L[2]["speed"]["why"]')"
assert_eq "the page was read again in round 2" 2 "$(grep -cx 'GET /' "$TMP/gone.log")"
assert_eq "and the script asked for both times" 2 "$(grep -c 'GET /assets/index-gone.js' "$TMP/gone.log")"
assert_contains "the line says why" "$OUT" "speed: answered 404"

echo "a content type that is very long"
run_logger --base "http://127.0.0.1:$P/longtype/api/jsonp/" --probe off --rounds 1
assert_eq "the log keeps exactly 200 characters of it" 200 "$(jv "$LOG" 'len(L[1]["first"]["position"]["type"])')"
assert_eq "and they are the start of it" true \
    "$(jv "$LOG" 'L[1]["first"]["position"]["type"].startswith("application/javascript; x=aaa")')"

echo "a script that answers 404 with a big body"
serve goneb --site gone-big --request-log "$TMP/goneb.log"
SITE_GB="http://127.0.0.1:$PORT"
run_logger --base "http://127.0.0.1:$PORT/api/jsonp/" --probe off --speed-site "$SITE_GB" --interval 1 --rounds 2
assert_eq "it is a 404, not a site that ignores ranges, in both rounds" '["answered 404", "answered 404"]' \
    "$(jv "$LOG" '[L[1]["speed"]["why"], L[2]["speed"]["why"]]')"
assert_eq "the page was read again in round 2: the stale address was forgotten" 2 "$(grep -cx 'GET /' "$TMP/goneb.log")"

echo "a script that answers 404 and then stalls"
serve gonest --site gone-stall --request-log "$TMP/gonest.log"
SITE_GS="http://127.0.0.1:$PORT"
run_logger --base "http://127.0.0.1:$PORT/api/jsonp/" --probe off --speed-site "$SITE_GS" --speed-timeout 1 \
    --interval 1 --rounds 2
assert_eq "the status decides, not the timeout, in both rounds" '["answered 404", "answered 404"]' \
    "$(jv "$LOG" '[L[1]["speed"]["why"], L[2]["speed"]["why"]]')"
assert_eq "the page was read again in round 2: the stale address was forgotten" 2 "$(grep -cx 'GET /' "$TMP/gonest.log")"

echo "a site that ignores the range"
serve ignores --site ignoresrange --request-log "$TMP/ignores.log"
SITE_I="http://127.0.0.1:$PORT"
run_logger --base "http://127.0.0.1:$PORT/api/jsonp/" --probe off --speed-site "$SITE_I" --interval 1 --rounds 2
assert_eq "exits 0" 0 "$RC"
assert_eq "round 1: the site ignores ranges" '"the site ignores ranges"' "$(jv "$LOG" 'L[1]["speed"]["why"]')"
assert_eq "no more than 256 KB were fetched" true \
    "$(jv "$LOG" 'L[1]["speed"]["bytes"] <= 262144 and L[2]["speed"]["bytes"] <= 262144')"
assert_contains "the line says so" "$OUT" "speed: the site ignores ranges"
assert_eq "the page was read once: the script's address was kept" 1 "$(grep -cx 'GET /' "$TMP/ignores.log")"
assert_eq "and the script asked for in both rounds" 2 "$(grep -c 'GET /assets/index-test.js' "$TMP/ignores.log")"
serve ignoresnl --site ignoresrange-nolength
run_logger --base "http://127.0.0.1:$PORT/api/jsonp/" --probe off --speed-site "http://127.0.0.1:$PORT" --rounds 1
assert_eq "without a Content-Length: the same" '"the site ignores ranges"' "$(jv "$LOG" 'L[1]["speed"]["why"]')"
assert_eq "and no more than 256 KB were fetched" true "$(jv "$LOG" 'L[1]["speed"]["bytes"] <= 262144')"

echo "a page that names a script on another origin"
serve other --request-log "$TMP/other.log"
OTHER_PORT=$PORT
serve elsewhere --site otherorigin --other-port "$OTHER_PORT"
run_logger --base "http://127.0.0.1:$PORT/api/jsonp/" --probe off --speed-site "http://127.0.0.1:$PORT" --rounds 1
assert_eq "the sample says why" '{"why": "page names no script on its own site"}' "$(jv "$LOG" 'L[1]["speed"]')"
assert_file_missing "nothing was fetched from the other origin" "$TMP/other.log"

echo "--film wins over everything; --no-film with --speed-site uses the given site"
serve film --request-log "$TMP/film.log"
SITE_F="http://127.0.0.1:$PORT"
run_logger --base "http://127.0.0.1:$PORT/api/jsonp/" --probe off --film --speed-site "$SITE_F" --rounds 1
assert_eq "--film: exits 0" 0 "$RC"
assert_eq "--film: no speed in the round" false "$(jv "$LOG" '"speed" in L[1]')"
assert_eq "--film: no speed site in the start record" null "$(jv "$LOG" 'L[0]["speed_site"]')"
assert_eq "--film: the page was never asked for" 0 "$(grep -cx 'GET /' "$TMP/film.log")"
assert_file_lacks "--film: nothing downloaded" "$TMP/film.log" "/assets/"
run_logger --base "http://127.0.0.1:$PORT/api/jsonp/" --probe off --no-film --speed-site "$SITE_F" --rounds 1
assert_eq "--no-film with --speed-site: exits 0" 0 "$RC"
assert_eq "--no-film with --speed-site: sampled from the given site" "$SCRIPT_BYTES" \
    "$(jv "$LOG" 'L[1]["speed"]["bytes"]')"
assert_eq "--no-film with --speed-site: the site is recorded" "\"$SITE_F\"" "$(jv "$LOG" 'L[0]["speed_site"]')"

echo "pure functions"
# Stand-ins for curl, each a two-line script that prints one version line.
for stub in "8.3.0:curl 8.3.0 (x86_64-pc-linux-gnu) libcurl/8.3.0" "8.4.0:curl 8.4.0 (x) libcurl/8.4.0" \
    "8.10.1:curl 8.10.1 (x) libcurl/8.10.1" "7.88.1:curl 7.88.1 (x) libcurl/7.88.1" "text:hello, this is not curl"; do
    printf '#!/bin/sh\necho "%s"\n' "${stub#*:}" > "$TMP/curl-${stub%%:*}"
    chmod +x "$TMP/curl-${stub%%:*}"
done
RESULTS=$(python3 -B - "$LOGGER" "$TMP" "$FREE" "$P" 2>&1 <<'PY'
import contextlib, importlib.util, io, json, os, shutil, signal, sys, tempfile, time, types

spec = importlib.util.spec_from_file_location("train_feed_logger", sys.argv[1])
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def check(name, expected, actual):
    if expected == actual:
        print("PASS " + name)
    else:
        print("FAIL %s (expected %r, got %r)" % (name, expected, actual))


for name, text, expected in [
    ("plain JSON", '{"a": 1}', {"a": 1}),
    ("named callback", 'cb({"a": 1});', {"a": 1}),
    ("parenthesised", '({ "a":1 });', {"a": 1}),
    ("an array", "[1, 2]", [1, 2]),
    ("a web page", "<html>sign in</html>", None),
    ("empty", "", None),
    ("nothing", None, None),
    ("a bare number is not feed data", "cb(5);", None),
    ("nesting too deep for the parser", "cb(" + "[" * 5000 + "]" * 5000 + ");", None),
]:
    check("parse_feed: " + name, expected, m.parse_feed(text))


def lists(levels):
    return "[" * levels + "]" * levels


def alternating(levels):
    value = None
    for level in range(levels, 0, -1):
        value = [value] if level % 2 else {"a": value}
    return json.dumps(value)


wide = json.dumps([{"a": i} for i in range(10000)])
check("parse_feed: the depth limit is 32", 32, m.MAX_DEPTH)
check("parse_feed: 32 levels of lists are data", json.loads(lists(32)), m.parse_feed(lists(32)))
check("parse_feed: 33 levels of lists are not", None, m.parse_feed(lists(33)))
check("parse_feed: 32 levels of dicts and lists are data", json.loads(alternating(32)), m.parse_feed(alternating(32)))
check("parse_feed: 33 are not", None, m.parse_feed(alternating(33)))
check("parse_feed: 5,000 levels are not (by the interpreter or by the depth check)", None, m.parse_feed(lists(5000)))
check("parse_feed: 100,000 levels are not", None, m.parse_feed(lists(100000)))
check("parse_feed: a wide, shallow answer is data", json.loads(wide), m.parse_feed(wide))

check("printable: an escape sequence", "?[31mred", m.printable("\x1b[31mred"))
check("printable: a non-ASCII character", "caf?", m.printable("café"))
check("printable: a tab and DEL", "a?b?", m.printable("a\tb\x7f"))
check("printable: space and tilde stay", " ~", m.printable(" ~"))
check("printable: capped at 40", "a" * 40, m.printable("a" * 50))
check("printable: a number", "52.1", m.printable(52.1))
check("printable: the cap counts before replacing", "?" * 40, m.printable("\x1b" * 50))
check("printable: a longer limit", "a" * 200, m.printable("a" * 300, 200))
check("printable: a longer limit still replaces", "?" * 200, m.printable("\x1b" * 300, 200))

check("kbps: an ordinary transfer", 10486, m.kbps(262144, 1.0, 1.2))
check("kbps: a phase of exactly 0.05 s", 41943, m.kbps(262144, 1.0, 1.05))
check("kbps: the same phase from 0.1 and 0.15", 41943, m.kbps(262144, 0.1, 0.15))
check("kbps: just under 0.05 s", None, m.kbps(262144, 1.0, 1.049999))
check("kbps: zero bytes", None, m.kbps(0, 1.0, 2.0))
check("kbps: one second for 1000 bytes", 8, m.kbps(1000, 0.0, 1.0))

SITE = "https://cinefilm.chrisjgregory.dev"
check("the default site", SITE, m.SPEED_SITE)


def decide(film, no_film, site, tty, answers):
    asked = []
    queue = list(answers)

    def ask(question):
        asked.append(question)
        return queue.pop(0)

    return m.wants_speed(film, no_film, site, tty, ask), asked


Q = "Will a film be playing on this laptop during the journey? [Y/n] "
for name, args, expected, asks in [
    ("--film beats everything", (True, True, "http://x", True, ["n"]), None, 0),
    ("--speed-site", (False, False, "http://x", True, ["y"]), "http://x", 0),
    ("--speed-site beats --no-film", (False, True, "http://x", True, ["y"]), "http://x", 0),
    ("--no-film", (False, True, None, True, ["y"]), SITE, 0),
    ("no terminal never asks", (False, False, None, False, ["n"]), None, 0),
    ("Enter", (False, False, None, True, [""]), None, 1),
    ("y", (False, False, None, True, ["y"]), None, 1),
    ("YES with spaces", (False, False, None, True, ["  YES "]), None, 1),
    ("N", (False, False, None, True, ["N"]), SITE, 1),
    ("no", (False, False, None, True, ["no"]), SITE, 1),
    ("garbage then n", (False, False, None, True, ["x", "n"]), SITE, 2),
    ("garbage three times", (False, False, None, True, ["x", "maybe", "?", "n"]), None, 3),
    ("no answer is a film", (False, False, None, True, [None]), None, 1),
    ("no answer after garbage is a film", (False, False, None, True, ["x", None]), None, 2),
]:
    got, asked = decide(*args)
    check("wants_speed: " + name, (expected, asks), (got, len(asked)))
    check("wants_speed: " + name + " (the question)", [Q] * asks, asked)


def read(stdin_text, ready):
    calls = []
    stdin = io.StringIO(stdin_text)

    def fake_select(r, w, x, timeout):
        calls.append((r, w, x, timeout))
        return (r if ready else []), [], []

    printed = io.StringIO()
    with contextlib.redirect_stdout(printed):
        answer = m.read_answer(Q, 7, stdin, fake_select)
    return answer, printed.getvalue(), calls, stdin


answer, printed, calls, stdin = read("n\n", True)
check("read_answer: a ready line is returned", "n\n", answer)
check("read_answer: it prints the question and nothing more", Q, printed)
check("read_answer: it waited on stdin for the given time", [([stdin], [], [], 7)], calls)
answer, printed, _, _ = read("n\n", False)
check("read_answer: nobody answers", None, answer)
check("read_answer: it says so", Q + "\nNo answer: assuming a film will play.\n", printed)
answer, printed, _, _ = read("", True)
check("read_answer: end of input", None, answer)
check("read_answer: end of input says so too", Q + "\nNo answer: assuming a film will play.\n", printed)


def with_subprocess(run):
    real = m.subprocess
    m.subprocess = types.SimpleNamespace(run=run, PIPE=-1, DEVNULL=-3)
    try:
        return m.wifi_link()
    finally:
        m.subprocess = real


def profiler(report):
    return lambda *args, **kwargs: types.SimpleNamespace(stdout=report if isinstance(report, bytes) else json.dumps(report).encode())


def missing(*args, **kwargs):
    raise FileNotFoundError("system_profiler")


current = {"_name": "TrainWiFi", "spairport_signal_noise": "-60 dBm / -90 dBm", "spairport_network_rate": 144,
           "spairport_network_mcs": 7, "spairport_network_phymode": "802.11n", "spairport_network_channel": "6 (2GHz)"}
report = {"SPAirPortDataType": [{"spairport_airport_interfaces": [
    {"_name": "awdl0"}, {"_name": "en0", "spairport_current_network_information": current}]}]}
check("wifi_link: the interface with a network", {
    "name": "TrainWiFi", "signal_noise": "-60 dBm / -90 dBm", "rate": 144, "mcs": 7, "phy": "802.11n",
    "channel": "6 (2GHz)"}, with_subprocess(profiler(report)))
check("wifi_link: no interface has a network", None, with_subprocess(profiler(
    {"SPAirPortDataType": [{"spairport_airport_interfaces": [{"_name": "awdl0"}]}]})))
check("wifi_link: malformed output", None, with_subprocess(profiler(b"not json")))
check("wifi_link: the command is missing", None, with_subprocess(missing))


def line(**record):
    return m.status_line(dict(t=0, **record), {}).split("  |  ")[1:]  # without the time


base = {"feed": {}, "base": "http://x/"}
answering = "feed answering (http)"
check("status_line: too fast to measure", [answering, "speed too fast to measure"], line(
    speed={"bytes": 262144, "ms": 900, "first_byte_ms": 100, "kbps": None}, **base))
check("status_line: nothing received", [answering, "speed: nothing received"], line(
    speed={"bytes": 0, "ms": None, "first_byte_ms": None, "kbps": None}, **base))
check("status_line: a why", [answering, "speed: timed out"], line(
    speed={"bytes": 10, "ms": 1000, "first_byte_ms": 5, "kbps": 3, "why": "timed out"}, **base))
check("status_line: an exact figure", [answering, "speed 2.4 Mbit/s"], line(
    speed={"bytes": 262144, "ms": 900, "first_byte_ms": 100, "kbps": 2400}, **base))
check("status_line: one decimal", [answering, "speed 12.3 Mbit/s"], line(
    speed={"bytes": 262144, "ms": 900, "first_byte_ms": 100, "kbps": 12345}, **base))
check("status_line: links skip what is not a link", [answering, "links: lte -60"], line(
    feed={"connectivity": {"links": ["x", 5, None, {"technology": "lte", "rssi": "-60"}]}}, base="http://x/"))
check("status_line: no link at all leaves the part out", [answering], line(
    feed={"connectivity": {"links": ["x", 5]}}, base="http://x/"))
check("status_line: an internet check that answered 200", [answering, "internet 35 ms (answered 200)"], line(
    probe={"ms": 35, "code": 200}, **base))
check("status_line: and one that answered 204", [answering, "internet 35 ms"], line(
    probe={"ms": 35, "code": 204}, **base))
check("status_line: and one that failed", [answering, "internet could not connect"], line(
    probe={"ms": None, "why": "could not connect"}, **base))


GOOD = {"ok": True, "ms": 1, "code": 200, "ip": "10.0.0.1", "type": "application/javascript",
        "body": 'cb({"a": 1});', "headers": ""}


def with_fetch(fake, play):
    real = m.fetch
    m.fetch = fake
    try:
        return play()
    finally:
        m.fetch = real


calls = []


def refusing(url, timeout, insecure=False, max_size=None):
    calls.append((url, insecure))
    if not insecure:
        return {"ok": False, "why": "certificate not trusted", "ms": 1, "curl": 60}
    return dict(GOOD)  # a copy: ask_part adds keys to what it gets


def two_rounds():
    logger = m.Logger(["https://x.test/api/jsonp/"], None, False)
    first = logger.round(1)
    after_first = len(calls)
    second = logger.round(2)
    return first, second, after_first


first, second, after_first = with_fetch(refusing, two_rounds)
check("untrusted: tls in the first round", "untrusted", first["tls"])
check("untrusted: tls in the second round", "untrusted", second["tls"])
check("untrusted: only one request was refused in the first round", 1, sum(1 for _, i in calls[:after_first] if not i))
check("untrusted: none in the second", 0, sum(1 for _, i in calls[after_first:] if not i))
check("untrusted: the second round asked every part once", 5, len(calls) - after_first)
check("untrusted: every part was still read", sorted(m.PARTS), sorted(second["feed"]))

accepting = {"now": False}
trust_calls = []
firsts = []


def clearing(url, timeout, insecure=False, max_size=None):
    trust_calls.append((url, insecure))
    if not insecure and not accepting["now"]:
        return {"ok": False, "why": "certificate not trusted", "ms": 1, "curl": 60}
    return dict(GOOD)  # a copy: ask_part adds keys to what it gets


def trust_journey():
    logger = m.Logger(["https://x.test/api/jsonp/"], None, False, retry_every=3)
    rounds, sizes = {}, []
    for n in range(1, 6):
        accepting["now"] = n >= 4  # refused in rounds 1 to 3, accepted from round 4 (a full check)
        before = len(trust_calls)
        rounds[n] = logger.round(n)
        firsts.append(trust_calls[before][1])  # the position request goes first: the parts wait for it
        refused = sum(1 for _, insecure in trust_calls[before:] if not insecure and not accepting["now"])
        sizes.append((len(trust_calls) - before, refused))
    return rounds, sizes


rounds, sizes = with_fetch(clearing, trust_journey)
check("trust: untrusted in rounds 1 to 3, absent from round 4", ["untrusted"] * 3 + [None] * 2,
      [rounds[n].get("tls") for n in range(1, 6)])
check("trust: rounds 2 and 3 make no refused request", [0, 0], [sizes[1][1], sizes[2][1]])
check("trust: round 4 makes one request per part, none refused", (5, 0), sizes[3])
check("trust: round 5 is not a full check and stays unmarked", (5, 0), sizes[4])
check("trust: position is read with -k in rounds 2 and 3 (marked, not full checks)", [True, True], firsts[1:3])
check("trust: a full check asks the marked address without -k", False, firsts[3])


def timing_out_at_full_check(url, timeout, insecure=False, max_size=None):
    trust_calls.append((url, insecure))
    if not insecure and phase["now"] == "timeout":
        return {"ok": False, "why": "timed out", "ms": 1, "curl": 28}
    if not insecure:
        return {"ok": False, "why": "certificate not trusted", "ms": 1, "curl": 60}
    return dict(GOOD)


phase = {"now": "refuse"}


def no_answer_journey():
    logger = m.Logger(["https://x.test/api/jsonp/"], None, False, retry_every=2)
    out = {}
    for n, now in ((1, "refuse"), (2, "refuse"), (3, "timeout"), (4, "refuse")):
        phase["now"] = now
        before = len(trust_calls)
        out[n] = logger.round(n)
        out[n, "first"] = trust_calls[before][1]
        out[n, "marked"] = "https://x.test/api/jsonp/" in logger.untrusted
    return out


seen = with_fetch(timing_out_at_full_check, no_answer_journey)
check("no answer: round 3 is a full check whose position request timed out, so it has no feed",
      False, "feed" in seen[3])
check("no answer: the address is still marked afterwards", True, seen[3, "marked"])
# With one address and no feed in round 3 nothing is in use, so round 4 is a full check too (although
# (4 - 1) % 2 is not 0): it asks position without -k first, and the mark survives that refusal.
check("no answer: so round 4 asks position without -k, and is marked again", (False, True, "untrusted"),
      (seen[4, "first"], seen[4, "marked"], seen[4].get("tls")))

clock = {"now": 0.0}


def reading(answers):
    given = []

    def reader(question, timeout):
        given.append(timeout)
        clock["now"] += answers[len(given) - 1][0]
        return answers[len(given) - 1][1]

    return reader, given


reader, given = reading([(45, "x"), (10, "x"), (20, "x")])
ask = m.limited_asker(60, lambda: clock["now"], reader)
check("limited_asker: three askings share one allowance", (None, [60, 15, 5]), (
    m.wants_speed(False, False, None, True, ask), given))
clock["now"] = 0.0
reader, given = reading([(45, "x"), (30, "x"), (0, None)])
ask = m.limited_asker(60, lambda: clock["now"], reader)
check("limited_asker: with nothing left the reader gets 0 and the answer is assumed", (None, [60, 15, 0]), (
    m.wants_speed(False, False, None, True, ask), given))
clock["now"] = 0.0
reader, given = reading([(1, "n")])
check("limited_asker: a prompt answer is used", (m.SPEED_SITE, [60]), (
    m.wants_speed(False, False, None, True, m.limited_asker(60, lambda: clock["now"], reader)), given))

check("failed_line: an escape sequence is made printable", "round failed: ?[31mboom",
      m.failed_line("\x1b[31mboom"))
check("failed_line: the text is cut at 200 characters", "round failed: " + "x" * 200, m.failed_line("x" * 300))

def curl_at(path):
    real = m.CURL
    m.CURL = path
    try:
        version = m.curl_version()
        return version, m.curl_problem(version)
    finally:
        m.CURL = real


stubs = sys.argv[2]
for name, version, refused in [("8.3.0", (8, 3, 0), True), ("8.4.0", (8, 4, 0), False),
                               ("8.10.1", (8, 10, 1), False), ("7.88.1", (7, 88, 1), True)]:
    found, problem = curl_at(stubs + "/curl-" + name)
    check("curl %s: the version is read as numbers" % name, version, found)
    check("curl %s: %s" % (name, "refused, naming the version" if refused else "accepted"),
          True if refused else None, (name in problem) if refused else problem)
found, problem = curl_at(stubs + "/curl-text")
check("curl: a first line that is not a curl version is refused", (None, True), (found, "could not be read" in problem))
found, problem = curl_at(stubs + "/curl-missing")
check("curl: a path that does not exist is refused", (None, True), (found, "could not be read" in problem))
check("curl: two parts are enough (8.4 is 8.4.0)", None, m.curl_problem((8, 4)))
check("curl: the real one on this machine is new enough", None, m.curl_problem(m.curl_version()))


def main_with(curl_path):
    """Runs main() with a stand-in curl and an empty HOME: what it exits with, what it said, what it created.
    The only feed address is a loopback port with nothing listening: no real address is ever asked."""
    home = tempfile.mkdtemp(dir=stubs)  # under the test's own temporary directory, removed with it
    saved = (m.CURL, sys.argv, os.environ.get("HOME"))
    m.CURL = curl_path
    sys.argv = ["train-feed-logger.py", "--no-wifi", "--no-laptop", "--no-awake", "--probe", "off", "--rounds", "1",
                "--base", "http://127.0.0.1:%s/api/jsonp/" % sys.argv[3]]
    os.environ["HOME"] = home
    said = io.StringIO()
    try:
        with contextlib.redirect_stderr(said):
            code = m.main()
    except SystemExit as stopped:
        code = stopped.code
    finally:
        m.CURL, sys.argv = saved[0], saved[1]
        if saved[2] is None:
            os.environ.pop("HOME", None)
        else:
            os.environ["HOME"] = saved[2]
    made = os.listdir(home)
    shutil.rmtree(home)
    return code, said.getvalue(), made


code, said, made = main_with(stubs + "/curl-8.3.0")
check("main: an old curl stops the start with exit 2", 2, code)
check("main: and says what is needed and what was found", True, "needs curl 8.4.0" in said and "8.3.0" in said)
check("main: and creates nothing", [], made)
code, said, made = main_with(stubs + "/curl-missing")
check("main: an unreadable curl stops the start with exit 2 too", (2, [], True), (code, made, "could not be read" in said))

for name, first, second, expected in [
    ("another port", "http://h.test/a", "http://h.test:8080/a", False),
    ("another scheme", "https://h.test/a", "http://h.test/a", False),
    ("the default http port", "http://h.test/a", "http://h.test:80/b", True),
    ("the default https port", "https://h.test/a", "https://h.test:443/b", True),
    ("the host's case", "http://H.Test/a", "http://h.test/b", True),
    ("a port that is not one", "http://h.test:99999/a", "http://h.test/b", False),
]:
    check("same_origin: " + name, expected, m.same_origin(first, second))


def script_found(page):
    return with_fetch(lambda url, timeout, insecure=False, max_size=None: dict(GOOD, body=page),
                      lambda: m.find_script("https://site.test", 5))


check("find_script: a relative src is on the site", ("https://site.test/assets/a.js", None),
      script_found('<script src="/assets/a.js"></script>'))
check("find_script: an absolute src on the site", ("https://site.test/assets/a.js", None),
      script_found('<script src="https://site.test/assets/a.js"></script>'))
check("find_script: a protocol-relative src elsewhere is skipped", (None, "page names no script on its own site"),
      script_found('<script src="//evil.test/assets/a.js"></script>'))
check("find_script: the first one on the site is taken after one elsewhere", ("https://site.test/assets/b.js", None),
      script_found('<script src="https://evil.test/assets/a.js"></script><script src="/assets/b.js"></script>'))
check("find_script: no script at all", (None, "page names no script"), script_found("<html></html>"))
check("find_script: a src urljoin cannot join is skipped, the next good one is taken",
      ("https://site.test/assets/b.js", None),
      script_found('<script src="http://[/assets/a.js"></script><script src="/assets/b.js"></script>'))
check("find_script: a src with a NUL is skipped, the next good one is taken", ("https://site.test/assets/b.js", None),
      script_found('<script src="/assets/a\x00.js"></script><script src="/assets/b.js"></script>'))
check("find_script: a src with a non-ASCII character is skipped", ("https://site.test/assets/b.js", None),
      script_found('<script src="/assets/\u00e9.js"></script><script src="/assets/b.js"></script>'))
check("find_script: with only broken ones the reason is the usual one", (None, "page names no script"),
      script_found('<script src="http://[/assets/a.js"></script><script src="/assets/a\x00.js"></script>'))


def round_with_failing_sample(logger):
    def explode(record):
        raise ValueError("embedded null byte")

    logger.take_speed_sample = explode
    return with_fetch(lambda url, timeout, insecure=False, max_size=None: dict(GOOD), lambda: logger.round(1))


sample_logger = m.Logger(["http://a.test/api/jsonp/"], None, False, speed_site="http://site.test")
sample_logger.script_url = "http://site.test/assets/a.js"  # as if found in an earlier round
sample_logger.speed_from_logged = True
record = round_with_failing_sample(sample_logger)
check("a speed sample that raises leaves the round its feed", sorted(m.PARTS), sorted(record.get("feed", {})))
check("and records what and why", {"why": "failed (ValueError: embedded null byte)"}, record.get("speed"))
check("and forgets the script's address", (None, False), (sample_logger.script_url, sample_logger.speed_from_logged))
del sample_logger.take_speed_sample  # the real one again
asked_pages = []


def reading_pages(url, timeout, insecure=False, max_size=None):
    asked_pages.append(url)
    return dict(GOOD, body='<script src="/assets/a.js"></script>')


real_sample = m.sample_speed
m.sample_speed = lambda url, timeout: {"bytes": 1, "ms": 1, "first_byte_ms": 1, "kbps": None}
try:
    with_fetch(reading_pages, lambda: sample_logger.round(2))
finally:
    m.sample_speed = real_sample
check("the next round reads the page again", True, "http://site.test" in asked_pages)

for what, call in (("fetch", lambda: m.fetch("http://x.test/a\x00b", 3)), ("timed_curl", lambda: m.timed_curl("http://x.test/a\x00b", 3))):
    got = call()
    check(what + ": a NUL in the address is a why, not an exception", True, "could not run curl" in got["why"])


def stubbed_curl(name, version_line_and_exit, call):
    path = stubs + "/" + name
    with open(path, "w") as out:
        out.write("#!/bin/sh\n" + version_line_and_exit + "\n")
    os.chmod(path, 0o755)
    real = m.CURL
    m.CURL = path
    try:
        return call()
    finally:
        m.CURL = real


check("probe: a status that was received decides first, whatever curl exits with", {"ms": None, "why": "answered 404"},
      stubbed_curl("curl-404-timeout", "printf '404 10 0.1 10.0'; exit 28", lambda: m.probe("http://x.test/")))
check("sample_speed: the same", "answered 404", stubbed_curl(
    "curl-404-dropped", "printf '404 10 0.1 10.0'; exit 56", lambda: m.sample_speed("http://x.test/a.js", 3))["why"])
check("sample_speed: no status at all falls through to curl's reason", "timed out", stubbed_curl(
    "curl-0-timeout", "printf '000 0 0 0'; exit 28", lambda: m.sample_speed("http://x.test/a.js", 3))["why"])

a_down = {"now": False}


def two_addresses(url, timeout, insecure=False, max_size=None):
    host = url.split("/")[2]
    part = url.split("?")[0].rstrip("/").split("/")[-1]
    if host == "a.test" and part == "user":
        return {"ok": False, "why": "answered 404", "ms": 1, "code": 404}
    if host == "a.test" and part == "position" and a_down["now"]:
        return {"ok": False, "why": "empty answer", "ms": 1, "curl": 52}
    return dict(GOOD)  # a copy: ask_part adds keys to what it gets


def journey():
    logger = m.Logger(["http://a.test/api/jsonp/", "http://b.test/api/jsonp/"], None, False)
    rounds = {}
    for n in range(1, 6):
        a_down["now"] = n == 5
        rounds[n] = logger.round(n)
    return rounds


rounds = with_fetch(two_addresses, journey)
check("failure counts: a part that keeps failing is skipped on the address in use", ["user"], rounds[4]["skipped"])
check("failure counts: they start again on another address", [None, "b.test"], [
    rounds[5].get("skipped"), rounds[5]["base"].split("/")[2]])
check("failure counts: so the part is asked there", True, "user" in rounds[5]["feed"])

# ---- the laptop's kind and state, and the class travelled (version 3) ----
FEED_PORT = sys.argv[4]
BATT_AC = "Now drawing from 'AC Power'\n -InternalBattery-0 (id=1234567)\t100%; charged; 0:00 remaining present: true\n"
BATT_ON = "Now drawing from 'Battery Power'\n -InternalBattery-0 (id=1234567)\t64%; discharging; 3:10 remaining present: true\n"
BATT_CHARGING = "Now drawing from 'AC Power'\n -InternalBattery-0 (id=1234567)\t47%; charging; 1:05 remaining present: true\n"
check("parse_batt: on AC, charged", ("ac", 100, "charged"), m.parse_batt(BATT_AC))
check("parse_batt: on the battery, discharging", ("battery", 64, "discharging"), m.parse_batt(BATT_ON))
check("parse_batt: on AC, charging", ("ac", 47, "charging"), m.parse_batt(BATT_CHARGING))
check("parse_batt: the battery's id is nowhere in the answer", False, "1234567" in repr(m.parse_batt(BATT_ON)))
check("parse_batt: a desktop has no second line", ("ac", None, None), m.parse_batt("Now drawing from 'AC Power'\n"))
check("parse_batt: an empty answer", (None, None, None), m.parse_batt(""))
check("parse_batt: nothing at all", (None, None, None), m.parse_batt(None))
check("parse_batt: a power source it does not know", (None, 64, "discharging"),
      m.parse_batt(BATT_ON.replace("Battery Power", "UPS Power")))
check("parse_batt: a percentage out of range", ("ac", None, "charged"), m.parse_batt(BATT_AC.replace("100%", "150%")))
check("parse_batt: a word where the number belongs", ("ac", None, "charged"), m.parse_batt(BATT_AC.replace("100%", "full%")))
check("parse_batt: no estimate gives no charge word", ("battery", 64, None),
      m.parse_batt(BATT_ON.replace("discharging; 3:10 remaining", "(no estimate)")))
check("parse_batt: a charge word longer than 20 letters", ("battery", 64, None),
      m.parse_batt(BATT_ON.replace("discharging", "d" * 21)))
check("parse_batt: another short word is kept", ("battery", 64, "finishing"),
      m.parse_batt(BATT_ON.replace("discharging", "finishing")))
check("parse_batt: a very long first line", (None, None, None), m.parse_batt("x" * 30000 + "\n" + "y" * 30000))
check("parse_batt: a very long second line", ("ac", None, None), m.parse_batt("Now drawing from 'AC Power'\n" + "y" * 30000))
check("parse_batt: bytes that are not UTF-8", (None, None, None),
      m.parse_batt(b"Now drawing from '\xff\xfe'\n \xff\xfe\n".decode("utf-8", "replace")))

PM_G = "Active Profiles:\nBattery Power -1*\nCurrently in use:\n standby 1\n lowpowermode         %s\n womp 0\n"
check("parse_low_power: 0", 0, m.parse_low_power(PM_G % "0"))
check("parse_low_power: 1", 1, m.parse_low_power(PM_G % "1"))
check("parse_low_power: no such line", None, m.parse_low_power(PM_G.replace("lowpowermode", "other")))
check("parse_low_power: a value that is not 0 or 1", None, m.parse_low_power(PM_G % "2"))
check("parse_low_power: a word where the number belongs", None, m.parse_low_power(PM_G % "yes"))
check("parse_low_power: an empty answer", None, m.parse_low_power(""))
check("parse_low_power: a very long line", None, m.parse_low_power(" lowpowermode " + "1" * 30000))
THERM = ("Note: No thermal warning level has been recorded\n2026-01-01 00:00:00 +0000 CPU Power notify\n"
         "\tCPU_Scheduler_Limit \t= 100\n\tCPU_Available_CPUs \t= 4\n\tCPU_Speed_Limit \t= %s\n")
check("parse_cpu_limit: 75", 75, m.parse_cpu_limit(THERM % "75"))
check("parse_cpu_limit: 100", 100, m.parse_cpu_limit(THERM % "100"))
check("parse_cpu_limit: 0", 0, m.parse_cpu_limit(THERM % "0"))
check("parse_cpu_limit: out of range", None, m.parse_cpu_limit(THERM % "101"))
check("parse_cpu_limit: a word where the number belongs", None, m.parse_cpu_limit(THERM % "fast"))
check("parse_cpu_limit: no such line, as on a Mac with an Apple chip", None,
      m.parse_cpu_limit(THERM.replace("CPU_Speed_Limit", "Other_Limit") % "75"))
check("parse_cpu_limit: an empty answer", None, m.parse_cpu_limit(""))
check("parse_cpu_limit: a very long line", None, m.parse_cpu_limit("CPU_Speed_Limit = " + "1" * 30000))
check("parse_load: the first of three", 1.5, m.parse_load("{ 1.50 1.60 1.70 }\n"))
check("parse_load: whole numbers", 3.0, m.parse_load("{ 3 2 1 }"))
check("parse_load: an empty answer", None, m.parse_load(""))
check("parse_load: a word where a number belongs", None, m.parse_load("{ busy 1.0 1.0 }"))
check("parse_load: only two numbers", None, m.parse_load("{ 1.0 2.0 }"))
check("parse_load: a number out of range", None, m.parse_load("{ 99999999 1.0 1.0 }"))
check("parse_load: a very long line", None, m.parse_load("{ " + "1" * 30000 + " 1 1 }"))
SWAP = "total = 2048.00M  used = %s  free = 1535.50M  (encrypted)\n"
check("parse_swap: the used figure", 512.5, m.parse_swap(SWAP % "512.50M"))
check("parse_swap: none used", 0.0, m.parse_swap(SWAP % "0.00M"))
check("parse_swap: a figure in another unit is not guessed", None, m.parse_swap(SWAP % "1.50G"))
check("parse_swap: a word where the number belongs", None, m.parse_swap(SWAP % "lots M"))
check("parse_swap: no used figure", None, m.parse_swap("total = 2048.00M\n"))
check("parse_swap: an empty answer", None, m.parse_swap(""))
check("parse_swap: a very long line", None, m.parse_swap("used = " + "1" * 30000 + "M"))

DEVICE = "Mac99,9\nAcme Chip 9000 @ 3.00GHz\n6\n12\n34359738368\n"
DEVICE_PARSED = {"model": "Mac99,9", "cpu": "Acme Chip 9000 @ 3.00GHz", "cores": 6, "threads": 12,
                 "memory_bytes": 34359738368}
check("parse_device: five lines", DEVICE_PARSED, m.parse_device(DEVICE))
check("parse_device: no final line end", DEVICE_PARSED, m.parse_device(DEVICE.rstrip("\n")))
check("parse_device: an empty answer", None, m.parse_device(""))
check("parse_device: nothing at all", None, m.parse_device(None))
check("parse_device: four lines are not guessed at", None, m.parse_device("Mac99,9\n6\n12\n34359738368\n"))
check("parse_device: six lines are not guessed at", None, m.parse_device(DEVICE + "extra\n"))
check("parse_device: zero cores", dict(DEVICE_PARSED, cores=None), m.parse_device(DEVICE.replace("\n6\n", "\n0\n")))
check("parse_device: a negative number", dict(DEVICE_PARSED, threads=None), m.parse_device(DEVICE.replace("\n12\n", "\n-12\n")))
check("parse_device: a number that is not whole", dict(DEVICE_PARSED, cores=None), m.parse_device(DEVICE.replace("\n6\n", "\n6.5\n")))
check("parse_device: a word where a number belongs", dict(DEVICE_PARSED, threads=None),
      m.parse_device(DEVICE.replace("\n12\n", "\nmany\n")))
check("parse_device: a memory figure of twenty digits", dict(DEVICE_PARSED, memory_bytes=None),
      m.parse_device(DEVICE.replace("34359738368", "1" * 20)))
check("parse_device: a text of exactly 100 characters is kept", dict(DEVICE_PARSED, cpu="c" * 100),
      m.parse_device(DEVICE.replace(DEVICE_PARSED["cpu"], "c" * 100)))
check("parse_device: a text of 101 is not", dict(DEVICE_PARSED, cpu=None),
      m.parse_device(DEVICE.replace(DEVICE_PARSED["cpu"], "c" * 101)))
check("parse_device: a very long line", dict(DEVICE_PARSED, model=None),
      m.parse_device(DEVICE.replace("Mac99,9", "m" * 30000)))
check("parse_device: a text that is not ASCII", dict(DEVICE_PARSED, model=None), m.parse_device(DEVICE.replace("Mac99,9", "Café")))
check("parse_device: bytes that are not UTF-8", dict(DEVICE_PARSED, model=None),
      m.parse_device(DEVICE.replace("Mac99,9", b"\xff\xfe".decode("utf-8", "replace"))))
check("parse_device: nothing usable", None, m.parse_device("\n\n\n\n\n"))

GOOD_WIFI = '{"on":true,"rssi":-61,"noise":-90,"rate":433,"phy":5,"channel":44,"band":2,"width":3}'
WIFI_PARSED = {"wifi_rssi": -61, "wifi_noise": -90, "wifi_rate": 433, "wifi_phy": 5, "wifi_band": 2, "wifi_width": 3,
               "wifi_channel": 44}
NO_WIFI = {key: None for key in m.WIFI_KEYS}


def wifi_with(**changes):
    data = json.loads(GOOD_WIFI)
    for key, value in changes.items():
        if isinstance(value, str) and value == "MISSING":
            del data[key]
        else:
            data[key] = value
    return json.dumps(data)


def wifi_expect(**changes):
    return dict(WIFI_PARSED, **changes)


check("parse_wifi_light: a line of the real shape", WIFI_PARSED, m.parse_wifi_light(GOOD_WIFI + "\n"))
check("parse_wifi_light: the seven keys", sorted(m.WIFI_KEYS), sorted(WIFI_PARSED))
for name, text in [("null", "null"), ("an empty answer", ""), ("nothing", None), ("text that is not JSON", "no wifi here"),
                   ("a JSON list", "[1, 2]"), ("a bare number", "5"), ("on false", wifi_with(on=False)),
                   ("on as text", wifi_with(on="true")), ("on missing", wifi_with(on="MISSING")),
                   ("bytes that are not UTF-8", b"\xff\xfe".decode("utf-8", "replace")),
                   ("a very long number", '{"on":true,"rssi":' + "1" * 30000 + "}"),
                   ("nesting too deep", "[" * 5000 + "]" * 5000)]:
    check("parse_wifi_light: " + name + " gives seven nulls", NO_WIFI, m.parse_wifi_light(text))
for name, changes, expected in [
    ("a signal of 0", dict(rssi=0), dict(wifi_rssi=None)),
    ("a signal of -120", dict(rssi=-120), dict(wifi_rssi=-120)),
    ("a signal below -120", dict(rssi=-121), dict(wifi_rssi=None)),
    ("a signal that is not whole", dict(rssi=-60.5), dict(wifi_rssi=None)),
    ("a signal as text", dict(rssi="-61"), dict(wifi_rssi=None)),
    ("a signal as a boolean", dict(rssi=True), dict(wifi_rssi=None)),
    ("a noise of 0", dict(noise=0), dict(wifi_noise=None)),
    ("a noise out of range", dict(noise=-200), dict(wifi_noise=None)),
    ("a noise as a boolean", dict(noise=False), dict(wifi_noise=None)),
    ("a rate of 0", dict(rate=0), dict(wifi_rate=None)),
    ("a negative rate", dict(rate=-5), dict(wifi_rate=None)),
    ("a rate of 100000", dict(rate=100000), dict(wifi_rate=100000)),
    ("a rate above 100000", dict(rate=100001), dict(wifi_rate=None)),
    ("a rate rounded to one decimal", dict(rate=433.26), dict(wifi_rate=433.3)),
    ("a rate that rounds to nothing", dict(rate=0.04), dict(wifi_rate=None)),
    ("a rate as text", dict(rate="433"), dict(wifi_rate=None)),
    ("a rate as a boolean", dict(rate=True), dict(wifi_rate=None)),
    ("a standard of 0", dict(phy=0), dict(wifi_phy=None)),
    ("a standard of 21", dict(phy=21), dict(wifi_phy=None)),
    ("a standard of 20", dict(phy=20), dict(wifi_phy=20)),
    ("a band as text", dict(band="2"), dict(wifi_band=None)),
    ("a band as a boolean", dict(band=True), dict(wifi_band=None)),
    ("a width of 0", dict(width=0), dict(wifi_width=None)),
    ("a channel of 0", dict(channel=0), dict(wifi_channel=None)),
    ("a channel of 233", dict(channel=233), dict(wifi_channel=233)),
    ("a channel of 234", dict(channel=234), dict(wifi_channel=None)),
    ("a channel as text", dict(channel="44"), dict(wifi_channel=None)),
    ("a channel that is null", dict(channel=None), dict(wifi_channel=None)),
    ("a key missing", dict(noise="MISSING"), dict(wifi_noise=None)),
    ("channel, band and width missing", dict(channel="MISSING", band="MISSING", width="MISSING"),
     dict(wifi_channel=None, wifi_band=None, wifi_width=None)),
]:
    check("parse_wifi_light: " + name, wifi_expect(**expected), m.parse_wifi_light(wifi_with(**changes)))
check("parse_wifi_light: NaN is not a number", wifi_expect(wifi_rssi=None),
      m.parse_wifi_light(GOOD_WIFI.replace("-61", "NaN")))
check("parse_wifi_light: infinity is not a number", wifi_expect(wifi_rate=None),
      m.parse_wifi_light(GOOD_WIFI.replace("433", "Infinity")))
script = m.WIFI_SCRIPT.lower()
check("the fixed script asks for no name, address, country or scan", [], [
    word for word in ("ssid", "hardwareaddress", "countrycode", "scan") if word in script])
check("the fixed script keeps its Number() calls", 7, m.WIFI_SCRIPT.count("Number("))
check("the tools are each an absolute path", ("/usr/sbin/sysctl", "/usr/bin/pmset", "/usr/bin/osascript"),
      (m.SYSCTL, m.PMSET, m.OSASCRIPT))
check("the limits are 2 seconds and 20,000 bytes, and six calls fit in a round's 14 seconds", (2, 20000, True),
      (m.TOOL_TIMEOUT_S, m.TOOL_MAX_BYTES, 6 * m.TOOL_TIMEOUT_S < 14))


def tool(name, body):
    path = stubs + "/" + name
    with open(path, "w") as out:
        out.write("#!/bin/sh\n" + body + "\n")
    os.chmod(path, 0o755)
    return path


check("run_tool: what a tool prints", "hello\n", m.run_tool([tool("tool-ok", "echo hello")]))
check("run_tool: a tool that is missing", None, m.run_tool([stubs + "/tool-not-there"]))
check("run_tool: a tool that exits non-zero, whatever it printed", None, m.run_tool([tool("tool-fails", "echo hello; exit 1")]))
check("run_tool: exactly the limit is kept", 20000, len(m.run_tool([tool("tool-limit", "exec head -c 20000 /dev/zero")]) or ""))
check("run_tool: one byte over the limit", None, m.run_tool([tool("tool-over", "exec head -c 20001 /dev/zero")]))
began = time.monotonic()
check("run_tool: a tool that never stops printing", None, m.run_tool([tool("tool-endless", "exec yes")]))
check("run_tool: and is stopped at once", True, time.monotonic() - began < 2)
real_timeout = m.TOOL_TIMEOUT_S
m.TOOL_TIMEOUT_S = 0.5
began = time.monotonic()
try:
    slow = m.run_tool([tool("tool-slow", "exec sleep 30")])
    stalled = m.run_tool([tool("tool-stalled", "echo started; exec sleep 30")])
finally:
    m.TOOL_TIMEOUT_S = real_timeout
check("run_tool: a tool that takes longer than its time", (None, None), (slow, stalled))
check("run_tool: each was stopped after its time, not waited for", True, time.monotonic() - began < 3)
check("run_tool: bytes that are not UTF-8 come back replaced", "��x\n",
      m.run_tool([tool("tool-bytes", "printf '\\377\\376x\\n'")]))
check("run_tool: it is no shell: a metacharacter in the path is only a path", None, m.run_tool([stubs + "/a;echo hi"]))

TOOLS = tempfile.mkdtemp(dir=stubs)
for name, text in [("batt", BATT_ON), ("therm", THERM % "75"), ("g", PM_G % "1"), ("device", DEVICE),
                   ("load", "{ 1.50 1.60 1.70 }\n"), ("swap", SWAP % "512.50M"), ("wifi", GOOD_WIFI + "\n")]:
    with open(TOOLS + "/" + name, "w") as out:
        out.write(text)
PMSET_BODY = ('case "$*" in\n "-g batt") cat %s/batt;;\n "-g therm") cat %s/therm;;\n "-g") cat %s/g;;\n *) exit 3;;\nesac'
              % (TOOLS, TOOLS, TOOLS))
SYSCTL_BODY = ('case "$*" in\n "-n vm.loadavg") cat %s/load;;\n "-n vm.swapusage") cat %s/swap;;\n'
               ' "-n hw.model machdep.cpu.brand_string hw.physicalcpu hw.logicalcpu hw.memsize") cat %s/device;;\n'
               ' *) exit 3;;\nesac' % (TOOLS, TOOLS, TOOLS))
OSASCRIPT_BODY = 'printf "%%s\\n" "$@" > %s/osascript-called\ncat %s/wifi' % (TOOLS, TOOLS)
GOOD_TOOLS = {"PMSET": tool("stand-in-pmset", PMSET_BODY), "SYSCTL": tool("stand-in-sysctl", SYSCTL_BODY),
              "OSASCRIPT": tool("stand-in-osascript", OSASCRIPT_BODY)}
LAPTOP = dict(power="battery", battery=64, charge="discharging", low_power=1, cpu_limit=75, load=1.5, swap_mb=512.5,
              **WIFI_PARSED)


def with_tools(overrides, call):
    saved = {key: getattr(m, key) for key in overrides}
    for key, value in overrides.items():
        setattr(m, key, value)
    try:
        return call()
    finally:
        for key, value in saved.items():
            setattr(m, key, value)


def called():
    return os.path.exists(TOOLS + "/osascript-called")


check("read_laptop: every field, from stand-in tools", LAPTOP, with_tools(GOOD_TOOLS, m.read_laptop))
with open(TOOLS + "/osascript-called") as marked:
    check("read_laptop: the fixed script went to osascript as one argument", "-l\nJavaScript\n-e\n" + m.WIFI_SCRIPT + "\n",
          marked.read())
os.remove(TOOLS + "/osascript-called")
check("read_laptop: with no Wi-Fi the seven fields are null", dict(LAPTOP, **NO_WIFI),
      with_tools(GOOD_TOOLS, lambda: m.read_laptop(False)))
check("read_laptop: and osascript was not called", False, called())
check("read_device: five lines from a stand-in", DEVICE_PARSED, with_tools(GOOD_TOOLS, m.read_device))
missing_all = {"PMSET": stubs + "/nope-1", "SYSCTL": stubs + "/nope-2", "OSASCRIPT": stubs + "/nope-3"}
check("read_laptop: no tool at all (not a Mac) is None", None, with_tools(missing_all, m.read_laptop))
check("read_device: no tool at all is None", None, with_tools(missing_all, m.read_device))
for broken, what, nulls in [
    ("PMSET", "pmset", ["power", "battery", "charge", "low_power", "cpu_limit"]),
    ("SYSCTL", "sysctl", ["load", "swap_mb"]),
    ("OSASCRIPT", "osascript", m.WIFI_KEYS),
]:
    for how, body in [("is missing", None), ("exits non-zero", "echo garbage; exit 1"), ("says something unexpected", "echo what"),
                      ("prints more than the limit", "exec head -c 30000 /dev/zero")]:
        path = stubs + "/nope-4" if body is None else tool("broken-" + what, body)
        got = with_tools(dict(GOOD_TOOLS, **{broken: path}), m.read_laptop)
        check("read_laptop: %s %s: only its fields are null" % (what, how), dict(LAPTOP, **{k: None for k in nulls}), got)
m.TOOL_TIMEOUT_S = 0.5
began = time.monotonic()
try:
    got = with_tools(dict(GOOD_TOOLS, SYSCTL=tool("slow-sysctl", "exec sleep 30")), m.read_laptop)
finally:
    m.TOOL_TIMEOUT_S = real_timeout
check("read_laptop: a sysctl that takes too long: its fields are null, the others there", dict(LAPTOP, load=None, swap_mb=None), got)
check("read_laptop: it took two waits of the time, not more", True, time.monotonic() - began < 3)


def with_reader(reader):
    logger = m.Logger(["http://a.test/api/jsonp/"], None, False, laptop=reader)
    return with_fetch(lambda url, timeout, insecure=False, max_size=None: dict(GOOD), lambda: logger.round(1))


def exploding():
    raise ValueError("boom")


record = with_reader(exploding)
check("a laptop reader that fails does not lose the round's feed", (sorted(m.PARTS), False),
      (sorted(record.get("feed", {})), "laptop" in record))
record = with_reader(lambda: {"power": "ac"})
check("a laptop reader's answer is the round's laptop", ({"power": "ac"}, sorted(m.PARTS)),
      (record.get("laptop"), sorted(record["feed"])))
check("a reader with nothing to say leaves the key out", False, "laptop" in with_reader(lambda: None))
real_wait = m.SIDE_WAIT_S
m.SIDE_WAIT_S = 0.2
began = time.monotonic()
try:
    record = with_reader(lambda: time.sleep(2) or {"power": "ac"})
finally:
    m.SIDE_WAIT_S = real_wait
check("a late laptop reader neither holds the round up nor leaves a laptop key, and the feed is kept",
      (True, sorted(m.PARTS), False), (time.monotonic() - began < 1.5, sorted(record.get("feed", {})), "laptop" in record))
check("no reader, no key", False, "laptop" in with_fetch(
    lambda url, timeout, insecure=False, max_size=None: dict(GOOD),
    lambda: m.Logger(["http://a.test/api/jsonp/"], None, False).round(1)))

for name, answer, expected in [
    ("f", "f\n", "first"), ("F", "F\n", "first"), ("first", "first\n", "first"), ("First with spaces", "  First \n", "first"),
    ("s", "s\n", "standard"), ("standard", "standard\n", "standard"), ("an empty answer (Return)", "\n", "standard"),
    ("something else", "x\n", "standard"), ("an empty text", "", "standard"),
]:
    asked = []
    got = m.wants_class(None, True, lambda question, answer=answer, **more: asked.append((question, more)) or answer)
    check("wants_class: " + name, (expected, "answer", [(m.CLASS_QUESTION, {"unanswered": m.NO_CLASS_NOTE})]),
          (got[0], got[1], asked))
check("wants_class: the question", "Standard or first class? [S/f] ", m.CLASS_QUESTION)
check("wants_class: no answer in time is nobody saying", (None, None), m.wants_class(None, True, lambda question, **more: None))


def must_not_ask(question, **more):
    raise AssertionError("asked")


check("wants_class: no terminal is nobody saying, and nothing is asked", (None, None), m.wants_class(None, False, must_not_ask))
for option in ("standard", "first"):
    check("wants_class: --class " + option + " asks nothing", (option, "option"), m.wants_class(option, True, must_not_ask))
    check("wants_class: --class " + option + " beats no terminal", (option, "option"), m.wants_class(option, False, must_not_ask))


def read_note(note):
    printed = io.StringIO()
    with contextlib.redirect_stdout(printed):
        answer = m.read_answer(m.CLASS_QUESTION, 1, io.StringIO("f\n"), lambda r, w, x, t: ([], [], []), **note)
    return answer, printed.getvalue()


check("read_answer: the class question's own note", (None, m.CLASS_QUESTION + "\nNo answer: the class is not recorded.\n"),
      read_note(dict(unanswered=m.NO_CLASS_NOTE)))
check("read_answer: the film's note is still the default", (None, m.CLASS_QUESTION + "\nNo answer: assuming a film will play.\n"),
      read_note({}))


def both(film, option, answers):
    """Asks the way main() does: the film question, then the class question, one allowance for both."""
    clock["now"] = 0.0
    given, asked = [], []

    def reader(question, timeout, **more):
        given.append(timeout)
        asked.append(question)
        spent, text = answers[len(given) - 1]
        clock["now"] += spent
        return text

    ask = m.limited_asker(60, lambda: clock["now"], reader)
    chosen = m.wants_speed(film, False, None, True, ask)
    return chosen, m.wants_class(option, True, ask), given, asked


chosen, picked, given, asked = both(False, None, [(45, "n"), (10, "f")])
check("the two questions share one allowance: 60, then 15", (m.SPEED_SITE, ("first", "answer"), [60, 15]), (chosen, picked, given))
check("the film is asked first, then the class", [Q, m.CLASS_QUESTION], asked)
chosen, picked, given, asked = both(False, None, [(60, "n"), (5, "f")])
check("when the film's answer used it all, the class gets 0 seconds", [60, 0], given)
chosen, picked, given, asked = both(True, None, [(30, "s")])
check("with --film only the class is asked, with the whole allowance", (None, ("standard", "answer"), [60]), (chosen, picked, given))
chosen, picked, given, asked = both(True, "first", [])
check("with --film and --class nothing is asked", (None, ("first", "option"), []), (chosen, picked, given))
chosen, picked, given, asked = both(False, None, [(20, "x"), (20, "x"), (20, "x"), (5, "f")])
check("three unclear answers and the class share the one allowance", [60, 40, 20, 0], given)


def run_main(extra, overrides=None):
    """Runs main() against the stand-in feed and the given stand-in tools, with an empty HOME. Returns the log's
    records and its raw text. The real Wi-Fi profiler is never called."""
    home = tempfile.mkdtemp(dir=stubs)
    overrides = dict({"wifi_link": lambda: None}, **(overrides or {}))
    saved_module = {key: getattr(m, key) for key in overrides}
    saved = (sys.argv, os.environ.get("HOME"), {n: signal.getsignal(getattr(signal, n)) for n in ("SIGINT", "SIGTERM", "SIGHUP")})
    sys.argv = ["train-feed-logger.py", "--no-awake", "--probe", "off", "--rounds", "1", "--timeout", "3",
                "--base", "http://127.0.0.1:%s/api/jsonp/" % FEED_PORT] + extra
    os.environ["HOME"] = home
    for key, value in overrides.items():
        setattr(m, key, value)
    try:
        with contextlib.redirect_stdout(io.StringIO()):
            m.main()
    finally:
        for key, value in saved_module.items():
            setattr(m, key, value)
        sys.argv = saved[0]
        os.environ["HOME"] = saved[1] if saved[1] is not None else ""
        for n, handler in saved[2].items():
            signal.signal(getattr(signal, n), handler)
    folder = home + "/train-feed-logger/logs"
    with open(folder + "/" + os.listdir(folder)[0]) as log:
        raw = log.read()
    shutil.rmtree(home)
    return [json.loads(x) for x in raw.splitlines()], raw


records, raw = run_main([], GOOD_TOOLS)
check("a whole run: version 3 in the start line", 3, records[0]["version"])
check("a whole run: the start line has the device", DEVICE_PARSED, records[0]["device"])
check("a whole run: the round has the laptop, the Wi-Fi fields too", LAPTOP, records[1]["laptop"])
check("a whole run: and the feed", True, "position" in records[1]["feed"])
check("a whole run: the battery's id is nowhere in the log", (False, False, False),
      ("1234567" in raw, "InternalBattery" in raw, "Battery Power" in raw))
check("a whole run: no terminal, so the class is not said", (None, None),
      (records[0]["travel_class"], records[0]["travel_class_from"]))
os.remove(TOOLS + "/osascript-called")
records, raw = run_main(["--no-wifi"], GOOD_TOOLS)
check("a run with --no-wifi: the laptop has the other fields and seven nulls", dict(LAPTOP, **NO_WIFI), records[1]["laptop"])
check("a run with --no-wifi: osascript was not called", False, called())
records, raw = run_main(["--no-laptop"], GOOD_TOOLS)
check("a run with --no-laptop: no device in the start line, no laptop in the round", (False, False),
      ("device" in records[0], "laptop" in records[1]))
check("a run with --no-laptop: osascript was not called", False, called())
records, raw = run_main([], missing_all)
check("a run with no tools: the device is null, the round has no laptop and still its feed", (None, False, True),
      (records[0]["device"], "laptop" in records[1], "position" in records[1]["feed"]))
for option in ("standard", "first"):
    records, raw = run_main(["--class", option], GOOD_TOOLS)
    check("a run with --class %s: both keys in the start line" % option, (option, "option"),
          (records[0]["travel_class"], records[0]["travel_class_from"]))
PY
)
assert_eq "the pure functions ran without an error" 0 "$(count_lines "$RESULTS" "Traceback")"
PURE=0
while IFS= read -r line; do
    case "$line" in
        "PASS "*) ok "${line#PASS }"; PURE=$((PURE + 1)) ;;
        "FAIL "*) bad "${line#FAIL }"; PURE=$((PURE + 1)) ;;
    esac
done <<< "$RESULTS"
assert_eq "all 331 pure checks reported" 331 "$PURE"

echo "compiles"
PYTHONPYCACHEPREFIX="$TMP/pyc" python3 -m py_compile "$LOGGER" "$STAND_IN"
assert_eq "the logger and the stand-in compile" 0 "$?"
assert_file_missing "no __pycache__ beside the script" "$HERE/../__pycache__"
assert_file_missing "no __pycache__ beside the tests" "$HERE/__pycache__"

finish_tests
