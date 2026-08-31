import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * EXERCISES.md rung 6 — run the invariant tests against the planted-bug engine.
 *
 * The bug is ONE expression in src/BrokenLsm.java's compact(). This script
 * builds the same workload the real test suite uses, compacts with the broken
 * engine, and reports which answers changed.
 *
 * Run:  javac -d out src/*.java tests/*.java experiments/*.java   # once
 *       java -cp out ExpDebugBroken
 */
public class ExpDebugBroken {

    /** Each engine under test: a display name and a way to open one on a directory. */
    interface EngineFactory {
        Lsm open(Path dir) throws IOException;
    }

    public static void main(String[] args) throws Exception {
        String[] names = {"Lsm", "BrokenLsm"};
        EngineFactory[] factories = {
                dir -> new Lsm(dir, 4, 999),
                dir -> new BrokenLsm(dir, 4, 999),
        };

        for (int i = 0; i < names.length; i++) {
            Path d = Files.createTempDirectory("lsm-debug-");
            Lsm db = build(factories[i], d);
            Map<String, String> before = answers(db);
            db.compact();
            Map<String, String> after = answers(db);
            Map<String, String> changed = new LinkedHashMap<>();
            for (String k : before.keySet()) {
                if (!java.util.Objects.equals(before.get(k), after.get(k))) {
                    changed.put(k, "(" + before.get(k) + " -> " + after.get(k) + ")");
                }
            }
            System.out.println();
            System.out.println("=== " + names[i] + " ===");
            System.out.println("  answers before compaction: " + before);
            System.out.println("  answers after  compaction: " + after);
            if (!changed.isEmpty()) {
                System.out.println("  *** " + changed.size() + " ANSWER(S) CHANGED: " + changed);
                System.out.println("  *** Compaction is supposed to be LOGICALLY INVISIBLE (README §12,");
                System.out.println("  *** LSM invariant 4). This engine violates it.");
            } else {
                System.out.println("  every answer identical — invariant 4 holds.");
            }
            db.close();
            deleteTree(d);
        }

        System.out.println(
                "\nQuestions (answers in SOLUTIONS.md):\n"
                + "  1. Which single expression in BrokenLsm.java's compact() is wrong?\n"
                + "  2. Which test in tests/LsmTest.java catches it?\n"
                + "  3. Which README invariant does it violate, and what would a USER of a\n"
                + "     real database observe when this bug ships?");
    }

    static Lsm build(EngineFactory factory, Path d) throws IOException {
        Lsm db = factory.open(d);
        for (int i = 0; i < 40; i++) db.put(String.format("k%02d", i % 10), "v" + i);   // every key overwritten ~4x
        for (int i = 0; i < 10; i += 2) db.delete(String.format("k%02d", i));           // delete the even keys
        for (String pad : new String[]{"pad1", "pad2", "pad3"}) db.put(pad, "x");       // force the tombstones to flush
        return db;
    }

    static Map<String, String> answers(Lsm db) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            String k = String.format("k%02d", i);
            out.put(k, db.get(k));
        }
        return out;
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
