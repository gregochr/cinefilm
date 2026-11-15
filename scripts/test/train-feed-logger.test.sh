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

# run_logger ARGS: runs the logger with no terminal. Sets OUT, RC, LOG (its log file) and LOGDIR.
run_logger() {
    LOGDIR=$(mktemp -d "$TMP/logs.XXXXXX")
    OUT=$("$LOGGER" --no-wifi --no-awake --log-dir "$LOGDIR" "$@" </dev/null 2>&1)
    RC=$?
    LOG=""
    for LOG in "$LOGDIR"/*.jsonl; do break; done
}

# start_bg NAME ARGS: like run_logger, but in the background so a long run overlaps the other cases.
# Read it with collect NAME.
start_bg() {
    local name="$1"
    shift
    mkdir -p "$TMP/$name.logs"
    (
        "$LOGGER" --no-wifi --no-awake --log-dir "$TMP/$name.logs" "$@" </dev/null >"$TMP/$name.out" 2>&1
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
    LOGDIR="$TMP/$name.logs"
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
assert_eq "start record: version 2" 2 "$(jv "$LOG" 'L[0]["version"]')"
assert_eq "start record: no speed site" null "$(jv "$LOG" 'L[0]["speed_site"]')"
assert_eq "start record: the timeout" 3.0 "$(jv "$LOG" 'L[0]["timeout"]')"
assert_contains "the line shows users" "$OUT" "38 of 93 online"
assert_contains "the line shows each link" "$OUT" "links: lte -62, disconnected 0, lte -35"
assert_contains "the line shows the internet check" "$OUT" "internet "
assert_not_contains "a 204 needs no note" "$OUT" "(answered"
assert_not_contains "no speed in the line" "$OUT" "speed"

echo "an internet check that answers 200, as a sign-in page does"
run_logger --base "$BASE" --probe "http://127.0.0.1:$P/generate_200" --rounds 1
assert_eq "exits 0" 0 "$RC"
assert_contains "the line says what answered" "$OUT" " ms (answered 200)"
assert_eq "the record keeps the code" 200 "$(jv "$LOG" 'L[1]["probe"]["code"]')"

echo "default log directory, and who may read the log"
HOME_DIR="$TMP/home"
mkdir -p "$HOME_DIR"
OUT=$(umask 022; HOME="$HOME_DIR" "$LOGGER" --no-wifi --no-awake --base "$BASE" --probe off --rounds 1 </dev/null 2>&1)
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
mkdir "$TMP/existing"
chmod 755 "$TMP/existing"
"$LOGGER" --no-wifi --no-awake --log-dir "$TMP/existing" --base "$BASE" --probe off --rounds 1 >/dev/null 2>&1 </dev/null
assert_eq "an existing folder keeps its mode" 755 "$(mode_of "$TMP/existing")"

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
assert_eq "150,000 bytes of valid data are read as data" 150000 "$(jv "$LOG" 'len(L[1]["feed"]["position"]["pad"])')"
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
    LOGDIR=$(mktemp -d "$TMP/logs.XXXXXX")
    OUT=$("$LOGGER" --no-wifi --no-awake --log-dir "$LOGDIR" --base "$BASE" --probe off --rounds 1 "$1" "$2" \
        </dev/null 2>&1)
    RC=$?
    assert_eq "$1 $2 is refused" 2 "$RC"
    assert_contains "$1 $2: the message names the option" "$OUT" "$1 must"
    assert_eq "$1 $2: no log was started" 0 "$(count_files "$LOGDIR")"
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
    LOGDIR=$(mktemp -d "$TMP/logs.XXXXXX")
    "$LOGGER" --no-wifi --no-awake --log-dir "$LOGDIR" --base "$BASE" --probe off --interval 1 "$@" \
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
LOGDIR=$(mktemp -d "$TMP/logs.XXXXXX")
"$LOGGER" --no-wifi --no-awake --log-dir "$LOGDIR" --base "$BASE" --probe off --interval 1 --rounds 3 \
    </dev/null 2>"$TMP/pipe.err" | head -n 1 >/dev/null
assert_eq "it still exits 0" 0 "${PIPESTATUS[0]}"
for LOG in "$LOGDIR"/*.jsonl; do break; done
assert_eq "the log holds start, three rounds and stop" '["start", "round", "round", "round", "stop"]' \
    "$(jv "$LOG" '[x.get("event", "round") for x in L]')"
assert_eq "nothing was written to stderr" "" "$(<"$TMP/pipe.err")"

echo "a machine without system_profiler or caffeinate"
if [ "$(uname)" = Linux ]; then
    LOGDIR=$(mktemp -d "$TMP/logs.XXXXXX")
    OUT=$("$LOGGER" --log-dir "$LOGDIR" --base "$BASE" --probe off --rounds 1 </dev/null 2>&1)
    RC=$?
    for LOG in "$LOGDIR"/*.jsonl; do break; done
    assert_eq "exits 0" 0 "$RC"
    assert_eq "no wifi reading" false "$(jv "$LOG" '"wifi" in L[1]')"
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
RESULTS=$(python3 -B - "$LOGGER" 2>&1 <<'PY'
import contextlib, importlib.util, io, json, sys, types

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
assert_eq "all 105 pure checks reported" 105 "$PURE"

echo "compiles"
PYTHONPYCACHEPREFIX="$TMP/pyc" python3 -m py_compile "$LOGGER" "$STAND_IN"
assert_eq "the logger and the stand-in compile" 0 "$?"
assert_file_missing "no __pycache__ beside the script" "$HERE/../__pycache__"
assert_file_missing "no __pycache__ beside the tests" "$HERE/__pycache__"

finish_tests
