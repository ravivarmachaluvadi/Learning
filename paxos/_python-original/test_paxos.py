"""
test_paxos.py — the tests ARE the documentation.

Each test name is a scenario from README.md. Run them:

    python3 -m unittest test_paxos -v

Every test that involves more than one proposer ends by asserting the one
invariant that matters: at most one value is ever chosen.
"""

import random
import unittest

from paxos import Cluster, quorum_size


def two_proposer_cluster(**kw) -> Cluster:
    c = Cluster(num_acceptors=5, **kw)
    c.add_proposer("P1", "Zoo")
    c.add_proposer("P2", "Museum")
    return c


def get_chosen_by(c: Cluster, pid: str, n: int, acceptors: str) -> None:
    """Drive one full, successful Paxos round for `pid` using `acceptors`."""
    c.propose(pid, n=n)
    c.deliver_many(acceptors, "PREPARE", n)
    for _ in acceptors:
        c.deliver_to(pid, "PROMISE", n)
    c.deliver_many(acceptors, "ACCEPT", n)
    for _ in acceptors:
        c.deliver_to(pid, "ACCEPTED", n)


class QuorumMath(unittest.TestCase):

    def test_quorum_is_a_strict_majority(self):
        self.assertEqual(quorum_size(3), 2)
        self.assertEqual(quorum_size(5), 3)
        self.assertEqual(quorum_size(4), 3)   # even sizes buy nothing

    def test_any_two_quorums_always_overlap(self):
        """The single fact the whole protocol rests on."""
        nodes = "ABCDE"
        q = quorum_size(len(nodes))
        from itertools import combinations
        for left in combinations(nodes, q):
            for right in combinations(nodes, q):
                self.assertTrue(set(left) & set(right),
                                f"{left} and {right} share nobody")


class HappyPath(unittest.TestCase):

    def test_single_proposer_with_no_failures_chooses_its_own_value(self):
        c = two_proposer_cluster()
        get_chosen_by(c, "P1", 1, "ABC")
        self.assertEqual(c.chosen_values(), {"Zoo"})
        self.assertIsNone(c.proposers["P1"].adopted_from,
                          "nobody had accepted anything, so P1 kept its own value")
        self.assertEqual(c.proposers["P1"].phase, "done")

    def test_a_minority_of_crashed_acceptors_does_not_stop_consensus(self):
        c = two_proposer_cluster()
        c.crash("D", "E")                      # 2 of 5 down, 3 still up
        get_chosen_by(c, "P1", 1, "ABC")
        self.assertEqual(c.chosen_values(), {"Zoo"})


