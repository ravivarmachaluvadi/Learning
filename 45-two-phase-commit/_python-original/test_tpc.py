#!/usr/bin/env python3
"""Executable documentation: each test name is a scenario from README.md.

Run:  python3 -m unittest -v test_tpc
"""

import unittest

from tpc_lab import Bank, Blocked, Coordinator, Crash, fresh_banks, transfer_ops


def quiet():
    return Coordinator(narrate=False)


class Phase1Scenarios(unittest.TestCase):
    def test_happy_path_commits_both_banks_atomically(self):
        a, b = fresh_banks()
        result = quiet().run_transfer("t1", transfer_ops(a, b))
        self.assertEqual(result, "COMMITTED")
        self.assertEqual(a.accounts["alice"], 500)
        self.assertEqual(b.accounts["bob"], 700)
        self.assertEqual(a.locks, {}, "locks released after decision")

    def test_single_no_vote_aborts_everyone(self):
        a, b = fresh_banks()
        b.vote_no = True
        result = quiet().run_transfer("t2", transfer_ops(a, b))
        self.assertEqual(result, "ABORTED")
        self.assertEqual(a.accounts["alice"], 1000, "debit undone-by-never")
        self.assertEqual(b.accounts["bob"], 200)
        self.assertEqual(a.locks, {}, "prepared lock released on abort")

    def test_insufficient_funds_is_a_no_vote(self):
        a, b = fresh_banks()
        result = quiet().run_transfer("t3", transfer_ops(a, b, amount=5000))
        self.assertEqual(result, "ABORTED")

    def test_participant_silence_before_voting_is_a_safe_abort(self):
        a, b = fresh_banks()
        b.crash_before_vote = True
        result = quiet().run_transfer("t4", transfer_ops(a, b))
        self.assertEqual(result, "ABORTED")
        self.assertEqual(a.accounts["alice"], 1000)

    def test_prepared_account_blocks_other_customers(self):
        a, b = fresh_banks()
        a.prepare("t5", "alice", -500)          # phase 1 only; no decision yet
        with self.assertRaises(Blocked):
            a.read_balance("alice")
        self.assertEqual(b.read_balance("bob"), 200, "unlocked rows readable")


class CommitPointScenarios(unittest.TestCase):
    def test_decision_log_write_is_the_commit_point(self):
        a, b = fresh_banks()
        c = quiet()
        c.crash_after_decision_logged = True
        with self.assertRaises(Crash):
            c.run_transfer("t6", transfer_ops(a, b))
        # nobody was told, yet the transaction IS committed:
        self.assertEqual(c.decision_for("t6"), "COMMIT")
        self.assertEqual(a.accounts["alice"], 1000, "not yet applied anywhere")

    def test_crash_before_decision_means_presumed_abort(self):
        a, b = fresh_banks()
        c = quiet()
        c.crash_before_decision_logged = True
        with self.assertRaises(Crash):
            c.run_transfer("t7", transfer_ops(a, b))
        self.assertEqual(c.decision_for("t7"), "ABORT",
                         "no decision in log -> safe to abort")


class BlockingAndRecoveryScenarios(unittest.TestCase):
    def test_prepared_banks_block_holding_locks_while_coordinator_down(self):
        a, b = fresh_banks()
        c = quiet()
        c.crash_after_decision_logged = True
        with self.assertRaises(Crash):
            c.run_transfer("t8", transfer_ops(a, b))
        self.assertEqual(a.state["t8"], "PREPARED", "in doubt")
        self.assertEqual(b.state["t8"], "PREPARED", "in doubt")
        with self.assertRaises(Blocked):
            a.read_balance("alice")
        with self.assertRaises(Blocked):
            b.read_balance("bob")

    def test_recovered_coordinator_resends_decision_and_unblocks(self):
        a, b = fresh_banks()
        c = quiet()
        c.crash_after_decision_logged = True
        with self.assertRaises(Crash):
            c.run_transfer("t9", transfer_ops(a, b))
        c2 = Coordinator.recover(c.log, {"t9": transfer_ops(a, b)})
        self.assertEqual(a.accounts["alice"], 500)
        self.assertEqual(b.accounts["bob"], 700)
        self.assertEqual(a.read_balance("alice"), 500, "unblocked")
        self.assertEqual(c2.decision_for("t9"), "COMMIT")

    def test_recovery_completion_is_idempotent_for_participants(self):
        a, b = fresh_banks()
        c = quiet()
        c.crash_after_decision_logged = True
        with self.assertRaises(Crash):
            c.run_transfer("t10", transfer_ops(a, b))
        Coordinator.recover(c.log, {"t10": transfer_ops(a, b)})
        Coordinator.recover(c.log, {"t10": transfer_ops(a, b)})  # again
        self.assertEqual(a.accounts["alice"], 500, "applied exactly once")

    def test_participant_recovers_in_doubt_and_asks_coordinator(self):
        a, b = fresh_banks()
        c = quiet()
        a.crash_after_vote = True    # PREPARED hits A's log; the YES is lost
        result = c.run_transfer("t11", transfer_ops(a, b))
        self.assertEqual(result, "ABORTED",
                         "coordinator saw silence from A -> timeout -> NO")
        # bank A restarts from its durable log with boot-time balances:
        a2, in_doubt = Bank.recover("BANK-A", {"alice": 1000}, a.log)
        self.assertEqual(in_doubt, ["t11"], "PREPARED with no outcome")
        with self.assertRaises(Blocked):
            a2.read_balance("alice")     # promise stands across the restart
        a2.ask_coordinator(c, "t11")
        self.assertEqual(a2.state["t11"], "ABORTED")
        self.assertNotIn("alice", a2.locks)
        self.assertEqual(a2.read_balance("alice"), 1000)

    def test_same_participant_history_can_end_in_opposite_outcomes(self):
        """The heart of the blocking argument (experiment 6)."""
        endings = {}
        for world, knob in (("w1", "after"), ("w2", "before")):
            a, b = fresh_banks()
            c = quiet()
            setattr(c, "crash_%s_decision_logged" % knob, True)
            with self.assertRaises(Crash):
                c.run_transfer("t12", transfer_ops(a, b))
            self.assertEqual(a.state["t12"], "PREPARED",
                             "identical local state in both worlds")
            Coordinator.recover(c.log, {"t12": transfer_ops(a, b)})
            endings[world] = a.accounts["alice"]
        self.assertEqual(endings, {"w1": 500, "w2": 1000},
                         "opposite global outcomes from identical local view")


if __name__ == "__main__":
    unittest.main(verbosity=2)
