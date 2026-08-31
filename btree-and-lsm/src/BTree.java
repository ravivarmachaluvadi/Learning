import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A teaching B+ tree (README §10.1–10.2).
 *
 * Real shape, toy scale: pages hold at most maxKeys keys (default 4) so splits
 * happen early and often; real pages hold hundreds. All data lives in leaves;
 * internal pages hold only separator keys; leaves are chained left-to-right.
 *
 * I/O model: every page visited counts as one page READ, every page modified as
 * one page WRITE — the currencies of the amplification scorecard (§7).
 */
public class BTree {

    /**
     * One fixed-size unit of the tree: a leaf (keys+values) or an internal
     * signpost page (keys+children).
     */
    public static final class Page {
        public final boolean leaf;
        public final List<String> keys = new ArrayList<>();
        public final List<String> values = new ArrayList<>();   // leaves only
        public final List<Page> children = new ArrayList<>();   // internal pages only
        public Page next;   // leaf chain: makes range scans a linked-list stroll

        Page(boolean leaf) {
            this.leaf = leaf;
        }
    }

    /** One (key, value) pair handed back by rangeScan. */
    public record Entry(String key, String value) {}

    /** What a page hands its parent when it splits: the promoted separator and the new right page. */
    private record Split(String separator, Page right) {}

    public final int maxKeys;
    public Page root = new Page(true);
    public long pageReads = 0;
    public long pageWrites = 0;
    public long splits = 0;
    public Set<Page> touchedPages = new HashSet<>();   // distinct pages written (for exp2)

    public BTree() {
        this(4);
    }

    public BTree(int maxKeys) {
        this.maxKeys = maxKeys;
    }

    // ---------- write path ----------

    public void insert(String key, String value) {
        Split result = insert(root, key, value);
        if (result != null) {
            // INVARIANT (uniform depth): the tree gets taller ONLY here — the
            // old root splits and a new root is created above it, so every
            // leaf stays at exactly the same depth forever. No rebalancing
            // procedure exists because none is needed.
            Page newRoot = new Page(false);
            newRoot.keys.add(result.separator());
            newRoot.children.add(root);
            newRoot.children.add(result.right());
            root = newRoot;
            wrote(newRoot);
        }
    }

    /** Insert under `page`; return the (separator, new right page) if `page` split, else null. */
    private Split insert(Page page, String key, String value) {
        pageReads++;
        if (page.leaf) {
            int i = bisectLeft(page.keys, key);
            if (i < page.keys.size() && page.keys.get(i).equals(key)) {
                page.values.set(i, value);      // update in place
                wrote(page);
                return null;
            }
            page.keys.add(i, key);
            page.values.add(i, value);
            wrote(page);
            if (page.keys.size() <= maxKeys) {
                return null;
            }
            return splitLeaf(page);
        }
        // internal page: route by separator.
        // INVARIANT (sortedness): child i holds keys < keys[i]; a key equal to
        // a separator routes right, because the separator is the right page's
        // first key.
        int i = bisectRight(page.keys, key);
        Split result = insert(page.children.get(i), key, value);
        if (result == null) {
            return null;
        }
        page.keys.add(i, result.separator());
        page.children.add(i + 1, result.right());
        wrote(page);
        if (page.keys.size() <= maxKeys) {
            return null;
        }
        return splitInternal(page);
    }

    private Split splitLeaf(Page page) {
        // The split (§10.2): full page divides at its median; the right half's
        // first key is promoted to the parent as the new separator.
        int mid = page.keys.size() / 2;
        Page right = new Page(true);
        right.keys.addAll(page.keys.subList(mid, page.keys.size()));
        right.values.addAll(page.values.subList(mid, page.values.size()));
        page.keys.subList(mid, page.keys.size()).clear();
        page.values.subList(mid, page.values.size()).clear();
        right.next = page.next;     // keep the leaf chain intact
        page.next = right;
        splits++;
        wrote(page);
        wrote(right);
        return new Split(right.keys.get(0), right);
    }

    private Split splitInternal(Page page) {
        // Internal split: the median separator moves UP (it no longer belongs
        // in either half — it routes between them).
        int mid = page.keys.size() / 2;
        String separator = page.keys.get(mid);
        Page right = new Page(false);
        right.keys.addAll(page.keys.subList(mid + 1, page.keys.size()));
        right.children.addAll(page.children.subList(mid + 1, page.children.size()));
        page.keys.subList(mid, page.keys.size()).clear();
        page.children.subList(mid + 1, page.children.size()).clear();
        splits++;
        wrote(page);
        wrote(right);
        return new Split(separator, right);
    }

