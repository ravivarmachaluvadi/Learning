import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Experiment 2 — key order: the UUID incident, measured (README F-B1, F-B2).
 *
 * Two identical B-trees of 5,000 keys. Then 1,000 MORE inserts:
 *   tree A: sequential appends (auto-increment style)
 *   tree B: keys landing between existing keys (UUIDv4 style)
 * Measured over those 1,000 inserts only: cold pages dragged into cache,
 * splits, and final fill factor. Then a bonus: bloat (F-B2) — delete half the
 * keys and watch zero pages get reclaimed.
 *
 * The LSM columns show the same 1,000 writes are order-blind there: everything
 * lands in the memtable either way.
 *
 * Run:  javac -d out src/*.java tests/*.java experiments/*.java   # once
 *       java -cp out Exp2KeyOrder
 */
public class Exp2KeyOrder {

    static final int BASE = 5_000;
    static final int TAIL = 1_000;

    public static void main(String[] args) throws Exception {
        List<String> baseKeys = new ArrayList<>();
        for (int k = 0; k < BASE * 10; k += 10) baseKeys.add(String.format("%07d", k));

        BTree seq = new BTree(4), rnd = new BTree(4);
        for (String k : baseKeys) {
            seq.insert(k, "v");
            rnd.insert(k, "v");
        }

        seq.resetCounters();
        rnd.resetCounters();
        Set<BTree.Page> preSeq = pages(seq), preRnd = pages(rnd);

        for (int i = 0; i < TAIL; i++) {
            seq.insert(String.format("%07d", BASE * 10 + i * 10), "v");   // append at the right edge
        }
        List<Integer> pool = new ArrayList<>();
        for (int k = 0; k < BASE * 10; k += 10) pool.add(k);
        Collections.shuffle(pool, new Random(9));
        List<Integer> scattered = new ArrayList<>(pool.subList(0, TAIL));
        for (int k : scattered) {
            rnd.insert(String.format("%07d", k) + "5", "v");              // lands mid-tree, everywhere
        }

        int coldSeq = intersectionSize(seq.touchedPages, preSeq);
        int coldRnd = intersectionSize(rnd.touchedPages, preRnd);

        System.out.printf("After %,d base keys, measuring the NEXT %,d inserts only:%n%n", BASE, TAIL);
        System.out.printf("%-44s%12s%12s%n", "", "sequential", "scattered");
        System.out.printf("%-44s%,12d%,12d%n", "cold pages dragged into cache", coldSeq, coldRnd);
        System.out.printf("%-44s%,12d%,12d%n", "total distinct pages written",
                seq.touchedPages.size(), rnd.touchedPages.size());
        System.out.printf("%-44s%,12d%,12d%n", "splits", seq.splits, rnd.splits);
        System.out.printf("%-44s%11s%12s%n", "final fill factor",
                pct(seq.fillFactor()), pct(rnd.fillFactor()));
        System.out.println(
                "\n(Note the splits row: appends split the rightmost page constantly because\n"
                + "this toy splits at the median like any other page — while scattered inserts\n"
                + "sank into existing slack. Real engines special-case the rightmost split\n"
                + "(leave the left page ~full, start the right page empty) exactly because\n"
                + "appends are so common. The cold-page row is the one the UUID incident is\n"
                + "about: " + coldSeq + " warm pages vs " + coldRnd + " cache misses.)");

        // ---- the LSM doesn't care (same writes, both orders) ----
        List<String> seqTail = new ArrayList<>();
        for (int i = 0; i < TAIL; i++) seqTail.add(String.format("%07d", BASE * 10 + i * 10));
        List<String> scatteredTail = new ArrayList<>();
        for (int k : scattered) scatteredTail.add(String.format("%07d", k) + "5");

        String[] orders = {"sequential", "scattered"};
        List<List<String>> tails = List.of(seqTail, scatteredTail);
        long[] written = new long[2];
        for (int i = 0; i < 2; i++) {
            Path d = Files.createTempDirectory("lsm-exp2-");
            Lsm db = new Lsm(d, 500, 999);
            for (String k : baseKeys) db.put(k, "v");
            long before = db.bytesWritten;
            for (String k : tails.get(i)) db.put(k, "v");
            written[i] = db.bytesWritten - before;
            db.close();
            deleteTree(d);
        }
        System.out.printf("%nLSM, same %,d writes: %s order wrote %,d bytes; %s order wrote %,d bytes"
                        + " — order-blind, as promised:%n",
                TAIL, orders[0], written[0], orders[1], written[1]);
        System.out.println("every write is a WAL append + memtable insert regardless of key.");

        // ---- bonus: bloat (F-B2) ----
        int pagesBefore = rnd.pageCount();
        for (int i = 0; i < baseKeys.size(); i += 2) rnd.delete(baseKeys.get(i));
        System.out.printf("%nBloat bonus: deleted %,d of %,d keys; pages before/after: %,d / %,d"
                        + " — zero reclaimed.%n",
                baseKeys.size() / 2, rnd.keyCount() + baseKeys.size() / 2, pagesBefore, rnd.pageCount());
        System.out.println("This slack is what VACUUM / REINDEX / pg_repack exist to hand back (F-B2).");

        System.out.println(
                "\nLesson: same logical work, wildly different physical cost — key ORDER is a\n"
                + "B-tree performance parameter (hence UUIDv7/ULID), and no parameter at all\n"
                + "for an LSM. This is F-B1 with your own numbers.");
    }

    static Set<BTree.Page> pages(BTree tree) {
        Set<BTree.Page> ids = new HashSet<>();
        walk(tree.root, ids);
        return ids;
    }

    static void walk(BTree.Page p, Set<BTree.Page> ids) {
        ids.add(p);
        if (!p.leaf) for (BTree.Page c : p.children) walk(c, ids);
    }

    static int intersectionSize(Set<BTree.Page> a, Set<BTree.Page> b) {
        Set<BTree.Page> copy = new HashSet<>(a);
        copy.retainAll(b);
        return copy.size();
    }

    static String pct(double fraction) {
        return String.format("%.0f%%", fraction * 100);
    }

    static void deleteTree(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
