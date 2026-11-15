#!/usr/bin/python3
# -*- coding: utf-8 -*-
"""A stand-in for the train's Wi-Fi feed and for a small site, for scripts/test/train-feed-logger.test.sh.

Loopback only: it binds 127.0.0.1 on a free port and writes that port to the file named on the command line.
It is not a test file itself (its name does not end in .test.sh, so run-all.sh does not run it).

    train-feed-stand-in.py PORT_FILE [--cert C --key K] [--die-after N] [--escape]
                           [--missing PART] [--html-part PART]
                           [--site normal|noscript|trickle|brokenscript|bigpage]
                           [--request-log FILE] [--connection-log FILE]

Feed:  GET /api/jsonp/<part>/?callback=cb  ->  cb({...});   (with no callback: ({...});)
       Path prefixes pick a behaviour: /anon/... ignores the callback name, /html/... answers a web page as a
       sign-in portal would, /slow/... answers after three seconds, /big/... pads each answer to 150,000 bytes
       (valid), /huge/... to over 250,000 bytes with a Content-Length, /chunked/... the same without one,
       /bigindex/... answers the parts normally but its index (/api/jsonp/) with 250,000 bytes.
       --connection-log writes one line for every connection accepted, including ones whose TLS handshake a
       client then refuses (those never reach a request, so --request-log cannot see them).
Site:  / is a page naming /assets/index-test.js (300,000 bytes, which honours a Range header and sends slowly
       enough for a speed to be measurable); /generate_204 is an empty 204 and /generate_200 a page that answers 200.
"""
import argparse
import json
import os
import ssl
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

START = time.time()
SCRIPT = b"x" * 300000
PAGE = (b'<!doctype html><html><head>'
        b'<script type="module" crossorigin src="/assets/index-test.js"></script>'
        b'</head><body></body></html>')
PAGE_GONE = PAGE.replace(b"index-test.js", b"index-gone.js")  # a release changed the script's hashed name
PAGE_BIG = b"<!doctype html><html><body>" + b"x" * 250000 + b"</body></html>"
PAGE_NO_SCRIPT = b"<!doctype html><html><head></head><body>nothing to download here</body></html>"
SIGN_IN = b"<html><body>Sign in to the train Wi-Fi</body></html>"
PADDING = {"big": 150000, "huge": 250000, "chunked": 250000}
PARTS = ("position", "connectivity", "users", "user", "system")
OPTIONS = None
STATE = {"positions": 0}
LOCK = threading.Lock()


