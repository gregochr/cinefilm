#!/usr/bin/python3
# -*- coding: utf-8 -*-
"""Train feed logger.

A measuring script, not part of the CineFilm app (see docs/engineering/train-feed-logger.md). Run it on a
train's Wi-Fi (double-click a copy named train-feed-logger.command, or `python3 train-feed-logger.py`) and leave
its window open for the journey.

Every 30 seconds by default it asks the train's own Wi-Fi system (Icomera's local feed at www.ombord.info, a
name that exists only on the train) where the train is, how strong each of its mobile connections is and how
many passengers are online, and times one tiny request to the internet. In its first round it also reads this
Mac's own Wi-Fi link, once: reading it can make the Mac look for other networks, which would disturb the film if
repeated. When no film will be playing it can also time a download of the first 256 KB, and never more, of
CineFilm's own page code (it asks at the start, and assumes a film if nobody answers within a minute; --film and
--no-film answer in advance). Each round is one line of JSON in ~/train-feed-logger/logs, written at once, so
nothing is lost if the window closes. The log holds where the train went, so its place is fixed and no option
moves it: always ~/train-feed-logger/logs, never beside this file, so a run from a checkout cannot put it in the
repository.

It only reads: it sends nothing but those requests, changes nothing, and stops when its window is closed (or
with Ctrl-C). While it runs it keeps the Mac from idling to sleep. Feed data is untrusted: what is printed has
every character outside printable ASCII replaced, while the log keeps the raw values. It needs curl 8.4.0 or
newer, which is what stops an answer that has no length at the size limit, and refuses to start with an older one.
"""
import argparse
import json
import math
import os
import platform
import re
import select
import signal
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from urllib.parse import urljoin, urlsplit

VERSION = 3
DEFAULT_BASES = ["https://www.ombord.info/api/jsonp/", "http://www.ombord.info/api/jsonp/"]
# "position" comes first: it is the one that decides whether an address is answering.
PARTS = ["position", "connectivity", "users", "user", "system"]
DEFAULT_PROBE = "https://www.gstatic.com/generate_204"
CURL = "/usr/bin/curl"
# The laptop's own state, each tool by its absolute path and never through a shell.
SYSCTL = "/usr/sbin/sysctl"
PMSET = "/usr/bin/pmset"
OSASCRIPT = "/usr/bin/osascript"
SIDE_WAIT_S = 14  # how long a round waits for each side thread (the internet check, the laptop's read)
TOOL_TIMEOUT_S = 2  # for each tool; six calls one after another stay inside SIDE_WAIT_S
TOOL_MAX_BYTES = 20000
TEXT_MAX = 100  # characters of a device text kept
MARK = "\n@@train-feed-logger@@ "
DEFAULT_TIMEOUT_S = 8
DEFAULT_SPEED_TIMEOUT_S = 12
# The owner's own server; its page shell is served without a session.
SPEED_SITE = "https://cinefilm.chrisjgregory.dev"
SPEED_BYTES = 262144
FEED_MAX_BYTES = 50000  # a hostile or broken feed must not make us hold a huge body
PRINT_MAX = 40
QUESTION = "Will a film be playing on this laptop during the journey? [Y/n] "
CLASS_QUESTION = "Standard or first class? [S/f] "
ANSWER_WAIT_S = 60  # the questions, together, must never hold logging up
NO_FILM_NOTE = "\nNo answer: assuming a film will play."
NO_CLASS_NOTE = "\nNo answer: the class is not recorded."
PROBE_TIMEOUT_S = 10
PROBE_MAX_BYTES = 65536  # only the status and the timing are used: the body is never kept
TYPE_KEPT = 200  # characters of a content type kept in the log
TEXT_KEPT = 300  # characters of a non-feed answer (a sign-in page, say) kept in the log
INDEX_KEPT = 5000
DEFAULT_RETRY_EVERY = 10  # rounds between full checks of every address and of parts that keep failing
GIVE_UP_AFTER = 3  # failures in a row before a part of the feed is only tried every --retry-every rounds
# Deeper than this is not feed data: the real feed is four levels deep, the interpreter's own limit differs between
# Python versions, and a structure it accepts could still be too deep to write back out as JSON.
MAX_DEPTH = 32
MIN_CURL = (8, 4, 0)
WRAPPED = re.compile(r"^[\w$.]*\s*\((.*)\)\s*;?$", re.S)
CURL_ERRORS = {
    1: "only http and https are asked",
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
# One fixed script, passed as one argument with nothing put into it. It never asks for the network's name, the access
# point's address, the laptop's hardware address or the country code, and never starts a scan: add no property to it.
WIFI_SCRIPT = """ObjC.import('CoreWLAN');
function run() {
  try {
    var i = $.CWWiFiClient.sharedWiFiClient.interface;
    if (!i || i.isNil()) { return 'null'; }
    var c = i.wlanChannel;
    var has = c && !c.isNil();
    return JSON.stringify({
      on: Boolean(i.powerOn),
      rssi: Number(i.rssiValue),
      noise: Number(i.noiseMeasurement),
      rate: Number(i.transmitRate),
      phy: Number(i.activePHYMode),
      channel: has ? Number(c.channelNumber) : null,
      band: has ? Number(c.channelBand) : null,
      width: has ? Number(c.channelWidth) : null
    });
  } catch (e) { return 'null'; }
}
"""
WIFI_KEYS = ["wifi_rssi", "wifi_noise", "wifi_rate", "wifi_phy", "wifi_band", "wifi_width", "wifi_channel"]


def curl_command(url, timeout, *options):
    """The curl command for one GET. Only http and https, and no redirect is followed: the address asked is the
    address the owner gave (or the page's own, for the speed sample)."""
    return [CURL, "-sS", "--proto", "=http,https", "--max-time", str(timeout),
            "--connect-timeout", str(min(timeout, 5)), "-A", "train-feed-logger/%d" % VERSION, *options, url]


def fetch(url, timeout, insecure=False, max_size=None):
    """One GET with curl. Returns ok, ms, and either code, ip, type, body and headers, or why."""
    options = ["-D", "/dev/stderr", "-o", "-", "-w", MARK + "%{http_code} %{time_total} %{remote_ip} %{content_type}"]
    if insecure:
        options.append("-k")
    if max_size:
        # From curl 8.4.0 this stops a body that has no length too, which is why an older curl is refused at the start.
        options += ["--max-filesize", str(max_size)]
    cmd = curl_command(url, timeout, *options)
    started = time.time()
    try:
        done = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout + 5)
    except subprocess.TimeoutExpired:
        return {"ok": False, "why": "timed out", "ms": int((time.time() - started) * 1000)}
    except (OSError, ValueError) as error:  # defensive: a NUL in an address (find_script filters those out first)
        return {"ok": False, "why": "could not run curl (%s)" % error, "ms": 0}
    raw_body, _, raw_tail = done.stdout.rpartition(MARK.encode())
    body, tail = raw_body.decode("utf-8", "replace"), raw_tail.decode("utf-8", "replace")
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
        "type": fields[3].strip()[:TYPE_KEPT] if len(fields) > 3 else "",
        "body": body,
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
        except (ValueError, RecursionError):  # nesting the interpreter itself refuses raises the second
            continue
        if isinstance(data, (dict, list)) and not too_deep(data):
            return data
    return None