class TheAdoptRule(unittest.TestCase):
    """Phase 1 exists so that a new proposer can discover what came before."""

    def test_acceptor_rejects_a_proposal_below_its_promise(self):
        c = two_proposer_cluster()
        c.propose("P2", n=9)
        c.deliver_to("A", "PREPARE", 9)        # A now promises 9
        c.propose("P1", n=4)
        c.deliver_to("A", "PREPARE", 4)        # 4 < 9 -> refused
        replies = [m for m in c.queue if m.dst == "P1" and m.kind == "NACK"]
        self.assertEqual(len(replies), 1)
        self.assertEqual(replies[0].last_n, 9)

    def test_a_later_proposer_must_abandon_its_own_value_and_carry_the_chosen_one(self):
        """The safety property, demonstrated end to end.

        P1 gets 'Zoo' chosen by {A,B,C}. P2 then runs a whole round with
        {C,D,E} and *wanted* 'Museum' -- but C is in both majorities and
        remembers, so P2 is forced to propose 'Zoo' instead.
        """
        c = two_proposer_cluster()
        get_chosen_by(c, "P1", 1, "ABC")
        self.assertEqual(c.chosen_values(), {"Zoo"})

        c.propose("P2", n=2)
        c.deliver_many("CDE", "PREPARE", 2)
        for _ in "CDE":
            c.deliver_to("P2", "PROMISE", 2)

        p2 = c.proposers["P2"]
        self.assertEqual(p2.own_value, "Museum")
        self.assertEqual(p2.proposed_value, "Zoo",
                         "P2 had to adopt the value it learned from acceptor C")
        self.assertEqual(p2.adopted_from, 1)

        c.run()
        self.assertEqual(c.chosen_values(), {"Zoo"})
        self.assertTrue(c.safety_holds())

    def test_a_value_accepted_by_only_a_minority_may_still_be_replaced(self):
        """The mirror image: 'accepted by some' is NOT 'chosen'.

        P1's ACCEPT reaches only A and B (2 of 5). Nothing is chosen, so P2 is
        free to get a different value chosen -- and that is correct behaviour,
        not a bug.
        """
        c = two_proposer_cluster()
        c.propose("P1", n=1)
        c.deliver_many("ABC", "PREPARE", 1)
        for _ in "ABC":
            c.deliver_to("P1", "PROMISE", 1)
        c.deliver_many("AB", "ACCEPT", 1)      # only 2 accept
        c.drop_to("C", "ACCEPT", 1)
        c.drop_to("D", "ACCEPT", 1)
        c.drop_to("E", "ACCEPT", 1)
        self.assertEqual(c.chosen_values(), set(), "2 of 5 is not a majority")

        c.propose("P2", n=2)
        c.deliver_many("CDE", "PREPARE", 2)
        for _ in "CDE":
            c.deliver_to("P2", "PROMISE", 2)
        self.assertEqual(c.proposers["P2"].proposed_value, "Museum",
                         "none of C,D,E had accepted anything, so P2 keeps its own value")
        c.deliver_many("CDE", "ACCEPT", 2)
        self.assertEqual(c.chosen_values(), {"Museum"})
        self.assertTrue(c.safety_holds())

    def test_proposer_adopts_the_highest_numbered_accepted_value_not_the_first_it_hears(self):
        c = Cluster(num_acceptors=5)
        c.add_proposer("P1", "Zoo")
        c.add_proposer("P2", "Museum")
        c.add_proposer("P3", "Beach")

        # A accepts an old proposal (n=1, Zoo); B accepts a newer one (n=5, Museum).
        c.propose("P1", n=1)
        c.deliver_many("ABC", "PREPARE", 1)
        for _ in "ABC":
            c.deliver_to("P1", "PROMISE", 1)
        c.deliver_to("A", "ACCEPT", 1)

        c.propose("P2", n=5)
        c.deliver_many("BCD", "PREPARE", 5)
        for _ in "BCD":
            c.deliver_to("P2", "PROMISE", 5)
        c.deliver_to("B", "ACCEPT", 5)

        # P3 asks A and B (plus E) and must take the n=5 value, not the n=1 one.
        c.propose("P3", n=7)
        c.deliver_many("ABE", "PREPARE", 7)
        for _ in "ABE":
            c.deliver_to("P3", "PROMISE", 7)
        self.assertEqual(c.proposers["P3"].proposed_value, "Museum")
        self.assertEqual(c.proposers["P3"].adopted_from, 5)


class Failures(unittest.TestCase):

    def test_losing_a_majority_stalls_progress_without_breaking_safety(self):
        c = two_proposer_cluster()
        c.crash("C", "D", "E")                 # 3 of 5 down: no quorum remains
        c.propose("P1", n=1)
        c.run()
        self.assertEqual(c.chosen_values(), set(), "no majority, so nothing can be chosen")
        self.assertTrue(c.safety_holds(), "stalling is allowed; deciding wrongly is not")

    def test_duplicate_messages_change_nothing(self):
        c = two_proposer_cluster()
        c.propose("P1", n=1)
        c.deliver_many("ABC", "PREPARE", 1)
        for _ in "ABC":
            c.deliver_to("P1", "PROMISE", 1)
        c.deliver_many("ABC", "ACCEPT", 1)
        first = list(c.chosen)

        for dup in range(3):                   # replay the same ACCEPTs
            c.proposers["P1"].receive(
                type(c.queue[0])("A", "P1", "ACCEPTED", n=1, value="Zoo")
                if c.queue else None)
        c.run()
        self.assertEqual(c.chosen_values(), {"Zoo"})
        self.assertEqual(len(c.chosen), len(first),
                         "a value is announced chosen exactly once")

    def test_a_stale_accept_arriving_late_is_refused(self):
        c = two_proposer_cluster()
        c.propose("P1", n=1)
        c.deliver_many("ABC", "PREPARE", 1)
        for _ in "ABC":
            c.deliver_to("P1", "PROMISE", 1)   # ACCEPT(1) now in flight

        c.propose("P2", n=2)                   # a newer round overtakes it
        c.deliver_many("ABC", "PREPARE", 2)

        c.deliver_many("ABC", "ACCEPT", 1)     # the old ACCEPT finally lands
        self.assertEqual(c.chosen_values(), set())
        for a in "ABC":
            self.assertIsNone(c.acceptors[a].accepted_value,
                              f"{a} must refuse an ACCEPT below its promise")


