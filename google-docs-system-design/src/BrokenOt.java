import java.util.*;

/**
 * A planted bug, for the debugging exercise.
 *
 * ONE line of Ot.transform is different here: when two people insert at exactly
 * the same position, this version does not break the tie. It just keeps the
 * position it was given.
 *
 * That looks harmless. Find out what it costs.
 *
 * Run:  java src/BrokenOt.java
 */
public final class BrokenOt {

    static Ot.Op brokenTransform(Ot.Op a, Ot.Op b) {
        if (a.kind == Ot.Kind.NOOP || b.kind == Ot.Kind.NOOP) return a;
        if (a.kind == Ot.Kind.INSERT && b.kind == Ot.Kind.INSERT) {
            if (b.pos < a.pos) return a.withPos(a.pos + 1);
            if (b.pos > a.pos) return a;
            return a;                              // <-- THE BUG. The real rule is on the next line.
            // return a.site.compareTo(b.site) < 0 ? a : a.withPos(a.pos + 1);
        }
        if (a.kind == Ot.Kind.INSERT && b.kind == Ot.Kind.DELETE) return b.pos < a.pos ? a.withPos(a.pos - 1) : a;
        if (a.kind == Ot.Kind.DELETE && b.kind == Ot.Kind.INSERT) return b.pos <= a.pos ? a.withPos(a.pos + 1) : a;
        if (b.pos < a.pos) return a.withPos(a.pos - 1);
        if (b.pos > a.pos) return a;
        return Ot.Op.noop(a.site);
    }

    public static void main(String[] args) {
        System.out.println("Scenario: the document says \"HAT\". Alice types S at the end.");
        System.out.println("At the very same moment, Bob types ! at the end.\n");

        Collab.Sim s = new Collab.Sim("HAT", Collab.Mode.OT, BrokenOt::brokenTransform, "Alice", "Bob");
        s.type("Alice", 3, 'S');
        s.type("Bob", 3, '!');
        s.settle();
        s.printEvents();
        System.out.println("\n   " + s.docs());
        System.out.println("   converged = " + s.converged());

        int diverged = 0;
        for (int seed = 0; seed < 500; seed++) {
            Random rnd = new Random(seed);
            Collab.Sim f = new Collab.Sim("HAT", Collab.Mode.OT, BrokenOt::brokenTransform, "Alice", "Bob", "Carol");
            String[] sites = {"Alice", "Bob", "Carol"};
            try {
                for (int step = 0; step < 20; step++) {
                    String site = sites[rnd.nextInt(3)];
                    Collab.Client cl = f.c(site);
                    if (rnd.nextInt(100) < 30 && cl.doc.length() > 1) f.erase(site, rnd.nextInt(cl.doc.length()));
                    else f.type(site, rnd.nextInt(cl.doc.length() + 1), (char) ('a' + rnd.nextInt(4)));
                    for (int k = rnd.nextInt(3); k > 0; k--) { int i = f.nextDeliverable(); if (i >= 0) f.deliverAt(i); }
                }
                f.settleRandom(rnd);
                if (!f.converged()) diverged++;
            } catch (RuntimeException e) { diverged++; }
        }
        System.out.println("\n   random-schedule check: diverged in " + diverged + " of 500 runs");
        System.out.println("   (the correct transform diverges in 0 — see Tests.java)");
    }
}
