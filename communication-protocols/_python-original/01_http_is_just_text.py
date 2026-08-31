"""
Experiment 01 — HTTP is just text over a TCP socket (and framing is real).

Proves two claims from README §4 and §10.2:
  1. An HTTP request is literally text you could type; any program that can
     write bytes to a socket can speak it.
  2. A socket is a byte STREAM, not a message pipe: one recv() call has no
     relationship to one message. The receiver must find message boundaries
     itself — HTTP does it with the blank line + Content-Length.

Run:  python3 experiments/01_http_is_just_text.py
"""

import json
import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 8471


class MenuHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"  # keep-alive: one connection, many requests

    def do_GET(self):
        if self.path == "/menu":
            body = json.dumps({"restaurant": "Biryani House",
                               "items": [{"dosa": 120}, {"biryani": 250}]}).encode()
        else:
            body = json.dumps({"notice": "closing soon"}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))  # <- the framing promise
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):  # keep the demo output clean
        pass


def start_server():
    server = ThreadingHTTPServer(("127.0.0.1", PORT), MenuHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def part1_handwritten_http():
    print("=" * 72)
    print("PART 1 — speak HTTP with a raw socket, no library")
    print("=" * 72)

    # A raw OS socket: the same primitive from README §3.
    sock = socket.create_connection(("127.0.0.1", PORT))

    request = (
        "GET /menu HTTP/1.1\r\n"
        "Host: 127.0.0.1\r\n"
        "Connection: close\r\n"
        "\r\n"                      # blank line = "headers done" — HTTP's framing
    )
    print("\n--- bytes we WRITE into the socket (hand-typed HTTP) ---")
    print(request.replace("\r\n", "\\r\\n\n"))
    sock.sendall(request.encode())

    print("--- bytes the server writes BACK (read until it closes) ---")
    raw = b""
    while chunk := sock.recv(4096):
        raw += chunk
    print(raw.decode())
    sock.close()
    print("No HTTP library was involved. HTTP is text + discipline over TCP.\n")


def part2_framing():
    print("=" * 72)
    print("PART 2 — one recv() is NOT one message (the framing problem, live)")
    print("=" * 72)

    sock = socket.create_connection(("127.0.0.1", PORT))
    # Pipeline TWO requests in a single write. Two logical exchanges, one stream.
    two_requests = (
        "GET /menu HTTP/1.1\r\nHost: x\r\n\r\n"
        "GET /notice HTTP/1.1\r\nHost: x\r\n\r\n"
    )
    sock.sendall(two_requests.encode())

    # Read the stream in small gulps to show boundaries don't align with recv().
    print("\nEach line below is what ONE recv(120) call returned:")
    stream = b""
    sock.settimeout(2.0)
    try:
        while True:
            chunk = sock.recv(120)
            if not chunk:
                break
            print(f"  recv -> {len(chunk):3d} bytes: {chunk[:60]!r}...")
            stream += chunk
            if stream.count(b"\r\n\r\n") >= 2 and stream.endswith(b"}"):
                break
    except socket.timeout:
        pass
    sock.close()

    print("\nTwo responses arrived GLUED into one byte stream. To split them we")
    print("do what every HTTP library does: find the blank line, read the")
    print("Content-Length header, count exactly that many body bytes:\n")

    rest = stream
    for i in (1, 2):
        head, rest = rest.split(b"\r\n\r\n", 1)
        length = next(int(line.split(b":")[1]) for line in head.split(b"\r\n")
                      if line.lower().startswith(b"content-length"))
        body, rest = rest[:length], rest[length:]
        print(f"  message {i}: Content-Length={length:3d} -> body {body.decode()}")

    print("\nWHAT YOU JUST SAW")
    print("-" * 72)
    print("TCP delivered every byte, in order — and told us nothing about where")
    print("one message ends. Framing (blank line + Content-Length) is the FIRST")
    print("thing every protocol in this lab defines. (README §4, §10.2)")


if __name__ == "__main__":
    server = start_server()
    try:
        part1_handwritten_http()
        part2_framing()
    finally:
        server.shutdown()
