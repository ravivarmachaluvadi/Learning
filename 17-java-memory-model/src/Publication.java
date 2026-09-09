/*
 * EXPERIMENT 4 — SAFE PUBLICATION (handing an object to another thread)
 * =====================================================================
 *
 * A writer thread builds an object and stores it in a shared field.
 * A reader thread spins until the field is non-null, then reads the fields.
 *
 * The danger: constructing an object is several separate writes --
 *
 *      allocate memory for Config
 *      write a = 7
 *      write b = 7
 *      write c = 7
 *      write the reference into 'shared'
 *
 * Nothing in a plain field write forces those to become visible in that order.
 * Another thread can legally see a non-null reference to a Config whose fields
 * are still 0. The object is published, but only half-built.
 *
 *   java -cp out Publication unsafe        plain fields, plain reference
 *   java -cp out Publication finalfields   final fields, plain reference
 *   java -cp out Publication volatileref   plain fields, volatile reference
 *
 * HONEST WARNING ABOUT THIS MACHINE:
 * x86 hardware never reorders one store past another store, so on an Intel or
 * AMD CPU the 'unsafe' mode will almost certainly report zero anomalies. That
 * is NOT a pass. It means this specific hardware happens to be strong enough
 * to hide the bug. The same bytecode on ARM (Apple Silicon, AWS Graviton,
 * most phones) reorders stores freely and does break. This experiment is here
 * to teach you that a green run on your laptop proves nothing about the model.
 */
public class Publication {

    static final int TRIALS = Integer.getInteger("trials", 200_000);

    /** How long the reader waits for a publication before giving up on a round. */
    static final int SPIN_LIMIT = Integer.getInteger("spins", 5_000);

    /** Plain fields: the JMM gives no guarantee about seeing these after publication. */
    static class PlainConfig {
        int a, b, c;
        PlainConfig(int v) { a = v; b = v; c = v; }
    }

    /** Final fields: the JMM makes a special promise for these (see README §11). */
    static class FinalConfig {
        final int a, b, c;
        FinalConfig(int v) { a = v; b = v; c = v; }
    }

    static PlainConfig plainRef;
    static volatile PlainConfig volatileRef;
    static FinalConfig finalRef;

    // two-thread ticket barrier (same idea as Reordering.java)
    static volatile long ticketW, ticketR;

    public static void main(String[] args) throws Exception {
        final String mode = args.length > 0 ? args[0].toLowerCase() : "unsafe";
        if (!mode.equals("unsafe") && !mode.equals("finalfields") && !mode.equals("volatileref")) {
            throw new IllegalArgumentException("mode must be: unsafe | finalfields | volatileref");
        }

        System.out.println("=== EXPERIMENT 4: SAFE PUBLICATION ===================================");
        System.out.println("mode             : " + mode);
        System.out.println("  reference field: " + (mode.equals("volatileref") ? "volatile" : "plain"));
        System.out.println("  object fields  : " + (mode.equals("finalfields") ? "final" : "plain"));
        System.out.println("trials           : " + String.format("%,d", TRIALS));
        System.out.println("cpu              : " + System.getProperty("os.arch"));
        System.out.println();

        final long[] anomalies = new long[1];   // saw a reference, but its fields were stale/partial
        final long[] missed    = new long[1];   // never saw the reference at all this round

        Thread writer = new Thread(() -> {
            long t = 0;
            for (int i = 1; i <= TRIALS; i++) {
                ticketW = ++t;
                while (ticketR < t) { Thread.onSpinWait(); }

                switch (mode) {
                    case "unsafe"      -> plainRef    = new PlainConfig(i);
                    case "finalfields" -> finalRef    = new FinalConfig(i);
                    case "volatileref" -> volatileRef = new PlainConfig(i);
                }

                ticketW = ++t;
                while (ticketR < t) { Thread.onSpinWait(); }

                plainRef = null; finalRef = null; volatileRef = null;
            }
        }, "writer");

        Thread reader = new Thread(() -> {
            long t = 0;
            for (int i = 1; i <= TRIALS; i++) {
                ticketR = ++t;
                while (ticketW < t) { Thread.onSpinWait(); }

                // spin until something is published, then check it is whole
                int a = -1, b = -1, c = -1;
                for (int spin = 0; spin < SPIN_LIMIT; spin++) {
                    switch (mode) {
                        case "unsafe" -> {
                            PlainConfig p = plainRef;
                            if (p != null) { a = p.a; b = p.b; c = p.c; }
                        }
                        case "finalfields" -> {
                            FinalConfig f = finalRef;
                            if (f != null) { a = f.a; b = f.b; c = f.c; }
                        }
                        case "volatileref" -> {
                            PlainConfig v = volatileRef;
                            if (v != null) { a = v.a; b = v.b; c = v.c; }
                        }
                    }
                    if (a != -1) break;
                }
                // a == -1 means we never saw the publication at all in this round. That is
                // not a torn object -- it is a *different* JMM symptom (the plain read got
                // hoisted or the write never became visible), so it gets its own counter.
                // An anomaly is seeing the OBJECT but not its CONTENTS.
                if (a == -1) {
                    missed[0]++;
                } else if (a != i || b != i || c != i) {
                    anomalies[0]++;
                }

                ticketR = ++t;
                while (ticketW < t) { Thread.onSpinWait(); }
            }
        }, "reader");

        long start = System.nanoTime();
        writer.start();
        reader.start();
        writer.join();
        reader.join();
        long millis = (System.nanoTime() - start) / 1_000_000;

        System.out.println("  partially-visible objects observed : " + String.format("%,d", anomalies[0]));
        System.out.println("  publication never seen at all      : " + String.format("%,d", missed[0])
                + "  (reader gave up after " + String.format("%,d", SPIN_LIMIT) + " spins)");
        System.out.println("  elapsed                            : " + millis + " ms");
        System.out.println();

        if (anomalies[0] > 0) {
            System.out.println("RESULT: *** UNSAFE PUBLICATION OBSERVED *** the reader saw an object");
            System.out.println("        whose constructor had not finished from its point of view.");
        } else {
            System.out.println("RESULT: no anomaly observed on this run.");
            if (mode.equals("unsafe")) {
                System.out.println("  Read this carefully: 'not observed' is NOT 'cannot happen'.");
                System.out.println("  x86 does not reorder store-store in hardware, so the only thing that");
                System.out.println("  could break this run is the JIT, and it chose not to. Ship this code");
                System.out.println("  to an ARM server and the guarantee you are relying on does not exist.");
            } else {
                System.out.println("  Expected: this mode is safe by the rules of the model, not by luck.");
            }
        }
        System.out.println("======================================================================");
    }
}
