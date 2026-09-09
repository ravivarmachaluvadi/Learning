import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Scenario tests for the LSM engine — each test name is a claim from README §10.
 *
 *     javac -d out src/*.java tests/*.java experiments/*.java   # once
 *     java -cp out LsmTest
 *
 * Each scenario gets its own temp directory (deleted afterwards) so you can
 * point `ls` at a real one while experimenting.
 */
public class LsmTest {

    static int passed = 0, failed = 0;

    public static void main(String[] args) {
        System.out.println("Running LsmTest");
        System.out.println();

        run("newest_wins_across_memtable_and_flushed_tables", LsmTest::newestWinsAcrossMemtableAndTables);
        run("delete_writes_a_tombstone_and_old_value_survives_on_disk", LsmTest::deleteWritesATombstone);
        run("compaction_preserves_every_answer_and_reclaims_space", LsmTest::compactionPreservesEveryAnswer);
        run("crash_recovery_replays_wal", LsmTest::crashRecoveryReplaysWal);
        run("tombstones_slow_range_reads", LsmTest::tombstonesSlowRangeReads);
        run("bloom_filters_skip_files_for_absent_keys", LsmTest::bloomFiltersSkipFilesForAbsentKeys);

        System.out.println();
        System.out.printf("%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ tests

    static void newestWinsAcrossMemtableAndTables(Path dir) throws IOException {
        Lsm db = fresh(dir, 4);
        db.put("k05", "old");
        for (int i = 0; i < 3; i++) db.put("fill" + i, "x");   // forces a flush; "old" is now on disk
        check(db.tableCount() >= 1, "expected at least one flushed table, got " + db.tableCount());
        db.put("k05", "new");                                  // newer value, still in RAM
        check("new".equals(db.get("k05")), "memtable did not shadow the SSTable: " + db.get("k05"));
        db.close();
    }

    static void deleteWritesATombstone(Path dir) throws IOException {
        Lsm db = fresh(dir, 2);
        db.put("k1", "alive");
        db.put("k2", "x");                  // flush: k1 is in an immutable file
        db.delete("k1");
        db.put("k3", "y");                  // flush the tombstone too
        check(db.get("k1") == null, "reads should say k1 is gone, got " + db.get("k1"));
        List<String> onDisk = new ArrayList<>();
        for (Lsm.SSTable t : db.tables)
            for (Lsm.Entry e : t.entries())
                if (e.key().equals("k1")) onDisk.add(e.value());
        // ...but the immutable file still physically holds BOTH the old value
        // and the tombstone — deletion is a write (§10.4)
        check(onDisk.contains("alive"), "the old value should still be on disk: " + onDisk);
        check(onDisk.contains(Lsm.TOMBSTONE), "the tombstone should be on disk: " + onDisk);
        db.close();
    }

    static void compactionPreservesEveryAnswer(Path dir) throws IOException {
        Lsm db = fresh(dir, 4);
        for (int i = 0; i < 40; i++) db.put(String.format("k%02d", i % 10), "v" + i);   // heavy overwriting → shadowed copies
        for (int i = 0; i < 10; i += 2) db.delete(String.format("k%02d", i));
        db.put("pad1", "x");
        db.put("pad2", "x");
        db.put("pad3", "x");                                                            // flush tombstones
        Map<String, String> before = answers(db);
        long bytesBefore = db.diskBytes();
        int tablesBefore = db.tableCount();
        db.compact();
        Map<String, String> after = answers(db);
        // INVARIANT 4: compaction is logically invisible
        check(before.equals(after), "compaction changed answers: " + before + " -> " + after);
        // physically, shadowed values and tombstones died
        check(db.diskBytes() < bytesBefore, "disk did not shrink: " + bytesBefore + " -> " + db.diskBytes());
        check(db.tableCount() < tablesBefore, "table count did not shrink");
        check(db.tableCount() == 1, "expected exactly one merged table, got " + db.tableCount());
        db.close();
    }

    static void crashRecoveryReplaysWal(Path dir) throws IOException {
        Lsm db = fresh(dir, 100);              // nothing will flush
        db.put("a", "1");
        db.put("b", "2");
        db.delete("a");
        // simulate a crash: no clean close, just abandon the object
        db.flushWal();
        Lsm db2 = new Lsm(dir);                // restart = load tables + replay WAL
        check(db2.get("a") == null, "the delete did not survive the crash");
        check("2".equals(db2.get("b")), "the put did not survive the crash");
        db2.close();
    }

    static void tombstonesSlowRangeReads(Path dir) throws IOException {
        Lsm db = fresh(dir, 10);
        for (int i = 0; i < 100; i++) db.put(String.format("job%03d", i), "payload");
        for (int i = 0; i < 90; i++) db.delete(String.format("job%03d", i));   // the queue anti-pattern (F-L2)
        db.resetCounters();
        List<Lsm.Entry> live = db.rangeScan("job000", "job099");
        check(live.size() == 10, "expected 10 live entries, got " + live.size());
        // to find 10 live entries the scan waded through ~10x the records
        check(db.recordsScanned >= 100, "only " + db.recordsScanned + " records scanned");
        db.close();
    }

    static void bloomFiltersSkipFilesForAbsentKeys(Path dir) throws IOException {
        Lsm db = new Lsm(dir, 5, 999, true, 10);
        for (int i = 0; i < 30; i++) db.put(String.format("k%03d", i), "v");   // ~6 SSTables
        db.resetCounters();
        for (int i = 0; i < 200; i++) db.get("missing" + i);
        long withBloom = db.fileProbes;
        check(db.bloomSkips > 0, "no bloom skips happened at all");
        db.close();
        // same reads, blooms off → every table probed every time
        Lsm db2 = new Lsm(dir, false);
        db2.resetCounters();
        for (int i = 0; i < 200; i++) db2.get("missing" + i);
        check(db2.fileProbes > withBloom * 5,
                "blooms off gave " + db2.fileProbes + " probes vs " + withBloom + " with blooms on");
        db2.close();
    }

    // ---------------------------------------------------------------- helpers

    /** compactAt=999: only compact when a test says so. */
    static Lsm fresh(Path dir, int memtableLimit) throws IOException {
        return new Lsm(dir, memtableLimit, 999);
    }

    static Map<String, String> answers(Lsm db) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            String k = String.format("k%02d", i);
            out.put(k, db.get(k));
        }
        return out;
    }

    interface Scenario {
        void run(Path dir) throws Exception;
    }

    /** One PASS/FAIL line per scenario, each in its own throwaway directory. */
    static void run(String name, Scenario scenario) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("lsm-test-");
            scenario.run(dir);
            passed++;
            System.out.println("  PASS  " + name);
        } catch (Throwable t) {
            failed++;
            String detail = t.getMessage() == null ? t.toString() : t.getMessage();
            System.out.println("  FAIL  " + name + "  (" + detail + ")");
        } finally {
            deleteTree(dir);
        }
    }

    static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }

    static void deleteTree(Path dir) {
        if (dir == null) return;
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