    /**
     * Lazy delete: remove from the leaf, never merge underfull pages —
     * exactly the slack-accumulation real engines also allow (bloat, F-B2).
     * Real engines merge/reclaim lazily too (VACUUM, purge threads).
     */
    public boolean delete(String key) {
        Page page = root;
        while (!page.leaf) {
            pageReads++;
            page = page.children.get(bisectRight(page.keys, key));
        }
        pageReads++;
        int i = bisectLeft(page.keys, key);
        if (i < page.keys.size() && page.keys.get(i).equals(key)) {
            page.keys.remove(i);
            page.values.remove(i);
            wrote(page);
            return true;
        }
        return false;
    }

    // ---------- read path ----------

    /** One page read per level — read cost IS the height (§7 fanout). */
    public String search(String key) {
        Page page = root;
        while (!page.leaf) {
            pageReads++;
            page = page.children.get(bisectRight(page.keys, key));
        }
        pageReads++;
        int i = bisectLeft(page.keys, key);
        if (i < page.keys.size() && page.keys.get(i).equals(key)) {
            return page.values.get(i);
        }
        return null;
    }

    /**
     * Descend once to lo's leaf, then follow the leaf chain — the B+
     * tree's range superpower (§10.2).
     */
    public List<Entry> rangeScan(String lo, String hi) {
        Page page = root;
        while (!page.leaf) {
            pageReads++;
            page = page.children.get(bisectRight(page.keys, lo));
        }
        List<Entry> out = new ArrayList<>();
        while (page != null) {
            pageReads++;
            for (int i = 0; i < page.keys.size(); i++) {
                String k = page.keys.get(i);
                if (k.compareTo(hi) > 0) {
                    return out;
                }
                if (k.compareTo(lo) >= 0) {
                    out.add(new Entry(k, page.values.get(i)));
                }
            }
            page = page.next;
        }
        return out;
    }

    // ---------- introspection ----------

    private void wrote(Page page) {
        pageWrites++;
        touchedPages.add(page);   // Page has identity equality — one entry per physical page
    }

    public int height() {
        int h = 1;
        Page page = root;
        while (!page.leaf) {
            h++;
            page = page.children.get(0);
        }
        return h;
    }

    public int pageCount() {
        return pageCount(root);
    }

    private static int pageCount(Page p) {
        if (p.leaf) {
            return 1;
        }
        int n = 1;
        for (Page c : p.children) {
            n += pageCount(c);
        }
        return n;
    }

    public int keyCount() {
        return keyCount(root);
    }

    private static int keyCount(Page p) {
        if (p.leaf) {
            return p.keys.size();
        }
        int n = 0;
        for (Page c : p.children) {
            n += keyCount(c);
        }
        return n;
    }

    /** Average leaf occupancy: how much of the tree is data vs slack. */
    public double fillFactor() {
        int leaves = 0;
        int used = 0;
        Page page = root;
        while (!page.leaf) {
            page = page.children.get(0);
        }
        while (page != null) {
            leaves++;
            used += page.keys.size();
            page = page.next;
        }
        return leaves == 0 ? 0.0 : (double) used / (leaves * maxKeys);
    }

    /** All leaf depths — the uniform-depth invariant, checkable. */
    public List<Integer> leafDepths() {
        List<Integer> depths = new ArrayList<>();
        walkDepths(root, 1, depths);
        return depths;
    }

    private static void walkDepths(Page p, int d, List<Integer> depths) {
        if (p.leaf) {
            depths.add(d);
        } else {
            for (Page c : p.children) {
                walkDepths(c, d + 1, depths);
            }
        }
    }

    public void resetCounters() {
        pageReads = 0;
        pageWrites = 0;
        splits = 0;
        touchedPages = new HashSet<>();
    }

    // ---------- the two bisects every sorted page needs ----------

    /** Index of the first element >= key — where an equal key already sits. */
    static int bisectLeft(List<String> sorted, String key) {
        int lo = 0, hi = sorted.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted.get(mid).compareTo(key) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** Index of the first element > key — a key equal to a separator routes right. */
    static int bisectRight(List<String> sorted, String key) {
        int lo = 0, hi = sorted.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted.get(mid).compareTo(key) <= 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}
