"""A teaching B+ tree (README §10.1–10.2).

Real shape, toy scale: pages hold at most MAX_KEYS keys (default 4) so splits
happen early and often; real pages hold hundreds. All data lives in leaves;
internal pages hold only separator keys; leaves are chained left-to-right.

I/O model: every page visited counts as one page READ, every page modified as
one page WRITE — the currencies of the amplification scorecard (§7).
"""

from bisect import bisect_left, bisect_right


class Page:
    """One fixed-size unit of the tree: a leaf (keys+values) or an internal
    signpost page (keys+children)."""

    __slots__ = ("leaf", "keys", "values", "children", "next")

    def __init__(self, leaf):
        self.leaf = leaf
        self.keys = []
        self.values = []    # leaves only
        self.children = []  # internal pages only
        self.next = None    # leaf chain: makes range scans a linked-list stroll


class BPlusTree:
    def __init__(self, max_keys=4):
        self.max_keys = max_keys
        self.root = Page(leaf=True)
        self.page_reads = 0
        self.page_writes = 0
        self.splits = 0
        self.touched_pages = set()  # distinct pages written (for exp2)

    # ---------- write path ----------

    def insert(self, key, value):
        result = self._insert(self.root, key, value)
        if result is not None:
            # INVARIANT (uniform depth): the tree gets taller ONLY here — the
            # old root splits and a new root is created above it, so every
            # leaf stays at exactly the same depth forever. No rebalancing
            # procedure exists because none is needed.
            sep, right = result
            new_root = Page(leaf=False)
            new_root.keys = [sep]
            new_root.children = [self.root, right]
            self.root = new_root
            self._wrote(new_root)

    def _insert(self, page, key, value):
        """Insert under `page`; return (separator, new_right_page) if `page`
        split, else None."""
        self.page_reads += 1
        if page.leaf:
            i = bisect_left(page.keys, key)
            if i < len(page.keys) and page.keys[i] == key:
                page.values[i] = value          # update in place
                self._wrote(page)
                return None
            page.keys.insert(i, key)
            page.values.insert(i, value)
            self._wrote(page)
            if len(page.keys) <= self.max_keys:
                return None
            return self._split_leaf(page)
        # internal page: route by separator.
        # INVARIANT (sortedness): child i holds keys < keys[i]; a key equal to
        # a separator routes right, because the separator is the right page's
        # first key.
        i = bisect_right(page.keys, key)
        result = self._insert(page.children[i], key, value)
        if result is None:
            return None
        sep, right = result
        page.keys.insert(i, sep)
        page.children.insert(i + 1, right)
        self._wrote(page)
        if len(page.keys) <= self.max_keys:
            return None
        return self._split_internal(page)

    def _split_leaf(self, page):
        # The split (§10.2): full page divides at its median; the right half's
        # first key is promoted to the parent as the new separator.
        mid = len(page.keys) // 2
        right = Page(leaf=True)
        right.keys, page.keys = page.keys[mid:], page.keys[:mid]
        right.values, page.values = page.values[mid:], page.values[:mid]
        right.next, page.next = page.next, right   # keep the leaf chain intact
        self.splits += 1
        self._wrote(page)
        self._wrote(right)
        return right.keys[0], right

    def _split_internal(self, page):
        # Internal split: the median separator moves UP (it no longer belongs
        # in either half — it routes between them).
        mid = len(page.keys) // 2
        sep = page.keys[mid]
        right = Page(leaf=False)
        right.keys, page.keys = page.keys[mid + 1:], page.keys[:mid]
        right.children, page.children = page.children[mid + 1:], page.children[:mid + 1]
        self.splits += 1
        self._wrote(page)
        self._wrote(right)
        return sep, right

    def delete(self, key):
        """Lazy delete: remove from the leaf, never merge underfull pages —
        exactly the slack-accumulation real engines also allow (bloat, F-B2).
        Real engines merge/reclaim lazily too (VACUUM, purge threads)."""
        page = self.root
        while not page.leaf:
            self.page_reads += 1
            page = page.children[bisect_right(page.keys, key)]
        self.page_reads += 1
        i = bisect_left(page.keys, key)
        if i < len(page.keys) and page.keys[i] == key:
            page.keys.pop(i)
            page.values.pop(i)
            self._wrote(page)
            return True
        return False

    # ---------- read path ----------

    def search(self, key):
        """One page read per level — read cost IS the height (§7 fanout)."""
        page = self.root
        while not page.leaf:
            self.page_reads += 1
            page = page.children[bisect_right(page.keys, key)]
        self.page_reads += 1
        i = bisect_left(page.keys, key)
        if i < len(page.keys) and page.keys[i] == key:
            return page.values[i]
        return None

    def range_scan(self, lo, hi):
        """Descend once to lo's leaf, then follow the leaf chain — the B+
        tree's range superpower (§10.2)."""
        page = self.root
        while not page.leaf:
            self.page_reads += 1
            page = page.children[bisect_right(page.keys, lo)]
        out = []
        while page is not None:
            self.page_reads += 1
            for k, v in zip(page.keys, page.values):
                if k > hi:
                    return out
                if k >= lo:
                    out.append((k, v))
            page = page.next
        return out

    # ---------- introspection ----------

    def _wrote(self, page):
        self.page_writes += 1
        self.touched_pages.add(id(page))

    @property
    def height(self):
        h, page = 1, self.root
        while not page.leaf:
            h += 1
            page = page.children[0]
        return h

    def page_count(self):
        def count(p):
            return 1 if p.leaf else 1 + sum(count(c) for c in p.children)
        return count(self.root)

    def key_count(self):
        def count(p):
            return len(p.keys) if p.leaf else sum(count(c) for c in p.children)
        return count(self.root)

    def fill_factor(self):
        """Average leaf occupancy: how much of the tree is data vs slack."""
        leaves, used = 0, 0
        page = self.root
        while not page.leaf:
            page = page.children[0]
        while page is not None:
            leaves += 1
            used += len(page.keys)
            page = page.next
        return used / (leaves * self.max_keys) if leaves else 0.0

    def leaf_depths(self):
        """All leaf depths — the uniform-depth invariant, checkable."""
        depths = []
        def walk(p, d):
            if p.leaf:
                depths.append(d)
            else:
                for c in p.children:
                    walk(c, d + 1)
        walk(self.root, 1)
        return depths

    def reset_counters(self):
        self.page_reads = self.page_writes = self.splits = 0
        self.touched_pages = set()
