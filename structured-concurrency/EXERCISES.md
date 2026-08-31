# Exercises

**Predict** (reason before running), **modify** (change a demo, explain the
result), **debug** (find the planted bug). Answers in
[`SOLUTIONS.md`](SOLUTIONS.md) — write your answer down first; the gap
between your guess and the output is where the concept sticks.

All commands run from this directory; preview flags included.

---

## Predict

**P1 — same three forks, two policies.**
[`src/P1.java`](src/P1.java) forks the same three subtasks (100, 200,
300 ms, all succeed) twice: once under the default joiner, once under
`anySuccessfulResultOrThrow`. Predict both elapsed times and what the second
`join()` returns. Then:

```bash
java --enable-preview --source 25 src/P1.java
```

**P2 — a failure under `awaitAll`.**
Without running anything: scope opened with `Joiner.awaitAll()`, two forks —
one returns "ok" after 150 ms, one throws immediately. (a) Does `join()`
throw? (b) Is the 150 ms sibling cancelled? (c) How do you find out the
failure happened at all? Check yourself against the test
`await_all_joiner_does_not_fail_fast` in `src/ScenarioTests.java`
(`./run.sh test` runs it).

---

## Modify

**M1 — move the failure later.**
In `src/FailFast.java`, change the failing subtask's `Thread.sleep(50)` to
`Thread.sleep(400)`. Predict the new elapsed time, run it, then put it back.
What single sentence describes the relationship between "time of first
failure" and "cost of the whole scope" under the default joiner?

**M2 — loosen the deadline.**
In `src/Deadline.java`, change the timeout from 150 ms to 500 ms. Predict:
what prints now, and after how long? What does this tell you about where
`TimeoutException` sits relative to normal completion — is a timeout an
error of the *tasks* or of the *scope*?

**M3 — a failure under the racing joiner.**
In `src/RaceReplicas.java`, make replica-B (the fast one) **throw** after
40 ms instead of succeeding, leaving slow replica-A returning normally at
300 ms. Predict the outcome and elapsed time, run it, put it back. What did
you just learn about how `anySuccessfulResultOrThrow` treats a failure —
and why is that the right behavior for hedged requests?

---

## Debug

**D1 — "structured, but no faster".**
`java --enable-preview --source 25 src/BrokenPage.java` renders 5 widgets of
~100 ms each using scope/fork/join — and takes ~523 ms. No exception, no
pinning, no trick sleeps. Find the structural bug, state the rule it
reveals, fix it (aim: ~100 ms), and say which *enforced* rule (README §9
table) would have bitten you if you'd tried the obvious wrong fix of
hoisting the scope but keeping join inside the loop.

**D2 — the subtask that wouldn't die.**
`java --enable-preview --source 25 src/UnkillableChild.java`: the sibling
fails at 50 ms, yet `join()` only throws after ~565 ms. Explain the exact
sequence (when was the child interrupted? how many times? what did it do?),
name the guarantee that HELD and the property that was LOST, and fix
`resilientFetch` so the scope exits in ~60 ms while keeping its retry
behavior for genuine failures.