def too_deep(data):
    """Whether dicts and lists are nested more than MAX_DEPTH levels (each one counts as a level).
    A loop with its own stack: no recursion, and it stops as soon as the limit is passed."""
    stack = [(data, 1)]
    while stack:
        value, depth = stack.pop()
        if depth > MAX_DEPTH:
            return True
        children = value.values() if isinstance(value, dict) else value
        stack.extend((child, depth + 1) for child in children if isinstance(child, (dict, list)))
    return False


def ask_part(base, part, timeout, untrusted=False):
    """Asks one part of the feed. Adds data when the answer is feed data, and tls when the certificate was refused
    and the answer was read anyway.

    untrusted: the address is marked as one whose certificate is refused, so read it anyway at once. Such a read
    says nothing about the certificate, so it never carries tls: the mark is the Logger's alone."""
    url = "%s%s/?callback=cb&_=%d" % (base, part, int(time.time() * 1000))
    result = fetch(url, timeout, insecure=untrusted, max_size=FEED_MAX_BYTES)
    if not untrusted and not result["ok"] and result.get("curl") in UNTRUSTED:
        # It answers over https, but with a certificate a browser would refuse. Read it anyway: it is only the
        # train's own status, and knowing this is half the point.
        retry = fetch(url, timeout, insecure=True, max_size=FEED_MAX_BYTES)
        if retry["ok"]:
            result = retry
            result["tls"] = "untrusted"
    if result["ok"]:
        data = parse_feed(result["body"])
        if data is None:
            result["ok"] = False
            result["why"] = "answered, but not with data"
            result["text"] = result["body"][:TEXT_KEPT]
        else:
            result["data"] = data
            result["named"] = result["body"].lstrip().startswith("cb(")
    return result


