"""Scenario tests for the B+ tree — each test name is a claim from README §10."""

import random
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))
from btree import BPlusTree


class BTreeScenarios(unittest.TestCase):

    def test_split_grows_tree_from_the_top_and_keeps_leaves_at_uniform_depth(self):
        t = BPlusTree(max_keys=4)
        heights = [t.height]
        for k in range(1, 101):
            t.insert(f"{k:04d}", k)
            heights.append(t.height)
        # height only ever grows, one level at a time (root splits)
        self.assertTrue(all(b - a in (0, 1) for a, b in zip(heights, heights[1:])))
        self.assertGreater(t.height, 1)
        # THE invariant: every leaf at exactly the same depth, no rebalancing ever run
        self.assertEqual(len(set(t.leaf_depths())), 1)

    def test_every_inserted_key_is_findable_and_updates_replace(self):
        t = BPlusTree(max_keys=4)
        keys = list(range(500))
        random.Random(7).shuffle(keys)
        for k in keys:
            t.insert(f"{k:04d}", f"v{k}")
        for k in range(500):
            self.assertEqual(t.search(f"{k:04d}"), f"v{k}")
        t.insert("0042", "NEW")
        self.assertEqual(t.search("0042"), "NEW")
        self.assertIsNone(t.search("9999"))

    def test_search_cost_equals_height_page_reads(self):
        t = BPlusTree(max_keys=4)
        for k in range(1000):
            t.insert(f"{k:05d}", k)
        t.reset_counters()
        t.search("00500")
        self.assertEqual(t.page_reads, t.height)  # one page read per level — fanout's promise

    def test_range_scan_follows_the_leaf_chain_in_sorted_order(self):
        t = BPlusTree(max_keys=4)
        keys = list(range(200))
        random.Random(3).shuffle(keys)
        for k in keys:
            t.insert(f"{k:04d}", k)
        result = t.range_scan("0030", "0060")
        self.assertEqual([k for k, _ in result], [f"{k:04d}" for k in range(30, 61)])

    def test_key_order_changes_the_write_working_set_auto_increment_vs_uuid(self):
        # F-B1, the UUID incident: build two identical trees of 400 keys, then
        # measure ONLY the next 100 inserts. Auto-increment style appends land
        # in one hot rightmost page (tiny, cacheable working set); UUID-style
        # keys land BETWEEN existing keys all over the tree (every write drags
        # a different cold page into cache).
        def all_page_ids(tree):
            ids = set()
            def walk(p):
                ids.add(id(p))
                if not p.leaf:
                    for c in p.children:
                        walk(c)
            walk(tree.root)
            return ids

        base = [f"{k:05d}" for k in range(0, 4000, 10)]
        seq, rnd = BPlusTree(4), BPlusTree(4)
        for k in base:
            seq.insert(k, k)
            rnd.insert(k, k)
        seq.reset_counters(); rnd.reset_counters()
        self._pre_seq = all_page_ids(seq)   # what "cold page" means below
        self._pre_rnd = all_page_ids(rnd)
        for i in range(100):
            seq.insert(f"{4000 + i:05d}", i)              # append at the right edge
        scattered = random.Random(11).sample(range(0, 4000, 10), 100)
        for i, k in enumerate(scattered):
            rnd.insert(f"{k:05d}x", i)                    # lands mid-tree, everywhere
        self.assertEqual(seq.key_count(), rnd.key_count())  # same amount of work...
        # ...but count COLD pages: pages that existed before the window and got
        # written during it (freshly created pages are cheap — already in
        # cache). Appends re-touch only the right edge; scattered inserts drag
        # in a cold page nearly every write.
        cold_seq = seq.touched_pages & self._pre_seq
        cold_rnd = rnd.touched_pages & self._pre_rnd
        self.assertGreater(len(cold_rnd), 5 * max(1, len(cold_seq)))

    def test_lazy_delete_leaves_page_count_untouched_bloat(self):
        t = BPlusTree(max_keys=4)
        for k in range(400):
            t.insert(f"{k:04d}", k)
        pages_before = t.page_count()
        for k in range(0, 400, 2):
            self.assertTrue(t.delete(f"{k:04d}"))
        # half the keys gone, zero pages reclaimed — bloat (F-B2), by design
        self.assertEqual(t.page_count(), pages_before)
        self.assertEqual(t.key_count(), 200)


if __name__ == "__main__":
    unittest.main()
