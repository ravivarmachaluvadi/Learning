import java.util.List;

/**
 * Stubs for EXERCISES.md rung 5 (Implement).
 *
 * Both start as UnsupportedOperationException, so the exercise suite
 * (`java -cp out ExercisesTest`) fails RED until you write them.
 * Answers in SOLUTIONS.md — try first.
 */
public class Exercises {

    /** One (key, value) pair inside a table. A value may be Lsm.TOMBSTONE. */
    public record Entry(String key, String value) {}

    /** One table handed to the merge: its sequence number and its sorted entries. */
    public record Table(int seq, List<Entry> entries) {}

    /**
     * Exercise 5.1 — the fanout maths (README §7).
     *
     * Given `nKeys` keys in a B+ tree whose pages hold at most `maxKeys`
     * keys, return the SMALLEST possible height (number of levels, root
     * included) such a tree could have — i.e. if every page were completely
     * full.
     *
     * Why this matters: it is the floor on read cost. A real tree is taller
     * because pages carry slack (fill factor ~70%), which is exactly what
     * tests/ExercisesTest.java checks — your number must be <= the real tree's
     * height, and within a level or two of it.
     *
     * Examples:
     *     predictedHeight(1, 4)    == 1    // one page, one level
     *     predictedHeight(4, 4)    == 1    // still fits in the root leaf
     *     predictedHeight(5, 4)    == 2    // must split: root + 2 leaves
     *     predictedHeight(1000, 4) == 5
     */
    public static int predictedHeight(int nKeys, int maxKeys) {
        throw new UnsupportedOperationException("exercise 5.1");
    }

    /**
     * Exercise 5.2 — the heart of compaction (README §10.6, invariant 4).
     *
     * `tables` is a list of Table(seq, entries) in ARBITRARY order, where
     * `entries` is a list of (key, value) pairs sorted by key. A value may be
     * the string Lsm.TOMBSTONE ("&lt;TOMBSTONE&gt;") meaning "this key was deleted".
     *
     * The `seq` number — NOT the position in the list — says which table is
     * newer (higher seq = newer). Relying on position is how real merge bugs
     * happen; the tests check you didn't.
     *
     * Return the compacted result as a list of (key, value) pairs, sorted by
     * key, where:
     *   * for each key, the value from the HIGHEST seq wins (newest wins), and
     *   * keys whose winning value is a tombstone are omitted entirely
     *     (compaction is where tombstones and shadowed values physically die).
     *
     * Example:
     *     newestWinsMerge(List.of(
     *         new Table(1, List.of(new Entry("a", "old"), new Entry("b", "keep"))),
     *         new Table(2, List.of(new Entry("a", "new"), new Entry("c", Lsm.TOMBSTONE)))))
     *     == List.of(new Entry("a", "new"), new Entry("b", "keep"))
     *
     * Getting the priority backwards resurrects deleted data — which is exactly
     * the planted bug in src/BrokenLsm.java (rung 6).
     */
    public static List<Entry> newestWinsMerge(List<Table> tables) {
        throw new UnsupportedOperationException("exercise 5.2");
    }
}
