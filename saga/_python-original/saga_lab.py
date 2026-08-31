#!/usr/bin/env python3
"""Saga pattern — single-process learning lab.

The story: a travel agency books a trip = flight + hotel + car, in that
order. Each booking lives in a DIFFERENT service with its OWN database, so
no single ACID transaction can cover the trip. A saga runs the three local
transactions in sequence and, when one fails, runs COMPENSATIONS (cancel
bookings) in reverse order for the ones that already committed.

What is REAL here:      the coordination logic — saga log, step ordering,
                        reverse-order compensation, idempotency, recovery.
What is SIMULATED here: the services' databases (dicts inside objects that
                        survive "crashes"), the crash itself (an exception
                        at a chosen point; "restart" = a new orchestrator
                        object over the same durable saga log), and the
                        event bus for the choreography variant.

Run the guided experiments:
  python3 saga_lab.py happy-path
  python3 saga_lab.py car-fails-compensate
  python3 saga_lab.py orchestrator-crash-recovery
  python3 saga_lab.py compensation-retry
  python3 saga_lab.py dirty-read
  python3 saga_lab.py choreography
  python3 saga_lab.py all
"""

import sys


class Crash(Exception):
    """Simulates a process dying at an exact point."""


class StepFailed(Exception):
    """A service refused the request (business failure, e.g. sold out)."""


# ---------------------------------------------------------------------------
# Three services. Each owns its state (its "database") and offers a
# forward action (book) and a compensation (cancel). Both are IDEMPOTENT:
# they remember which booking-ids they have already handled.
# ---------------------------------------------------------------------------
class Service:
    def __init__(self, name, capacity):
        self.name = name
        self.capacity = capacity          # e.g. seats / rooms / cars left
        self.bookings = {}                # booking_id -> "BOOKED"/"CANCELLED"
        self.fail_next_book = False       # experiment knob: refuse next book
        self.fail_next_cancel = 0         # experiment knob: fail N cancels

    def book(self, booking_id):
        if booking_id in self.bookings and self.bookings[booking_id] == "BOOKED":
            return "already-booked (idempotent: no double charge)"
        if self.fail_next_book:
            self.fail_next_book = False
            raise StepFailed("%s: sold out" % self.name)
        if self.capacity <= 0:
            raise StepFailed("%s: sold out" % self.name)
        self.capacity -= 1
        self.bookings[booking_id] = "BOOKED"
        return "booked (capacity now %d)" % self.capacity

    def cancel(self, booking_id):
        if self.fail_next_cancel > 0:
            self.fail_next_cancel -= 1
            raise ConnectionError("%s: cancel endpoint timed out" % self.name)
        if self.bookings.get(booking_id) == "BOOKED":
            self.bookings[booking_id] = "CANCELLED"
            self.capacity += 1
            return "cancelled (capacity back to %d)" % self.capacity
        # cancelling something never booked (or already cancelled) is a no-op
        return "nothing to cancel (idempotent no-op)"


# ---------------------------------------------------------------------------
# Orchestration: one coordinator drives the steps and owns a DURABLE log.
# The log is the saga's memory: after any crash, a new orchestrator can
# read it and know exactly where to resume.
# ---------------------------------------------------------------------------
class SagaLog:
    """Durable append-only log. Survives orchestrator crashes (in real life:
    a database table; here: an object that outlives the orchestrator)."""

    def __init__(self):
        self.records = []                 # (saga_id, entry) tuples

    def append(self, saga_id, entry):
        self.records.append((saga_id, entry))

    def entries(self, saga_id):
        return [e for (s, e) in self.records if s == saga_id]


