"""
Experiment 03 — polling vs push (SSE), racing to detect one event.

Proves the claim from README §10.6–10.7: pull pays twice (wasted requests AND
detection delay); push pays neither. One server-side event fires at a random
moment; a 1-second poller and an SSE stream race to notice it.

The SSE client is a RAW SOCKET reading `data:` lines, so you also see the
real wire format of a Server-Sent-Events stream (README §10.7).

Run:  python3 experiments/03_poll_vs_push.py
"""

import json
import random
import socket
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 8473
POLL_INTERVAL = 1.0

event_fired_at = None            # wall-clock time the order status changed
event = threading.Event()        # the server-side "news happened" signal


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        if self.path == "/status":                      # ---- the PULL endpoint
            body = json.dumps({"status": "DELIVERED" if event.is_set()
                               else "COOKING"}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        elif self.path == "/events":                    # ---- the PUSH endpoint
            # An SSE response: normal HTTP that simply never finishes.
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.end_headers()
            self.wfile.flush()
            event.wait()                                # block until news exists
            # The instant there is news, write it. No client request involved.
            self.wfile.write(b'data: {"status": "DELIVERED"}\n\n')
            self.wfile.flush()

    def log_message(self, *a):
        pass


def poller(result):
    """Pull: ask every POLL_INTERVAL seconds until the answer changes."""
    requests = 0
    while True:
        requests += 1
        with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/status") as r:
            status = json.loads(r.read())["status"]
        if status == "DELIVERED":
            result["latency"] = time.perf_counter() - event_fired_at
            result["requests"] = requests
            return
        time.sleep(POLL_INTERVAL)


def sse_listener(result, ready):
    """Push: one request, then just listen. Raw socket so the wire is visible."""
    sock = socket.create_connection(("127.0.0.1", PORT))
    sock.sendall(b"GET /events HTTP/1.1\r\nHost: x\r\n\r\n")
    buf = b""
    while b"\r\n\r\n" not in buf:            # consume the response headers
        buf += sock.recv(1024)
    ready.set()                              # stream is open; race may start
    while b"\n\n" not in buf.split(b"\r\n\r\n", 1)[1]:
        buf += sock.recv(1024)               # block here until the server pushes
    result["latency"] = time.perf_counter() - event_fired_at
    result["wire"] = buf.split(b"\r\n\r\n", 1)[1].decode().strip()
    sock.close()


if __name__ == "__main__":
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()

    poll_result, sse_result = {}, {}
    sse_ready = threading.Event()
    threading.Thread(target=sse_listener, args=(sse_result, sse_ready),
                     daemon=True).start()
    sse_ready.wait()
    t_poll = threading.Thread(target=poller, args=(poll_result,), daemon=True)
    t_poll.start()

    delay = random.uniform(2.0, 5.0)
    print(f"Order placed. Kitchen will finish at a random moment (~{delay:.1f}s)…")
    time.sleep(delay)
    event_fired_at = time.perf_counter()
    event.set()                              # <-- the news happens, exactly once

    t_poll.join(timeout=10)
    time.sleep(0.2)

    print(f"\n  poller  (ask every {POLL_INTERVAL:.0f}s): "
          f"{poll_result['requests']} requests, "
          f"noticed after {poll_result['latency']*1000:7.1f} ms")
    print(f"  SSE push (1 request)   : 1 request,  "
          f"noticed after {sse_result['latency']*1000:7.1f} ms")
    print(f"\n  bytes the SSE client actually received on the wire:")
    print(f"      {sse_result['wire']!r}")

    print("\nWHAT YOU JUST SAW")
    print("-" * 72)
    print("The poller paid TWICE: every no-news request was wasted work, and it")
    print("still noticed late (on average, half the poll interval). The push")
    print("channel spent one request and noticed in ~network time. Multiply the")
    print("poller by 10,000 customers to get README §10.6's prediction. SSE's")
    print("wire format is just 'data:' lines in a never-ending HTTP response.")
    server.shutdown()
