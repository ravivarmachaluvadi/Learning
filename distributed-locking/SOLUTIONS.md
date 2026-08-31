# Solutions

Attempt each rung first — [EXERCISES.md](EXERCISES.md).

## Rung 1

**1.1** Two facts do all the damage. First, **a process cannot observe its
own delays**: between any two of its instructions the world may have moved
arbitrarily far (GC, swap, migration), so "I hold the lock" is always a
belief about the past. Between threads, a stalled thread's lock stays
*correct* — the OS keeps it held; nothing else can enter. Second, **death
and slowness are indistinguishable from outside**: the only evidence anyone
has about a remote process is silence, and silence has two meanings with
opposite correct responses (wait forever vs take the lock away). A machine
lock never faces this — the kernel *knows* when a process dies and cleans
up. Distributed locking is the art of living without those two certainties.

**1.2** Job one: the TTL is the **recovery deadline** — after a holder
crashes, the system is stuck for at most one TTL (measured: recovery ≈ TTL
for every value). Job two: it is the **pause tolerance** — any stall longer
than the TTL turns a live holder into a stale one. Short helps job one and
sabotages job two; long, the reverse. "Long enough to be safe" cannot be
finished because it means "longer than my longest future pause", and the
longest future pause (GC, swap, VM migration, human with a debugger) has no
upper bound you can know. The `dilemma` experiment prints the compromise
row by row.

