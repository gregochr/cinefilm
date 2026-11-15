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

ALL_PARTS='["position", "connectivity", "users", "user", "system"]'
SCRIPT_BYTES=262144

echo "ordinary feed, two rounds"
serve ok
P=$PORT
if [ -n "$P" ]; then ok "the stand-in started"; else bad "the stand-in did not start"; fi
BASE="http://127.0.0.1:$P/api/jsonp/"
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
assert_eq "no speed sample without a terminal" false "$(jv "$LOG" '"speed" in L[1]')"
assert_eq "start record: version 2" 2 "$(jv "$LOG" 'L[0]["version"]')"
assert_eq "start record: no speed site" null "$(jv "$LOG" 'L[0]["speed_site"]')"
assert_eq "start record: the timeout" 3.0 "$(jv "$LOG" 'L[0]["timeout"]')"
assert_contains "the line shows users" "$OUT" "38 of 93 online"
assert_contains "the line shows each link" "$OUT" "links: lte -62, disconnected 0, lte -35"
assert_contains "the line shows the internet check" "$OUT" "internet "
assert_not_contains "no speed in the line" "$OUT" "speed"

echo "default log directory"
HOME_DIR="$TMP/home"
mkdir -p "$HOME_DIR"
OUT=$(HOME="$HOME_DIR" "$LOGGER" --no-wifi --no-awake --base "$BASE" --probe off --rounds 1 </dev/null 2>&1)
assert_eq "exits 0" 0 "$?"
FOUND=0
for f in "$HOME_DIR"/train-feed-logger/logs/train-feed-*.jsonl; do
    [ -f "$f" ] && FOUND=$((FOUND + 1))
done
assert_eq "one log under HOME/train-feed-logger/logs" 1 "$FOUND"
assert_contains "the banner names that directory" "$OUT" "$HOME_DIR/train-feed-logger/logs/train-feed-"
assert_file_missing "no logs directory beside the script" "$HERE/../logs"
assert_file_missing "no logs directory beside the test" "$HERE/logs"

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

echo "a slow feed with a short --timeout"
run_logger --base "http://127.0.0.1:$P/slow/api/jsonp/" --probe off --rounds 1 --timeout 1
assert_eq "exits 0" 0 "$RC"
assert_contains "timed out" "$OUT" "feed not answering (http: timed out)"
assert_eq "recorded against the address" '"timed out"' \
    "$(jv "$LOG" 'L[1]["bases"]["http://127.0.0.1:'"$P"'/slow/api/jsonp/"]')"

echo "nothing listening"
FREE=$(python3 -B -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')
run_logger --base "http://127.0.0.1:$FREE/api/jsonp/" --probe off --rounds 1
assert_eq "exits 0" 0 "$RC"
assert_contains "could not connect" "$OUT" "feed not answering (http: could not connect)"

echo "an untrusted certificate"
if command -v openssl >/dev/null 2>&1 \
    && openssl req -x509 -newkey rsa:2048 -nodes -keyout "$TMP/key.pem" -out "$TMP/cert.pem" -days 1 \
        -subj "/CN=127.0.0.1" >/dev/null 2>&1; then
    serve tls --cert "$TMP/cert.pem" --key "$TMP/key.pem"
    run_logger --base "https://127.0.0.1:$PORT/api/jsonp/" --probe off --rounds 1 --timeout 5
    assert_eq "exits 0" 0 "$RC"
    assert_contains "answering, with the certificate named" "$OUT" "feed answering (https, certificate not trusted)"
    assert_eq "tls untrusted in the round" '"untrusted"' "$(jv "$LOG" 'L[1]["tls"]')"
    assert_eq "the data was read" true "$(jv "$LOG" '"latitude" in L[1]["feed"]["position"]')"
else
    echo "  SKIP the certificate case: openssl is missing or could not make a certificate"
fi

echo "failover to the second address"
serve dying --die-after 1
FIRST="http://127.0.0.1:$PORT/api/jsonp/"
serve second
SECOND="http://127.0.0.1:$PORT/api/jsonp/"
run_logger --base "$FIRST" --base "$SECOND" --probe off --interval 1 --rounds 3 --timeout 3
assert_eq "exits 0" 0 "$RC"
assert_eq "every round has a feed" 3 "$(count_lines "$OUT" "feed answering (http)")"
assert_eq "the base moves from the first to the second" "[\"$FIRST\", \"$SECOND\", \"$SECOND\"]" \
    "$(jv "$LOG" '[x["base"] for x in L if x.get("round")]')"

# stop_with SIGNAL: runs the logger until its first round, sends the signal, sets RC, OUT and LOG.
stop_with() {
    LOGDIR=$(mktemp -d "$TMP/logs.XXXXXX")
    "$LOGGER" --no-wifi --no-awake --log-dir "$LOGDIR" --base "$BASE" --probe off --interval 1 \
        </dev/null >"$TMP/stop.out" 2>&1 &
    local pid=$! tries=0
    while ! grep -qs '"round":1' "$LOGDIR"/*.jsonl && [ "$tries" -lt 100 ]; do
        sleep 0.1
        tries=$((tries + 1))
    done
    kill "-$1" "$pid"
    wait "$pid"
    RC=$?
    OUT=$(<"$TMP/stop.out")
    for LOG in "$LOGDIR"/*.jsonl; do break; done
}

echo "stopping"
for sig in INT HUP; do
    stop_with "$sig"
    assert_eq "SIG$sig: exits 0" 0 "$RC"
    assert_eq "SIG$sig: the last line is the stop event" '"stop"' "$(jv "$LOG" 'L[-1]["event"]')"
    assert_eq "SIG$sig: it counted the round" true "$(jv "$LOG" 'L[-1]["rounds"] >= 1')"
    assert_contains "SIG$sig: says it stopped" "$OUT" "Stopped after"
done

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
import importlib.util, sys

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
]:
    check("parse_feed: " + name, expected, m.parse_feed(text))

check("printable: an escape sequence", "?[31mred", m.printable("\x1b[31mred"))
check("printable: a non-ASCII character", "caf?", m.printable("café"))
check("printable: a tab and DEL", "a?b?", m.printable("a\tb\x7f"))
check("printable: space and tilde stay", " ~", m.printable(" ~"))
check("printable: capped at 40", "a" * 40, m.printable("a" * 50))
check("printable: a number", "52.1", m.printable(52.1))
check("printable: the cap counts before replacing", "?" * 40, m.printable("\x1b" * 50))

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
]:
    got, asked = decide(*args)
    check("wants_speed: " + name, (expected, asks), (got, len(asked)))
    check("wants_speed: " + name + " (the question)", [Q] * asks, asked)
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
assert_eq "all 46 pure checks reported" 46 "$PURE"

echo "compiles"
PYTHONPYCACHEPREFIX="$TMP/pyc" python3 -m py_compile "$LOGGER" "$STAND_IN"
assert_eq "the logger and the stand-in compile" 0 "$?"
assert_file_missing "no __pycache__ beside the script" "$HERE/../__pycache__"
assert_file_missing "no __pycache__ beside the tests" "$HERE/__pycache__"

finish_tests
