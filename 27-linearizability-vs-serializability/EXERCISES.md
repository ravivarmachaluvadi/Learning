# Exercises — linearizability vs serializability

All pencil-and-paper or visualization work; nothing to run. Attempt each
before opening `SOLUTIONS.md`. "Card N" = scenario card in
`visualization.html`.

## Rung 1 — Explain

**1.1** In your own words, no notation: what question does linearizability
answer, what question does serializability answer, and why can a system pass
one while failing the other?

**1.2** A colleague says "CAP's C means you can't have transactions under a
partition." What did they confuse, and what does CAP's C actually name?

## Rung 2 — Identify

**2.1** Open card 1 (the vanished deposit). Which judge panel goes red, and
what sentence does the green serializability panel give as its explaining
order? Point at the exact thing that makes that order legal for one judge
and illegal for another.

**2.2** In card 4 (write skew), identify: the objects, the transactions, the
shared *thing* the two transactions both depend on — and say which judge is
structurally unable to see that thing, and why.

## Rung 3 — Predict

**3.1** History: T1 = `write x=1` commits at 10:00. T2 = `read x → 0`,
running 10:00:05–10:00:06. Predict all three verdicts (L for object x, S,
SS). Check with card 1 (drag T2 fully after T1).

**3.2** Card 2, grouping OFF: two clients each do `read x→1000` then
`write x=200`, interleaved. Predict the three verdicts. Now predict what
changes when you toggle grouping ON — which single panel flips? Check.

**3.3** Card 5: T1 writes x and *finishes*; T2 then writes y; T3 reads
y→new, x→old. Before running: which serial order will the S judge print,
and which pair of transactions does the SS judge refuse to reorder?

## Rung 4 — Modify

**4.1** In card 1, drag T2 leftward until it *overlaps* T1. At what point
does the strict-serializability verdict change, and why exactly there? What
does this tell you about which histories real-time order actually
constrains?

**4.2** In card 5, give T2 a second write: besides `y=1`, T2 also writes
`x=2` (so T1 and T2 now conflict on x). T3 still reads `y→1, x→0`. Derive
by hand: does the serializability verdict survive, and precisely which
freedom did the S judge lose?

## Rung 5 — Trace

**5.1** By hand, all three judges, showing every candidate order: objects
`x`,`y` start 0; T1 = `write x=1; write y=1`; T2, overlapping T1, reads
`y→0` first, then `x→1` — the same answers as §10 (new x, old y) but read
in the **reverse order**, with these bars: `wx[1,2] wy[3.5,4.5]`,
`ry[2.2,3] rx[5,6]`. Which verdicts change vs §10, and which stay? Check
with card 6.

**5.2** Trace the §12 cross-bank composition example concretely: X and Y
each a register; T1 = `write X=debit; write Y=credit`; T2 = `read X; read
Y`. Write the interleaving in which each bank *locally* sees a serial
order but no global one exists, and name what T2 read.

## Rung 6 — Spot the flaw

**6.1** A vendor claims: "Our store is linearizable per key. Therefore any
program that only ever touches one key per operation is fully correct —
no transactions needed." Construct the smallest concrete counterexample
history (real values) and name the guarantee that silently did the work in
their claim.

**6.2** An architect proposes: "We'll get strict serializability cheaply:
run serializable Postgres, and route every read to the nearest replica —
replicas apply the exact same committed transactions in the exact same
order, so serializability is preserved." Two flaws hide here — one about
which guarantee replicas preserve, one about what 'preserved' even means
for strictness. Give a concrete failing history for each.

## Rung 7 — Handle failure

**7.1** The mobile app of incident 1 must never show a balance older than
the customer's own last acknowledged deposit, but the business refuses to
pay for linearizable reads on every request. Design the cheapest fix on
paper: what token flows where, who checks it, and which grid cell have you
actually landed in (it is not the top-right)?

**7.2** Your payment service runs on Oracle `SERIALIZABLE` (you now know
what that really is). The invariant "an account's holds may never exceed
its balance" spans many rows. Without changing databases, make write skew
impossible for this invariant — name the mechanism from a prior lab, the
exact statement shape, and what new failure mode you accepted in trade.

## Rung 8 — Design

**8.1** Design the consistency story for a brokerage: (a) trade execution
(debit cash, credit shares — must never fracture), (b) the portfolio
screen (must reflect the user's own just-completed trade), (c) the
end-of-day compliance report (must be a coherent snapshot, may be an hour
old). For each: name the grid cell it needs, the cheapest mechanism that
buys that cell and nothing more, and the concrete anomaly you accepted by
not buying more. Then answer: where in this design would buying Spanner
change anything, and where would it be wasted money?
