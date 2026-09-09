"""
Experiment 02 — the 304 diet: what validation actually saves (and what it doesn't).

Proves the claims from README §10.2:
  1. A conditional request that gets `304 Not Modified` moves ZERO body bytes
     while still renewing the copy's freshness.
  2. When the content HAS changed, the same conditional request gets a full
     200 with a new ETag — the client never has to guess which case it's in.
  3. Validation still costs a full round trip. Freshness costs nothing at all.
     (That distinction is the whole reason `max-age` exists alongside ETags.)

Run:  python3 experiments/02_the_304_diet.py
"""

import hashlib
import json
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 8483
NETWORK_DELAY = 0.05          # 50 ms one-way-ish, so round trips are visible

menu = {"restaurant": "Biryani House", "biryani": 250,
        "items": ["dosa"] * 800}      # padded so the body is realistically chunky


def current_body():
    return json.dumps(menu).encode()


def current_etag():
    # An ETag is just a fingerprint of the content: same bytes -> same tag.
    # This is the "version: dinner-v2" chalk mark from README §8.
    return '"' + hashlib.sha256(current_body()).hexdigest()[:12] + '"'


class Origin(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        time.sleep(NETWORK_DELAY)
        body, etag = current_body(), current_etag()

        # ---- THE validation check (§10.2). The client told us which version
        # ---- it already holds; if it still matches, we send NO BODY at all.
        if self.headers.get("If-None-Match") == etag:
            self.send_response(304)                      # Not Modified
            self.send_header("ETag", etag)
            self.send_header("Cache-Control", "max-age=300")
            self.end_headers()
            return

        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("ETag", etag)                   # the validator travels
        self.send_header("Cache-Control", "max-age=300") # with the contract
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a): pass


def request(if_none_match=None):
    """Returns (status, body_bytes, header_bytes, etag, elapsed_ms)."""
    req = urllib.request.Request(f"http://127.0.0.1:{PORT}/menu")
    if if_none_match:
        req.add_header("If-None-Match", if_none_match)
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req) as r:
            body = r.read()
            status, headers, etag = r.status, r.headers, r.headers.get("ETag")
    except urllib.error.HTTPError as e:          # urllib raises on 304
        body, status, headers, etag = b"", e.code, e.headers, e.headers.get("ETag")
    ms = (time.perf_counter() - t0) * 1000
    header_bytes = len(str(headers).encode())
    return status, len(body), header_bytes, etag, ms


if __name__ == "__main__":
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), Origin)
    threading.Thread(target=srv.serve_forever, daemon=True).start()

    print("STEP 1 — cold fetch: the cache has nothing, so it asks for everything")
    st, bb, hb, etag, ms = request()
    print(f"  {st} OK    body {bb:6d} bytes + headers {hb:3d}   {ms:5.1f} ms")
    print(f"  stored alongside the body: ETag {etag}\n")
    cold_total = bb + hb

    print("STEP 2 — copy went stale, content UNCHANGED: revalidate")
    st, bb, hb, etag2, ms = request(if_none_match=etag)
    print(f"  → sent  If-None-Match: {etag}")
    print(f"  {st} Not Modified   body {bb:6d} bytes + headers {hb:3d}   {ms:5.1f} ms")
    warm_total = bb + hb
    print(f"  the cache keeps the body it already had; its age clock resets.\n")

    print("STEP 3 — the kitchen raises the price; SAME conditional request")
    menu["biryani"] = 280
    st, bb, hb, etag3, ms = request(if_none_match=etag)
    print(f"  → sent  If-None-Match: {etag}   (now out of date)")
    print(f"  {st} OK    body {bb:6d} bytes + headers {hb:3d}   {ms:5.1f} ms")
    print(f"  new validator: ETag {etag3}\n")

    print("SCOREBOARD")
    print("-" * 72)
    print(f"  full response ..... {cold_total:6d} bytes")
    print(f"  304 revalidation .. {warm_total:6d} bytes"
          f"   ({100 * warm_total / cold_total:.1f}% of a full response)")
    print(f"  bytes saved ....... {cold_total - warm_total:6d}"
          f"   ({100 * (1 - warm_total / cold_total):.1f}% saved)")
    print(f"  round trips ....... 1 either way  (~{ms:.0f} ms — NOT saved)")

    print("\nWHAT YOU JUST SAW")
    print("-" * 72)
    print("Validation is a byte diet, not a latency cure: a 304 carries no body,")
    print("but the client still waited a full round trip to learn that. That is")
    print("exactly why `max-age` exists alongside ETags — while a copy is FRESH,")
    print("no request happens at all (0 bytes, 0 ms). Freshness saves the trip;")
    print("validation saves the payload once the trip is unavoidable.")
    print("Step 3's lesson: the client sends the identical conditional request")
    print("in both cases. It never has to know whether the content changed —")
    print("the origin decides, and answers 304 or 200 accordingly. (§10.2)")
    srv.shutdown()