def curl_version():
    """The version of curl as a tuple of numbers, read from the first line of `curl --version`, or None."""
    try:
        done = subprocess.run([CURL, "--version"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=5)
    except (OSError, subprocess.TimeoutExpired):
        return None
    found = re.match(r"curl (\d+(?:\.\d+)*)", done.stdout.decode("utf-8", "replace"))
    return tuple(int(part) for part in found.group(1).split(".")) if found else None


def curl_problem(version):
    """None when curl is new enough (MIN_CURL or newer), otherwise the sentence to print."""
    need = ("This needs curl 8.4.0 or newer, because an older curl cannot stop an answer that has no length "
            "at the size limit")
    if version is None:
        return "%s, and the version of %s could not be read." % (need, CURL)
    if (tuple(version) + (0, 0, 0))[:3] >= MIN_CURL:  # as numbers: 8.10.1 is newer than 8.4.0
        return None
    return "%s, and %s is %s." % (need, CURL, ".".join(str(part) for part in version))


def timed_curl(url, timeout, *options):
    """Runs curl with its body thrown away and returns what it reports (code, size, first, total, curl: the exit
    status), or just why when it could not run or reported nothing usable."""
    cmd = curl_command(url, timeout, "-o", "/dev/null",
                       "-w", "%{http_code} %{size_download} %{time_starttransfer} %{time_total}", *options)
    try:
        done = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout + 5)
    except subprocess.TimeoutExpired:
        return {"why": "timed out"}
    except (OSError, ValueError) as error:  # defensive: a NUL in an address (find_script filters those out first)
        return {"why": "could not run curl (%s)" % error}
    try:
        code, size, first, total = done.stdout.decode("ascii", "replace").split()
        return {"code": int(code), "size": int(size), "first": float(first), "total": float(total),
                "curl": done.returncode}
    except ValueError:
        return {"why": CURL_ERRORS.get(done.returncode, "curl error %d" % done.returncode)}


def probe(url):
    """Times one tiny request to the internet. Its body is never kept, and curl stops one past PROBE_MAX_BYTES."""
    joiner = "&" if "?" in url else "?"
    got = timed_curl("%s%s_=%d" % (url, joiner, int(time.time() * 1000)), PROBE_TIMEOUT_S,
                     "--max-filesize", str(PROBE_MAX_BYTES))
    if "why" in got:
        return {"ms": None, "why": got["why"]}
    # A status that was received decides first, whatever else curl reports (a 404 that then stalls is a 404).
    if got["code"] and not 200 <= got["code"] < 300:
        return {"ms": None, "why": "answered %d" % got["code"]}
    if got["curl"] not in (0, 63):
        return {"ms": None, "why": CURL_ERRORS.get(got["curl"], "curl error %d" % got["curl"])}
    if not 200 <= got["code"] < 300:
        return {"ms": None, "why": "answered %d" % got["code"]}
    if got["curl"] == 63:  # too big: the status was still received
        return {"ms": int(got["first"] * 1000), "code": got["code"], "body": "not read"}
    return {"ms": int(got["total"] * 1000), "code": got["code"]}


def kbps(size, first_byte_s, total_s):
    """Speed over the transfer phase in kbit/s, or None when it was too short to measure (under 0.05 s) or empty."""
    phase = round(total_s - first_byte_s, 6)  # curl reports microseconds; avoids 0.15 - 0.1 landing under 0.05
    if size <= 0 or phase < 0.05:
        return None
    return int(round(size * 8 / 1000 / phase))


def origin(url):
    """(scheme, host, port) of an address, with the default port filled in; None when it cannot be read."""
    try:
        parts = urlsplit(url)
        return parts.scheme, (parts.hostname or "").lower(), parts.port or {"http": 80, "https": 443}.get(parts.scheme)
    except ValueError:
        return None


def same_origin(first, second):
    return origin(first) is not None and origin(first) == origin(second)


def find_script(site, timeout):
    """The address of the site's first script under /assets/ on the site's own origin, read from its page: a script
    anywhere else is skipped, or the sample would fetch from a host nobody chose. Returns (address, why)."""
    page = fetch(site, timeout, max_size=FEED_MAX_BYTES)
    if not page["ok"]:
        return None, page["why"]
    elsewhere = False
    for value in SCRIPT_SRC.findall(page["body"]):
        if value.endswith(".js") and "/assets/" in value and all(" " <= c <= "~" for c in value):
            try:
                address = urljoin(site, value)
            except ValueError:  # such as an invalid IPv6 address: a broken page must not stop the sample for good
                continue
            if same_origin(address, site):
                return address, None
            elsewhere = True
    return None, "page names no script on its own site" if elsewhere else "page names no script"


