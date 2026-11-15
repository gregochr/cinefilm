#!/usr/bin/python3
# -*- coding: utf-8 -*-
"""Train feed logger.

A measuring script, not part of the CineFilm app (see docs/engineering/train-feed-logger.md). Run it on a
train's Wi-Fi (double-click a copy named train-feed-logger.command, or `python3 train-feed-logger.py`) and leave
its window open for the journey.

Every 30 seconds it asks the train's own Wi-Fi system (Icomera's local feed at www.ombord.info, a name that
exists only on the train) where the train is, how strong each of its mobile connections is and how many
passengers are online, and times one tiny request to the internet. In its first round it also reads this Mac's own Wi-Fi
link, once: reading it can make the Mac look for other networks, which would disturb the film if repeated.
When no film will be playing it can also time a download of the first 256 KB of CineFilm's own page code
(it asks at the start; --film and --no-film answer in advance). Each round is one line of JSON in
~/train-feed-logger/logs, written at once, so nothing is lost if the window closes. The log holds where the train
went, so it is never written beside this file: a run from a git checkout must not put it in the repository.

It only reads: it sends nothing but those requests, changes nothing, and stops when its window is closed (or
with Ctrl-C). While it runs it keeps the Mac from idling to sleep. Feed data is untrusted: what is printed has
every character outside printable ASCII replaced, while the log keeps the raw values.
"""
import argparse
import json
import os
import platform
import re
import signal
import subprocess
import sys
import threading
import time
from urllib.parse import urljoin
from datetime import datetime, timezone

VERSION = 2
DEFAULT_BASES = ["https://www.ombord.info/api/jsonp/", "http://www.ombord.info/api/jsonp/"]
# "position" comes first: it is the one that decides whether an address is answering.
PARTS = ["position", "connectivity", "users", "user", "system"]
DEFAULT_PROBE = "https://www.gstatic.com/generate_204"
CURL = "/usr/bin/curl"
MARK = "\n@@train-feed-logger@@ "
DEFAULT_TIMEOUT_S = 8
DEFAULT_SPEED_TIMEOUT_S = 12
SPEED_SITE = "https://cinefilm.chrisjgregory.dev"  # the owner's own server; its page shell is served without a session
SPEED_BYTES = 262144
FEED_MAX_FILESIZE = 1000000  # a hostile or broken feed must not make us hold a huge body
PRINT_MAX = 40
QUESTION = "Will a film be playing on this laptop during the journey? [Y/n] "
PROBE_TIMEOUT_S = 10
MAX_BODY = 60000
RETRY_EVERY = 10  # rounds between tries of an address, or a part of the feed, that is not answering
GIVE_UP_AFTER = 3  # failures in a row before a part of the feed is only tried every RETRY_EVERY rounds
WRAPPED = re.compile(r"^[\w$.]*\s*\((.*)\)\s*;?$", re.S)
CURL_ERRORS = {
    6: "could not find it",
    7: "could not connect",
    28: "timed out",
    35: "secure connection failed",
    51: "certificate not trusted",
    52: "empty answer",
    56: "connection dropped",
    60: "certificate not trusted",
    63: "answer too large",
}
UNTRUSTED = (51, 60)
SCRIPT_SRC = re.compile(r'src="([^"]*)"')


