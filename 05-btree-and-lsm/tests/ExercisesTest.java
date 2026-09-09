import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Red-first tests for EXERCISES.md rung 5. These FAIL until you implement
 * the stubs in src/Exercises.java — that is the point.
 *
 *     javac -d out src/*.java tests/*.java experiments/*.java   # once
 *     java -cp out ExercisesTest
 */
public class ExercisesTest {

    static int passed = 0, failed = 0;

    public static void main(String[] args) {
        System.out.println("Running ExercisesTest  (RED until you do rung 5)");
        System.out.println();

        run("5.1  small_cases_match_the_javadoc", ExercisesTest::smallCasesMatchTheJavadoc);
        run("5.1  is_a_floor_on_the_real_trees_height", ExercisesTest::isAFloorOnTheRealTreesHeight);
        run("5.1  growth_is_logarithmic_not_linear", ExercisesTest::growthIsLogarithmicNotLinear);
        run("5.2  javadoc_example", ExercisesTest::javadocExample);
        run("5.2  newest_table_wins_regardless_of_list_position", ExercisesTest::newestTableWinsRegardlessOfPosition);
        run("5.2  tombstone_in_the_newest_table_removes_the_key", ExercisesTest::tombstoneRemovesTheKey);
        run("5.2  a_newer_value_resurrects_nothing_but_overrides_an_older_tombstone", ExercisesTest::newerValueOverridesTombstone);
        run("5.2  output_is_sorted_by_key", ExercisesTest::outputIsSortedByKey);

        System.out.println();
        System.out.printf("%d passed, %d failed%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    // -------------------------------------------- 5.1  predictedHeight

    static void smallCasesMatchTheJavadoc() {
        check(Exercises.predictedHeight(1, 4) == 1, "predictedHeight(1, 4) should be 1");
        check(Exercises.predictedHeight(4, 4) == 1, "predictedHeight(4, 4) should be 1");
        check(Exercises.predictedHeight(5, 4) == 2, "predictedHeight(5, 4) should be 2");
    }

    static void isAFloorOnTheRealTreesHeight() {
        // A real tree is never SHORTER than the perfectly-packed prediction,
        // and (with ~70% fill) never wildly taller either.
        for (int n : new int[]{50, 500, 2_000}) {
            BTree t = new BTree(4);
            List<String> keys = new ArrayList<>();
            for (int k = 0; k < n; k++) keys.add(String.format("%06d", k));
            Collections.shuffle(keys, new Random(n));
            for (String k : keys) t.insert(k, "v");
            int predicted = Exercises.predictedHeight(n, 4);
            check(predicted <= t.height(), "n=" + n + ": prediction exceeded reality");
            check(t.height() - predicted <= 3, "n=" + n + ": prediction far too optimistic");
        }
    }

    static void growthIsLogarithmicNotLinear() {
        // 100x the keys must NOT mean 100x the height — that IS fanout.
        check(Exercises.predictedHeight(1_000_000, 100) < 6,
                "predictedHeight(1_000_000, 100) should be under 6");
    }

    // -------------------------------------------- 5.2  newestWinsMerge

    static void javadocExample() {
        List<Exercises.Entry> out = Exercises.newestWinsMerge(List.of(
                table(1, e("a", "old"), e("b", "keep")),
                table(2, e("a", "new"), e("c", Lsm.TOMBSTONE))));
        List<Exercises.Entry> want = List.of(e("a", "new"), e("b", "keep"));
        check(want.equals(out), "expected " + want + ", got " + out);
    }

    static void newestTableWinsRegardlessOfPosition() {
        Map<String, String> out = asMap(Exercises.newestWinsMerge(List.of(
                table(1, e("k", "v1")),
                table(5, e("k", "v5")),
                table(3, e("k", "v3")))));
        check("v5".equals(out.get("k")), "the highest seq must win, got " + out.get("k"));
    }

    static void tombstoneRemovesTheKey() {
        Map<String, String> out = asMap(Exercises.newestWinsMerge(List.of(
                table(1, e("gone", "alive"), e("stays", "yes")),
                table(2, e("gone", Lsm.TOMBSTONE)))));
        check(!out.containsKey("gone"), "the tombstoned key survived: " + out);
        check("yes".equals(out.get("stays")), "an untouched key was lost: " + out);
    }

    static void newerValueOverridesTombstone() {
        // delete then re-insert: the key must come back
        Map<String, String> out = asMap(Exercises.newestWinsMerge(List.of(
                table(1, e("k", "first")),
                table(2, e("k", Lsm.TOMBSTONE)),
                table(3, e("k", "reinserted")))));
        check("reinserted".equals(out.get("k")), "re-insert after delete lost the key: " + out);
    }

    static void outputIsSortedByKey() {
        List<Exercises.Entry> out = Exercises.newestWinsMerge(List.of(
                table(1, e("a", "1"), e("m", "1"), e("z", "1")),
                table(2, e("b", "2"), e("y", "2"))));
        List<String> keys = new ArrayList<>();
        for (Exercises.Entry entry : out) keys.add(entry.key());
        List<String> sorted = new ArrayList<>(keys);
        Collections.sort(sorted);
        check(sorted.equals(keys), "output is not sorted by key: " + keys);
    }

    // ---------------------------------------------------------------- helpers

    static Exercises.Entry e(String key, String value) {
        return new Exercises.Entry(key, value);
    }

    static Exercises.Table table(int seq, Exercises.Entry... entries) {
        return new Exercises.Table(seq, List.of(entries));
    }

    static Map<String, String> asMap(List<Exercises.Entry> entries) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Exercises.Entry entry : entries) out.put(entry.key(), entry.value());
        return out;
    }

    interface Scenario {
        void run() throws Exception;
    }

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