def sample_speed(url, timeout):
    """Downloads the first 256 KB of url to nowhere, and never more: curl refuses a longer answer, which is what a
    site that ignores the range sends. Returns bytes, ms, first_byte_ms and kbps, and why if it failed."""
    got = timed_curl(url, timeout, "-r", "0-%d" % (SPEED_BYTES - 1), "--max-filesize", str(SPEED_BYTES))
    if "why" in got:
        return {"bytes": 0, "ms": None, "first_byte_ms": None, "kbps": None, "why": got["why"]}
    result = {
        "bytes": got["size"], "ms": int(got["total"] * 1000), "first_byte_ms": int(got["first"] * 1000),
        "kbps": kbps(got["size"], got["first"], got["total"]),
    }
    if got["code"] and not 200 <= got["code"] < 300:
        # A status that was received decides first, whatever else curl reports: a 404 that then stalls or drops is
        # still a 404, and the address is forgotten.
        result["why"] = "answered %d" % got["code"]
    elif got["curl"] not in (0, 63):
        result["why"] = CURL_ERRORS.get(got["curl"], "curl error %d" % got["curl"])
    elif not 200 <= got["code"] < 300:  # no status at all
        result["why"] = "answered %d" % got["code"]
    elif got["curl"] == 63:  # a 2xx that curl stopped at the limit
        result["why"] = "the site ignores ranges"
    return result


def read_answer(question, timeout, stdin=None, select_fn=None, unanswered=NO_FILM_NOTE):
    """Prints the question and returns the line typed, or None when nobody answers within timeout or input ends
    (and then prints the note)."""
    stdin = stdin or sys.stdin
    select_fn = select_fn or select.select
    sys.stdout.write(question)
    sys.stdout.flush()
    ready, _, _ = select_fn([stdin], [], [], timeout)
    line = stdin.readline() if ready else ""
    if not line:
        print(unanswered)
        return None
    return line


def limited_asker(allowance, clock, reader):
    """The ask to give wants_speed: all its askings share one allowance of seconds, so a few unclear answers
    cannot hold the start for longer than that."""
    deadline = clock() + allowance

    def ask(question, **more):
        return reader(question, max(0, deadline - clock()), **more)

    return ask


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
        answer = ask(QUESTION)
        if answer is None:  # nobody answered in time, or no input: assume a film
            return None
        answer = answer.strip().lower()
        if answer in ("", "y", "yes"):
            return None
        if answer in ("n", "no"):
            return SPEED_SITE
    return None


def wants_class(option, is_tty, ask):
    """(travel class, where it came from): standard or first, from the option or from the answer; (None, None) when
    nobody said (no answer in time, or no terminal to ask on). An answer starting with f is first, any other is
    standard, an empty one included."""
    if option:
        return option, "option"
    if not is_tty:
        return None, None
    answer = ask(CLASS_QUESTION, unanswered=NO_CLASS_NOTE)
    if answer is None:
        return None, None
    return ("first" if answer.lstrip()[:1] in ("f", "F") else "standard"), "answer"


def run_tool(command):
    """The text a tool prints, or None when it is missing, fails, prints more than TOOL_MAX_BYTES or takes longer
    than TOOL_TIMEOUT_S. No shell, and nothing it prints is passed on as it stands: only what a parser takes from it."""
    deadline = time.monotonic() + TOOL_TIMEOUT_S
    try:
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                   stderr=subprocess.DEVNULL)
    except (OSError, ValueError):
        return None
    got = []

    def read():
        try:
            got.append(process.stdout.read(TOOL_MAX_BYTES + 1))
        except (OSError, ValueError):
            pass

    reader = threading.Thread(target=read, daemon=True)
    reader.start()
    reader.join(max(0, deadline - time.monotonic()))
    done = not reader.is_alive()
    code = None
    if done and got and len(got[0]) <= TOOL_MAX_BYTES:
        try:
            code = process.wait(timeout=max(0, deadline - time.monotonic()))
        except subprocess.TimeoutExpired:
            code = None
    if code is None:  # too slow or too much: it is stopped, never waited for
        try:
            process.kill()
        except OSError:
            pass
        process.wait()
    if done:
        process.stdout.close()
    if code != 0:
        return None
    return got[0].decode("utf-8", "replace")


def device_text(line):
    text = line.strip()
    return text if 1 <= len(text) <= TEXT_MAX and all(" " <= c <= "~" for c in text) else None


def device_number(line):
    text = line.strip()
    return int(text) if re.fullmatch(r"[0-9]{1,18}", text) and int(text) > 0 else None


def parse_device(text):
    """What kind of machine: model, cpu, cores, threads and memory_bytes from the five lines of one sysctl call, each
    null when it is not what it should be. None when the answer is not five lines or nothing in it is usable."""
    if text is None:
        return None
    lines = text.split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    if len(lines) != 5:
        return None
    device = {"model": device_text(lines[0]), "cpu": device_text(lines[1]), "cores": device_number(lines[2]),
              "threads": device_number(lines[3]), "memory_bytes": device_number(lines[4])}
    return device if any(value is not None for value in device.values()) else None


