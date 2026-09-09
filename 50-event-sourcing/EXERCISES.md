# Exercises

**Predict**, **modify**, **debug** — in that order of difficulty. Write your
answers down before running anything; answers in [`SOLUTIONS.md`](SOLUTIONS.md).

---

## Predict

**P1 — read a stream like a teller reads a passbook.**
Stream `account-7` contains exactly:

```
v1 AccountOpened{owner: priya}
v2 MoneyDeposited{amount: 100}
v3 MoneyWithdrawn{amount: 30}
v4 MoneyDeposited{amount: 40, channel: wire}
v5 DepositReversed{amount: 40}
v6 ComplianceNoteAttached{note: routine check}   <- no code handles this type
```

Predict: (a) `balance` and `version` after a full load; (b) the balance
`asOfVersion = 4`; (c) does the unknown v6 event crash the replay, and what
does it do to `version`? Check:

```bash
cat > P1.java <<'JAVA'
import java.util.List;

public class P1 {
    public static void main(String[] args) {
        try (var s = new EsLab.EventStore()) {
            s.append("account-7", 0, List.of(new EsLab.Event("AccountOpened", EsLab.payload("owner", "priya"))));
            s.append("account-7", 1, List.of(new EsLab.Event("MoneyDeposited", EsLab.payload("amount", 100.0, "channel", "app"))));
            s.append("account-7", 2, List.of(new EsLab.Event("MoneyWithdrawn", EsLab.payload("amount", 30.0, "channel", "app"))));
            s.append("account-7", 3, List.of(new EsLab.Event("MoneyDeposited", EsLab.payload("amount", 40.0, "channel", "wire"))));
            s.append("account-7", 4, List.of(new EsLab.Event("DepositReversed", EsLab.payload("amount", 40.0))));
            s.append("account-7", 5, List.of(new EsLab.Event("ComplianceNoteAttached", EsLab.payload("note", "routine check"))));
            var a = EsLab.Account.load(s, "7");
            System.out.println("full: " + a.account().balance() + " " + a.account().version() + " replayed " + a.replayed());
            System.out.println("as_of 4: " + EsLab.Account.load(s, "7", /* useSnapshot */ false, /* asOfVersion */ 4).account().balance());
        }
    }
}
JAVA
java -cp ../lib/h2.jar P1.java && rm P1.java
```

**P2 — snapshots are cache, prove it to yourself.**
A stream has 131 events; a snapshot exists at version ~100. Predict: how
many events does `useSnapshot = true` replay? Now the snapshot row is
deleted — what changes about (a) the loaded state, (b) the load cost? Which
test in `EsTest.java` already pins each half of your answer?

---

## Modify

**M1 — remove the last line of defense.**
Delete `UNIQUE (stream, version)` from the events table. Every test still
passes (run them!). So what was the constraint for? Describe the exact
interleaving of two *processes* (not threads in this lab — real concurrent
writers on a shared Postgres) where the Python-level version check passes
for both, and only the constraint stands between you and two events with
the same version. What is that failure called?

**M2 — make `apply` opinionated, watch history break.**
Change `Account.apply` to throw an `IllegalArgumentException` on event types it doesn't
recognize ("defensive programming!"). Run the tests. Which one fails, and
what does it represent in real life? Then answer the deeper version: if
`apply` instead enforced *today's* business rule (say "balance may never
exceed 10,000") — what class of previously-legal history becomes
unreadable, and what is the iron rule about where validation may live?

**M3 — the right to be forgotten (design exercise).**
`AccountOpened{owner: "priya"}` is personal data in an immutable log.
Sketch crypto-shredding for this lab: what would you encrypt, what key
granularity, where do keys live, what happens on erasure request, and what
does a post-shred replay produce? One subtle question to answer explicitly:
does `apply` need to change to survive replaying a shredded stream?

---

## Debug

**D1 — the bank that never says no twice.**
`java -cp ../lib/h2.jar BrokenEs.java`: two withdrawals of 50 from a balance
of 70 both "succeed"; the balance prints **-30.0**. Read
`handleWithMoreDetermination` and explain the exact sequence — what did
it reuse that it must not have? Which test in `EsTest.java` pins the correct
behavior? State the fix in one sentence, and the general law about what a
`ConcurrencyConflict` is actually telling you.
