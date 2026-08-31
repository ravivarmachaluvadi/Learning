"""
Experiment 02 — round trips, not bytes, dominate latency.

Proves the claim from README §10.4: a screen built from several DEPENDENT
REST calls pays one network round trip per call, so an aggregated single
call (the GraphQL idea) wins by roughly the number of calls — even though
it transfers the same data.

The server sleeps 30 ms per request to simulate realistic client↔server
network latency (a phone on 4G is often 50–300 ms; we're being kind).

Run:  python3 experiments/02_round_trips.py
"""

import json
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 8472
SIMULATED_RTT = 0.030  # seconds of network latency per request

DATA = {
    "/me":             {"id": 1, "name": "Ravi", "active_order": 97},
    "/orders/97":      {"id": 97, "status": "COOKING", "restaurant": 42, "rider": 7},
    "/restaurants/42": {"id": 42, "name": "Biryani House"},
    "/riders/7":       {"id": 7, "lat": 18.52, "lng": 73.85},
}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        time.sleep(SIMULATED_RTT)  # the network commute — paid PER REQUEST
        if self.path == "/home-screen":
            # The aggregated "one query, exactly the screen's shape" endpoint.
            payload = {
                "me": DATA["/me"],
                "activeOrder": {**DATA["/orders/97"],
                                "restaurant": DATA["/restaurants/42"],
                                "rider": DATA["/riders/7"]},
            }
        else:
            payload = DATA.get(self.path, {})
        body = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


def get(path):
    with urllib.request.urlopen(f"http://127.0.0.1:{PORT}{path}") as r:
        return json.loads(r.read())


def rest_chatty():
    """4 calls; each NEEDS the previous response, so none can run in parallel."""
    me = get("/me")                                   # round trip 1
    order = get(f"/orders/{me['active_order']}")      # round trip 2 (needs #1)
    get(f"/restaurants/{order['restaurant']}")        # round trip 3 (needs #2)
    get(f"/riders/{order['rider']}")                  # round trip 4 (needs #2)


def aggregated():
    """One round trip carrying exactly the screen's shape."""
    get("/home-screen")


if __name__ == "__main__":
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()

    t0 = time.perf_counter()
    rest_chatty()
    chatty_ms = (time.perf_counter() - t0) * 1000

    t0 = time.perf_counter()
    aggregated()
    agg_ms = (time.perf_counter() - t0) * 1000

    server.shutdown()

    print("Home screen needs: me + active order + restaurant + rider location")
    print(f"Simulated network latency: {SIMULATED_RTT*1000:.0f} ms per request\n")
    print(f"  4 dependent REST calls : {chatty_ms:7.1f} ms")
    print(f"  1 aggregated call      : {agg_ms:7.1f} ms")
    print(f"  ratio                  : {chatty_ms/agg_ms:7.1f}x\n")
    print("WHAT YOU JUST SAW")
    print("-" * 72)
    print("Same data either way — the difference is purely the number of round")
    print("trips. Dependent calls serialize, so latency multiplies by call count.")
    print("This measured gap IS the argument for GraphQL / aggregated endpoints /")
    print("HTTP/2 multiplexing on chatty mobile screens. (README §7 'round trip',")
    print("§10.4). At a real 200 ms mobile RTT, multiply everything by ~7.")
