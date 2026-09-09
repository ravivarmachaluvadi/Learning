import java.util.*;

/**
 * Three planted bugs, each one a mistake that looks entirely reasonable in review.
 *
 *     java src/BrokenRing.java        show all three
 *     java src/BrokenRing.java 2      show just bug 2
 *
 * For each, work out the symptom, WHICH TEST in src/Tests.java catches it, and which
 * guarantee from README §12 it breaks -- before reading the output.
 */
public final class BrokenRing {

    static final List<String> KEYS = Tests.keys(20_000);

    public static void main(String[] args) {
        Set<Integer> only = new HashSet<>();
        for (String a : args) only.add(Integer.parseInt(a));
        if (only.isEmpty() || only.contains(1)) bug1();
        if (only.isEmpty() || only.contains(2)) bug2();
        if (only.isEmpty() || only.contains(3)) bug3();
        System.out.println("\nWhich test catches each? Which guarantee in README §12 does each break?");
    }

    static void header(int n, String what, String looks) {
        System.out.println("\n" + "=".repeat(74));
        System.out.println("BUG " + n + " -- " + what);
        System.out.println("what a reviewer would think: \"" + looks + "\"");
        System.out.println("=".repeat(74));
    }

    /** Machine positions derived from where the machine sits in the list, not from its name. */
    static void bug1() {
        header(1, "a machine's ring positions come from its INDEX in the list, not its name",
                  "the index is unique per machine and it is right there -- why hash the string?");
        Ring.BUG_POSITIONS_FROM_INDEX = true;
        try {
            Ring r = new Ring(200);
            for (int i = 0; i < 10; i++) r.addNode("n" + i);

            Map<String, String> before = Tests.assign(r, KEYS);
            r.addNode("n10");
            double onAdd = Tests.movedFraction(before, Tests.assign(r, KEYS));
            System.out.printf("%n  adding an 11th machine moved %.1f%% of keys -- which looks perfectly healthy.%n", onAdd * 100);

            Map<String, String> b2 = Tests.assign(r, KEYS);
            r.removeNode("n3");
            double onRemove = Tests.movedFraction(b2, Tests.assign(r, KEYS));
            System.out.printf("  removing ONE machine moved %.1f%% of keys.%n", onRemove * 100);

            Ring a = new Ring(200), b = new Ring(200);
            for (int i = 0; i < 8; i++) a.addNode("n" + i);
            for (int i = 7; i >= 0; i--) b.addNode("n" + i);
            int dis = 0;
            for (String k : KEYS) if (!a.owner(k).equals(b.owner(k))) dis++;
            System.out.printf("  two clients with the SAME 8 machines, added in different orders,%n");
            System.out.printf("  disagree about %,d of %,d keys (%.1f%%).%n", dis, KEYS.size(), 100.0 * dis / KEYS.size());

            System.out.println("""
            
              Look at the first line again: adding a machine behaved beautifully, because
              appending to the list does not renumber anybody. The bug is invisible in the
              one operation people test.

              It appears the moment an index SHIFTS -- when a machine is removed, or when
              two clients built their list in different orders. Then a machine's positions
              move, which is precisely what a machine's positions must never do. The
              modulo disease is back: ownership again depends on a global property of the
              cluster, only now it is wearing a ring costume.

              The rule this breaks: a machine's positions must be a function of that
              machine ALONE -- never of the cluster it happens to be in.""".indent(0));
        } finally { Ring.BUG_POSITIONS_FROM_INDEX = false; }
    }

    /** The replica walk forgets to skip machines it has already collected. */
    static void bug2() {
        header(2, "the replica walk does not skip machines it already chose",
                  "it walks clockwise and collects three -- what else would it do?");
        Ring.BUG_REPLICAS_NOT_DISTINCT = true;
        try {
            Ring r = new Ring(200);
            for (int i = 0; i < 6; i++) r.addNode("n" + i);
            int allSame = 0, someDup = 0;
            String example = null;
            for (String k : KEYS) {
                List<String> o = r.owners(k, 3);
                if (new HashSet<>(o).size() == 1) { allSame++; if (example == null) example = k + " -> " + o; }
                if (new HashSet<>(o).size() < o.size()) someDup++;
            }
            System.out.printf("%n  asking for 3 copies of each of %,d keys, over 6 machines:%n", KEYS.size());
            System.out.printf("    keys where at least two 'copies' are the same machine : %,d (%.1f%%)%n",
                    someDup, 100.0 * someDup / KEYS.size());
            System.out.printf("    keys where ALL THREE copies are one machine           : %,d (%.1f%%)%n",
                    allSame, 100.0 * allSame / KEYS.size());
            if (example != null) System.out.println("    for example: " + example);
            System.out.println("""
            
              With 200 positions per machine, adjacent positions on the ring frequently
              belong to the SAME machine -- so a walk that just takes the next three
              positions can take three of one machine's own.

              Every one of those keys reads as replicated three ways and is stored once.
              Nothing fails, no error appears, and the data is correct; you find out on
              the day a machine dies and takes the only copy with it.

              Note also that a small test cluster hides this. With one position per
              machine the walk always lands on three different machines and the bug
              cannot occur -- so the configuration you are most likely to develop
              against is the one that cannot reveal it.""".indent(0));
        } finally { Ring.BUG_REPLICAS_NOT_DISTINCT = false; }
    }

    /** The successor search stops at the end of the array instead of coming round. */
    static void bug3() {
        header(3, "the search stops at the end of the array instead of wrapping to the start",
                  "clamping to the last index is safer than letting it run off the end");
        Ring.BUG_NO_WRAP = true;
        try {
            System.out.printf("%n  %-24s %14s %16s %14s%n",
                    "cluster", "ring positions", "keys misplaced", "imbalance");
            for (int[] cfg : new int[][]{{2, 1}, {4, 1}, {8, 1}, {8, 10}, {8, 200}}) {
                int machines = cfg[0], vnodes = cfg[1];
                Ring broken = new Ring(vnodes), good = new Ring(vnodes);
                for (int i = 0; i < machines; i++) { broken.addNode("n" + i); good.addNode("n" + i); }
                int wrong = 0;
                Ring.BUG_NO_WRAP = false;
                Map<String, String> correct = Tests.assign(good, KEYS);
                Ring.BUG_NO_WRAP = true;
                for (String k : KEYS) if (!broken.owner(k).equals(correct.get(k))) wrong++;
                System.out.printf("  %-24s %14d %,10d (%4.1f%%) %13.2fx%n",
                        machines + " machines x " + vnodes + " pos", machines * vnodes,
                        wrong, 100.0 * wrong / KEYS.size(),
                        Tests.imbalance(Tests.counts(broken, KEYS)));
            }
            System.out.println("""

              Read that table from the bottom up, because the bottom row is the one that
              makes this dangerous.

              At a production-shaped 8 x 200, the gap above the last ring position is a
              sliver of the circle and almost nothing falls in it -- the bug is very nearly
              invisible. It gets steadily worse as the ring gets sparser, and at 2 machines
              with one position each a large fraction of the keyspace is simply handed to
              the wrong machine.

              So this is a latent bug that hides in exactly the configuration you run and
              appears in exactly the configuration you develop and test against -- or the
              day you scale a cluster down far enough. It never crashes, never errors, and
              every client agrees on the wrong answer, so nothing anywhere reports a
              problem.

              One ternary is the difference between a circle and a line.""".indent(0));
        } finally { Ring.BUG_NO_WRAP = false; }
    }
}