def fetch(url, timeout, insecure=False, max_size=None):
    """One GET with curl. Returns ok, ms, and either code, ip, type, body and headers, or why."""
    cmd = [
        CURL, "-sS", "--max-time", str(timeout), "--connect-timeout", str(min(timeout, 5)),
        "-A", "train-feed-logger/%d" % VERSION, "-D", "/dev/stderr", "-o", "-",
        "-w", MARK + "%{http_code} %{time_total} %{remote_ip} %{content_type}",
    ]
    if insecure:
        cmd.append("-k")
    if max_size:
        cmd += ["--max-filesize", str(max_size)]
    cmd.append(url)
    started = time.time()
    try:
        done = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout + 5)
    except subprocess.TimeoutExpired:
        return {"ok": False, "why": "timed out", "ms": int((time.time() - started) * 1000)}
    except OSError as error:
        return {"ok": False, "why": "could not run curl (%s)" % error, "ms": 0}
    body, _, tail = done.stdout.decode("utf-8", "replace").rpartition(MARK)
    fields = tail.split(" ", 3)
    try:
        ms = int(float(fields[1]) * 1000)
    except (IndexError, ValueError):
        ms = int((time.time() - started) * 1000)
    if done.returncode != 0:
        why = CURL_ERRORS.get(done.returncode, "curl error %d" % done.returncode)
        return {"ok": False, "why": why, "ms": ms, "curl": done.returncode}
    try:
        code = int(fields[0])
    except (IndexError, ValueError):
        code = 0
    result = {
        "ok": 200 <= code < 300,
        "ms": ms,
        "code": code,
        "ip": fields[2] if len(fields) > 2 else "",
        "type": fields[3].strip() if len(fields) > 3 else "",
        "body": body[:MAX_BODY],
        "headers": done.stderr.decode("utf-8", "replace")[:1500],
    }
    if not result["ok"]:
        result["why"] = "answered %d" % code
    return result


def parse_feed(body):
    """The feed's answer as data: plain JSON, or JSON wrapped in "name( ... );" or "( ... );". None otherwise."""
    text = (body or "").strip()
    candidates = [text]
    wrapped = WRAPPED.match(text)
    if wrapped:
        candidates.append(wrapped.group(1))
    for candidate in candidates:
        try:
            data = json.loads(candidate)
        except ValueError:
            continue
        if isinstance(data, (dict, list)):
            return data
    return None


def ask_part(base, part, timeout):
    """Asks one part of the feed. Adds data when the answer is feed data, and tls when the certificate was refused."""
    url = "%s%s/?callback=cb&_=%d" % (base, part, int(time.time() * 1000))
    result = fetch(url, timeout, max_size=FEED_MAX_FILESIZE)
    if not result["ok"] and result.get("curl") in UNTRUSTED:
        # It answers over https, but with a certificate a browser would refuse. Read it anyway: it is only the
        # train's own status, and knowing this is half the point.
        retry = fetch(url, timeout, insecure=True, max_size=FEED_MAX_FILESIZE)
        if retry["ok"]:
            result = retry
            result["tls"] = "untrusted"
    if result["ok"]:
        data = parse_feed(result["body"])
        if data is None:
            result["ok"] = False
            result["why"] = "answered, but not with data"
            result["text"] = result["body"][:300]
        else:
            result["data"] = data
            result["named"] = result["body"].lstrip().startswith("cb(")
    return result


def probe(url):
    """Times one tiny request to the internet."""
    joiner = "&" if "?" in url else "?"
    result = fetch("%s%s_=%d" % (url, joiner, int(time.time() * 1000)), PROBE_TIMEOUT_S)
    if result["ok"]:
        return {"ms": result["ms"], "code": result["code"]}
    return {"ms": None, "why": result["why"]}


def kbps(size, first_byte_s, total_s):
    """Speed over the transfer phase in kbit/s, or None when it was too short to measure (under 0.05 s) or empty."""
    phase = round(total_s - first_byte_s, 6)  # curl reports microseconds; avoids 0.15 - 0.1 landing under 0.05
    if size <= 0 or phase < 0.05:
        return None
    return int(round(size * 8 / 1000 / phase))


def find_script(site, timeout):
    """The address of the site's first script under /assets/, read from its page. Returns (address, why)."""
    page = fetch(site, timeout)
    if not page["ok"]:
        return None, page["why"]
    for value in SCRIPT_SRC.findall(page["body"]):
        if value.endswith(".js") and "/assets/" in value:
            return urljoin(site, value), None
    return None, "page names no script"


