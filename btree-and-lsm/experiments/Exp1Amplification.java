import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Experiment 1 — the amplification scorecard (README §7, §12, §14).
 *
 * 3,000 random puts into both engines, then reads over the whole key space.
 * Prints write amp, read amp, and space amp for each — the §12 table, measured.
 *
 * Run:  javac -d out src/*.java tests/*.java experiments/*.java   # once
 *       java -cp out Exp1Amplification
 */
public class Exp1Amplification {

    static final int N = 3_000;
    static final int PAGE_BYTES = 8192;   // model: one B-tree page write = one 8 KB page

    public static void main(String[] args) throws Exception {
        Random rng = new Random(42);
        // 1,000 distinct keys, 3,000 puts => every key overwritten ~3x on average,
        // so the LSM accumulates shadowed versions for compaction to reclaim (the
        // space-amp line below) while the B-tree just updates in place.
        List<String> keyspace = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) keyspace.add(String.format("k%09d", rng.nextInt(1_000_000_000)));
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < N; i++) keys.add(keyspace.get(rng.nextInt(keyspace.size())));
        String value = "x".repeat(90);    // ~100-byte rows, like a modest table row

        // ---------------- B-tree ----------------
        BTree bt = new BTree(4);
        for (String k : keys) bt.insert(k, value);
        long userBytes = 0;
        for (String k : keys) userBytes += k.length() + value.length();
        double btWriteAmp = (double) (bt.pageWrites * PAGE_BYTES) / userBytes;
        bt.resetCounters();
        List<String> sample = new ArrayList<>(keys);
        Collections.shuffle(sample, rng);
        sample = sample.subList(0, 500);
        for (String k : sample) bt.search(k);
        double btReadAmp = (double) bt.pageReads / sample.size();
        double btSpaceAmp = 1 / bt.fillFactor();

        // ---------------- LSM ----------------
        Path d = Files.createTempDirectory("lsm-exp1-");
        Lsm db = new Lsm(d, 200, 6);
        for (String k : keys) db.put(k, value);
        double lsmWriteAmp = (double) db.bytesWritten / db.userBytes;
        db.resetCounters();
        for (String k : sample) db.get(k);
        double lsmReadAmp = (double) (db.fileProbes + db.bloomSkips) / sample.size();   // places considered
        double lsmProbes = (double) db.fileProbes / sample.size();                      // disk touches
        int tablesBefore = db.tableCount();
        long spaceBefore = db.diskBytes();
        db.compact();
        long spaceAfter = db.diskBytes();
        db.resetCounters();
        for (String k : sample) db.get(k);
        double lsmReadAmpAfter = (double) db.fileProbes / sample.size();

        System.out.printf("%,d random ~100-byte puts, then 500 random gets%n%n", N);
        System.out.printf("%-38s%10s   %10s%n", "", "B-tree", "LSM");
        System.out.printf("%-38s%10.1f   %10.1f%n", "WRITE amp (disk bytes / user bytes)", btWriteAmp, lsmWriteAmp);
        System.out.printf("%-38s%10.1f   %10.1f%n", "READ amp (places checked per get)", btReadAmp, lsmReadAmp);
        System.out.printf("%-38s%10.1f   %10.1f  (blooms skip the rest)%n",
                "  ...of which real disk probes", btReadAmp, lsmProbes);
        System.out.printf("%-38s%10.2f   %10.2f  (before compaction)%n",
                "SPACE amp (page slack / dead data)", btSpaceAmp, (double) spaceBefore / Math.max(1, spaceAfter));
        System.out.printf("%nLSM detail: %d flushes, %d compactions, %d tables -> 1 after final compact%n",
                db.flushes, db.compactions, tablesBefore);
        System.out.printf("LSM read amp after compaction: %.1f probes/get (one merged table + memtable)%n",
                lsmReadAmpAfter);
        System.out.printf("B-tree detail: height %d, %d pages, fill factor %.0f%%%n",
                bt.height(), bt.pageCount(), bt.fillFactor() * 100);
        System.out.println(
                "\nLesson (§12): the B-tree pays a whole page write per row (huge write amp,\n"
                + "flat cheap reads); the LSM pays WAL+flush+compaction rewrites (modest write\n"
                + "amp, reads that degrade with table count and recover after compaction).\n"
                + "Neither wins both columns. They move the same sorting work to different moments.");
        db.close();
        deleteTree(d);
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
