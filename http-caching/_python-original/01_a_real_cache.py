"""
Experiment 01 — a real, rule-following caching proxy in ~60 lines.

Proves three claims from README §10.1 and §11.1:
  1. A cache turns N identical requests into 1 origin request (hit ratio).
  2. Hits skip the network commute entirely — latency collapses.
  3. A fresh copy serves the OLD data after the origin changes — and that is
     the cache honoring the contract, not a bug.

Run:  python3 experiments/01_a_real_cache.py
"""

import json
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ORIGIN_PORT, PROXY_PORT = 8481, 8482
MAX_AGE = 60
ORIGIN_DELAY = 0.2          # the origin is "far away" (README: RTT dominates)

price = {"biryani": 250}    # the origin's truth — mutable
origin_hits = 0


class Origin(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        global origin_hits
        origin_hits += 1
        time.sleep(ORIGIN_DELAY)
        body = json.dumps({"restaurant": "Biryani House", **price}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        # ---- the reuse contract, attached to the response itself (§10.1) ----
        self.send_header("Cache-Control", f"max-age={MAX_AGE}")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a): pass


class CachingProxy(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    store = {}              # cache key (path) -> entry

    def do_GET(self):
        entry = self.store.get(self.path)
        if entry:
            age = time.time() - entry["stored_at"]
            if age < entry["max_age"]:
                # ---- THE freshness check: age < max-age  →  serve without
                # ---- contacting the origin. This one comparison is HTTP
                # ---- caching's entire performance story. (§10.1)
                return self.reply(entry["body"], hit=True, age=age)
        # miss (or stale with no validator — this proxy has no ETag support yet)
        with urllib.request.urlopen(f"http://127.0.0.1:{ORIGIN_PORT}{self.path}") as r:
            body = r.read()
            cc = r.headers.get("Cache-Control", "")
        max_age = int(cc.split("max-age=")[1].split(",")[0]) if "max-age=" in cc else 0
        # obey the contract: store only what the origin allowed us to store
        if max_age > 0:
            self.store[self.path] = {"body": body, "stored_at": time.time(),
                                     "max_age": max_age}
        self.reply(body, hit=False, age=0)

    def reply(self, body, hit, age):
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("X-Cache", "HIT" if hit else "MISS")   # CDN debug style (§11.6)
        self.send_header("Age", str(int(age)))                  # how old is this copy
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a): pass


def fetch(port, path="/menu"):
    t0 = time.perf_counter()
    with urllib.request.urlopen(f"http://127.0.0.1:{port}{path}") as r:
        body = json.loads(r.read())
        xc = r.headers.get("X-Cache", "—")
        ag = r.headers.get("Age", "0")
    return body, xc, ag, (time.perf_counter() - t0) * 1000


if __name__ == "__main__":
    for port, handler in ((ORIGIN_PORT, Origin), (PROXY_PORT, CachingProxy)):
        srv = ThreadingHTTPServer(("127.0.0.1", port), handler)
        threading.Thread(target=srv.serve_forever, daemon=True).start()

    print("PHASE A — 5 users straight to the origin (no cache)")
    t0 = time.perf_counter(); origin_hits = 0
    for i in range(5):
        body, *_ , ms = fetch(ORIGIN_PORT)
        print(f"  user {i+1}: biryani=₹{body['biryani']}  {ms:6.0f} ms")
    print(f"  => origin answered {origin_hits} times, total {(time.perf_counter()-t0):.1f} s\n")

    print(f"PHASE B — 5 users through the caching proxy (Cache-Control: max-age={MAX_AGE})")
    print(f"  (spaced ~1 s apart so you can watch the copy's Age clock climb)")
    t0 = time.perf_counter(); origin_hits = 0
    for i in range(5):
        body, xc, ag, ms = fetch(PROXY_PORT)
        print(f"  user {i+1}: biryani=₹{body['biryani']}  {ms:6.0f} ms   "
              f"X-Cache: {xc:4s} Age: {ag}s  (fresh for {MAX_AGE - int(ag)} more s)")
        time.sleep(1.05)
    print(f"  => origin answered {origin_hits} time(s) for 5 users\n")

    print("PHASE C — the origin raises the price to ₹280 …")
    price["biryani"] = 280
    time.sleep(0.3)
    body, xc, ag, ms = fetch(PROXY_PORT)
    truth = json.loads(urllib.request.urlopen(
        f"http://127.0.0.1:{ORIGIN_PORT}/menu").read())["biryani"]
    print(f"  origin's truth is now : ₹{truth}")
    print(f"  proxy serves          : ₹{body['biryani']}   X-Cache: {xc}, Age: {ag}s")
    print(f"  …and will keep serving ₹250 until the copy's age reaches {MAX_AGE} s.\n")

    print("WHAT YOU JUST SAW")
    print("-" * 72)
    print("B: one MISS filled the cache; four HITs never touched the origin and")
    print("   returned ~200 ms faster — hit ratio 80% from five requests.")
    print("C: the stale price is NOT a malfunction. max-age=60 was a promise —")
    print("   'reuse freely for 60 s' — and the proxy is keeping it. Staleness")
    print("   is a declared consistency window (README §12), and the origin has")
    print("   no way to recall the copy early (§11.1). Choose max-age like the")
    print("   promise it is.")
