# Solutions

## P1 — (a) 70.0, version 6 · (b) 110.0 · (c) no crash; version still advances

(a) Fold: 0 → +100 → -30 → +40 → -40 → (no-op) = **70.0**, six events so
**version 6** (the unknown event still counts — it happened). (b) As of v4
the reversal hasn't happened: 100-30+40 = **110.0**. (c) `apply`'s fallback
branch tolerates unknown types and only bumps the version — replay is
*total*: it must survive every fact ever recorded, including types added by
future (or auditing) code it never heard of.

## P2 — ~31; state identical; cost back to 131

Snapshot at 100 + tail 31 = replay ~31 events. Delete the snapshot: (a) the
state is bit-for-bit the same — pinned by `snapshot_state_equals_full_replay`;
(b) the load replays all 131 — the difference
`snapshot_load_replays_only_the_tail` measures. Snapshots are *pure* cache:
they can be deleted, rebuilt, or wrong-versioned away with zero data loss.
Anything you can't delete without losing information does not belong in a
snapshot.

## M1 — the TOCTOU window; a lost update

The lab's check is SELECT-max-version *then* INSERT. Within this
single-connection lab, that's race-free. With two real processes on a
shared database: P1 SELECTs (version 3), P2 SELECTs (version 3), P1 INSERTs
v4 and commits, P2 INSERTs v4 and commits — both checks passed at the time
they ran (a time-of-check/time-of-use race). Now the stream has two
version-4 events: replay order between them is undefined and one writer's
decision was made against state that no longer existed — a **lost update**,
the exact disease expected-version exists to cure. `UNIQUE(stream,version)`
makes the database itself refuse the second insert regardless of
application-level races. Production stores either rely on precisely such a
constraint or serialize appends per stream internally.

## M2 — `unknown_event_types_are_tolerated_on_replay` fails; apply must be total

The failing test represents *your own future*: any event type added after
today makes yesterday's deployed replayer crash on tomorrow's history —
and auditing/compliance events written by other components do this
routinely. The deeper version: if `apply` enforces today's 10,000 cap, then
any account that *legally* held 15,000 back when the cap was 50,000 can no
longer be loaded — history that was valid when written becomes unreadable
retroactively. The iron rule: **`decide` validates (against today's rules,
before the fact); `apply` records (whatever the rules were, after the
fact).** Rules change; facts don't.

## M3 — crypto-shredding sketch

Encrypt the *personal fields* of event payloads (`owner`), not whole
events, with a per-person (or per-stream) data key; store keys in a
separate key table/KMS keyed by subject id; event payloads hold ciphertext.
On erasure request: delete the key — every copy of the history (backups
included!) instantly becomes unreadable ciphertext for those fields, while
versions, amounts, and structure survive for accounting. Post-shred replay
produces a fully valid account whose owner reads as "(erased)"/undecryptable.
And yes, `apply` (or the read path) must change: decryption failure must be
handled as a *normal outcome* — a total `apply` treats "key gone" as
"field redacted", never as an error. (The alternative discipline: never put
personal data in events at all — store a subject id and keep PII in a
mutable store; then event sourcing's immutability and GDPR never meet.)

## D1 — it reused the DECISION; conflicts demand re-deciding, not re-sending

Sequence: the broken handler loads at version 2 (balance 70) and computes
`MoneyWithdrawn(50)` once. Teller 1 lands v3 (balance 20). The broken
append conflicts — correct so far — but the "retry" only refreshes the
*version number* and re-sends the **same event**, decided against a world
where the balance was 70. The store, asked "is the stream at 3?", truthfully
answers yes, and appends. No rule ran; balance -30.

The pinning test: `retry_re_decides_and_the_invariant_holds_against_fresh_state`
— its two assertions (a rejection, and balance 20.0) are exactly what the bug
violates.

But be precise about *when* it violates them, because a careless swap will
mislead you. Dropping the broken handler into that test verbatim still
**passes**: the test expresses its command as a lambda evaluated after the
conflicting write, so the broken handler happens to load fresh state and
re-decide by accident. The bug fires only when the decision is genuinely
**stale** — computed before the conflicting write — which is the interleaving
`BrokenEs.java`'s `main` deliberately arranges. Run that and you get
`ACCEPTED | balance -20.0` where the test demands rejection at 20.0.

This is worth more than the bug itself: a concurrency bug that a
single-threaded test cannot reach is the normal case, not a curiosity. The
suite never exercises the conflict-retry branch at all, which is why the bug
survives a green run. Fix in one sentence: on conflict, **reload
the aggregate and re-run `decide` against the fresh state** (exactly what
`EsLab.handle` does). The law: a `ConcurrencyConflict` is not an error to
be retried away — it is the store telling you *"the world you decided in no
longer exists; decide again."*
