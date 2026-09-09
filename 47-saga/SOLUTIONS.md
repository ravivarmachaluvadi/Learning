# Solutions

## Tier 1 — Predict

**P1.** Sequential case: A completes (all capacities 1→0); B is refused at
the flight step and aborts having booked nothing — correct and fair.
Interleaved case, actual output: A pauses holding flight+hotel; **B is
refused at its first step (`flight: sold out`) and ABORTS**; A resumes and
COMPLETES (car capacity was untouched by B). B lost — here to a trip that
did ultimately happen, so no harm was done *this time*. The anomaly is that
B's rejection was based on **provisional, in-flight state**: had A's car
step failed instead, A would have aborted, capacity would have returned,
and B was turned away for a room that was never really sold — the
business-level **dirty read** of section 10(e). Whether the provisional
read "turns out fine" is decided by A's future, which B cannot see; the
countermeasure (semantic lock / PENDING status) doesn't prevent the read,
it makes the provisionality explicit so the business can choose a policy
(waitlist B, hold B for 10 minutes, oversell by one).

**P2.** Recovery reads the log: it contains `SAGA_STARTED` only (the crash
hit before `flight:done` was written). So the new orchestrator re-calls
**flight** — which answers "already-booked (idempotent)" because the crash
in P2 happened *after* the service committed — then proceeds to call hotel
and car normally (first time each). In experiment 3 the crash was after
*hotel's* book: flight WAS logged (skipped without any call), hotel was
re-called and deduped, car ran fresh. The general rule: the step in the
"acted but not yet logged" gap is exactly the one that gets a duplicate
call; everything before is skipped by the log, everything after runs
normally.

**P3.** Expected bus history:
`TripRequested → FlightBooked → HotelFailed → FlightCancelled` —
one compensation event (`FlightCancelled`, undoing the flight); the **car
service is never touched** (its trigger, `HotelBooked`, never fired).
Compensation count scales with how deep the saga got, not with saga length.

**P4.** The pivot must sit **before shipping** — at the card charge:
order = reserve (compensatable) → charge (pivot) → ship (retryable) →
email (retryable). "Charge fails after the parcel shipped" must be made
**impossible by ordering**, because shipping can't be undone and a failed
charge can't be tolerated after it. If the business insists on
ship-before-charge, then the charge must be made *retryable* — i.e. it is
not allowed to fail permanently, which means pre-authorizing the card
(a hold) before shipping… which is a reserve step, which is the same
answer wearing a different hat: un-compensatable and can-fail steps must
be separated by a pivot, and "can-fail" is converted to "can't-fail" by
reserving earlier.

## Tier 2 — Modify

**M1.** `test_compensations_run_in_reverse_order` fails (`[flight, hotel]`
instead of `[hotel, flight]`). Concrete case where forward
order is *wrong*, not just untested: step 1 creates a customer record,
step 2 creates an order **referencing** that customer. Forward-order
compensation deletes the customer first — and the order-cancellation then
runs against a dangling reference (or a foreign-key error, and now the
compensation itself fails). Undo must unwind dependencies in the opposite
order they were built — same reason destructors and `defer` stacks run
LIFO.

**M2.** `test_recovery_retry_does_not_double_book_thanks_to_idempotency`
fails: hotel capacity ends at **1** (two seats taken for one trip; expected
2). The broken pair: logging *after* acting means a crash in the gap makes
the recovery re-act; that's only safe if re-acting is a no-op — i.e. the
participant is idempotent. The two candidate fixes: (1) make participants
idempotent by saga id — generally available, the standard answer; (2) make
"act + log" atomic — only possible when the orchestrator and participant
share one database (a modular monolith), which across services they don't.
(Logging *before* acting just moves the hole — see D1.)

