"""Scenario tests = executable documentation for the CQRS lab.
Run: python3 -m unittest -v test_cqrs
"""
import unittest

from cqrs_lab import CommandRejected, Projector, ReadSide, WriteSide, wait_for

DAY = "2026-08-27"


def shop():
    w, r = WriteSide(), ReadSide()
    return w, r, Projector(w, r)


class CommandSideScenarios(unittest.TestCase):

    def test_command_that_violates_an_invariant_is_rejected_and_emits_nothing(self):
        w, r, p = shop()
        with self.assertRaises(CommandRejected):
            w.place_order("ord-1", "priya", [("keyboard", -1, 80.0)], day=DAY)
        self.assertEqual(w.last_seq(), 0, "a rejected command must not leak an event")

    def test_cannot_cancel_a_shipped_order_and_no_event_is_emitted_for_the_attempt(self):
        w, r, p = shop()
        w.place_order("ord-1", "priya", [("keyboard", 1, 80.0)], day=DAY)
        w.mark_shipped("ord-1")
        before = w.last_seq()
        with self.assertRaises(CommandRejected):
            w.cancel_order("ord-1", day=DAY)
        self.assertEqual(w.last_seq(), before)


class ConsistencyWindowScenarios(unittest.TestCase):

    def test_query_is_stale_until_the_projector_runs(self):
        w, r, p = shop()
        w.place_order("ord-1", "priya", [("keyboard", 1, 80.0)], day=DAY)
        self.assertEqual(r.dashboard("priya"), [], "the write happened; the read side hasn't heard yet")
        self.assertEqual(p.lag(), 1)

    def test_query_is_fresh_after_the_projector_catches_up(self):
        w, r, p = shop()
        w.place_order("ord-1", "priya", [("keyboard", 1, 80.0), ("mouse", 2, 20.0)], day=DAY)
        p.run_once()
        self.assertEqual(r.dashboard("priya"), [("ord-1", "PLACED", 120.0)])
        self.assertEqual(r.revenue(DAY), 120.0)
        self.assertEqual(p.lag(), 0)

    def test_read_your_own_writes_via_wait_for(self):
        w, r, p = shop()
        w.place_order("ord-1", "priya", [("keyboard", 1, 80.0)], day=DAY)
        wait_for(p, w.last_seq())          # the "wait until you can see me" technique
        self.assertEqual(len(r.dashboard("priya")), 1)


class RebuildScenarios(unittest.TestCase):

    def test_read_model_rebuilt_from_scratch_matches_the_incrementally_built_one(self):
        w, r, p = shop()
        w.place_order("ord-1", "priya", [("keyboard", 1, 80.0)], day=DAY)
        p.run_once()
        w.place_order("ord-2", "amit", [("desk", 1, 300.0)], day=DAY)
        w.cancel_order("ord-2", day=DAY)
        p.run_once()

        fresh = ReadSide()                              # "drop the read database"
        Projector(w, fresh).run_once()                  # replay history
        self.assertEqual(fresh.dashboard("priya"), r.dashboard("priya"))
        self.assertEqual(fresh.dashboard("amit"), r.dashboard("amit"))
        self.assertEqual(fresh.revenue(DAY), r.revenue(DAY))

    def test_a_read_model_invented_later_backfills_from_old_events(self):
        w, r, p = shop()
        w.place_order("ord-1", "priya", [("keyboard", 2, 80.0), ("mouse", 1, 20.0)], day=DAY)
        w.place_order("ord-2", "amit", [("keyboard", 1, 80.0)], day=DAY)
        # Months later, someone asks a question nobody designed for:
        # "what are our top products by units sold?" Build it from history.
        units: dict[str, int] = {}
        for _seq, type_, e in w.events_after(0):
            if type_ == "OrderPlaced":
                for item in e["items"]:
                    units[item["product"]] = units.get(item["product"], 0) + item["qty"]
        self.assertEqual(units, {"keyboard": 3, "mouse": 1})


class DeliveryFaultScenarios(unittest.TestCase):

    def _redeliver_everything(self, read):
        read.db.execute("UPDATE projector_checkpoint SET last_seq=0")
        read.db.commit()

    def test_duplicate_delivery_is_harmless_when_the_projection_is_idempotent(self):
        w, r, p = shop()
        w.place_order("ord-1", "priya", [("keyboard", 1, 80.0)], day=DAY)
        w.cancel_order("ord-1", day=DAY)
        p.run_once()
        self._redeliver_everything(r)                   # at-least-once delivery strikes
        p.run_once()
        self.assertEqual(r.revenue(DAY), 0.0, "place then cancel = 0, even after replay")
        self.assertEqual(r.dashboard("priya"), [("ord-1", "CANCELLED", 80.0)])

    def test_duplicate_delivery_double_counts_when_the_projection_is_naive(self):
        w = WriteSide(); r = ReadSide()
        naive = Projector(w, r, idempotent=False)
        w.place_order("ord-1", "priya", [("keyboard", 1, 80.0)], day=DAY)
        naive.run_once()
        self._redeliver_everything(r)
        naive.run_once()
        self.assertEqual(r.revenue(DAY), 160.0, "the bug we are demonstrating: 80 counted twice")

    def test_crash_mid_batch_with_atomic_checkpoint_is_all_or_nothing(self):
        w, r, p = shop()
        w.place_order("ord-1", "priya", [("keyboard", 1, 80.0)], day=DAY)
        w.place_order("ord-2", "amit", [("desk", 1, 300.0)], day=DAY)
        with self.assertRaises(RuntimeError):
            p.run_once(crash_before_checkpoint=True)
        # nothing half-applied, checkpoint untouched:
        self.assertEqual(r.dashboard("priya"), [])
        self.assertEqual(r.checkpoint(), 0)
        p.run_once()                                    # restart: everything applies exactly once
        self.assertEqual(r.revenue(DAY), 380.0)

    def test_crash_mid_batch_without_atomic_checkpoint_corrupts_a_naive_projection(self):
        w = WriteSide(); r = ReadSide()
        broken = Projector(w, r, idempotent=False, checkpoint_in_txn=False)
        w.place_order("ord-1", "priya", [("keyboard", 1, 80.0)], day=DAY)
        with self.assertRaises(RuntimeError):
            broken.run_once(crash_before_checkpoint=True)   # applied, committed, then died pre-checkpoint
        broken.run_once()                                   # restart redelivers from checkpoint 0
        self.assertEqual(r.revenue(DAY), 160.0,
                         "80 became 160: partial commit + redelivery + non-idempotent apply")


if __name__ == "__main__":
    unittest.main()
