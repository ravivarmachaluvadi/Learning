#!/usr/bin/env python3
"""Executable documentation: each test name is a scenario from README.md.

Run:  python3 -m unittest -v test_saga
"""

import unittest

from saga_lab import (
    Bus, Crash, Orchestrator, SagaLog, Service, fresh_world,
    wire_choreography,
)


def quiet(services, log):
    return Orchestrator(services, log, narrate=False)


class OrchestrationScenarios(unittest.TestCase):
    def setUp(self):
        self.services, self.log = fresh_world(capacity=3)

    def test_happy_path_books_all_three_services(self):
        result = quiet(self.services, self.log).run("t1")
        self.assertEqual(result, "COMPLETED")
        for name in ("flight", "hotel", "car"):
            self.assertEqual(self.services[name].bookings["t1"], "BOOKED")
            self.assertEqual(self.services[name].capacity, 2)

    def test_failure_at_car_compensates_hotel_then_flight(self):
        self.services["car"].fail_next_book = True
        result = quiet(self.services, self.log).run("t2")
        self.assertEqual(result, "ABORTED")
        self.assertEqual(self.services["flight"].bookings["t2"], "CANCELLED")
        self.assertEqual(self.services["hotel"].bookings["t2"], "CANCELLED")
        self.assertNotIn("t2", self.services["car"].bookings)
        # all capacity returned: the trip left no trace on availability
        for name in ("flight", "hotel", "car"):
            self.assertEqual(self.services[name].capacity, 3)

    def test_compensations_run_in_reverse_order(self):
        order = []
        for name in ("flight", "hotel"):
            svc = self.services[name]
            original = svc.cancel
            svc.cancel = (lambda bid, n=name, o=original:
                          (order.append(n), o(bid))[1])
        self.services["car"].fail_next_book = True
        quiet(self.services, self.log).run("t3")
        self.assertEqual(order, ["hotel", "flight"])

    def test_failure_at_first_step_compensates_nothing(self):
        self.services["flight"].fail_next_book = True
        result = quiet(self.services, self.log).run("t4")
        self.assertEqual(result, "ABORTED")
        for name in ("flight", "hotel", "car"):
            self.assertEqual(self.services[name].capacity, 3)
            self.assertNotIn("t4", self.services[name].bookings)

    def test_orchestrator_crash_then_recovery_completes_saga(self):
        with self.assertRaises(Crash):
            quiet(self.services, self.log).run(
                "t5", crash_after_book="hotel"
            )
        result = quiet(self.services, self.log).run("t5")  # new orchestrator
        self.assertEqual(result, "COMPLETED")

    def test_recovery_retry_does_not_double_book_thanks_to_idempotency(self):
        with self.assertRaises(Crash):
            quiet(self.services, self.log).run(
                "t6", crash_after_book="hotel"
            )
        quiet(self.services, self.log).run("t6")
        # hotel.book was called twice for t6, but only one seat was taken
        self.assertEqual(self.services["hotel"].capacity, 2)

    def test_completed_steps_are_skipped_on_recovery(self):
        with self.assertRaises(Crash):
            quiet(self.services, self.log).run(
                "t7", crash_after_book="hotel"
            )
        calls = []
        flight = self.services["flight"]
        original = flight.book
        flight.book = lambda bid: (calls.append(bid), original(bid))[1]
        quiet(self.services, self.log).run("t7")
        self.assertEqual(calls, [], "flight:done was in the log; not re-run")

    def test_failing_compensation_is_retried_until_it_succeeds(self):
        self.services["car"].fail_next_book = True
        self.services["hotel"].fail_next_cancel = 3
        result = quiet(self.services, self.log).run("t8")
        self.assertEqual(result, "ABORTED")
        self.assertEqual(self.services["hotel"].bookings["t8"], "CANCELLED")
        self.assertEqual(self.services["hotel"].capacity, 3)

    def test_mid_saga_state_is_visible_to_outsiders_no_isolation(self):
        services, log = fresh_world(capacity=1)
        orch = quiet(services, log)
        self.assertEqual(orch.run("t9", stop_after="hotel"), "PAUSED")
        # another customer looks while the saga is still in flight:
        self.assertEqual(services["hotel"].capacity, 0, "sees 'sold out'")
        services["car"].fail_next_book = True
        orch.run("t9")
        self.assertEqual(services["hotel"].capacity, 1, "later undone")


class ChoreographyScenarios(unittest.TestCase):
    def setUp(self):
        self.services, _ = fresh_world(capacity=3)
        self.bus = Bus(narrate=False)
        wire_choreography(self.bus, self.services)

    def test_event_chain_books_all_three_services(self):
        self.bus.publish("TripRequested", "c1")
        self.assertEqual(
            self.bus.history,
            ["TripRequested", "FlightBooked", "HotelBooked", "CarBooked"],
        )
        for name in ("flight", "hotel", "car"):
            self.assertEqual(self.services[name].bookings["c1"], "BOOKED")

    def test_car_failure_ripples_cancellations_backwards(self):
        self.services["car"].fail_next_book = True
        self.bus.publish("TripRequested", "c2")
        self.assertEqual(self.services["hotel"].bookings["c2"], "CANCELLED")
        self.assertEqual(self.services["flight"].bookings["c2"], "CANCELLED")
        for name in ("flight", "hotel", "car"):
            self.assertEqual(self.services[name].capacity, 3)


if __name__ == "__main__":
    unittest.main(verbosity=2)