def read_device():
    return parse_device(run_tool([SYSCTL, "-n", "hw.model", "machdep.cpu.brand_string", "hw.physicalcpu",
                                  "hw.logicalcpu", "hw.memsize"]))


def parse_batt(text):
    """(power, battery, charge) from `pmset -g batt`: ac or battery from its first line, the percentage and the
    word after it from its second. The battery's own id is never taken."""
    lines = (text or "").split("\n")
    power = battery = charge = None
    if "'AC Power'" in lines[0][:200]:
        power = "ac"
    elif "'Battery Power'" in lines[0][:200]:
        power = "battery"
    if len(lines) > 1:
        second = lines[1][:500]
        found = re.search(r"(?<![0-9])([0-9]{1,6})%", second)
        if found and int(found.group(1)) <= 100:
            battery = int(found.group(1))
        found = re.search(r"%;\s*([A-Za-z]{1,20})\s*(?:;|$)", second)
        if found:
            charge = found.group(1).lower()
    return power, battery, charge


def parse_low_power(text):
    found = re.search(r"^\s*lowpowermode[ \t]+([01])[ \t]*$", text or "", re.M)
    return int(found.group(1)) if found else None


def parse_cpu_limit(text):
    found = re.search(r"^\s*CPU_Speed_Limit\s*=\s*([0-9]{1,3})\s*$", text or "", re.M)
    return int(found.group(1)) if found and int(found.group(1)) <= 100 else None


def parse_load(text):
    found = re.fullmatch(r"\s*\{\s*([0-9]+(?:\.[0-9]+)?)\s+[0-9]+(?:\.[0-9]+)?\s+[0-9]+(?:\.[0-9]+)?\s*\}\s*",
                         text or "")
    if not found:
        return None
    value = float(found.group(1))
    return round(value, 2) if math.isfinite(value) and value <= 100000 else None


def parse_swap(text):
    found = re.search(r"\bused\s*=\s*([0-9]+(?:\.[0-9]+)?)M\b", text or "")
    if not found:
        return None
    value = float(found.group(1))
    return round(value, 1) if math.isfinite(value) and value <= 100000000 else None


def is_number(value):
    """A number that is not a boolean, a text, nan or infinity (an integer of any size is one: it is just out of range)."""
    return not isinstance(value, bool) and (isinstance(value, int) or (isinstance(value, float) and math.isfinite(value)))


def whole(value, low, high):
    """The number as a whole number inside [low, high]; None for anything else (a boolean and a text included)."""
    if not is_number(value):
        return None
    return int(value) if value == int(value) and low <= value <= high else None


def parse_wifi_light(text):
    """The seven wifi_ fields from the one line the fixed script prints; every one null unless the answer is an
    object whose on is true and the value is a number inside its range."""
    none = {key: None for key in WIFI_KEYS}
    try:
        data = json.loads((text or "").strip())
    except (ValueError, RecursionError):
        return none
    if not isinstance(data, dict) or data.get("on") is not True:
        return none
    rate = data.get("rate")
    if not is_number(rate) or not 0 < rate <= 100000 or round(rate, 1) <= 0:
        rate = None
    else:
        rate = round(rate, 1)
    return {
        "wifi_rssi": whole(data.get("rssi"), -120, -1), "wifi_noise": whole(data.get("noise"), -120, -1),
        "wifi_rate": rate, "wifi_phy": whole(data.get("phy"), 1, 20), "wifi_band": whole(data.get("band"), 1, 20),
        "wifi_width": whole(data.get("width"), 1, 20), "wifi_channel": whole(data.get("channel"), 1, 233),
    }


def read_laptop(with_wifi=True):
    """The laptop's own state, or None when nothing at all could be read (not a Mac). At most six tool calls, one
    after another; a tool that fails gives null for its fields and nothing else."""
    power, battery, charge = parse_batt(run_tool([PMSET, "-g", "batt"]))
    laptop = {
        "power": power, "battery": battery, "charge": charge,
        "low_power": parse_low_power(run_tool([PMSET, "-g"])),
        "cpu_limit": parse_cpu_limit(run_tool([PMSET, "-g", "therm"])),
        "load": parse_load(run_tool([SYSCTL, "-n", "vm.loadavg"])),
        "swap_mb": parse_swap(run_tool([SYSCTL, "-n", "vm.swapusage"])),
    }
    laptop.update(parse_wifi_light(run_tool([OSASCRIPT, "-l", "JavaScript", "-e", WIFI_SCRIPT]) if with_wifi else None))
    return laptop if any(value is not None for value in laptop.values()) else None


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


