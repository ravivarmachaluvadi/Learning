# Exercises — two-phase commit

Attempt each before opening `SOLUTIONS.md`. Commit to an answer, then run
the command.

## Tier 1 — Predict

**P1.** Bank A votes YES; Bank B votes NO. At the moment B's NO arrives at
the coordinator, what is locked where? What exactly does Bank A's durable
log contain at that instant, and what will be appended next? Verify:
`java TpcLab.java participant-votes-no`.

**P2.** The coordinator crashes *after* logging COMMIT and *after* telling
Bank A, but *before* telling Bank B. A customer now reads alice at Bank A
and bob at Bank B. What does each read return (value or BLOCKED)? Is this
state a violation of atomicity? Verify with:
```bash
cat > P2.java <<'EOF'
public class P2 {
    public static void main(String[] args) {
        var banks = TpcLab.freshBanks();
        var a = banks[0]; var b = banks[1];
        var c = new TpcLab.Coordinator();
        c.log.add(TpcLab.LogEntry.begin("t", ""));
        a.prepare("t", "alice", -500); b.prepare("t", "bob", 500);
        c.log.add(TpcLab.LogEntry.of("t", "COMMIT"));
        a.commit("t");                      // A was told; then coordinator dies
        TpcLab.tryRead(a, "alice"); TpcLab.tryRead(b, "bob");
    }
}
EOF
java P2.java && rm P2.java
```

**P3.** In `two-possible-worlds`, suppose Bank A, stuck in doubt, adopts
the rule "if I hear nothing for 60 seconds, ABORT and release my locks —
customers are waiting." In which of the two worlds does this rule destroy
money, and what is the resulting global state? Could the symmetric rule
("timeout ⇒ COMMIT") ever be safe?

**P4.** Three participants instead of two: A and B vote YES, C votes NO.
How many durable log writes have happened *in total across all four nodes*
by the time the coordinator logs its decision (count BEGIN, PREPARED
entries, VOTED-NO, and the decision itself)? Which of those writes was, in
hindsight, wasted work — and why is it unavoidable?

## Tier 2 — Modify

**M1.** Reorder `Bank.prepare` so it replies YES **before** appending
`PREPARED` to its durable log (move the `log.add` after the
`crashAfterVote` block, i.e. after the vote is sent). Which test fails?
Walk the exact crash timing that turns this into money creation or loss.
What is the general rule about promises and logs that this breaks — and
where did the outbox and saga labs each enforce the same rule?

**M2.** Implement cooperative termination: give `Bank` a method
`askPeer(peer, txn)` where an in-doubt bank asks another participant.
Write the decision table: peer state → what the asker may safely do.
(States: peer never heard of txn / peer VOTED-NO / peer PREPARED (in
doubt too) / peer COMMITTED / peer ABORTED.) Which single row of that
table is the one where blocking remains?

**M3.** Change the coordinator to log the decision *before* collecting the
last vote ("optimistic commit": log COMMIT once A votes YES, assuming B
will too). Construct the run where this violates atomicity. Which property
of the commit point did the optimization break?

**M4.** Add a `heuristicAbort(txn)` method to `Bank` that force-aborts an
in-doubt transaction (the operator's `ROLLBACK FORCE`). Then write the
sequence of events in which using it leaves the system permanently
inconsistent, and name the log entries on each node that an auditor would
find afterwards. Why do real databases keep such a dangerous method?

## Tier 3 — Debug

**D1.** A teammate "fixes slow transfers" by having banks release their
locks immediately after voting YES ("we've logged PREPARED, the promise is
durable — why keep customers waiting?"). All 12 tests still pass except
one. Which — and construct the concrete anomaly: two transfers, one
reader, and a final set of balances that never should have coexisted.
What does this tell you about *which* ACID letter the locks were carrying?

**D2.** Production incident (classic XA): after a datacenter power blip,
Bank B's database shows a transaction in `pg_prepared_xacts` that is nine
hours old; a nightly batch job is stuck behind its locks; the application's
transaction-manager host was decommissioned in last week's migration and
its recovery log is gone forever. Enumerate the operator's options and the
risk of each. What single architectural decision (available since before
the incident) would have made this scenario structurally impossible?

**D3.** Someone proposes: "our coordinator never crashes for long — let's
skip its durable log entirely and keep decisions in memory; participants
re-ask on reconnect anyway." Find the run where two participants receive
**opposite verdicts for the same transaction** with no crash of either
participant. (Hint: coordinator restarts between the two answers, and
presumed abort does the rest.) Which single property of the commit point
does the in-memory version lack?