class Orchestrator:
    STEPS = ["flight", "hotel", "car"]    # forward order

    def __init__(self, services, log, narrate=True):
        self.services = services          # name -> Service
        self.log = log
        self.narrate = narrate

    def _say(self, msg):
        if self.narrate:
            print("    " + msg)

    def run(self, saga_id, crash_after_book=None, stop_after=None):
        """Execute the saga, resuming from whatever the log already holds.

        crash_after_book: name of a step; the orchestrator 'dies' after the
        service booked but BEFORE the log records the completion — the gap
        that forces services to be idempotent.
        stop_after: name of a step; pause the saga (cleanly, log intact)
        right after that step — used to peek at mid-saga state.
        """
        done = self.log.entries(saga_id)
        if "SAGA_STARTED" not in done:
            self.log.append(saga_id, "SAGA_STARTED")
            self._say("saga %s: STARTED" % saga_id)

        for step in self.STEPS:
            if ("%s:done" % step) in done:
                self._say("saga log says '%s' already done — skipping" % step)
                continue
            svc = self.services[step]
            try:
                result = svc.book(saga_id)
                self._say("%s.book(%s) -> %s" % (step, saga_id, result))
                if crash_after_book == step:
                    raise Crash(
                        "orchestrator died after %s.book(), before logging it"
                        % step
                    )
                self.log.append(saga_id, "%s:done" % step)
                if stop_after == step:
                    self._say("saga %s: PAUSED after '%s' (still in flight)"
                              % (saga_id, step))
                    return "PAUSED"
            except StepFailed as e:
                self._say("%s.book(%s) -> FAILED: %s" % (step, saga_id, e))
                self.log.append(saga_id, "%s:failed" % step)
                self._compensate(saga_id)
                self.log.append(saga_id, "SAGA_ABORTED")
                self._say("saga %s: ABORTED (all completed steps undone)"
                          % saga_id)
                return "ABORTED"
        self.log.append(saga_id, "SAGA_COMPLETED")
        self._say("saga %s: COMPLETED" % saga_id)
        return "COMPLETED"

    def _compensate(self, saga_id):
        """Undo completed steps in REVERSE order. Compensations may fail
        transiently; they are retried until they succeed — a compensation
        is not allowed to give up."""
        done = self.log.entries(saga_id)
        completed = [s for s in self.STEPS if ("%s:done" % s) in done]
        for step in reversed(completed):
            svc = self.services[step]
            while True:
                try:
                    result = svc.cancel(saga_id)
                    self._say("COMPENSATE %s.cancel(%s) -> %s"
                              % (step, saga_id, result))
                    self.log.append(saga_id, "%s:compensated" % step)
                    break
                except ConnectionError as e:
                    self._say("COMPENSATE %s.cancel(%s) -> %s — RETRYING"
                              % (step, saga_id, e))


# ---------------------------------------------------------------------------
# Choreography: no coordinator. Services subscribe to each other's events
# on a bus and react. The saga's flow lives in the subscriptions.
# ---------------------------------------------------------------------------
class Bus:
    def __init__(self, narrate=True):
        self.handlers = {}                # event type -> [fn]
        self.narrate = narrate
        self.history = []

    def subscribe(self, event_type, fn):
        self.handlers.setdefault(event_type, []).append(fn)

    def publish(self, event_type, saga_id):
        self.history.append(event_type)
        if self.narrate:
            print("    event: %s (%s)" % (event_type, saga_id))
        for fn in list(self.handlers.get(event_type, [])):
            fn(saga_id)


def wire_choreography(bus, services):
    """The whole trip-flow, expressed only as reactions to events.
    TripRequested -> flight books -> FlightBooked -> hotel books ->
    HotelBooked -> car books -> CarBooked (done).
    Any *Failed event triggers the cancellations that undo prior steps."""

    def try_book(svc, ok_event, fail_event):
        def handler(saga_id):
            try:
                svc.book(saga_id)
                bus.publish(ok_event, saga_id)
            except StepFailed:
                bus.publish(fail_event, saga_id)
        return handler

    bus.subscribe("TripRequested",
                  try_book(services["flight"], "FlightBooked", "FlightFailed"))
    bus.subscribe("FlightBooked",
                  try_book(services["hotel"], "HotelBooked", "HotelFailed"))
    bus.subscribe("HotelBooked",
                  try_book(services["car"], "CarBooked", "CarFailed"))

    # compensation chain: each failure event undoes the step before it
    bus.subscribe("CarFailed",
                  lambda sid: (services["hotel"].cancel(sid),
                               bus.publish("HotelCancelled", sid)))
    bus.subscribe("HotelFailed",
                  lambda sid: (services["flight"].cancel(sid),
                               bus.publish("FlightCancelled", sid)))
    bus.subscribe("HotelCancelled",
                  lambda sid: (services["flight"].cancel(sid),
                               bus.publish("FlightCancelled", sid)))


# ---------------------------------------------------------------------------
# Guided experiments
# ---------------------------------------------------------------------------
def fresh_world(capacity=3):
    services = {
        "flight": Service("flight", capacity),
        "hotel": Service("hotel", capacity),
        "car": Service("car", capacity),
    }
    return services, SagaLog()


def show_state(services):
    for s in services.values():
        print("    %-6s capacity=%d bookings=%s"
              % (s.name, s.capacity, s.bookings or "{}"))


def experiment_happy_path():
    print("=" * 72)
    print("EXPERIMENT 1: happy path — three local transactions in sequence")
    print("=" * 72)
    services, log = fresh_world()
    Orchestrator(services, log).run("trip-1")
    show_state(services)
    print()
    print("VERDICT: no distributed transaction anywhere — three ordinary")
    print("local commits, glued together by the orchestrator and its log.")
    print()