def feed(part):
    t = time.time() - START
    if part == "position":
        latitude = "%.6f" % (52.0 + t * 0.0005)
        if OPTIONS.escape:
            latitude = "\u001b[31m52.1"
        return {"version": "1.6", "time": str(int(time.time())), "age": "0",
                "latitude": latitude, "longitude": "%.6f" % (-0.25 - t * 0.0001),
                "altitude": "23.4", "speed": "55.2", "cmg": "351.2", "satellites": "9", "mode": "3"}
    if part == "connectivity":
        return {"version": "1.6", "online": "1", "bundleid": "101", "bundleip": "10.101.0.1", "links": [
            {"index": "103", "device_type": "modem", "device_subtype": "sierra-7710", "device_state": "up",
             "link_state": "available", "rssi": "-62", "technology": "lte", "operator_id": "23430",
             "apninfo": "everywhere", "umts_info": {"net_status": "registered", "lac": "1234", "cellid": "5678"}},
            {"index": "104", "device_type": "modem", "device_subtype": "sierra-7710", "device_state": "down",
             "link_state": "disconnected", "rssi": "0", "technology": "", "operator_id": "", "apninfo": "",
             "umts_info": {}},
            {"index": "105", "device_type": "modem", "device_subtype": "sierra-7710", "device_state": "up",
             "link_state": "available", "rssi": "-35", "technology": "lte", "operator_id": "23410",
             "apninfo": "mobile.o2.co.uk", "umts_info": {"net_status": "registered", "lac": "22", "cellid": "33"}}]}
    if part == "users":
        return {"version": "1.6", "total": "93", "online": "38"}
    if part == "user":      # invented: nobody has published this part
        return {"version": "1.6", "invented": "1", "dataused": "123456"}
    if part == "system":    # invented: nobody has published this part
        return {"version": "1.6", "invented": "1", "system_id": "12345"}
    return None


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def reply(self, code, content_type, body, extra=None):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        for name, value in (extra or {}).items():
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # noqa: N802 - the name http.server looks for
        path, _, query = self.path.partition("?")
        if OPTIONS.request_log:
            with LOCK, open(OPTIONS.request_log, "a", encoding="utf-8") as log:
                log.write("GET %s\n" % path)
        segments = [s for s in path.split("/") if s]
        mode = ""
        if segments and segments[0] in ("anon", "html", "slow", "big", "huge", "chunked", "bigindex"):
            mode = segments.pop(0)
        if segments[:2] == ["api", "jsonp"]:
            self.feed_request(mode, segments[2] if len(segments) > 2 else "", query)
        elif path == "/":
            pages = {"noscript": PAGE_NO_SCRIPT, "brokenscript": PAGE_GONE, "bigpage": PAGE_BIG}
            page = pages.get(OPTIONS.site, PAGE)
            self.reply(200, "text/html", page)
        elif path == "/assets/index-test.js":
            self.script()
        elif path == "/assets/index-gone.js":
            self.reply(404, "text/plain", b"not found")
        elif path == "/generate_204":
            self.send_response(204)
            self.end_headers()
        elif path == "/generate_200":
            self.reply(200, "text/html", SIGN_IN)
        else:
            self.reply(404, "text/plain", b"not found")

    def feed_request(self, mode, part, query):
        if part == "position" and OPTIONS.die_after is not None:
            with LOCK:
                STATE["positions"] += 1
                dead = STATE["positions"] > OPTIONS.die_after
            if dead:
                self.close_connection = True  # answers nothing: curl reports an empty reply
                return
        if mode == "html" or (part and part == OPTIONS.html_part):
            self.reply(200, "text/html", SIGN_IN)
            return
        if part and part == OPTIONS.missing:
            self.reply(404, "text/plain", b"not found")
            return
        if mode == "slow":
            time.sleep(3)
        if part == "":
            text = "parts: " + " ".join(PARTS)
            self.reply(200, "text/plain", (text + " " * 250000 if mode == "bigindex" else text).encode())
            return
        data = feed(part)
        if data is None:
            self.reply(404, "text/plain", b"no such part")
            return
        callback = ""
        for pair in query.split("&"):
            if pair.startswith("callback="):
                callback = pair[len("callback="):]
        name = "" if mode == "anon" else callback
        if mode in PADDING:
            data = dict(data, pad="x" * PADDING[mode])
        body = ("%s(%s);" % (name, json.dumps(data))).encode()
        if mode == "chunked":  # no Content-Length: the body ends when the connection does
            self.send_response(200)
            self.send_header("Content-Type", "application/javascript")
            self.end_headers()
            self.wfile.write(body)
            return
        self.reply(200, "application/javascript", body)

    def script(self):
        wanted = self.headers.get("Range")
        if wanted != "bytes=0-262143":
            self.reply(200, "application/javascript", SCRIPT)
            return
        body = SCRIPT[:262144]
        self.send_response(206)
        self.send_header("Content-Type", "application/javascript")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Content-Range", "bytes 0-262143/%d" % len(SCRIPT))
        self.end_headers()
        self.wfile.flush()
        if OPTIONS.site == "trickle":
            self.wfile.write(body[:1000])  # some bytes, then a stall
            self.wfile.flush()
            time.sleep(6)
            return
        for start in range(0, len(body), 32768):  # paced, so the transfer takes a measurable time
            self.wfile.write(body[start:start + 32768])
            self.wfile.flush()
            time.sleep(0.03)


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def get_request(self):
        request, address = super().get_request()
        if OPTIONS.connection_log:
            with LOCK, open(OPTIONS.connection_log, "a", encoding="utf-8") as log:
                log.write("connection\n")
        if OPTIONS.cert:
            # The handshake happens on first read, in the connection's own thread, so a refused one cannot
            # block accept.
            request = self.context.wrap_socket(request, server_side=True, do_handshake_on_connect=False)
        return request, address

    def handle_error(self, request, client_address):
        pass  # a client that gives up (a timeout, a refused certificate) is expected here


def main():
    global OPTIONS
    parser = argparse.ArgumentParser()
    parser.add_argument("port_file")
    parser.add_argument("--cert")
    parser.add_argument("--key")
    parser.add_argument("--die-after", type=int, default=None, help="stop answering after this many position requests")
    parser.add_argument("--escape", action="store_true", help="put an escape sequence in the latitude")
    parser.add_argument("--missing", help="this part of the feed answers 404")
    parser.add_argument("--html-part", help="this part of the feed answers a web page")
    parser.add_argument("--site", default="normal",
                        choices=("normal", "noscript", "trickle", "brokenscript", "bigpage"))
    parser.add_argument("--request-log")
    parser.add_argument("--connection-log")
    OPTIONS = parser.parse_args()
    server = Server(("127.0.0.1", 0), Handler)
    if OPTIONS.cert:
        server.context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        server.context.load_cert_chain(OPTIONS.cert, OPTIONS.key)
    with open(OPTIONS.port_file + ".tmp", "w", encoding="utf-8") as out:
        out.write(str(server.server_address[1]))
    # Renamed once complete, so a reader never sees an empty file.
    os.rename(OPTIONS.port_file + ".tmp", OPTIONS.port_file)
    server.serve_forever()
    return 0


if __name__ == "__main__":
    sys.exit(main())
