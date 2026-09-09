"""A teaching LSM-tree engine (README §10.3–10.7) — with REAL files.

Point it at a directory and `ls` it while you work: you'll see `wal.log`
grow, `sstable_N.txt` files appear at each flush, and compaction replace
them. Toy thresholds (flush at 6 keys, compact at 4 tables) so everything
happens early; real engines use ~64 MB memtables — the logic is identical.

I/O counters: `bytes_written` (write amp numerator), `file_probes` and
`bloom_skips` (read amp), `disk_bytes()` vs live data (space amp).
"""

import hashlib
import heapq
import os
from bisect import bisect_right
from itertools import groupby

TOMBSTONE = "<TOMBSTONE>"   # §10.4: immutable files can't be edited, so a
                            # delete is a WRITE of this marker; newest-wins
                            # makes older values invisible until compaction
                            # physically drops them.


class BloomFilter:
    """§10.5: answers 'definitely not here' or 'maybe here'. It can never
    wrongly skip a file (no false negatives) — only occasionally probe in
    vain (~1% false positives at ~10 bits/key)."""

    def __init__(self, n_keys, bits_per_key=10, n_hashes=3):
        self.m = max(8, n_keys * bits_per_key)
        self.n_hashes = n_hashes
        self.bits = bytearray((self.m + 7) // 8)

    def _positions(self, key):
        for salt in range(self.n_hashes):
            h = hashlib.blake2b(f"{salt}:{key}".encode(), digest_size=8)
            yield int.from_bytes(h.digest(), "big") % self.m

    def add(self, key):
        for p in self._positions(key):
            self.bits[p // 8] |= 1 << (p % 8)

    def maybe_contains(self, key):
        return all(self.bits[p // 8] & (1 << (p % 8)) for p in self._positions(key))


class SSTable:
    """One immutable sorted file + its two in-RAM accelerators
    (bloom filter, sparse index). Never modified after creation."""

    def __init__(self, path, seq, sparse_every=4, bits_per_key=10):
        self.path = path
        self.seq = seq            # higher seq = newer = wins (§10.3)
        self.sparse_keys = []     # every Nth key ...
        self.sparse_offsets = []  # ... and its byte offset: the sparse index
        keys = []
        offset = 0
        with open(path, "r", encoding="utf-8") as f:
            for i, line in enumerate(f):
                key = line.split("\t", 1)[0]
                keys.append(key)
                if i % sparse_every == 0:
                    self.sparse_keys.append(key)
                    self.sparse_offsets.append(offset)
                offset += len(line.encode("utf-8"))
        self.count = len(keys)
        self.bloom = BloomFilter(self.count, bits_per_key)
        for k in keys:
            self.bloom.add(k)

    def get(self, key):
        """One seek (via the sparse index) + a short forward scan."""
        i = bisect_right(self.sparse_keys, key) - 1
        if i < 0:
            return None
        with open(self.path, "r", encoding="utf-8") as f:
            f.seek(self.sparse_offsets[i])
            for line in f:
                k, v = line.rstrip("\n").split("\t", 1)
                if k == key:
                    return v
                if k > key:      # sorted file: passed it — not here
                    return None
        return None

    def entries(self):
        with open(self.path, "r", encoding="utf-8") as f:
            for line in f:
                k, v = line.rstrip("\n").split("\t", 1)
                yield k, v


class LSM:
    def __init__(self, dirpath, memtable_limit=6, compact_at=4,
                 use_bloom=True, bits_per_key=10):
        self.dir = dirpath
        os.makedirs(dirpath, exist_ok=True)
        self.memtable_limit = memtable_limit
        self.compact_at = compact_at
        self.use_bloom = use_bloom
        self.bits_per_key = bits_per_key
        self.mem = {}
        self.tables = []          # oldest → newest
        self.next_seq = 0
        # counters
        self.bytes_written = 0
        self.user_bytes = 0
        self.file_probes = 0
        self.bloom_skips = 0
        self.records_scanned = 0
        self.flushes = 0
        self.compactions = 0
        # RECOVERY (§10.7): load surviving SSTables, then replay the WAL —
        # this is what "restart after a crash" means. Acknowledge nothing
        # the log can't replay.
        for name in sorted(os.listdir(dirpath)):
            if name.startswith("sstable_"):
                seq = int(name.split("_")[1].split(".")[0])
                self.tables.append(SSTable(os.path.join(dirpath, name), seq,
                                           bits_per_key=bits_per_key))
                self.next_seq = max(self.next_seq, seq + 1)
        self.tables.sort(key=lambda t: t.seq)
        self.wal_path = os.path.join(dirpath, "wal.log")
        if os.path.exists(self.wal_path):
            with open(self.wal_path, "r", encoding="utf-8") as f:
                for line in f:
                    op, k, v = line.rstrip("\n").split("\t")
                    self.mem[k] = v if op == "P" else TOMBSTONE
        self.wal = open(self.wal_path, "a", encoding="utf-8")

    # ---------- write path ----------

    def put(self, key, value):
        self._log("P", key, value)
        self.mem[key] = value           # newest-wins: the memtable IS newest
        self.user_bytes += len(key) + len(str(value))
        if len(self.mem) >= self.memtable_limit:
            self._flush()

    def delete(self, key):
        """§10.4: a delete is a write. The old value (possibly in an
        immutable file) stays on disk until compaction."""
        self._log("D", key, TOMBSTONE)
        self.mem[key] = TOMBSTONE
        if len(self.mem) >= self.memtable_limit:
            self._flush()

    def _log(self, op, key, value):
        # INVARIANT (WAL-before-ack): the write hits the append-only log
        # BEFORE we acknowledge; a crash loses only what the log can replay.
        line = f"{op}\t{key}\t{value}\n"
        self.wal.write(line)
        self.wal.flush()
        self.bytes_written += len(line.encode("utf-8"))

    def _flush(self):
        """§10.3: the frozen memtable becomes an immutable SSTable in ONE
        sequential write — this is how random-order puts become sequential
        disk I/O."""
        path = os.path.join(self.dir, f"sstable_{self.next_seq:04d}.txt")
        with open(path, "w", encoding="utf-8") as f:
            for k in sorted(self.mem):
                f.write(f"{k}\t{self.mem[k]}\n")
        self.bytes_written += os.path.getsize(path)
        self.tables.append(SSTable(path, self.next_seq,
                                   bits_per_key=self.bits_per_key))
        self.next_seq += 1
        self.flushes += 1
        self.mem = {}
        # the flushed data is durable in the SSTable; its WAL entries are
        # no longer needed — truncate so the log stays short (§10.7)
        self.wal.close()
        self.wal = open(self.wal_path, "w", encoding="utf-8")
        if len(self.tables) >= self.compact_at:
            self.compact()

    # ---------- read path ----------

    def get(self, key):
        # INVARIANT (newest wins): memtable first, then tables newest→oldest;
        # the FIRST hit is the answer. This order DEFINES the current value.
        key = str(key)
        if key in self.mem:
            v = self.mem[key]
            return None if v == TOMBSTONE else v
        for t in reversed(self.tables):
            if self.use_bloom and not t.bloom.maybe_contains(key):
                self.bloom_skips += 1   # 'definitely not here' — no disk touch
                continue
            self.file_probes += 1
            v = t.get(key)
            if v is not None:
                return None if v == TOMBSTONE else v
        return None

    def range_scan(self, lo, hi):
        """Merge every source, newest-wins per key. Counts every physical
        record examined — including tombstones and shadowed versions — which
        is exactly why delete-heavy ranges get slow (F-L2)."""
        sources = [iter(sorted(self.mem.items()))]
        sources += [t.entries() for t in self.tables]
        prios = [self.next_seq + 1] + [t.seq + 1 for t in self.tables]
        merged = heapq.merge(*[self._tagged(src, p, lo, hi)
                               for src, p in zip(sources, prios)])
        out = []
        for k, group in groupby(merged, key=lambda e: e[0]):
            newest = next(group)
            self.records_scanned += 1 + sum(1 for _ in group)
            if newest[2] != TOMBSTONE:
                out.append((k, newest[2]))
        return out

    @staticmethod
    def _tagged(entries, prio, lo=None, hi=None):
        """Tag (key, value) pairs as (key, -prio, value) so heapq.merge sorts
        same-key groups newest-first. A plain function (not a generator
        expression in a loop) so `prio` is bound EAGERLY per source — the
        late-binding version of this silently merged with the wrong
        priorities."""
        for k, v in entries:
            if (lo is None or k >= lo) and (hi is None or k <= hi):
                yield (k, -prio, v)

    # ---------- maintenance ----------

    def compact(self):
        """§10.6 (size-tiered, one tier): k-way merge of ALL tables into one,
        dropping shadowed values and tombstones.
        INVARIANT (compaction is logically invisible): every get() answers
        the same before and after — verified by test_compaction_preserves_answers.
        Note the F-L3 shape: the output is fully written BEFORE inputs are
        deleted, so disk usage transiently spikes."""
        if not self.tables:
            return
        merged = heapq.merge(*[self._tagged(t.entries(), t.seq)
                               for t in self.tables])
        path = os.path.join(self.dir, f"sstable_{self.next_seq:04d}.txt")
        with open(path, "w", encoding="utf-8") as f:
            for k, group in groupby(merged, key=lambda e: e[0]):
                newest = next(group)          # smallest -seq = newest table
                if newest[2] != TOMBSTONE:    # tombstones die here — and only
                    f.write(f"{k}\t{newest[2]}\n")  # here (simplified: no
                                              # gc_grace in the toy)
        self.bytes_written += os.path.getsize(path)
        old = self.tables
        self.tables = [SSTable(path, self.next_seq,
                               bits_per_key=self.bits_per_key)]
        self.next_seq += 1
        self.compactions += 1
        for t in old:
            os.remove(t.path)

    # ---------- introspection ----------

    def disk_bytes(self):
        return sum(os.path.getsize(t.path) for t in self.tables)

    def table_count(self):
        return len(self.tables)

    def close(self):
        self.wal.close()

    def reset_counters(self):
        self.file_probes = self.bloom_skips = self.records_scanned = 0