def sample_speed(url, timeout):
    """Downloads the first 256 KB of url to nowhere. Returns bytes, ms, first_byte_ms and kbps, and why if it failed."""
    cmd = [
        CURL, "-sS", "--max-time", str(timeout), "--connect-timeout", str(min(timeout, 5)),
        "-A", "train-feed-logger/%d" % VERSION, "-r", "0-%d" % (SPEED_BYTES - 1), "-o", "/dev/null",
        "-w", "%{http_code} %{size_download} %{time_starttransfer} %{time_total}", url,
    ]
    empty = {"bytes": 0, "ms": None, "first_byte_ms": None, "kbps": None}
    try:
        done = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout + 5)
    except subprocess.TimeoutExpired:
        return dict(empty, why="timed out")
    except OSError as error:
        return dict(empty, why="could not run curl (%s)" % error)
    try:
        code, size, first_byte, total = done.stdout.decode("ascii", "replace").split()
        code, size, first_byte, total = int(code), int(size), float(first_byte), float(total)
    except ValueError:
        return dict(empty, why=CURL_ERRORS.get(done.returncode, "curl error %d" % done.returncode))
    result = {
        "bytes": size, "ms": int(total * 1000), "first_byte_ms": int(first_byte * 1000),
        "kbps": kbps(size, first_byte, total),
    }
    if done.returncode != 0:
        result["why"] = CURL_ERRORS.get(done.returncode, "curl error %d" % done.returncode)
    elif not 200 <= code < 300:
        result["why"] = "answered %d" % code
    return result


def wants_speed(film, no_film, speed_site, is_tty, ask):
    """The site to take the speed sample from, or None. Asks only on a terminal, and only when not told."""
    if film:
        return None
    if speed_site:
        return speed_site
    if no_film:
        return SPEED_SITE
    if not is_tty:
        return None
    for _ in range(3):
        answer = ask(QUESTION).strip().lower()
        if answer in ("", "y", "yes"):
            return None
        if answer in ("n", "no"):
            return SPEED_SITE
    return None


def wifi_link():
    """This Mac's own Wi-Fi link (signal, noise, rate), or None. Takes seconds, and is read once."""
    try:
        done = subprocess.run(["/usr/sbin/system_profiler", "SPAirPortDataType", "-json"],
                              stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=12)
        report = json.loads(done.stdout.decode("utf-8", "replace"))
        for interface in report["SPAirPortDataType"][0].get("spairport_airport_interfaces", []):
            current = interface.get("spairport_current_network_information") or {}
            if "spairport_signal_noise" not in current:
                continue
            return {
                "name": current.get("_name"),
                "signal_noise": current.get("spairport_signal_noise"),
                "rate": current.get("spairport_network_rate"),
                "mcs": current.get("spairport_network_mcs"),
                "phy": current.get("spairport_network_phymode"),
                "channel": current.get("spairport_network_channel"),
            }
    except Exception:  # pylint: disable=broad-except
        return None
    return None


def in_threads(jobs):
    """Runs {key: function} side by side and returns {key: result}."""
    results = {}

    def run(key, job):
        try:
            results[key] = job()
        except Exception as error:  # pylint: disable=broad-except
            results[key] = {"ok": False, "why": "failed (%s)" % error, "ms": 0}

    threads = [threading.Thread(target=run, args=item, daemon=True) for item in jobs.items()]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    return results


