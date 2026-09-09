# Redis — exercises

Eight rungs, easiest first. Rungs 1–3 need only the README and the
[visualization](https://claude.ai/code/artifact/492e3d97-6221-482d-847c-8ee9302ece13).
From rung 4 you are editing and running code.

Answers in **[SOLUTIONS.md](SOLUTIONS.md)** — attempt first. For rungs 7–8 the
"answer" is a worked discussion, not a single right output.

```bash
cd ~/Documents/Learning/13-redis
java src/Tests.java              # 25 scenario tests
java src/Experiments.java N      # experiment N (1..9)
java src/BrokenRedis.java        # the three planted bugs
java src/MiniRedis.java 6380 &   # a real server
java src/Cli.java 6380           # talk to it
```

---

## Rung 1 — Explain

Write these out in your own words. No notation, no jargon you cannot define.

**1.1** Why does Redis exist? Name a specific problem it solves that neither a
`ConcurrentHashMap` inside your app nor a Postgres table solves — and say what is
wrong with each of those two answers specifically.

**1.2** Explain where Redis's atomicity comes from **without using the words "lock",
"synchronized", "thread-safe" or "mutex"**. If you cannot, you have not got it yet.

**1.3** Someone says: "Redis's data types are just a convenience — you could do all
of it client-side." Say why they are wrong, using the samosa jar.

**1.4** You have already learned about caching. Give one concrete example of Redis
being used as a cache and one of it being used as something that is definitely *not*
a cache, and state the single test that tells the two apart.

---

## Rung 2 — Identify

**2.1** In the visualization, run the **"one client asks for a list of every key"**
scenario. Pause it midway and answer from the screen alone: which client is being
served, how many are waiting, what is the longest anyone has waited, and what does
the verdict band say is happening?

**2.2** In `src/Commands.java`, find the exact lines that make `INCR` atomic. How
many lines are they? How many of them mention concurrency in any way?

**2.3** Start a server and use `INFO` to answer: how many keys are resident, how many
bytes have been written to the log, and how many of those bytes are actually on disk?
Which of those three numbers can lie to you, and about what?

**2.4** Decode this by hand, then check with `nc`:

```
*3\r\n$4\r\nHSET\r\n$6\r\nuser:1\r\n$5\r\nalice\r\n
```

What is wrong with it? (Look at the argument count.)

**2.5** In the visualization, set the memory limit to 3 keys and store a fourth. Which
key was thrown out, and — this is the real question — could you have predicted *which
one* before running it? Why not?

---

## Rung 3 — Predict

Commit to an answer, then run the check.

**3.1** With active expiry switched off, you run `SET k v PX 100` and wait five
seconds without touching `k`. What does `DBSIZE` return? What does `GET k` return?
Why are those two answers consistent with each other?
*Check:* `java src/Tests.java` → `expired_key_is_invisible_but_can_still_be_resident`.

**3.2** `maxmemory-policy` is `volatile-lru`. Memory is full. **No key in the
instance has a TTL.** What happens to the next write, and why is this a nastier
failure than `noeviction`?

**3.3** Predict all four values:

```
SET good 0
SET text hello
MULTI
INCR good
INCR text     <- this will fail
INCR good
EXEC
```
What are the three replies inside `EXEC`? What is `good` afterwards?
*Check:* the test `multi_exec_does_not_roll_back_after_a_failed_command`.

**3.4** Two clients both `WATCH balance`, both read 100, both `MULTI; SET balance 90;
EXEC`. How many succeed? Now: both use `DECRBY balance 10` instead. How many succeed?
Which is correct, and what is the cost difference?
*Check:* `java src/Experiments.java 9`.

**3.5** `appendfsync always`. You `kill -9` the process. How much is lost? Now the
machine loses power. How much is lost? Now `appendfsync no` for both. Fill in all
four cells before running `java src/Experiments.java 6`.

**3.6** A master has three replicas and acknowledges a write. `WAIT 3 100` returns
`1`. What do you now know, what do you *not* know, and what can you do about it?

---

## Rung 4 — Modify

Change something, predict what breaks, then measure. For each: **which property
moved — safety or liveness? Did correctness change at all?**

**4.1** In `src/Experiments.java`, experiment 5 runs `allkeys-lru` with
`maxmemory-samples` 5 and 10. Add runs at **2** and at **50**. Predict both hit
ratios first. Then answer: why does Redis default to 5 rather than 50?

**4.2** In `src/Store.java`, `activeExpireCycle` goes round again only while more
than 25% of a sample was dead (`deadInSample * 4 <= sample` breaks the loop). Change
it to always stop after one round. Re-run `java src/Experiments.java 4`. How many
more sweeps are needed? What would that mean on a real instance running the sweep ten
times a second?

**4.3** In experiment 7, change the replication delay from `1500` to `0`. The lost
writes go to zero. **Is the write now guaranteed to survive a failover?** Answer
carefully — this is the question the whole of §11 F5 exists to make you get right.

**4.4** In `src/MiniRedis.java`, `cron()` calls `flushAof()` on every loop iteration.
Change it to run only every 200th iteration. Which experiment's numbers change, and
which stay identical? Explain why `kill -9` behaves differently now but power loss
does not.

---

## Rung 5 — Implement

Each of these must be **one atomic command** — that is the whole point. Add a test to
`src/Tests.java` named after the scenario.

**5.1 `GETEX key PX <ms>`** — return the value and reset its deadline in one command.
Then answer: what specifically goes wrong if a client does `GET` then `PEXPIRE`
instead? Construct the interleaving.

**5.2 `INCRCAP key max`** — increment, but return `-1` and change nothing if the
result would exceed `max`. Explain why a client cannot build this correctly out of
`GET` and `INCR`, and why it cannot build it correctly out of `INCR` and `DECR`
either (what does the second one break?).

**5.3 `LMOVE src dst`** — atomically pop from the tail of one list and push to the
head of another. This is the *reliable queue* pattern. Write a test proving no job
can be lost even if the worker crashes immediately after `LMOVE` returns. Then say
what is still missing before this is a production queue.

---

## Rung 6 — Debug

`java src/BrokenRedis.java` runs three planted bugs. Each is a mistake somebody has
shipped, and each looks reasonable in review.

For **each** bug, answer four things **before** reading the output:

1. What is the observable symptom?
2. **Which test in `src/Tests.java` catches it?** (Find it by name.)
3. Which invariant from README §12 does it violate?
4. Why would this be hard to find in production?

| bug | the change | run |
|---|---|---|
| 1 | `EXPIRE` is sent to replicas exactly as typed, not rewritten to an absolute deadline | `java src/BrokenRedis.java 1` |
| 2 | `INCR` reads the hash table directly instead of through the expiry check | `java src/BrokenRedis.java 2` |
| 3 | `SET` is logged exactly as typed, keeping `NX` and a relative deadline | `java src/BrokenRedis.java 3` |

**6.4** Bug 2 is the quietest of the three. Say precisely why: what makes a bug where
*one* command disagrees with *another* command about the same key so much harder to
find than a bug where a command is simply wrong?

**6.5 — the important one.** Turn each bug on by flipping its flag at the top of
`src/Commands.java`, and run the **full** test suite each time.

Two of the three are caught. **One of them passes all 25 tests.** Find out which,
before reading on, and then answer:

- Why did the suite miss it? What is the shape of the thing it fails to check?
- Which of the three bugs would do the most damage in production? Is it the same one
  the suite missed? (It is. Sit with that for a moment.)
- **Write the test that catches it.** Name it after the scenario. Verify it fails
  with the flag on and passes with it off — a test you have not seen fail is not
  evidence of anything.

This is the most useful exercise on the page. A green suite is a statement about the
tests, not about the code.

---

## Rung 7 — Handle failure

Extend the system to survive something it currently does not.

**7.1 — Make the lock safe.** Experiment 8 shows two clients holding one lock, and
fencing tokens fixing it *at the resource*. Now build it properly:
- issue the token with `INCR fence:<resource>` on acquisition,
- have the resource reject any token below the highest it has honoured,
- write the correct **release**, which must check ownership and delete in one atomic
  step (a plain `GET` then `DEL` can delete someone else's lock — construct that
  interleaving too).

Then answer the hard part: **which resources can actually do this, and what do you do
about the ones that cannot?** Name a real example of each.

**7.2 — Make the job queue survive a dead worker.** `LPOP` removes a job from the
queue and hands it to a worker. If that worker dies, the job is gone. Rebuild it so
no job is ever lost:
- `LMOVE queue processing` when taking a job,
- remove from `processing` only on success,
- something must reclaim jobs stranded in `processing`.

Now say what your design does when a job is *slow* rather than dead, and why that is
the same problem as F7.

**7.3 — Make the write in experiment 7 survive.** Client writes `order:99 paid`, gets
`OK`, the master dies. Enumerate every option and its real cost:
`WAIT`, `min-replicas-to-write`, `appendfsync always`, AWS MemoryDB, and "don't put
it only in Redis". Which do you actually pick for a payment, and why is that answer
different from the one you pick for a session?

---

## Rung 8 — Design

Production-scale prompts. There is no single correct answer; there are answers that
survive the follow-up questions.

**8.1 — Session store, three datacentres.** 50 million sessions, 2 KB each, users
pinned to their nearest DC 60 ms apart, 40,000 reads/sec and 4,000 writes/sec at
peak. Design it. Cover: how much RAM and how many instances; whether you replicate
across DCs and what that costs; what happens to logged-in users when one DC goes
dark; which parts of this lab survive unchanged and which must change; and the three
things you alert on.

**8.2 — "Top 100 products this hour".** 200,000 view events per second. The obvious
design is `ZINCRBY leaderboard:<hour> 1 <product>`. Find the wall it hits — be
specific about which resource runs out first and why sharding does not help — and
design around it. What accuracy would you trade, and what would you say to the
product manager who asks for exact counts?

**8.3 — The two-paragraph reply.** A colleague proposes moving the shopping basket
out of Postgres into Redis "because Redis is faster". Write the reply you would
actually send. It must name the specific thing that could go wrong, quantify it, and
end with a concrete proposal rather than a refusal.

**8.4 — Read your own incident.** You are on call. The alert is *"p99 latency 400 ms,
was 2 ms"*. `INFO` shows `connected_clients: 1200`, `evicted_keys: 0`,
`used_memory` at 60% of `maxmemory`, `master_repl_offset` healthy. What is your first
command, your second, and your third — and what does each one rule in or out?