class Stop:
    """A plain flag. threading.Event.set() takes a lock, and a signal handler runs in the main thread, which may
    be waiting on that same lock: a rare deadlock."""

    def __init__(self):
        self.now = False

    def request(self, *_):
        self.now = True

    def sleep(self, seconds):
        """Sleeps in steps of at most 0.2 s, so a stop request is seen within that."""
        end = time.monotonic() + seconds  # monotonic: the wall clock can jump (a time sync, the Mac waking)
        while not self.now:
            left = end - time.monotonic()
            if left <= 0:
                return
            time.sleep(min(0.2, left))


class Logger:
    def __init__(self, bases, probe_url, read_wifi, timeout=DEFAULT_TIMEOUT_S, speed_site=None,
                 speed_timeout=DEFAULT_SPEED_TIMEOUT_S, retry_every=DEFAULT_RETRY_EVERY, laptop=None):
        self.bases = bases
        self.laptop = laptop  # a function that reads the laptop's state, or None
        self.probe_url = probe_url
        self.read_wifi = read_wifi
        self.timeout = timeout
        self.speed_site = speed_site
        self.speed_timeout = speed_timeout
        self.retry_every = retry_every
        self.script_url = None  # found once, then kept until the site says it is gone
        self.speed_from_logged = False
        self.state = {base: None for base in bases}  # None not asked yet, "ok", or why not
        self.active = None
        self.failures = {}  # per part, for the address in use: they belong to the address
        self.failures_for = None
        # Addresses whose certificate was refused at the last full check: read with -k until the next one.
        self.untrusted = set()
        self.seen_first = set()
        self.indexed = set()

    def first_up(self):
        for base in self.bases:
            if self.state[base] == "ok":
                return base
        return None

    def note(self, base, result, measured=False):
        """Records what an answer says. measured: it was asked without -k, so it also says what the certificate is
        today (a read with -k says nothing, and a failure with no answer says nothing either)."""
        self.state[base] = "ok" if result["ok"] else result["why"]
        if result.get("tls"):
            self.untrusted.add(base)
        elif measured and "code" in result:
            self.untrusted.discard(base)

    def ask(self, base, part, measured=False):
        return ask_part(base, part, self.timeout, base in self.untrusted and not measured)

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
            side_threads.append(threading.Thread(
                target=lambda: side.update(probe=probe(self.probe_url)), daemon=True))
        if self.read_wifi and n == 1:
            side_threads.append(threading.Thread(target=lambda: side.update(wifi=wifi_link()), daemon=True))
        if self.laptop:
            def read_state():
                try:
                    side.update(laptop=self.laptop())
                except Exception:  # pylint: disable=broad-except
                    pass  # an extra: whatever goes wrong in it must not touch the feed

            side_threads.append(threading.Thread(target=read_state, daemon=True))
        for thread in side_threads:
            thread.start()

        # Ask every address while none is answering, and again every so often; otherwise only the one in use.
        retry_round = (n - 1) % self.retry_every == 0
        ask_all = self.active is None or retry_round
        asked = self.bases if ask_all else [self.active]
        # A full check also asks without -k, so a certificate that is accepted again is noticed.
        positions = in_threads({base: (lambda base=base: self.ask(base, "position", ask_all)) for base in asked})
        for base, result in positions.items():
            self.note(base, result, ask_all)
        self.active = self.first_up()
        # An address that answered at the last full check but was not asked this time: ask it before relying on it.
        while self.active is not None and self.active not in positions:
            positions[self.active] = self.ask(self.active, "position")
            self.note(self.active, positions[self.active])
            self.active = self.first_up()
        record["asked"] = [base for base in self.bases if base in positions]
        record["bases"] = dict(self.state)
        # What an address said when it answered with something that is not the feed (a sign-in page, say).
        texts = {base: result["text"] for base, result in positions.items() if "text" in result}
        if texts:
            record["bases_text"] = texts

        if self.active is not None:
            base = self.active
            if base != self.failures_for:
                self.failures, self.failures_for = {}, base
            rest, skipped = [], []
            for part in PARTS[1:]:
                if self.failures.get(part, 0) >= GIVE_UP_AFTER and not retry_round:
                    skipped.append(part)
                else:
                    rest.append(part)
            answers = in_threads({part: (lambda part=part: self.ask(base, part)) for part in rest})
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
            if base in self.untrusted:
                record["tls"] = "untrusted"
            record["feed"] = feed
            record["ms"] = ms
            if errs:
                record["errs"] = errs
            if skipped:
                record["skipped"] = skipped
            if first:
                record["first"] = first
            if base not in self.indexed:
                # The feed's own list of what it offers, once.
                self.indexed.add(base)
                index = fetch(base, self.timeout, insecure=base in self.untrusted, max_size=FEED_MAX_BYTES)
                record["index"] = index["body"][:INDEX_KEPT] if index["ok"] else index.get("why")

        for thread in side_threads:
            thread.join(timeout=SIDE_WAIT_S)
        if "probe" in side:
            record["probe"] = side["probe"]
        if side.get("wifi"):
            record["wifi"] = side["wifi"]
        if side.get("laptop"):
            record["laptop"] = side["laptop"]
        if self.speed_site:
            # Last, so it cannot disturb the timings above. And it fails closed: it is an extra, and the feed is what
            # the journey is for, so nothing that goes wrong in it may throw the rest of the record away.
            try:
                self.take_speed_sample(record)
            except Exception as error:  # pylint: disable=broad-except
                record["speed"] = {"why": "failed (%s: %s)" % (type(error).__name__, str(error)[:100])}
                # Whatever it was may have come from the script's address: read the page again next round.
                self.script_url = None
                self.speed_from_logged = False
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
        if re.match(r"answered 4\d\d$", record["speed"].get("why", "")):
            # A release changes the script's hashed name: read the page again next round.
            self.script_url = None
            self.speed_from_logged = False