class Logger:
    def __init__(self, bases, probe_url, read_wifi, timeout=DEFAULT_TIMEOUT_S, speed_site=None,
                 speed_timeout=DEFAULT_SPEED_TIMEOUT_S):
        self.bases = bases
        self.probe_url = probe_url
        self.read_wifi = read_wifi
        self.timeout = timeout
        self.speed_site = speed_site
        self.speed_timeout = speed_timeout
        self.script_url = None  # found once, then kept for the run
        self.speed_from_logged = False
        self.state = {base: None for base in bases}  # None not asked yet, "ok", or why not
        self.active = None
        self.failures = {}
        self.seen_first = set()
        self.indexed = set()

    def first_up(self):
        for base in self.bases:
            if self.state[base] == "ok":
                return base
        return None

    def round(self, n):
        now = time.time()
        record = {
            "t": int(now * 1000),
            "at": datetime.fromtimestamp(now, timezone.utc).isoformat(timespec="seconds"),
            "round": n,
        }
        side = {}
        side_threads = []
        if self.probe_url:
            side_threads.append(threading.Thread(target=lambda: side.update(probe=probe(self.probe_url)), daemon=True))
        if self.read_wifi and n == 1:
            side_threads.append(threading.Thread(target=lambda: side.update(wifi=wifi_link()), daemon=True))
        for thread in side_threads:
            thread.start()

        # Ask every address while none is answering, and again every so often; otherwise only the one in use.
        ask_all = self.active is None or n % RETRY_EVERY == 1
        asked = self.bases if ask_all else [self.active]
        positions = in_threads({base: (lambda base=base: ask_part(base, "position", self.timeout)) for base in asked})
        for base, result in positions.items():
            self.state[base] = "ok" if result["ok"] else result["why"]
        if ask_all:
            record["bases"] = dict(self.state)
        self.active = self.first_up()
        # An address that answered at the last full check but was not asked this time: ask it before relying on it.
        while self.active is not None and self.active not in positions:
            positions[self.active] = ask_part(self.active, "position", self.timeout)
            result = positions[self.active]
            self.state[self.active] = "ok" if result["ok"] else result["why"]
            self.active = self.first_up()
        # What an address said when it answered with something that is not the feed (a sign-in page, say).
        texts = {base: result["text"] for base, result in positions.items() if "text" in result}
        if texts:
            record["bases_text"] = texts

        if self.active is not None:
            base = self.active
            rest = [part for part in PARTS[1:]
                    if self.failures.get(part, 0) < GIVE_UP_AFTER or n % RETRY_EVERY == 1]
            answers = in_threads({part: (lambda part=part: ask_part(base, part, self.timeout)) for part in rest})
            answers["position"] = positions[base]
            feed, ms, errs, first = {}, {}, {}, {}
            for part in PARTS:
                if part not in answers:
                    continue
                result = answers[part]
                ms[part] = result["ms"]
                if result["ok"]:
                    feed[part] = result["data"]
                    self.failures[part] = 0
                    if part not in self.seen_first:
                        # Kept once: what a web page would need to know to read this part itself.
                        self.seen_first.add(part)
                        first[part] = {"named": result["named"], "type": result["type"], "headers": result["headers"]}
                else:
                    errs[part] = result["why"]
                    if "text" in result:
                        errs[part + "_text"] = result["text"]
                    self.failures[part] = self.failures.get(part, 0) + 1
            record["base"] = base
            record["ip"] = positions[base].get("ip")
            if positions[base].get("tls"):
                record["tls"] = positions[base]["tls"]
            record["feed"] = feed
            record["ms"] = ms
            if errs:
                record["errs"] = errs
            if first:
                record["first"] = first
            if base not in self.indexed:
                # The feed's own list of what it offers, once.
                self.indexed.add(base)
                index = fetch(base, self.timeout, insecure=bool(record.get("tls")), max_size=FEED_MAX_FILESIZE)
                record["index"] = index["body"][:5000] if index["ok"] else index.get("why")

        for thread in side_threads:
            thread.join(timeout=14)
        if "probe" in side:
            record["probe"] = side["probe"]
        if side.get("wifi"):
            record["wifi"] = side["wifi"]
        if self.speed_site:
            # Last, so it cannot disturb the timings above.
            self.take_speed_sample(record)
        return record

    def take_speed_sample(self, record):
        if self.script_url is None:
            self.script_url, why = find_script(self.speed_site, self.timeout)
            if self.script_url is None:
                record["speed"] = {"why": why}
                return
        if not self.speed_from_logged:
            self.speed_from_logged = True
            record["speed_from"] = self.script_url
        record["speed"] = sample_speed(self.script_url, self.speed_timeout)


