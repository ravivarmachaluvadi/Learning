"""Tests for impl_exercise.py — each test name states the rule it enforces."""
import unittest

from impl_exercise import chosen_value, pick_value_from_promises


class PickValueRule(unittest.TestCase):

    def test_clean_history_means_you_may_use_your_own_value(self):
        promises = [("A", 0, None), ("B", 0, None), ("C", 0, None)]
        self.assertEqual(pick_value_from_promises(promises, "Museum"), "Museum")

    def test_any_reported_value_must_be_adopted_over_your_own(self):
        promises = [("A", 0, None), ("B", 1, "Zoo"), ("C", 0, None)]
        self.assertEqual(pick_value_from_promises(promises, "Museum"), "Zoo")

    def test_the_highest_numbered_accepted_value_wins_not_the_most_common(self):
        # Two acceptors report an OLD value, one reports a NEWER one.
        # Majority-of-reports is the tempting wrong rule; highest n is the law.
        promises = [("A", 3, "Zoo"), ("B", 3, "Zoo"), ("C", 5, "Museum")]
        self.assertEqual(pick_value_from_promises(promises, "Aquarium"), "Museum")


class ChosenRule(unittest.TestCase):

    QUORUM = 3

    def test_nothing_accepted_means_nothing_chosen(self):
        states = [(0, None)] * 5
        self.assertIsNone(chosen_value(states, self.QUORUM))

    def test_a_minority_writing_it_down_is_not_a_decision(self):
        states = [(1, "Zoo"), (1, "Zoo"), (0, None), (0, None), (0, None)]
        self.assertIsNone(chosen_value(states, self.QUORUM))

    def test_a_majority_on_the_same_proposal_number_is_a_decision(self):
        states = [(1, "Zoo"), (1, "Zoo"), (1, "Zoo"), (0, None), (0, None)]
        self.assertEqual(chosen_value(states, self.QUORUM), "Zoo")

    def test_same_value_under_different_numbers_does_not_count_as_one_quorum(self):
        # Three acceptors say "Zoo" — but not under the same proposal number.
        # No single (n, value) pair has a majority, so nothing is chosen YET.
        # (A value re-proposed under a higher n forms its own, new quorum.)
        states = [(1, "Zoo"), (1, "Zoo"), (2, "Zoo"), (0, None), (0, None)]
        self.assertIsNone(chosen_value(states, self.QUORUM))

    def test_a_later_full_quorum_decides_even_with_older_leftovers(self):
        states = [(1, "Zoo"), (2, "Zoo"), (2, "Zoo"), (2, "Zoo"), (0, None)]
        self.assertEqual(chosen_value(states, self.QUORUM), "Zoo")


if __name__ == "__main__":
    unittest.main()