def has(value):
    return value is not None and value != ""


def printable(value, limit=PRINT_MAX):
    """Text that is safe to print: feed data is untrusted and a terminal obeys escape sequences."""
    return "".join(c if " " <= c <= "~" else "?" for c in str(value)[:limit])


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
            if links:
                parts.append("links: " + ", ".join(links))
    else:
        reasons = ", ".join("%s: %s" % (scheme(base), printable(why or "not asked")) for base, why in state.items())
        parts.append("feed not answering (%s)" % reasons)
    probed = record.get("probe")
    if probed:
        if probed["ms"] is None:
            parts.append("internet %s" % printable(probed["why"]))
        elif probed.get("code") in (None, 204):
            parts.append("internet %d ms" % probed["ms"])
        else:
            # A sign-in page answers 200 where the real address answers an empty 204.
            parts.append("internet %d ms (answered %s)" % (probed["ms"], printable(probed["code"])))
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


def failed_line(error):
    """The line printed when a round failed as a whole. The text may carry feed data, so it is made printable."""
    return "round failed: %s" % printable(error, 200)


def check_arguments(parser, args):
    """Refuses values that would silently do the wrong thing (no time limit, a stop after one round)."""
    if not args.timeout > 0:
        parser.error("--timeout must be greater than 0")
    if not args.speed_timeout > 0:
        parser.error("--speed-timeout must be greater than 0")
    if not args.interval >= 1:
        parser.error("--interval must be at least 1")
    if args.rounds < 0:
        parser.error("--rounds must be at least 0")
    if args.retry_every < 1:
        parser.error("--retry-every must be at least 1")
    if args.speed_site and not re.match(r"https?://", args.speed_site):
        parser.error("--speed-site must start with http:// or https://")


