"""EXERCISES.md rung 6 (Debug) — an LSM with ONE planted bug.

Everything here is a copy of the real `compact()` from lsm.py with a single
character changed. The engine looks fine: writes work, reads work, files
merge, disk shrinks. Something is nonetheless deeply wrong.

Your job (exercise 6.1): find it by running the test suite against this
class, name which test fails, which README invariant it violates, and what a
user of a real database would observe.

    python3 experiments/exp_debug_broken.py

Do not read past the class if you want the hunt to be fair.
"""

import heapq
import os
from itertools import groupby

from lsm import LSM, TOMBSTONE


class BrokenLSM(LSM):
    def compact(self):
        if not self.tables:
            return
        merged = heapq.merge(*[self._tagged(t.entries(), t.seq)
                               for t in self.tables])
        path = os.path.join(self.dir, f"sstable_{self.next_seq:04d}.txt")
        with open(path, "w", encoding="utf-8") as f:
            for k, group in groupby(merged, key=lambda e: e[0]):
                winner = max(group, key=lambda e: e[1])
                if winner[2] != TOMBSTONE:
                    f.write(f"{k}\t{winner[2]}\n")
        self.bytes_written += os.path.getsize(path)
        old = self.tables
        from lsm import SSTable
        self.tables = [SSTable(path, self.next_seq, bits_per_key=self.bits_per_key)]
        self.next_seq += 1
        self.compactions += 1
        for t in old:
            os.remove(t.path)