def has(value):
    return value is not None and value != ""


def printable(value):
    """Text that is safe to print: feed data is untrusted and a terminal obeys escape sequences."""
    return "".join(c if " " <= c <= "~" else "?" for c in str(value)[:PRINT_MAX])


def scheme(base):
    return printable(base.split(":", 1)[0])


def status_line(record, state):
    parts = [datetime.fromtimestamp(record["t"] / 1000).strftime("%H:%M:%S")]
    if "feed" in record:
        trust = ", certificate not trusted" if record.get("tls") else ""
        parts.append("feed answering (%s%s)" % (scheme(record["base"]), trust))
        feed = record["feed"]
        position = feed.get("position")
        if isinstance(position, dict) and has(position.get("latitude")) and has(position.get("longitude")):
            parts.append("at %s, %s" % (printable(position["latitude"]), printable(position["longitude"])))
        users = feed.get("users")
        if isinstance(users, dict) and has(users.get("online")):
            total = " of %s" % printable(users["total"]) if has(users.get("total")) else ""
            parts.append("%s%s online" % (printable(users["online"]), total))
        connectivity = feed.get("connectivity")
        if isinstance(connectivity, dict) and isinstance(connectivity.get("links"), list):
            links = []
            for link in connectivity["links"][:12]:
                if not isinstance(link, dict):
                    continue
                kind = link.get("technology") if has(link.get("technology")) else link.get("link_state", "?")
                links.append("%s %s" % (printable(kind), printable(link.get("rssi", ""))))
            parts.append("links: " + ", ".join(links))
    else:
        reasons = ", ".join("%s: %s" % (scheme(base), printable(why or "not asked")) for base, why in state.items())
        parts.append("feed not answering (%s)" % reasons)
    probed = record.get("probe")
    if probed:
        parts.append("internet %s" % ("%d ms" % probed["ms"] if probed["ms"] is not None else printable(probed["why"])))
    speed = record.get("speed")
    if speed:
        if speed.get("why"):
            parts.append("speed: %s" % printable(speed["why"]))
        elif speed.get("kbps") is not None:
            parts.append("speed %.1f Mbit/s" % (speed["kbps"] / 1000))
        elif speed.get("bytes"):
            parts.append("speed too fast to measure")
        else:
            parts.append("speed: nothing received")
    return "  |  ".join(parts)


