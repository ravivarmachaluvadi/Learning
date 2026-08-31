# Exercises

Three kinds, in rising order of difficulty: **predict** (reason before you
run), **modify** (change a demo and explain what you see), **debug** (find a
planted bug). Answers are in [`SOLUTIONS.md`](SOLUTIONS.md) — commit to an
answer *in writing* before checking; that's where the learning is.

---

## Predict

**P1 — sleep vs. compute on one carrier.**
Three virtual threads run on a scheduler restricted to **one** carrier. Each
thread does: `Thread.sleep(100)` then ~100 ms of pure CPU spinning.
Roughly how long until all three finish — 200 ms, 300 ms, 400 ms, or 600 ms?
Reason it out from mount/unmount rules, write your answer down, then check
with:

```bash
java -Djdk.virtualThreadScheduler.parallelism=1 src/P1.java
```

(the source is [`src/P1.java`](src/P1.java) — resist reading the solution
hints in your head before committing to a number)

**P2 — when virtual threads *don't* help.**
`./run.sh throughput 8 50` runs only 8 blocking tasks. Predict both measured
numbers (pool of 8 platform threads vs. virtual) before running. What is the
general rule for when virtual threads make no difference to a blocking
workload?

---

## Modify

**M1 — kill the hopping.**
In `run.sh`, the `carriers` demo uses `parallelism=2`. Run it with
`parallelism=1` instead:
`java -Djdk.virtualThreadScheduler.parallelism=1 src/CarrierHopping.java`.
Threads still sleep and wake — do they ever change carrier now? Does the
program still finish quickly, and what does that tell you about the
difference between *releasing* a carrier and *switching* carriers?

**M2 — give the old world a fair fight.**
In `ThroughputRace.java`, the pool loses because it has 8 threads for 600
tasks. Change `poolSize` to 600. (a) Predict the measured time — does the
pool now tie with virtual threads? (b) Then explain why this "fix" is not
how you'd run a real server at 100,000 concurrent requests — Demo 1
(`./run.sh cheap`) already showed you this machine's hard evidence.

**M3 — break a scenario test on purpose.**
In `ScenarioTests.java`, the test
`blocked_virtual_thread_releases_its_carrier_to_others` passes on one
carrier. Predict: would it still pass if thread A *spun in a CPU loop* for
300 ms instead of sleeping 300 ms? Make that change (replace A's `sleep(300)`
with a `while`-loop on `System.nanoTime()`), run `./run.sh test`, and explain
the result in terms of §10(b) of the README. Undo the change afterwards.

---

## Debug

**D1 — the slow "virtual-threaded" crawler.**
`./run.sh` isn't wired for it on purpose — run `java src/BrokenCrawler.java`.
It fetches 40 pages of ~100 ms each "on virtual threads" but takes ~500 ms
instead of ~100 ms. The bug is one line. Find it, explain *exactly* why the
measured time is ~500 ms (do the arithmetic), fix it, and re-run to see
~100 ms. Which anti-pattern from README §10 is this?

**D2 — the vanishing output.**
A colleague reports that this program "sometimes prints nothing at all":

```java
public class Vanish {
    public static void main(String[] args) {
        Thread.startVirtualThread(() -> System.out.println("saved the order!"));
    }
}
```

Why can the message vanish? Which *guarantee that platform threads used to
give* is missing here (README §11), and what are two idiomatic fixes?
