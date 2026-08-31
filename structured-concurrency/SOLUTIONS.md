# Solutions

## P1 — 312 ms and 108 ms

Default joiner (`awaitAllSuccessfulOrThrow`): waits for **all** → the
slowest child governs → ~300 ms (measured 312). `anySuccessfulResultOrThrow`:
the **first success** wins → ~100 ms (measured 108), `join()` returns
`"cache"`, and the 200/300 ms children are cancelled mid-sleep. Same forks,
same code shape — the *joiner* is what defines "done".

## P2 — no throw, no cancellation, you must look

(a) `join()` returns normally — `awaitAll` has no fail-fast. (b) The
sibling runs to completion (the test asserts its `finally`-free happy path
finished and its state is `SUCCESS`). (c) The failure exists only on the
failed subtask's handle: `subtask.state() == FAILED`,
`subtask.exception()`. `awaitAll` is the "gather what you can" policy —
and it quietly shifts *all* error-handling responsibility onto you.

## M1 — ~410 ms; the sentence

The scope now runs until the failure at ~400 ms, then cancels the 5 s
sibling and throws: measured ~410 ms. The sentence: **under fail-fast, the
whole scope costs as much as the *first failure* (or the slowest success,
whichever resolves the policy first) — never as much as the slowest task.**

## M2 — normal completion at ~400 ms

Both children now beat the deadline: profile at 100 ms, orders at 400 ms,
`join()` returns normally at ~400 ms and the "unreachable" line prints.
The timeout is a property of the **scope**, not of any task: nothing about
the tasks changed, only the family's budget. `TimeoutException` appears
exactly when the budget, not the work, is exhausted.

## M3 — winner: replica-A at ~300 ms

A single failure does **not** end an `anySuccessfulResultOrThrow` scope —
it's recorded and the scope keeps waiting for someone to succeed; it throws
only when *no one* can anymore (all failed). So: B's failure at 40 ms is
noted, A wins at ~300 ms, elapsed ~300 ms. Right for hedging because a
hedge exists to survive one replica being broken — failing the whole
request because the *fast* replica errored would defeat the point.

## D1 — five scopes of one child each

The scope is opened **inside the loop**: each iteration forks one child,
joins it, closes. Concurrency in structured concurrency comes from siblings
*within one scope* — five one-child scopes are just sequential calls with
ceremony: 5 × ~100 ms ≈ 523 ms. Fix: hoist the scope, fork all five, join
once, then read the five subtask handles → ~105 ms. The rule it reveals:
**the scope is the unit of concurrency; fork everything before you join
anything.** The tempting wrong fix — one scope, but `join()` inside the
loop after each fork — dies on the second iteration with
`IllegalStateException: Already joined or scope is closed` (README §9:
fork-after-join is rejected).

Note the fixed version wants one more idea for real code: closing over the
loop variable per fork (fine for `List.of` iteration since `name` is
effectively final per iteration).

## D2 — interrupted once, at 50 ms; the child ate it

Sequence: sibling throws at 50 ms → scope cancels: **interrupts the child
once** → the child's attempt-1 `sleep` throws `InterruptedException`, the
child prints "retrying anyway!" and starts attempt 2 → nothing interrupts
it again (cancellation is not a machine gun) → attempt 2 sleeps the full
500 ms and returns "data" at ~550 ms → only now is every subtask finished,
so `join()` throws at ~565 ms.

**Held:** the close guarantee — the scope never let anything outlive it;
you were *held*, not leaked. **Lost:** promptness (liveness) — cancellation
is cooperative, and a subtask that swallows interruption converts
"cancelled" into "wait for it anyway". Fix — treat interruption as "stop
now", retry only genuine failures:

```java
static String resilientFetch() throws InterruptedException {
    for (int attempt = 1; attempt <= 4; attempt++) {
        try {
            Thread.sleep(500);
            return "data";
        } catch (InterruptedException e) {
            throw e;                        // cancelled: leave immediately
        } catch (RuntimeException e) {
            // a real failure of the attempt: loop and retry
        }
    }
    return "gave up";
}
```

With that, the scope exits in ~60 ms. (In this toy the only failure mode
*was* interruption, so the retry loop had nothing legitimate left to retry —
which is itself the point: retry logic must distinguish "it broke" from
"stop".)