class Liveness(unittest.TestCase):

    def test_two_proposers_can_duel_forever_without_ever_choosing_a_value(self):
        """Paxos is always safe. It is not always live -- and this is why."""
        c = two_proposer_cluster()
        n = 1
        for _ in range(8):                     # eight full rounds, no progress
            c.propose("P1", n=n)
            c.deliver_many("ABC", "PREPARE", n)
            for _ in "ABC":
                c.deliver_to("P1", "PROMISE", n)

            c.propose("P2", n=n + 1)           # P2 gazumps P1's promise
            c.deliver_many("ABC", "PREPARE", n + 1)
            for _ in "ABC":
                c.deliver_to("P2", "PROMISE", n + 1)

            c.deliver_many("ABC", "ACCEPT", n)  # P1's accept is now too old
            n += 2

        self.assertEqual(c.chosen_values(), set(), "nothing was ever chosen")
        self.assertTrue(c.safety_holds(), "but nothing wrong was chosen either")

    def test_the_duel_ends_the_moment_only_one_proposer_keeps_trying(self):
        """The production fix: elect a leader and let everyone else stand down."""
        c = two_proposer_cluster()
        n = 1
        for _ in range(4):                     # stage the same stalemate
            c.propose("P1", n=n)
            c.deliver_many("ABC", "PREPARE", n)
            for _ in "ABC":
                c.deliver_to("P1", "PROMISE", n)
            c.propose("P2", n=n + 1)
            c.deliver_many("ABC", "PREPARE", n + 1)
            for _ in "ABC":
                c.deliver_to("P2", "PROMISE", n + 1)
            c.deliver_many("ABC", "ACCEPT", n)
            n += 2
        self.assertEqual(c.chosen_values(), set(), "the duel chose nothing")

        # P2 stands down. P1 alone needs exactly one clean round.
        get_chosen_by(c, "P1", 101, "ABC")
        self.assertEqual(c.chosen_values(), {"Zoo"})


class Durability(unittest.TestCase):

    def test_an_acceptor_that_forgets_after_a_crash_breaks_consensus(self):
        """The #1 way real implementations get this wrong.

        An acceptor MUST write promised_n / accepted_n / accepted_value to
        stable storage BEFORE replying. Keep them in RAM only, and a reboot
        turns the acceptor into a liar -- it re-promises as if it had never
        voted, and two different values get chosen.

        This test asserts the FAILURE, to pin the bug down.
        """
        c = two_proposer_cluster(durable=False)
        get_chosen_by(c, "P1", 1, "ABC")
        self.assertEqual(c.chosen_values(), {"Zoo"})

        c.crash("A", "B", "C")                 # amnesia
        c.recover("A", "B", "C")
        for a in "ABC":
            self.assertIsNone(c.acceptors[a].accepted_value, "state was lost")

        get_chosen_by(c, "P2", 2, "ABC")
        self.assertEqual(c.chosen_values(), {"Zoo", "Museum"})
        self.assertFalse(c.safety_holds(),
                         "two different values chosen: consensus is broken")

    def test_the_same_crash_is_harmless_when_acceptors_persist_their_state(self):
        c = two_proposer_cluster(durable=True)
        get_chosen_by(c, "P1", 1, "ABC")
        c.crash("A", "B", "C")
        c.recover("A", "B", "C")
        get_chosen_by(c, "P2", 2, "ABC")
        self.assertEqual(c.chosen_values(), {"Zoo"})
        self.assertTrue(c.safety_holds())


class Chaos(unittest.TestCase):

    def test_safety_survives_hundreds_of_random_schedules(self):
        """Try hard to break the invariant instead of hoping it holds.

        Random message loss, duplication, crashes and recoveries, two
        proposers fighting, 300 different schedules. At most one value may
        ever be chosen in any of them.
        """
        for seed in range(300):
            rng = random.Random(seed)
            c = two_proposer_cluster(seed=seed)
            c.drop_rate = 0.25
            c.dup_rate = 0.15

            for rnd in range(6):
                for pid in ("P1", "P2"):
                    if rng.random() < 0.7:
                        c.retry(pid)
                victim = rng.choice("ABCDE")
                if rng.random() < 0.3:
                    c.crash(victim)
                c.run(200)
                c.recover(victim)
                c.run(200)

            self.assertTrue(
                c.safety_holds(),
                f"seed {seed}: chose more than one value -> {c.chosen_values()}")

    def test_chaos_can_still_reach_a_decision_once_the_noise_stops(self):
        """Safety is unconditional; liveness needs the network to behave."""
        decided = 0
        for seed in range(50):
            c = two_proposer_cluster(seed=seed)
            c.drop_rate = 0.25
            for _ in range(3):
                c.retry("P1")
                c.run(200)
            c.drop_rate = 0.0                  # the storm passes
            for attempt in range(5):
                c.retry("P1")
                c.run(200)
                if c.chosen_values():
                    break
            if c.chosen_values():
                decided += 1
            self.assertTrue(c.safety_holds())
        self.assertEqual(decided, 50, "a lone proposer on a quiet network always finishes")


if __name__ == "__main__":
    unittest.main(verbosity=2)
