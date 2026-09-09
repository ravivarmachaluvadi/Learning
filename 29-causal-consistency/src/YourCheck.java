import java.util.*;

/**
 * EXERCISE 5 — write the rule yourself.
 *
 * A replica has just received `w` from another replica. `applied` is the set of
 * writes this replica has already made visible. Return true if it is safe to
 * show `w` to clients right now, false if it must be held.
 *
 *   java src/YourCheck.java     runs six checks against your implementation
 *
 * You are done when it prints 6/6. The rule is one sentence long; the value of
 * the exercise is in check 4, which fails for the version most people write.
 */
public class YourCheck {

    static boolean safeToShow(Set<Dot> applied, Write w) {
        // ==================== YOUR CODE HERE ====================
        // Available on w:  w.id()      this write's own name, e.g. R1#3
        //                  w.key()     which key it sets
        //                  w.deps()    every write in its causal past
        //                  w.lamport() a single counter
        //                  w.vv()      one counter per replica
        //
        // Delete the next line and write the real rule.
        return true;
        // ========================================================
    }

    // ------------------------------------------------------------- self-check

    public static void main(String[] args) {
        int ok = 0;

        ok += report(1, "a write with no dependencies is shown immediately",
                safeToShow(new TreeSet<>(), write("R0#1", "post", Set.of())));

        ok += report(2, "a write whose only dependency is already applied is shown",
                safeToShow(new TreeSet<>(List.of(dot("R0#1"))),
                           write("R1#1", "comment", Set.of(dot("R0#1")))));

        ok += report(3, "a write whose dependency is missing is HELD",
                !safeToShow(new TreeSet<>(), write("R1#1", "comment", Set.of(dot("R0#1")))));

        ok += report(4, "a dependency on a DIFFERENT key still counts",
                !safeToShow(new TreeSet<>(List.of(dot("R1#1"))),
                            write("R1#2", "comment", Set.of(dot("R0#7"), dot("R1#1")))));

        ok += report(5, "several dependencies: all of them must be present",
                !safeToShow(new TreeSet<>(List.of(dot("R0#1"))),
                            write("R2#1", "like", Set.of(dot("R0#1"), dot("R1#1")))));

        // the end-to-end one: the story from scene 1, with your rule installed
        Cluster c = new Cluster(3, Rule.causalDeps(), new Random(1));
        c.customDeliverable = YourCheck::safeToShow;
        c.client("amira", 0); c.client("ben", 1); c.client("cara", 2);
        c.minDelay = 2; c.maxDelay = 2; c.linkDelay[0][2] = 10;
        c.put("amira", "post", "photo");
        c.run(3);
        c.get("ben", "post");
        c.put("ben", "comment", "nice");
        c.run(3);
        c.get("cara", "comment");
        ok += report(6, "Cara never sees the comment before the post", c.causalAnomalies == 0);

        System.out.printf("%n%d / 6%s%n", ok, ok == 6
            ? "  — correct. Compare with Cluster.deliverable(), case DEPS."
            : "  — keep going. If only check 4 fails, you filtered the dependencies by key.");
    }

    static Dot dot(String s) {
        String[] p = s.substring(1).split("#");
        return new Dot(Integer.parseInt(p[0]), Long.parseLong(p[1]));
    }

    static Write write(String id, String key, Set<Dot> deps) {
        return new Write(dot(id), key, "v", new TreeSet<>(deps), new TreeSet<>(deps), 1, new long[]{0, 0, 0});
    }

    static int report(int n, String what, boolean pass) {
        System.out.printf("  %d. %-52s %s%n", n, what, pass ? "PASS" : "fail");
        return pass ? 1 : 0;
    }
}
