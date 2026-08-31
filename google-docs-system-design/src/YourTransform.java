import java.util.*;

/**
 * EXERCISE 5 — write the transform function yourself.
 *
 * Fill in the four cases below. Then run:
 *
 *     java src/YourTransform.java
 *
 * It plugs YOUR function into the same server, the same clients and the same
 * network as the real one, and runs the scenarios from Tests.java plus 1000
 * random delivery schedules against it. Nothing is graded on style: either the
 * screens agree at the end or they do not.
 *
 * The starting version below is deliberately wrong — it returns every operation
 * unchanged, which is the naive design from §4.
 */
public final class YourTransform {

    /**
     * a was written for a document that does NOT contain b.
     * Return what a must become to have the same effect on a document that DOES.
     */
    static Ot.Op transform(Ot.Op a, Ot.Op b) {
        if (a.kind == Ot.Kind.NOOP || b.kind == Ot.Kind.NOOP) return a;

        // CASE 1 — both inserts.
        // TODO: if b landed before a, a shifts right. If after, a is unaffected.
        //       If they are at exactly the same position, break the tie the same
        //       way on every machine (a.site and b.site are the only values both
        //       machines are guaranteed to agree on).
        if (a.kind == Ot.Kind.INSERT && b.kind == Ot.Kind.INSERT) {
            return a;
        }

        // CASE 2 — a inserts, b deletes.
        // TODO: if b removed a character before a's insertion point, a shifts left.
        if (a.kind == Ot.Kind.INSERT && b.kind == Ot.Kind.DELETE) {
            return a;
        }

        // CASE 3 — a deletes, b inserts.
        // TODO: if b added a character at or before the one a wants to delete,
        //       a shifts right. Mind the boundary: "at" counts.
        if (a.kind == Ot.Kind.DELETE && b.kind == Ot.Kind.INSERT) {
            return a;
        }

        // CASE 4 — both delete.
        // TODO: three sub-cases. The third one is the interesting one: what
        //       should a become if b already deleted the very character a is
        //       aiming at? (Ot.Op.noop(a.site) exists for a reason.)
        return a;
    }

    // ------------------------------------------------------------------------

    public static void main(String[] args) {
        int[] score = {0, 0};
        run(score, "both type at once (C at the front, S at the end)", "HAT", "CHATS",
                sim -> { sim.type("Alice", 0, 'C'); sim.type("Bob", 3, 'S'); });
        run(score, "insert next to a delete", "CHATS", "CHAT!",
                sim -> { sim.erase("Alice", 4); sim.type("Bob", 5, '!'); });
        run(score, "both delete the same character", "CHATS", "CHAS",
                sim -> { sim.erase("Alice", 3); sim.erase("Bob", 3); });
        run(score, "both type at the same position", "HAT", "HATS!",
                sim -> { sim.type("Alice", 3, 'S'); sim.type("Bob", 3, '!'); });

        int diverged = 0;
        for (int seed = 0; seed < 1000; seed++) {
            Random rnd = new Random(seed);
            Collab.Sim s = new Collab.Sim("HAT", Collab.Mode.OT, YourTransform::transform, "Alice", "Bob", "Carol");
            String[] sites = {"Alice", "Bob", "Carol"};
            try {
                for (int step = 0; step < 30; step++) {
                    String site = sites[rnd.nextInt(3)];
                    Collab.Client cl = s.c(site);
                    if (rnd.nextInt(100) < 30 && cl.doc.length() > 1) s.erase(site, rnd.nextInt(cl.doc.length()));
                    else s.type(site, rnd.nextInt(cl.doc.length() + 1), (char) ('a' + rnd.nextInt(4)));
                    for (int k = rnd.nextInt(3); k > 0; k--) { int i = s.nextDeliverable(); if (i >= 0) s.deliverAt(i); }
                }
                s.settleRandom(rnd);
                if (!s.converged()) diverged++;
            } catch (RuntimeException e) { diverged++; }
        }
        boolean fuzzOk = diverged == 0;
        System.out.println((fuzzOk ? "PASS  " : "FAIL  ") + "1000 random delivery schedules — diverged " + diverged + " times");
        if (fuzzOk) score[0]++; else score[1]++;

        System.out.println("\n" + score[0] + " passed, " + score[1] + " failed");
        if (score[1] > 0) {
            System.out.println("Hint: every case is one sentence about positions. If you are stuck on the");
            System.out.println("last one, run 'java src/BrokenOt.java' to see what a missing tie-break does.");
            System.exit(1);
        }
        System.out.println("That is the real transform function. Compare yours with src/Ot.java.");
    }

    interface Script { void play(Collab.Sim sim); }

    static void run(int[] score, String name, String start, String expected, Script script) {
        Collab.Sim s = new Collab.Sim(start, Collab.Mode.OT, YourTransform::transform, "Alice", "Bob");
        String detail;
        boolean ok;
        try {
            script.play(s);
            s.settle();
            ok = s.converged() && s.server.doc.equals(expected);
            detail = s.docs();
        } catch (RuntimeException e) { ok = false; detail = "threw: " + e.getMessage(); }
        System.out.println((ok ? "PASS  " : "FAIL  ") + name + "\n        expected \"" + expected + "\"   got " + detail);
        if (ok) score[0]++; else score[1]++;
    }
}
