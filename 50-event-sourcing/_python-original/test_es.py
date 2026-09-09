"""Scenario tests = executable documentation for the event-sourcing lab.
Run: python3 -m unittest -v test_es
"""
import unittest

from es_lab import (Account, CommandRejected, ConcurrencyConflict, EventStore,
                    handle, maybe_snapshot)


def bank():
    store = EventStore()
    handle(store, "7", lambda a: a.decide_open("priya"))
    return store


class StateIsAReplay(unittest.TestCase):

    def test_current_state_is_a_fold_over_the_events(self):
        store = bank()
        handle(store, "7", lambda a: a.decide_deposit(100.0))
        handle(store, "7", lambda a: a.decide_withdraw(30.0))
        acct, replayed = Account.load(store, "7")
        self.assertEqual((acct.balance, acct.version, replayed), (70.0, 3, 3))

    def test_a_rejected_command_appends_nothing(self):
        store = bank()
        before = store.stream_version("account-7")
        with self.assertRaises(CommandRejected):
            handle(store, "7", lambda a: a.decide_withdraw(1.0))   # balance is 0
        self.assertEqual(store.stream_version("account-7"), before)

    def test_streams_are_isolated(self):
        store = bank()
        handle(store, "8", lambda a: a.decide_open("amit"))
        handle(store, "8", lambda a: a.decide_deposit(500.0))
        acct7, _ = Account.load(store, "7")
        self.assertEqual(acct7.balance, 0.0, "account 7 must not see account 8's money")


class TimeTravel(unittest.TestCase):

    def test_state_can_be_reconstructed_as_of_any_past_version(self):
        store = bank()
        handle(store, "7", lambda a: a.decide_deposit(100.0))
        handle(store, "7", lambda a: a.decide_withdraw(30.0))
        self.assertEqual(Account.load(store, "7", as_of_version=2)[0].balance, 100.0)
        self.assertEqual(Account.load(store, "7", as_of_version=3)[0].balance, 70.0)


class OptimisticConcurrency(unittest.TestCase):

    def test_stale_writer_gets_a_conflict_not_a_lost_update(self):
        store = bank()
        handle(store, "7", lambda a: a.decide_deposit(70.0))
        t1, _ = Account.load(store, "7")
        t2, _ = Account.load(store, "7")                     # same version
        store.append("account-7", t1.version, t1.decide_withdraw(50.0))
        with self.assertRaises(ConcurrencyConflict):
            store.append("account-7", t2.version, t2.decide_withdraw(40.0))

    def test_retry_re_decides_and_the_invariant_holds_against_fresh_state(self):
        store = bank()
        handle(store, "7", lambda a: a.decide_deposit(70.0))
        t1, _ = Account.load(store, "7")
        store.append("account-7", t1.version, t1.decide_withdraw(50.0))
        # handle() reloads (balance now 20) and re-decides: 40 must be rejected
        with self.assertRaises(CommandRejected):
            handle(store, "7", lambda a: a.decide_withdraw(40.0))
        self.assertEqual(Account.load(store, "7")[0].balance, 20.0, "never overdrawn")

    def test_retry_succeeds_when_the_re_decision_is_still_valid(self):
        store = bank()
        handle(store, "7", lambda a: a.decide_deposit(100.0))
        t2, _ = Account.load(store, "7")
        # someone else deposits meanwhile — t2's append would conflict...
        handle(store, "7", lambda a: a.decide_deposit(5.0))
        # ...but handle()'s retry loop absorbs it: a 40 withdrawal is still fine
        handle(store, "7", lambda a: a.decide_withdraw(40.0))
        self.assertEqual(Account.load(store, "7")[0].balance, 65.0)


class ImmutableHistory(unittest.TestCase):

    def test_corrections_are_new_events_and_history_keeps_both(self):
        store = bank()
        handle(store, "7", lambda a: [("MoneyDeposited", {"amount": 500.0, "channel": "wire"})])
        handle(store, "7", lambda a: [("DepositReversed", {"amount": 500.0, "reason": "wrong account"})])
        acct, _ = Account.load(store, "7")
        self.assertEqual(acct.balance, 0.0)
        types = [t for _, t, _ in store.read("account-7")]
        self.assertIn("MoneyDeposited", types)
        self.assertIn("DepositReversed", types, "the mistake AND the correction are both facts")


class Snapshots(unittest.TestCase):

    def test_snapshot_load_replays_only_the_tail(self):
        store = bank()
        for _ in range(130):
            handle(store, "7", lambda a: a.decide_deposit(1.0))
            maybe_snapshot(store, "7")
        _, cold = Account.load(store, "7")
        _, warm = Account.load(store, "7", use_snapshot=True)
        self.assertEqual(cold, 131)
        self.assertLess(warm, 40, f"snapshot load replayed {warm} events; expected only the tail")

    def test_snapshot_state_equals_full_replay(self):
        store = bank()
        for _ in range(130):
            handle(store, "7", lambda a: a.decide_deposit(1.0))
            maybe_snapshot(store, "7")
        self.assertEqual(Account.load(store, "7", use_snapshot=True)[0],
                         Account.load(store, "7")[0])


class SchemaEvolution(unittest.TestCase):

    def test_old_event_versions_are_upcast_on_read(self):
        store = EventStore()
        store.append("account-9", 0, [("AccountOpened", {"owner": "amit"})])
        store.append("account-9", 1, [("MoneyDeposited", {"amount": 10.0})], schema_v=1)
        _, _, payload = store.read("account-9")[-1]
        self.assertEqual(payload, {"amount": 10.0, "channel": "branch"},
                         "v1 events gain the field with the historically-correct default")

    def test_unknown_event_types_are_tolerated_on_replay(self):
        store = bank()
        store.append("account-7", 1, [("SomeFutureEventType", {"x": 1})])
        acct, _ = Account.load(store, "7")   # must not crash
        self.assertEqual(acct.version, 2)


if __name__ == "__main__":
    unittest.main()