def main():
    parser = argparse.ArgumentParser(description="Logs a train's own Wi-Fi feed to a file, every half minute.")
    parser.add_argument("--base", action="append", help="a feed address ending in /api/jsonp/ (for trying it out)")
    parser.add_argument("--interval", type=float, default=30, help="seconds between rounds (default 30)")
    parser.add_argument("--probe", default=DEFAULT_PROBE, help="the internet check's address, or off")
    parser.add_argument("--rounds", type=int, default=0, help="stop after this many rounds (default: run on)")
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT_S,
                        help="seconds allowed for each feed request (default %g)" % DEFAULT_TIMEOUT_S)
    # Never beside the script: the log holds where the train went and must not land in a checkout.
    parser.add_argument("--log-dir", default=os.path.expanduser("~/train-feed-logger/logs"),
                        help="where the log goes (default ~/train-feed-logger/logs)")
    parser.add_argument("--no-wifi", action="store_true", help="do not read this Mac's own Wi-Fi link")
    parser.add_argument("--no-awake", action="store_true", help="do not keep the Mac from idling to sleep")
    parser.add_argument("--speed-site", help="a site root to time a download from, when no film is playing")
    parser.add_argument("--no-film", action="store_true", help="no film will play: time a download from %s" % SPEED_SITE)
    parser.add_argument("--film", action="store_true", help="a film will play: take no speed sample and do not ask")
    parser.add_argument("--speed-timeout", type=float, default=DEFAULT_SPEED_TIMEOUT_S,
                        help="seconds allowed for the speed sample (default %g)" % DEFAULT_SPEED_TIMEOUT_S)
    args = parser.parse_args()
    if args.speed_site and not re.match(r"https?://", args.speed_site):
        parser.error("--speed-site must start with http:// or https://")

    bases = [base if base.endswith("/") else base + "/" for base in (args.base or DEFAULT_BASES)]
    probe_url = None if args.probe == "off" else args.probe
    interval = max(1.0, args.interval)

    def ask(question):
        try:
            return input(question)
        except EOFError:
            return ""  # no one to answer: assume a film

    try:
        speed_site = wants_speed(args.film, args.no_film, args.speed_site,
                                 sys.stdin.isatty() and sys.stdout.isatty(), ask)
    except KeyboardInterrupt:
        return 0

    os.makedirs(args.log_dir, exist_ok=True)
    path = os.path.join(args.log_dir, "train-feed-%s.jsonl" % datetime.now().strftime("%Y%m%d-%H%M%S"))
    log = open(path, "a", encoding="utf-8")  # pylint: disable=consider-using-with

    def write(record):
        log.write(json.dumps(record, separators=(",", ":")) + "\n")
        log.flush()
        os.fsync(log.fileno())

    stop = threading.Event()
    for name in ("SIGINT", "SIGTERM", "SIGHUP"):
        signal.signal(getattr(signal, name), lambda *_: stop.set())

    if not args.no_awake:
        try:
            # Ends by itself when this script does.
            subprocess.Popen(["/usr/bin/caffeinate", "-i", "-w", str(os.getpid())])  # pylint: disable=consider-using-with
        except OSError:
            pass

    print("Train feed logger. Leave this window open for the journey, and close it when you get off.")
    print("Log: %s" % path)
    print("Asking the train's Wi-Fi system every %g seconds. Away from a train it says the feed is not answering:" % interval)
    print("that is right, and the internet check still runs.")
    if speed_site:
        print("Speed sample: each round downloads the first 256 KB of a script from %s to time the link." % printable(speed_site))
        print("Run it only when no film is playing; it would compete with one.")
    print("")
    sys.stdout.flush()

    now = time.time()
    write({
        "t": int(now * 1000), "event": "start", "tool": "train-feed-logger", "version": VERSION,
        "bases": bases, "parts": PARTS, "probe": probe_url, "interval": interval,
        "speed_site": speed_site, "timeout": args.timeout,
        "macos": platform.mac_ver()[0], "python": platform.python_version(),
    })

    logger = Logger(bases, probe_url, not args.no_wifi, args.timeout, speed_site, args.speed_timeout)
    next_at = time.time()
    n = 0
    while not stop.is_set():
        n += 1
        try:
            record = logger.round(n)
        except Exception as error:  # pylint: disable=broad-except
            record = {"t": int(time.time() * 1000), "round": n, "error": str(error)[:300]}
        if stop.is_set():
            break
        write(record)
        print(status_line(record, logger.state) if "error" not in record else "round failed: %s" % record["error"])
        sys.stdout.flush()
        if args.rounds and n >= args.rounds:
            break
        next_at += interval
        now = time.time()
        if next_at < now:
            next_at = now  # fell behind: the Mac slept, or a round ran long
        stop.wait(next_at - now)

    write({"t": int(time.time() * 1000), "event": "stop", "rounds": n})
    log.close()
    try:
        print("")
        print("Stopped after %d rounds. The log is at %s" % (n, path))
    except OSError:
        pass  # The window has gone: the log is already written.
    return 0


if __name__ == "__main__":
    sys.exit(main())
