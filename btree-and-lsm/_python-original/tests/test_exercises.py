"""Red-first tests for EXERCISES.md rung 5. These FAIL until you implement
the stubs in src/exercises.py — that is the point."""

import random
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))
from btree import BPlusTree
from exercises import newest_wins_merge, predicted_height
from lsm import TOMBSTONE


class Exercise51PredictedHeight(unittest.TestCase):

    def test_small_cases_match_the_docstring(self):
        self.assertEqual(predicted_height(1, 4), 1)
        self.assertEqual(predicted_height(4, 4), 1)
        self.assertEqual(predicted_height(5, 4), 2)

    def test_is_a_floor_on_the_real_trees_height(self):
        # A real tree is never SHORTER than the perfectly-packed prediction,
        # and (with ~70% fill) never wildly taller either.
        for n in (50, 500, 2_000):
            t = BPlusTree(max_keys=4)
            keys = [f"{k:06d}" for k in range(n)]
            random.Random(n).shuffle(keys)
            for k in keys:
                t.insert(k, "v")
            predicted = predicted_height(n, 4)
            self.assertLessEqual(predicted, t.height, f"n={n}: prediction exceeded reality")
            self.assertLessEqual(t.height - predicted, 3, f"n={n}: prediction far too optimistic")

    def test_growth_is_logarithmic_not_linear(self):
        # 100x the keys must NOT mean 100x the height — that IS fanout.
        self.assertLess(predicted_height(1_000_000, 100) , 6)


class Exercise52NewestWinsMerge(unittest.TestCase):

    def test_docstring_example(self):
        self.assertEqual(
            newest_wins_merge([
                (1, [("a", "old"), ("b", "keep")]),
                (2, [("a", "new"), ("c", TOMBSTONE)]),
            ]),
            [("a", "new"), ("b", "keep")],
        )

    def test_newest_table_wins_regardless_of_list_position(self):
        out = dict(newest_wins_merge([
            (1, [("k", "v1")]),
            (5, [("k", "v5")]),
            (3, [("k", "v3")]),
        ]))
        self.assertEqual(out["k"], "v5")

    def test_tombstone_in_the_newest_table_removes_the_key(self):
        out = dict(newest_wins_merge([
            (1, [("gone", "alive"), ("stays", "yes")]),
            (2, [("gone", TOMBSTONE)]),
        ]))
        self.assertNotIn("gone", out)
        self.assertEqual(out["stays"], "yes")

    def test_a_newer_value_resurrects_nothing_but_overrides_an_older_tombstone(self):
        # delete then re-insert: the key must come back
        out = dict(newest_wins_merge([
            (1, [("k", "first")]),
            (2, [("k", TOMBSTONE)]),
            (3, [("k", "reinserted")]),
        ]))
        self.assertEqual(out["k"], "reinserted")

    def test_output_is_sorted_by_key(self):
        out = newest_wins_merge([
            (1, [("a", "1"), ("m", "1"), ("z", "1")]),
            (2, [("b", "2"), ("y", "2")]),
        ])
        self.assertEqual([k for k, _ in out], sorted(k for k, _ in out))


if __name__ == "__main__":
    unittest.main()