**1.3** Consensus bought an honest, highly available *lock service*: the
service itself survives minority failure, never disagrees with itself about
who holds the lock, and can mint a durable, monotonic fencing token
(ZooKeeper's zxid, etcd's revision). It bought nothing about *the client*:
a GC-paused holder still doesn't know it lost the session, still wakes,
still writes. ZooKeeper prevents the *service* from being wrong; only a
fencing check at the resource prevents the *stale client* from doing
damage. (Measured equivalently in the lab: the pause corrupts under any
lock service; only the resource's check ends it.)

## Rung 2

**2.1** (a) The expiry clause in `LockSrv.free()` —
`ttlEnabled && now >= expiresAt` ([World.java:76](src/World.java#L76)).
(b) `token++` on **every** grant in `acquire`, including expiry-takeovers
(bug #1 is its absence on that path). (c) The single comparison in
`Res.write`: `tok < maxToken → reject`. (d) `resource.observeRead(this, tok)`
in `startWork` — the read fence, added after the P=25 row of the pause
sweep lost an update with fencing "on" (§10.4).

**2.2** Pia's card still believes she holds token *n* and is mid-work — her
belief is frozen, which is the point. The lease bar shows expired/re-granted
to Quinn. The split-brain is the **"believed holders: 2"** counter (the
Java's `overlapTicks` is its time integral).

**2.3** The 2,641 extra messages bought *load on the lock service at its
busiest moments* — every release triggers a stampede of retries — plus
unfairness (whoever polls at the lucky tick wins; the watch queue is FIFO).
Same completion time here only because the service is a free in-process
object; a real lock service melting under poll storms is §11g and the
reason ZooKeeper's recipe watches the *previous* holder only.

## Rung 3

**3.1** **Zero** updates lost, at 300 ticks or 3 million. The arithmetic
doesn't care about duration: Quinn's grant carries a higher token; her
first fenced operation raises the resource's floor; Pia's eventual write
presents the smaller number and bounces. Pia experiences: wake, write,
rejection, re-acquire (fresh token, fresh read), redo, success. One redone
section, no corruption — the redo cost scales with the pause count, never
the pause length.

**3.2** No double-majority. Work the masks: Pia holds {S1,S2,S3}. A jump on
S4 expires nothing of Pia's (S4 never granted her anything). Quinn can
reach {S1,S4,S5}: S4 and S5 grant (free), but S1 still holds Pia's
unexpired lease and refuses — Quinn has 2 of 5, no majority, and polls
until Pia's leases genuinely lapse. The failure needs the jump on a server
**inside the holder's majority** (any of S1–S3), because two 3-of-5
majorities must share at least one server — the quorum-overlap rule from
the quorums lab, now working *against* safety: the shared server is exactly
where a single clock error breaks the intersection argument. Edit the test
to `servers[3].clockOffset = 100` and the assertion fails (no overlap).

**3.3** Accepted: the check is `token < maxToken`, and 7 < 7 is false —
equal tokens pass. For the *value write* in this lab that re-apply is
harmless-looking but wrong in general: "same token" means "same lease",
not "same request". The right outcome needs a **per-request idempotency
key** (dedup within a lease) *alongside* the token ordering (fencing
across leases) — the two checks answer different questions, which is
exactly planted bug #3's lesson run in reverse: dedup is not ordering, and
ordering is not dedup; real systems need both.

**3.4** The ghost packet / stale holder failures (§11b, §11d) now apply to
the index unfenced: a paused holder's index update lands after the next
holder's, and the index diverges from the file *silently*. "Updated
together" doesn't help because together-ness was a property of the
*holder's intent*, not of the messages — they travel separately and the
pause splits them. Every protected resource needs its own fence (or a
single fenced front door both writes go through).

## Rung 4

**4.1** With `msgDelay = 0` the write lands the instant it is sent, so a
send that happens while the lease is still (barely) alive can no longer
cross the expiry in flight — the **P=25 row** goes to 0 lost in the plain
column. That row was teaching the lab's own discovered bug: *hold time
includes the write's travel time*, so an in-flight write can be stale on
arrival even though the pause was shorter than the TTL — and only a fenced
read catches it.

**4.2** Unconditional renew = `expiresAt = now + ttl` regardless of caller.
Scenario: Pia holds and pauses; her lease expires; Quinn is granted; Pia's
client-side heartbeat timer (which survived the pause) fires and *extends
Quinn's lease window under Pia's belief* — or, in the nastier variant where
renew also sets `holder`, steals the lock outright. First failing test:
`renewal_extends_a_lease_while_the_holder_is_alive` still passes (happy
path!), but `pause_longer_than_ttl_makes_two_believed_holders` and its
siblings change shape — the honest catch is a new test asserting a
non-holder's renew returns false. (That the happy-path test stays green is
the lesson: theft bugs live outside the happy path.)

**4.3** `watch_mode_beats_polling_and_keeps_fifo_order` — notify-all
multiplies messages per release by the queue length, so the
`watchMsgs * 2 < pollMsgs` margin collapses as N grows. The recreated
production symptom is the **thundering herd**: every release wakes every
waiter, N-1 of which immediately fail and re-sleep — ZooKeeper's
"watch the previous node, not the lock" recipe exists to prevent precisely
this.

## Rung 5

Working bodies (verified 6/6 in this lab):

```java
static long yourAcquire(Srv s, int now, int cliId) {
    if (s.holder == null || now >= s.expiresAt) {
        s.holder = cliId;
        s.token++;                     // strictly larger on EVERY grant
        s.expiresAt = now + s.ttl;
        return s.token;
    }
    return -1;
}

static boolean yourFenceCheck(ResState r, long token, boolean isWrite) {
    if (token < r.maxToken) return false;
    r.maxToken = token;                // reads raise the floor too
    return true;
}
```

Why the bump on expiry-takeovers specifically: an expiry-grant is the ONLY
kind where the previous holder may still be alive and about to act — a
released lock's ex-holder is done by definition. Reusing the token there
hands both actors the same credential at the exact moment they must be
distinguishable (planted bug #1). Why check 6 feeds a read into the fence:
a new holder's read must *raise the floor* so that older writes still in
flight are refused on arrival — otherwise the read races the ghost packet
and write-only fencing accepts both writes in perfectly increasing order
(§10.4).

## Rung 6

- **token-reused-on-expiry** — diff: "cleanup: only bump the version on a
  real ownership change". Breaks **safety**, silently: fencing reports
  nothing wrong because both holders are equally credentialed. Symptom:
  rare corruption that looks like an application bug, near GC events.
- **token-counter-in-memory** — diff: "the counter doesn't need to be in
  the DB, it's just a number". Breaks **availability**: after a service
  restart every legitimate writer is rejected until the counter climbs
  back. Loud — error rates spike immediately after the restart.
- **dedup-instead-of-ordering** — diff: a "replay protection" feature that
  keeps a seen-set instead of a floor. Breaks **safety**: a stale holder's
  *first* write always carries an unseen token. Symptom: same as bug #1 —
  quiet, pause-correlated corruption.
- **write-only fencing** — diff: none; it's the *absence* of a line nobody
  knew to write. Breaks **safety** in the narrowest window (in-flight write
  vs new read), zero rejections logged, pause can be *shorter than the
  TTL*. The nearly perfectly silent one.

Time-to-detection ranking (defensible): **token-counter-in-memory** first
(minutes — it fails loudly and reliably on the next restart), then
**token-reused-on-expiry** and **dedup-instead-of-ordering** (weeks —
need a pause coinciding with contention, and the corruption is deniable),
with **write-only fencing** last (months-to-never — needs pause + in-flight
write + immediate re-grant, and even then the logs show two *accepted*
writes and nothing else). Any order is fine if argued from trigger
frequency × symptom visibility.

## Rung 7 (worked design)

The email API accepts no tokens, so the guarantee cannot live at the
resource — push it as close as possible and name the residue.

Design: (1) The digest job is gated by an **efficiency lock** (ShedLock on
Postgres is fine — the DB is already there). (2) The lock does not guard
the *send*; it guards the *claim*: a `digest_runs(date PRIMARY KEY, owner,
token)` row created with an **idempotent conditional insert** — yesterday's
lab's mechanism, `INSERT ... ON CONFLICT DO NOTHING`, count 1 = mine. The
date is the natural idempotency key; the DB is the fenceable front door.
(3) Only the claimant calls the API, and it records `sent_at` in the same
row *before* acknowledging completion, passing the provider an
**idempotency key** (`digest-2026-08-29`) if the API supports one (many
mail providers do — that moves the dedup into the resource after all).
(4) A reaper re-opens claims with no `sent_at` after a deadline.

The residual failure, written down: the claimant can crash (or pause)
**after the API accepted the send and before `sent_at` committed** — the
reaper then re-claims and the digest goes out twice. Without provider-side
idempotency this window cannot be closed, only shrunk (claim → send →
record, with the record immediately after the send). Monitor: provider-side
send count per digest date (reconciliation query against their API/logs),
alerting on `count > 1` — you detect the double-send you cannot prevent.

## Rung 8 (worked discussion)

- **(1) Payout workers / merchant queues** — this is not one lock, it's
  *assignment*: a claim per merchant. Correctness (a merchant drained twice
  = double payouts). Mechanism: the claim IS a conditional write on a
  `merchant_claims` row (owner, lease expiry, **generation** — the previous
  lab's rung 7 verbatim), and every payout the worker writes carries the
  generation, checked by the payments DB. The lock-service version (etcd
  lease per merchant, `mod_revision` as token) is equivalent; pick whichever
  store you already operate. Monitor: claims with expired leases still
  receiving writes (= fencing rejections), payouts per merchant per window.
- **(2) Nightly interest batch, writes only to Postgres** — the easy one,
  and the trap is over-engineering it: the work lives *inside* the
  fenceable resource. `pg_advisory_xact_lock` (or ShedLock) to avoid
  duplicate cost, and the batch's writes made idempotent/conditional
  (`WHERE last_interest_date < :today`) so a duplicate run is a no-op.
  Efficiency lock + conditional writes; no external lock service earns its
  keep. Monitor: rows updated per run (a duplicate run updating 0 is
  healthy; updating >0 twice is the alarm).
- **(3) Month-close, minutes long, external tax APIs** — correctness, long
  hold, and a box-G component. Consensus-backed lock (etcd/ZK) with session
  + renewal for the long hold; every *internal* write fenced with the
  lock's revision; the *external* calls get idempotency keys
  (`close-2026-08`) and a reconciliation step, because no token protects
  an API that doesn't check one. Also add the previous lab's shared-version
  bump on the ledger-month row so even a bypassing writer collides.
  Monitor: session expirations during a close (a paused closer mid-month-
  close is the nightmare scenario — page on it), reconciliation diffs.
- **The Redisson-wrapper review comment**, two paragraphs: *"RLock gives us
  a lease with watchdog renewal and no fencing token — as a duplicate-work
  suppressor it's fine, so (2) can use it. But (1) and (3) protect
  invariants: RLock has no token the database or the ledger could check, so
  a GC pause longer than the watchdog interval silently gives us two
  holders (we measured this shape in the locking lab — and Redis's own docs
  route correctness cases to fencing). Standardizing it 'for consistency'
  standardizes our failure mode.*
  *Counter-proposal: one internal library with two entry points —
  `efficiencyLock()` (Redisson/ShedLock, documented as best-effort) and
  `fencedClaim()` (etcd lease + revision, or the DB-row claim with a
  generation column, returning a token the caller MUST write with). The
  consistency worth having is that every correctness path returns a token
  and every store we own checks it."*