def experiment_car_fails():
    print("=" * 72)
    print("EXPERIMENT 2: the car rental is sold out — compensate backwards")
    print("=" * 72)
    services, log = fresh_world()
    services["car"].fail_next_book = True
    result = Orchestrator(services, log).run("trip-2")
    show_state(services)
    print()
    print("VERDICT: %s. Flight and hotel were already COMMITTED — they" % result)
    print("cannot be rolled back, only cancelled by new transactions, in")
    print("reverse order. The trip ends atomically-in-effect: all or nothing.")
    print()


def experiment_orchestrator_crash():
    print("=" * 72)
    print("EXPERIMENT 3: orchestrator dies mid-saga; a new one resumes")
    print("=" * 72)
    services, log = fresh_world()
    print("First run — crashes after hotel.book() succeeds but BEFORE the")
    print("log records it:")
    try:
        Orchestrator(services, log).run("trip-3", crash_after_book="hotel")
    except Crash as e:
        print("    CRASH: %s" % e)
    print("Recovery — a NEW orchestrator reads the durable saga log:")
    Orchestrator(services, log).run("trip-3")
    show_state(services)
    hotel = services["hotel"]
    print()
    print("VERDICT: the log said 'flight done' (skipped) but knew nothing of")
    print("the hotel, so the new orchestrator retried hotel.book(). The")
    print("service recognized the booking id -> no double charge. The saga")
    print("log gives durability; SERVICE IDEMPOTENCY absorbs the retry.")
    print("Hotel capacity is %d — booked once, not twice." % hotel.capacity)
    print()


def experiment_compensation_retry():
    print("=" * 72)
    print("EXPERIMENT 4: a compensation fails — it must be retried, forever")
    print("=" * 72)
    services, log = fresh_world()
    services["car"].fail_next_book = True
    services["hotel"].fail_next_cancel = 2      # first two cancels time out
    Orchestrator(services, log).run("trip-4")
    show_state(services)
    print()
    print("VERDICT: a forward step may fail permanently ('sold out') and the")
    print("saga aborts. A COMPENSATION is different: it is not allowed to")
    print("give up, because the saga is already half-done. Compensations must")
    print("be retryable (and therefore idempotent) until they succeed.")
    print()


def experiment_dirty_read():
    print("=" * 72)
    print("EXPERIMENT 5: the missing I in ACID — outsiders see mid-saga state")
    print("=" * 72)
    services, log = fresh_world(capacity=1)
    services["car"].fail_next_book = True
    orch = Orchestrator(services, log)

    orch.run("trip-5", stop_after="hotel")     # pause mid-saga, log intact
    print("  >>> Meanwhile another customer asks: any hotel rooms left?")
    print("      hotel capacity right now: %d  (they are told: SOLD OUT)"
          % services["hotel"].capacity)
    result = orch.run("trip-5")                # resume: car step fails
    print("  >>> After the saga %s, hotel capacity is back to %d."
          % (result.lower(), services["hotel"].capacity))
    print()
    print("VERDICT: the other customer observed state that was later undone —")
    print("a dirty read at the business level. ACID transactions hide")
    print("intermediate state (Isolation); sagas do NOT. Real systems soften")
    print("this with 'PENDING' statuses (semantic lock) instead of hiding it.")
    print()


def experiment_choreography():
    print("=" * 72)
    print("EXPERIMENT 6: choreography — same saga, no coordinator")
    print("=" * 72)
    services, _ = fresh_world()
    bus = Bus()
    wire_choreography(bus, services)
    print("Happy path (watch the event chain):")
    bus.publish("TripRequested", "trip-6a")
    show_state(services)
    print("Failure path — car sold out (watch compensation events ripple):")
    services["car"].fail_next_book = True
    bus.publish("TripRequested", "trip-6b")
    show_state(services)
    print()
    print("VERDICT: the flow works, but notice it is written NOWHERE — it")
    print("emerges from subscriptions spread across services. Adding a step")
    print("means touching several services; answering 'where is trip-6b")
    print("stuck?' means correlating events. That is the orchestration vs")
    print("choreography tradeoff in one screen.")
    print()


EXPERIMENTS = {
    "happy-path": experiment_happy_path,
    "car-fails-compensate": experiment_car_fails,
    "orchestrator-crash-recovery": experiment_orchestrator_crash,
    "compensation-retry": experiment_compensation_retry,
    "dirty-read": experiment_dirty_read,
    "choreography": experiment_choreography,
}

if __name__ == "__main__":
    choice = sys.argv[1] if len(sys.argv) > 1 else "all"
    if choice == "all":
        for fn in EXPERIMENTS.values():
            fn()
    elif choice in EXPERIMENTS:
        EXPERIMENTS[choice]()
    else:
        print("usage: python3 saga_lab.py [%s|all]" % "|".join(EXPERIMENTS))
        sys.exit(1)
