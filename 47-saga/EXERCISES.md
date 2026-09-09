# Exercises — saga

Attempt each before opening `SOLUTIONS.md`. Predict exercises are runnable;
commit to an answer first, then run the command.

## Tier 1 — Predict

**P1.** All three services have capacity 1. Two sagas, `trip-A` and
`trip-B`, run one after the other (A fully first, then B). Then rerun it
where A books flight+hotel, pauses (`stopAfter = "hotel"`), B runs fully,
then A resumes and its car step succeeds… can it? Predict both outcomes,
then check the second with:
```bash
cat > P1.java <<'EOF'
public class P1 {
    public static void main(String[] args) {
        var w = SagaLab.freshWorld(1);
        var a = new SagaLab.Orchestrator(w.services(), w.log());
        System.out.println(a.run("trip-A", null, "hotel"));
        System.out.println(new SagaLab.Orchestrator(w.services(), w.log()).run("trip-B"));
        System.out.println(a.run("trip-A"));
    }
}
EOF
java P1.java
```
Which customer lost, and to a trip that ended how? What is this anomaly
called in section 10(e)?

**P2.** The orchestrator crashes after `flight.book()` succeeds but before
logging `flight:done`. On recovery, exactly which services receive a
(re-)call, and why does the flight service's answer differ from the hotel's
in experiment 3?

**P3.** In the choreography experiment, suppose the HOTEL is sold out
instead of the car (`services.get("hotel").failNextBook = true`). Write down
the exact event sequence you expect on the bus, then run a scratch
`P3.java` (adapt experiment 6) to verify. How many compensation
events fire, and which service is never touched at all?

**P4.** A saga's steps are: (1) reserve inventory, (2) charge card,
(3) ship parcel, (4) email receipt. Shipping a parcel cannot be
compensated (recalling a shipped parcel is a new business process, not an
undo). Where must the pivot sit, which steps must be retryable, and what
does that force you to say about a card charge that fails *after* the
parcel shipped?

## Tier 2 — Modify

**M1.** Change `Orchestrator.compensate` to run compensations in FORWARD
order (`completed` instead of `completed.reversed()`). Which test fails?
Construct a concrete business case (any domain) where forward-order
compensation is not just untested but actually *wrong*.

**M2.** Remove the idempotency guard from `Service.book` (delete the first
two lines). Which test fails, with what wrong number? Explain why the
*combination* saga-log-after-action + non-idempotent-participant is the
broken pair, and name the two possible fixes (only one of which is
generally available).

**M3.** Add a semantic lock: give `Service` a `pending` map; `book` puts
the id in `pending` (still decrementing capacity), a new `confirm(id)`
moves it to `bookings`, and the orchestrator confirms all steps only after
the last step succeeds. What does the "another customer peeks" experiment
show now? What NEW operational question does confirm-at-the-end create
(hint: what if confirm never arrives)? You have just re-invented which
related pattern from section 16?

**M4.** In `wireChoreography`, add a fourth step: travel insurance, booked
after the car. List every subscription you had to add or change, including
the failure chain. Compare with the one-line change in
`Orchestrator.STEPS` (+ one entry in `services`). What does this tell you
about where each style's complexity grows?

## Tier 3 — Debug

**D1.** A teammate reworks the orchestrator to write the saga log entry
BEFORE calling the service ("log the intent, then act"):
`log.append(sagaId, step + ":done"); svc.book(sagaId);`. All tests still pass
except one crash-recovery scenario. Which test catches it, what is the
end-state of the world, and is this failure mode losing money or losing
bookings? Compare it to the outbox lab's mark-then-publish exercise (M2
there) — what is the general law here?

**D2.** Production incident: about once a week, a trip ends with a
cancelled flight, a cancelled hotel — and a BOOKED car. No errors in the
orchestrator's logs; the saga log shows `SAGA_ABORTED`. The car service's
logs show its `book` succeeded *after* the saga aborted. Reconstruct the
timeline (hint: the orchestrator uses a 30-second timeout on commands and
treats timeout as `StepFailed`). Which two properties from this lab, in
combination, prevent it? (One is a compensation-design rule, one is a
delivery-layer rule.)

**D3.** Someone "simplifies" the compensation loop: on `ConnectionError`
the orchestrator logs `{step}:compensated` anyway and continues ("we'll
clean it up in a nightly job"). Which test fails? Then argue the harder
question: the nightly-cleanup design is not insane — what would it need to
be actually correct, and what has it become at that point?