def main():
    parser = argparse.ArgumentParser(description="Logs a train's own Wi-Fi feed to a file, every half minute.")
    parser.add_argument("--base", action="append", help="a feed address ending in /api/jsonp/ (for trying it out)")
    parser.add_argument("--interval", type=float, default=30, help="seconds between rounds (default 30, at least 1)")
    parser.add_argument("--probe", default=DEFAULT_PROBE,
                        help="the internet check's address, which should answer an empty 204 (its body is never kept), "
                             "or off")
    parser.add_argument("--rounds", type=int, default=0, help="stop after this many rounds (default 0: run on)")
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT_S,
                        help="seconds allowed for each feed request (default %g)" % DEFAULT_TIMEOUT_S)
    parser.add_argument("--retry-every", type=int, default=DEFAULT_RETRY_EVERY,
                        help="rounds between full checks of every address and of parts that keep failing "
                             "(default %d, at least 1)" % DEFAULT_RETRY_EVERY)
    parser.add_argument("--no-wifi", action="store_true", help="do not read this Mac's own Wi-Fi link (neither the "
                                                               "one read in round 1 nor the light one of each round)")
    parser.add_argument("--no-laptop", action="store_true",
                        help="do not read this laptop's kind and state (power, heat, load, memory, Wi-Fi link)")
    parser.add_argument("--class", dest="travel_class", choices=["standard", "first"],
                        help="the class travelled in: answers the question without asking")
    parser.add_argument("--no-awake", action="store_true", help="do not keep the Mac from idling to sleep")
    parser.add_argument("--speed-site", help="a site root to time a download from, when no film is playing "
                                             "(its first script must honour a Range request)")
    parser.add_argument("--no-film", action="store_true",
                        help="no film will play: time a download from %s" % SPEED_SITE)
    parser.add_argument("--film", action="store_true", help="a film will play: take no speed sample and do not ask")
    parser.add_argument("--speed-timeout", type=float, default=DEFAULT_SPEED_TIMEOUT_S,
                        help="seconds allowed for the speed sample (default %g)" % DEFAULT_SPEED_TIMEOUT_S)
    args = parser.parse_args()
    check_arguments(parser, args)
    curl = curl_version()
    problem = curl_problem(curl)
    if problem:
        sys.stderr.write(problem + "\n")
        sys.exit(2)

    bases = [base if base.endswith("/") else base + "/" for base in (args.base or DEFAULT_BASES)]
    probe_url = None if args.probe == "off" else args.probe
    interval = args.interval

    try:
        is_tty = sys.stdin.isatty() and sys.stdout.isatty()
        ask = limited_asker(ANSWER_WAIT_S, time.monotonic, read_answer)  # the questions share one allowance
        speed_site = wants_speed(args.film, args.no_film, args.speed_site, is_tty, ask)
        travel_class, travel_class_from = wants_class(args.travel_class, is_tty, ask)
    except KeyboardInterrupt:
        return

    # As soon as the question is settled, so a stop request before the log exists ends the run cleanly.
    stop = Stop()
    for name in ("SIGINT", "SIGTERM", "SIGHUP"):
        signal.signal(getattr(signal, name), stop.request)

    # The log holds where the train went: readable by the owner alone.
    # Always under HOME and never from the command line: a run from a checkout cannot put it in the repository.
    log_dir = os.path.expanduser("~/train-feed-logger/logs")
    os.makedirs(log_dir, mode=0o700, exist_ok=True)
    path = os.path.join(log_dir, "train-feed-%s.jsonl" % datetime.now().strftime("%Y%m%d-%H%M%S"))
    log = os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600), "a", encoding="utf-8")

    def write(record):
        log.write(json.dumps(record, separators=(",", ":")) + "\n")
        log.flush()
        os.fsync(log.fileno())

    printing = True

    def say(text=""):
        """Prints a line. If the window has gone, stops printing and carries on logging."""
        nonlocal printing
        if not printing:
            return
        try:
            print(text)
            sys.stdout.flush()
        except OSError:
            printing = False
            try:
                # Python would otherwise exit 120 when it fails to flush the closed stdout at shutdown.
                os.dup2(os.open(os.devnull, os.O_WRONLY), sys.stdout.fileno())
            except (OSError, ValueError):
                pass

    if not args.no_awake:
        try:
            # Ends by itself when this script does.
            subprocess.Popen(["/usr/bin/caffeinate", "-i", "-w", str(os.getpid())])  # pylint: disable=consider-using-with
        except OSError:
            pass

    say("Train feed logger. Leave this window open for the journey, and close it when you get off.")
    say("Log: %s" % path)
    say("Asking the train's Wi-Fi system every %g seconds. Away from a train it says the feed is not answering:" % interval)
    say("that is right, and the internet check still runs.")
    if speed_site:
        say("Speed sample: each round downloads the first 256 KB of a script from %s to time the link."
            % printable(speed_site, 200))
        say("Run it only when no film is playing; it would compete with one.")
    say("")

    now = time.time()
    start = {
        "t": int(now * 1000), "event": "start", "tool": "train-feed-logger", "version": VERSION,
        "bases": bases, "parts": PARTS, "probe": probe_url, "interval": interval,
        "speed_site": speed_site, "timeout": args.timeout, "curl": ".".join(str(part) for part in curl),
        "macos": platform.mac_ver()[0], "python": platform.python_version(),
        "travel_class": travel_class, "travel_class_from": travel_class_from,
    }
    if not args.no_laptop:
        start["device"] = read_device()  # once; null when it cannot be read
    write(start)

    logger = Logger(bases, probe_url, not args.no_wifi, args.timeout, speed_site, args.speed_timeout,
                    args.retry_every, None if args.no_laptop else (lambda: read_laptop(not args.no_wifi)))
    next_at = time.monotonic()  # the schedule is not the wall clock; the records keep that
    n = 0
    while not stop.now:
        n += 1
        try:
            record = logger.round(n)
        except Exception as error:  # pylint: disable=broad-except
            record = {"t": int(time.time() * 1000), "round": n, "error": str(error)[:300]}
        if stop.now:
            break
        write(record)
        say(status_line(record, logger.state) if "error" not in record else failed_line(record["error"]))
        if args.rounds and n >= args.rounds:
            break
        next_at += interval
        now = time.monotonic()
        if next_at < now:
            next_at = now  # fell behind: a round ran long (the monotonic clock stands still while the Mac sleeps)
        stop.sleep(next_at - now)

    write({"t": int(time.time() * 1000), "event": "stop", "rounds": n})
    log.close()
    say("")
    say("Stopped after %d rounds. The log is at %s" % (n, path))


if __name__ == "__main__":
    main()
