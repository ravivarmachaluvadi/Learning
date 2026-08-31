"""Scenario tests for the LSM engine — each test name is a claim from README §10."""

import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))
from lsm import LSM, TOMBSTONE


class LSMScenarios(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="lsm-test-")

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def _fresh(self, **kw):
        kw.setdefault("compact_at", 999)  # only compact when a test says so
        return LSM(self.dir, **kw)

    def test_newest_wins_across_memtable_and_flushed_tables(self):
        db = self._fresh(memtable_limit=4)
        db.put("k05", "old")
        for i in range(3):
            db.put(f"fill{i}", "x")        # forces a flush; "old" is now on disk
        self.assertGreaterEqual(db.table_count(), 1)
        db.put("k05", "new")               # newer value, still in RAM
        self.assertEqual(db.get("k05"), "new")   # memtable shadows the SSTable
        db.close()

    def test_delete_writes_a_tombstone_and_old_value_survives_on_disk(self):
        db = self._fresh(memtable_limit=2)
        db.put("k1", "alive")
        db.put("k2", "x")                  # flush: k1 is in an immutable file
        db.delete("k1")
        db.put("k3", "y")                  # flush the tombstone too
        self.assertIsNone(db.get("k1"))    # reads say it's gone...
        on_disk = [v for t in db.tables for k, v in t.entries() if k == "k1"]
        # ...but the immutable file still physically holds BOTH the old value
        # and the tombstone — deletion is a write (§10.4)
        self.assertIn("alive", on_disk)
        self.assertIn(TOMBSTONE, on_disk)
        db.close()

    def test_compaction_preserves_every_answer_and_reclaims_space(self):
        db = self._fresh(memtable_limit=4)
        for i in range(40):
            db.put(f"k{i % 10:02d}", f"v{i}")   # heavy overwriting → shadowed copies
        for i in range(0, 10, 2):
            db.delete(f"k{i:02d}")
        db.put("pad1", "x"); db.put("pad2", "x"); db.put("pad3", "x")  # flush tombstones
        answers_before = {f"k{i:02d}": db.get(f"k{i:02d}") for i in range(10)}
        bytes_before, tables_before = db.disk_bytes(), db.table_count()
        db.compact()
        answers_after = {f"k{i:02d}": db.get(f"k{i:02d}") for i in range(10)}
        # INVARIANT 4: compaction is logically invisible
        self.assertEqual(answers_before, answers_after)
        # physically, shadowed values and tombstones died
        self.assertLess(db.disk_bytes(), bytes_before)
        self.assertLess(db.table_count(), tables_before)
        self.assertEqual(db.table_count(), 1)
        db.close()

    def test_crash_recovery_replays_wal(self):
        db = self._fresh(memtable_limit=100)   # nothing will flush
        db.put("a", "1")
        db.put("b", "2")
        db.delete("a")
        # simulate a crash: no clean close, just abandon the object
        db.wal.flush()
        db2 = LSM(self.dir)                    # restart = load tables + replay WAL
        self.assertIsNone(db2.get("a"))        # the delete survived
        self.assertEqual(db2.get("b"), "2")    # the put survived
        db2.close()

    def test_tombstones_slow_range_reads(self):
        db = self._fresh(memtable_limit=10)
        for i in range(100):
            db.put(f"job{i:03d}", "payload")
        for i in range(90):
            db.delete(f"job{i:03d}")           # the queue anti-pattern (F-L2)
        db.reset_counters()
        live = db.range_scan("job000", "job099")
        self.assertEqual(len(live), 10)
        # to find 10 live entries the scan waded through ~10x the records
        self.assertGreaterEqual(db.records_scanned, 100)
        db.close()

    def test_bloom_filters_skip_files_for_absent_keys(self):
        db = self._fresh(memtable_limit=5, use_bloom=True)
        for i in range(30):
            db.put(f"k{i:03d}", "v")           # ~6 SSTables
        db.reset_counters()
        for i in range(200):
            db.get(f"missing{i}")
        with_bloom = db.file_probes
        self.assertGreater(db.bloom_skips, 0)
        db.close()
        # same reads, blooms off → every table probed every time
        db2 = LSM(self.dir, use_bloom=False)
        db2.reset_counters()
        for i in range(200):
            db2.get(f"missing{i}")
        self.assertGreater(db2.file_probes, with_bloom * 5)
        db2.close()


if __name__ == "__main__":
    unittest.main()