**M3.** The peeking customer now sees capacity 0 but with the room in
`pending`, not `bookings` — the system can now *say* "1 room held
provisionally, may free up" instead of lying "sold out"; and after the
abort, the pending entry vanishes without a `CANCELLED` tombstone. The new
operational question: **a hold with no confirm is a leak** — the
orchestrator might die forever between last step and the confirm sweep, so
every hold needs an expiry (and expiry of a hold whose saga actually
completed but whose confirm was lost = a booked customer losing their
room — so confirm must beat expiry, with margin, and expiry must check the
saga's fate first). You have re-invented **TCC (Try-Confirm-Cancel)**:
`book→pending` is Try, `confirm` is Confirm, compensation is Cancel, and
the expiry is TCC's timeout. Section 16's line "application-level 2PC with
timeouts" should now feel earned.

**M4.** Choreography: you must add/change at least four wirings —
subscribe insurance to `CarBooked`; give insurance its own
`InsuranceBooked`/`InsuranceFailed` events; subscribe a handler for
`InsuranceFailed` that cancels the car and publishes `CarCancelled`; and
subscribe `CarCancelled` to the existing hotel-cancel handler (extending
the backward chain by one link). Miss the last one and a failed insurance
strands a booked hotel+flight — silently. Orchestration: append
`"insurance"` to `STEPS`, register the service — the compensation chain
comes free from `completed.reversed()`. Lesson: choreography's complexity
grows in the *edges* (every step touches its neighbors' subscriptions,
failure paths are hand-wired); orchestration's grows in one node.

## Tier 3 — Debug

**D1.** `test_orchestrator_crash_then_recovery_completes_saga` — with
intent-logging, the crash-after-book run has already written `hotel:done`…
but so has a crash *before* book, and that's the killer: log says done,
service never acted. Concretely: crash between `log.append(sagaId, "hotel:done")`
and `hotel.book()` → recovery skips the hotel → saga "COMPLETES" with **no
hotel room** — the customer lands with nowhere to sleep (losing a booking
and the customer; arguably worse than double-charging, which idempotency
can prevent and support can refund). The general law, same as outbox M2:
with two non-atomic acts, the one you do FIRST is the one that can end up
done-without-the-other. Record-then-act risks phantom completions
(at-most-once); act-then-record risks duplicate actions (at-least-once) —
and you choose act-then-record because duplicates are absorbable by
idempotency, while phantoms are unrecoverable silence.

**D2.** Timeline: car.book command sent; car service is slow (GC pause,
lock wait); orchestrator times out at 30 s, treats it as `StepFailed`,
compensates hotel and flight, logs `SAGA_ABORTED`; the car service then
finishes processing the *still-queued* command and books — after the saga
closed. A timeout is not a failure — it is *unknown outcome*. The two
missing properties: (1) **compensate the timed-out step too** — the
abort path must send `car.cancel(trip)` even though car never confirmed
booking, and idempotent-cancel ("nothing to cancel" is a fine no-op) makes
that safe whether the book landed or not — the lab's `Service.cancel`
no-op branch is exactly this; (2) at the delivery layer, commands need to
be **cancellable/fenced** (the cancel must not be overtaken by the stale
book — ordering per saga id, e.g. same partition key, per the outbox lab's
ordering section). Cancel-the-unknown + ordered delivery closes the hole.

**D3.** `test_failing_compensation_is_retried_until_it_succeeds` fails —
the hotel booking stays `BOOKED`, capacity stays 2, while the log claims
`hotel:compensated`: the log now *lies*, which is worse than being behind,
because recovery trusts it. For the nightly job to be correct it would
need: a durable record that the compensation is *pending* (not "done" —
so a new log state, `compensation-pending`), idempotent cancels (have),
detection of which cancels actually landed (reconciliation against the
service's state), retry with escalation, and ordering guarantees against
in-flight duplicates. At that point the "nightly job" has become… a
message relay draining a durable to-do table — i.e. the **transactional
outbox's relay**, rediscovered. The correct design was never "log
compensated and hope"; it is "log compensation-*requested* durably, let a
reliable deliverer own the retries" — which is what real orchestrators do
by emitting compensation commands through their outbox.
