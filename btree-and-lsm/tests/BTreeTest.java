import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Scenario tests for the B+ tree — each test name is a claim from README §10.
 *
 *     javac -d out src/*.java tests/*.java experiments/*.java   # once
 *     java -cp out BTreeTest
 *
 * One line per scenario, then a tally. Exit code 1 if anything failed.
 */
public class BTreeTest {

    static int passed = 0, failed = 0;

    public static void main(String[] args) {
        System.out.println("Running BTreeTest");
        System.out.println();

        run("split_grows_tree_from_the_top_and_keeps_leaves_at_uniform_depth",
                BTreeTest::splitGrowsTreeFromTheTop);
        run("every_inserted_key_is_findable_and_updates_replace",
                BTreeTest::everyInsertedKeyIsFindable);
        run("search_cost_equals_height_page_reads",
                BTreeTest::searchCostEqualsHeight);
        run("range_scan_follows_the_leaf_chain_in_sorted_order",
                BTreeTest::rangeScanFollowsTheLeafChain);
        run("key_order_changes_the_write_working_set_auto_increment_vs_uuid",
                BTreeTest::keyOrderChangesTheWriteWorkingSet);
        run("lazy_delete_leaves_page_count_untouched_bloat",
                BTreeTest::lazyDeleteLeavesPageCountUntouched);

        System.out.println();
        System.out.printf("%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ tests

    static void splitGrowsTreeFromTheTop() {
        BTree t = new BTree(4);
        List<Integer> heights = new ArrayList<>();
        heights.add(t.height());
        for (int k = 1; k <= 100; k++) {
            t.insert(String.format("%04d", k), String.valueOf(k));
            heights.add(t.height());
        }
        // height only ever grows, one level at a time (root splits)
        for (int i = 1; i < heights.size(); i++) {
            int step = heights.get(i) - heights.get(i - 1);
            check(step == 0 || step == 1, "height jumped by " + step + " at insert " + i);
        }
        check(t.height() > 1, "the tree never grew a level");
        // THE invariant: every leaf at exactly the same depth, no rebalancing ever run
        check(new HashSet<>(t.leafDepths()).size() == 1,
                "leaves sit at different depths: " + new HashSet<>(t.leafDepths()));
    }

    static void everyInsertedKeyIsFindable() {
        BTree t = new BTree(4);
        List<Integer> keys = new ArrayList<>();
        for (int k = 0; k < 500; k++) keys.add(k);
        Collections.shuffle(keys, new Random(7));
        for (int k : keys) t.insert(String.format("%04d", k), "v" + k);
        for (int k = 0; k < 500; k++) {
            check(("v" + k).equals(t.search(String.format("%04d", k))), "key " + k + " not findable");
        }
        t.insert("0042", "NEW");
        check("NEW".equals(t.search("0042")), "update did not replace the old value");
        check(t.search("9999") == null, "an absent key returned a value");
    }

    static void searchCostEqualsHeight() {
        BTree t = new BTree(4);
        for (int k = 0; k < 1000; k++) t.insert(String.format("%05d", k), String.valueOf(k));
        t.resetCounters();
        t.search("00500");
        // one page read per level — fanout's promise
        check(t.pageReads == t.height(), "page reads " + t.pageReads + " != height " + t.height());
    }

    static void rangeScanFollowsTheLeafChain() {
        BTree t = new BTree(4);
        List<Integer> keys = new ArrayList<>();
        for (int k = 0; k < 200; k++) keys.add(k);
        Collections.shuffle(keys, new Random(3));
        for (int k : keys) t.insert(String.format("%04d", k), String.valueOf(k));
        List<String> got = new ArrayList<>();
        for (BTree.Entry e : t.rangeScan("0030", "0060")) got.add(e.key());
        List<String> want = new ArrayList<>();
        for (int k = 30; k <= 60; k++) want.add(String.format("%04d", k));
        check(want.equals(got), "expected " + want + ", got " + got);
    }

    static void keyOrderChangesTheWriteWorkingSet() {
        // F-B1, the UUID incident: build two identical trees of 400 keys, then
        // measure ONLY the next 100 inserts. Auto-increment style appends land
        // in one hot rightmost page (tiny, cacheable working set); UUID-style
        // keys land BETWEEN existing keys all over the tree (every write drags
        // a different cold page into cache).
        List<String> base = new ArrayList<>();
        for (int k = 0; k < 4000; k += 10) base.add(String.format("%05d", k));
        BTree seq = new BTree(4), rnd = new BTree(4);
        for (String k : base) {
            seq.insert(k, k);
            rnd.insert(k, k);
        }
        seq.resetCounters();
        rnd.resetCounters();
        Set<BTree.Page> preSeq = allPages(seq);   // what "cold page" means below
        Set<BTree.Page> preRnd = allPages(rnd);
        for (int i = 0; i < 100; i++) {
            seq.insert(String.format("%05d", 4000 + i), String.valueOf(i));   // append at the right edge
        }
        List<Integer> pool = new ArrayList<>();
        for (int k = 0; k < 4000; k += 10) pool.add(k);
        Collections.shuffle(pool, new Random(11));
        for (int i = 0; i < 100; i++) {
            rnd.insert(String.format("%05d", pool.get(i)) + "x", String.valueOf(i));   // lands mid-tree, everywhere
        }
        check(seq.keyCount() == rnd.keyCount(), "the two trees did not do the same work");
        // ...but count COLD pages: pages that existed before the window and got
        // written during it (freshly created pages are cheap — already in
        // cache). Appends re-touch only the right edge; scattered inserts drag
        // in a cold page nearly every write.
        Set<BTree.Page> coldSeq = new HashSet<>(seq.touchedPages);
        coldSeq.retainAll(preSeq);
        Set<BTree.Page> coldRnd = new HashSet<>(rnd.touchedPages);
        coldRnd.retainAll(preRnd);
        check(coldRnd.size() > 5 * Math.max(1, coldSeq.size()),
                "cold pages: sequential " + coldSeq.size() + ", scattered " + coldRnd.size());
    }

    static void lazyDeleteLeavesPageCountUntouched() {
        BTree t = new BTree(4);
        for (int k = 0; k < 400; k++) t.insert(String.format("%04d", k), String.valueOf(k));
        int pagesBefore = t.pageCount();
        for (int k = 0; k < 400; k += 2) {
            check(t.delete(String.format("%04d", k)), "delete of key " + k + " removed nothing");
        }
        // half the keys gone, zero pages reclaimed — bloat (F-B2), by design
        check(t.pageCount() == pagesBefore,
                "pages went " + pagesBefore + " -> " + t.pageCount() + "; none should be reclaimed");
        check(t.keyCount() == 200, "expected 200 live keys, got " + t.keyCount());
    }

    // ---------------------------------------------------------------- helpers

    static Set<BTree.Page> allPages(BTree tree) {
        Set<BTree.Page> pages = new HashSet<>();
        walk(tree.root, pages);
        return pages;
    }

    static void walk(BTree.Page p, Set<BTree.Page> pages) {
        pages.add(p);
        if (!p.leaf) for (BTree.Page c : p.children) walk(c, pages);
    }

    interface Scenario {
        void run() throws Exception;
    }

    /** One PASS/FAIL line per scenario — a thrown assertion or exception is the failure. */
    static void run(String name, Scenario scenario) {
        try {
            scenario.run();
            passed++;
            System.out.println("  PASS  " + name);
        } catch (Throwable t) {
            failed++;
            String detail = t.getMessage() == null ? t.toString() : t.getMessage();
            System.out.println("  FAIL  " + name + "  (" + detail + ")");
        }
    }

    static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
