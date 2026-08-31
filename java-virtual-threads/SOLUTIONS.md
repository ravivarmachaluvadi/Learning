# Solutions

## P1 — ~400 ms

The three sleeps all overlap: each sleeper parks, unmounts, and frees the
single carrier, so 100 ms of sleeping happens *once* for everyone. The CPU
spins cannot overlap: computing code occupies the carrier, there is only one
carrier, and the scheduler never preempts — so the three 100 ms spins run
back to back. Total ≈ 100 (shared sleep) + 3 × 100 (serialized CPU) ≈
**400 ms**. Measured on this machine: ~410 ms. If you predicted 200 ms you
gave virtual threads credit for parallel *computation* — they never provide
that; if 600 ms, you missed that *waiting* overlaps.

## P2 — both ≈ 50 ms; the rule

With 8 tasks and 8 pool threads, every task can wait simultaneously in both
setups, so both measure ~50–60 ms. Rule: virtual threads pay off only when
**concurrent blocked tasks > affordable platform threads**. Below that line
the old pool is exactly as good. (This is why low-traffic services see no
gain from enabling them.)

## M1 — no hopping, still fast

With one carrier there is nowhere else to wake up, so every "woke from
sleep" line shows `worker-1`. The program still finishes in ~150 ms because
all six sleeps overlap: each sleeper *releases* the carrier even though it
can only ever *return* to the same one. Releasing (unmount on block) is the
scaling mechanism; hopping between carriers is just an incidental,
load-balancing side effect that proves the release happened.

## M2 — the pool ties, and why that's no fix

(a) With 600 pool threads every task waits at once: measured ≈ 60–80 ms,
essentially tying virtual threads (thread *creation* cost makes it slightly
slower to start). (b) The fix doesn't scale: platform threads are ~1 MB of
stack each and the OS caps them — Demo 1 showed macOS refusing this very
process at **thread #4,044**. "One platform thread per task" dies around
10³–10⁴ concurrency; virtual threads carry on to 10⁵–10⁶. The pool wasn't
wrong because pools are slow — it was rationing a resource that Loom makes
abundant.

## M3 — the test fails: spinning never parks

A CPU spin never calls a blocking operation, so A never parks, never
unmounts, and holds the *only* carrier for the full 300 ms. B cannot run
until A finishes, so `a.isAlive()` is false when B finally checks, and the
assertion fails with "carrier was not released". This is carrier
monopolization (README §10b): **unmounting only happens at blocking points**
— the scheduler cannot rescue you from a thread that never blocks.

## D1 — a *pool* of virtual threads

The bug: `Executors.newFixedThreadPool(8, Thread.ofVirtual().factory())` —
a fixed pool of **8** workers that merely *happen to be virtual*. Pooling
reintroduces the exact ceiling virtual threads exist to remove: only 8
fetches wait at once, so 40 tasks run in ⌈40/8⌉ = 5 waves × 100 ms ≈
**500 ms** (measured: 520 ms). Fix: `Executors.newVirtualThreadPerTaskExecutor()`
— one new thread per task, all 40 wait together, ~100 ms. Anti-pattern
§10(d): virtual threads are cheap enough to create per task; **pooling them
is always wrong** (to protect a scarce downstream resource, use a
`Semaphore`, not a small pool).

## D2 — virtual threads are always daemons

Platform threads are non-daemon by default, and a live non-daemon thread
keeps the JVM running. Virtual threads are **always daemon** (README §11;
`setDaemon(false)` throws), so `main` returning ends the JVM even mid-print —
whether the message appears is a race. Fixes: keep the `Thread` and
`t.join()` it; or submit via
`try (var ex = Executors.newVirtualThreadPerTaskExecutor()) { ... }`, whose
`close()` waits for every task (that's the scenario test
`executor_close_waits_for_submitted_tasks`).
