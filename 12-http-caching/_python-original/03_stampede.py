"""
Experiment 03 — the cache stampede (dogpile), and the ten lines that stop it.

Proves the claim from README §11.4:
  1. When one popular entry expires, EVERY concurrent request misses at the
     same instant and all of them hit the origin together — the cache's whole
     purpose inverted, on a timer you set yourself.
  2. Single-flight (one refill, everyone else waits for it) collapses that
     spike to exactly one origin request, with no extra staleness.

Run:  python3 experiments/03_stampede.py
"""

import threading
import time

CONCURRENT_READERS = 30
ORIGIN_WORK_SECONDS = 0.30      # a real DB query + render


class Origin:
    """Counts not just total hits but the WORST simultaneous load it saw —
    that concurrency number is what actually takes an origin down."""

    def __init__(self):
        self.hits = 0
        self.in_flight = 0
        self.peak_in_flight = 0
        self.lock = threading.Lock()

    def fetch_menu(self):
        with self.lock:
            self.hits += 1
            self.in_flight += 1
            self.peak_in_flight = max(self.peak_in_flight, self.in_flight)
        time.sleep(ORIGIN_WORK_SECONDS)
        with self.lock:
            self.in_flight -= 1
        return {"biryani": 250}


class NaiveCache:
    """Textbook cache-aside: look, miss, fetch, store. Correct — and it
    stampedes, because nothing coordinates the concurrent missers."""

    def __init__(self, origin, ttl):
        self.origin, self.ttl = origin, ttl
        self.value, self.stored_at = None, 0

    def get(self):
        if self.value is not None and time.time() - self.stored_at < self.ttl:
            return self.value, "HIT"
        value = self.origin.fetch_menu()        # <-- every misser arrives here
        self.value, self.stored_at = value, time.time()
        return value, "MISS"


class SingleFlightCache:
    """Same cache plus one rule: only ONE thread may refill a given key; the
    others wait for that refill and reuse its result."""

    def __init__(self, origin, ttl):
        self.origin, self.ttl = origin, ttl
        self.value, self.stored_at = None, 0
        self.refill_lock = threading.Lock()

    def get(self):
        if self.value is not None and time.time() - self.stored_at < self.ttl:
            return self.value, "HIT"
        # ---- SINGLE FLIGHT: the queue forms here, not at the origin. ----
        with self.refill_lock:
            # Re-check inside the lock: while we queued, the winner may have
            # already refilled the entry — then we are a HIT after all.
            if self.value is not None and time.time() - self.stored_at < self.ttl:
                return self.value, "HIT (waited for the refill)"
            value = self.origin.fetch_menu()    # exactly one thread reaches this
            self.value, self.stored_at = value, time.time()
            return value, "MISS (I did the refill)"


def run(cache_cls, label):
    origin = Origin()
    cache = cache_cls(origin, ttl=0.5)
    results = []

    cache.get()                      # warm it up: one legitimate miss
    warm_hits = origin.hits
    time.sleep(0.6)                  # …and now let the entry expire

    def reader(i):
        t0 = time.perf_counter()
        _, outcome = cache.get()
        results.append((i, outcome, (time.perf_counter() - t0) * 1000))

    threads = [threading.Thread(target=reader, args=(i,))
               for i in range(CONCURRENT_READERS)]
    t0 = time.perf_counter()
    for t in threads: t.start()
    for t in threads: t.join()
    wall = time.perf_counter() - t0

    misses = sum(1 for _, o, _ in results if o.startswith("MISS"))
    slowest = max(ms for _, _, ms in results)
    print(f"{label}")
    print(f"  origin requests from the {CONCURRENT_READERS} readers : "
          f"{origin.hits - warm_hits}")
    print(f"  worst simultaneous load the origin saw   : {origin.peak_in_flight}")
    print(f"  readers that went to the origin          : {misses}")
    print(f"  slowest reader                           : {slowest:6.0f} ms")
    print(f"  wall clock for all {CONCURRENT_READERS} readers          : {wall*1000:6.0f} ms\n")
    return origin.hits - warm_hits, origin.peak_in_flight


if __name__ == "__main__":
    print(f"{CONCURRENT_READERS} readers ask for the same menu at the same moment,")
    print(f"one millisecond after the cached copy expired.\n")

    naive_hits, naive_peak = run(NaiveCache, "WITHOUT single-flight (plain cache-aside)")
    sf_hits, sf_peak = run(SingleFlightCache, "WITH single-flight")

    print("WHAT YOU JUST SAW")
    print("-" * 72)
    print(f"The naive cache turned one expiry into {naive_hits} identical origin")
    print(f"requests, {naive_peak} of them running at once — a load spike shaped")
    print("like your TTL, arriving on schedule forever. Nothing was wrong with")
    print("any single line of that cache: the bug is that N missers were allowed")
    print("to miss independently.")
    print(f"\nSingle-flight made it {sf_hits} origin request. The other readers")
    print("queued on a lock in YOUR process (cheap) instead of on the origin's")
    print("database connections (expensive), and got the same fresh answer.")
    print("\nTwo production siblings of this fix, from README §11.4:")
    print("  • stale-while-revalidate — don't even make them wait: serve the")
    print("    slightly-stale copy instantly, refresh in the background.")
    print("  • TTL jitter — so a thousand DIFFERENT keys don't all expire on")
    print("    the same second and stampede together.")
