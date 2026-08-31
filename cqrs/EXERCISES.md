# Exercises

**Predict** (reason before running), **modify** (change the lab, explain),
**debug** (hunt the planted bug). Answers in [`SOLUTIONS.md`](SOLUTIONS.md) —
write your answers down first; the gap between guess and output is the
learning.

---

## Predict

**P1 — the window, precisely.**
Two orders are placed (`ord-1` priya ₹100, `ord-2` amit ₹50, same day), then
the projector runs with `batch=1` (one event only). **Point A**: what do
`dashboard('priya')`, `dashboard('amit')`, `revenue(day)`, and `lag()` each
return? Then `ord-1` is cancelled. **Point B**: `revenue`? `lag`? Then
`runOnce()` with the default batch. **Point C**: priya's dashboard row,
`revenue`, `lag`? Write down all eight values, then check:

```bash
cat > P1.java <<'JAVA'
import java.util.List;

public class P1 {
    public static void main(String[] args) {
        String D = "2026-08-27";
        CqrsLab.WriteSide w = new CqrsLab.WriteSide();
        CqrsLab.ReadSide r = new CqrsLab.ReadSide();
        CqrsLab.Projector p = new CqrsLab.Projector(w, r);
        w.placeOrder("ord-1", "priya", List.of(new CqrsLab.Item("kb", 1, 100.0)), D);
        w.placeOrder("ord-2", "amit", List.of(new CqrsLab.Item("mouse", 1, 50.0)), D);
        p.runOnce(1);
        System.out.println("A: " + r.dashboard("priya") + " " + r.dashboard("amit")
                + " " + r.revenue(D) + " lag " + p.lag());
        w.cancelOrder("ord-1", D);
        System.out.println("B: " + r.revenue(D) + " lag " + p.lag());
        p.runOnce();
        System.out.println("C: " + r.dashboard("priya") + " " + r.revenue(D) + " lag " + p.lag());
    }
}
JAVA
java -cp ../lib/h2.jar P1.java && rm P1.java
```

**P2 — can the two chalkboards disagree?**
In *this lab*, can `customer_dashboard` show an order whose money is not yet
in `daily_revenue` (or vice versa)? Read `Projector.runOnce` before
answering, and say exactly which line settles it. Then: in a production
system where the dashboard and revenue are separate services with separate
projectors on the same feed — same question. What changes, and what new
operational number do you suddenly care about?

---

## Modify

**M1 — the atomicity unit is the batch.**
Call `runOnce(1, true)` — batch of 1, crash before the checkpoint — on a feed
with two events, catch the exception, then call `runOnce(1)` twice. Predict
the read state after each call. What is the precise sentence describing what
a crash can and cannot do to the read model in this design?

**M2 — `waitFor` meets a dead projector.**
`waitFor(p, seq)` loops until the checkpoint catches up. What does it do if
the projector's source is unreachable (simulate: make `runOnce` return 0
without applying — e.g. temporarily point it at an empty `WriteSide`)? Fix
`waitFor` to give up after N attempts — and then answer the real design
question: when read-your-own-writes times out, what should the *user* see?
Give two defensible answers with their trade-offs.

**M3 — invent a board (constructive).**
Add a `topProducts(n)` read model: a real table in `ReadSide`, projected
from `OrderPlaced` item quantities (and decremented on `OrderCancelled`),
plus a query method. Make it idempotent (redelivery must not double-count
units — the dashboard-rowcount trick in `apply` shows one way). Prove it
with a redelivery test shaped like
`test_duplicate_delivery_is_harmless_when_the_projection_is_idempotent`.

---

## Debug

**D1 — negative revenue on quiet days.**
`java -cp ../lib/h2.jar BrokenCqrs.java` — a "cleaned up" cancel projection
passes its manual test (revenue 0 after place+cancel), yet after a routine
redelivery finance sees **-80.0**. Find the bug (it is an ordering bug of two
lines in `CleanedUpProjector.apply`), explain why the manual test could never
catch it, name the test in `CqrsTest.java` that would have, and state the
general rule for writing idempotent projections that this bug violates.
