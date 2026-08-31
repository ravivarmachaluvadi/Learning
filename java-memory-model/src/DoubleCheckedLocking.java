/*
 * EXPERIMENT 5 — DOUBLE-CHECKED LOCKING
 * =====================================
 *
 * The most famous JMM bug in Java's history. It was published as a recommended
 * idiom, appeared in books and in production code everywhere, and was quietly
 * broken for years. Nobody's tests caught it, because it passes on x86.
 *
 * The goal: create a singleton lazily, but avoid paying for a lock on every
 * call after the first one. So you check, lock, and check again.
 *
 * Run it:
 *   java -cp out DoubleCheckedLocking
 *
 * All four versions will print the same answer on this machine. That is the
 * lesson. You cannot test your way to memory-model correctness; you have to
 * reason about it. Read the comments on BrokenDcl below and convince yourself
 * why it is broken even though it works.
 */
public class DoubleCheckedLocking {

    static class Service {
        final String name;
        final int id;
        Service(String name, int id) { this.name = name; this.id = id; }
    }

    // ------------------------------------------------------------------
    // 1. BROKEN. This is the version that shipped in real code for years.
    // ------------------------------------------------------------------
    static class BrokenDcl {
        private static Service instance;          // <-- NOT volatile. This is the bug.

        static Service get() {
            if (instance == null) {               // first check, no lock (the fast path)
                synchronized (BrokenDcl.class) {
                    if (instance == null) {       // second check, holding the lock
                        instance = new Service("broken", 1);
                    }
                }
            }
            return instance;
        }
        /*
         * Why it is broken:
         *
         * `instance = new Service("broken", 1)` is not one operation. It is:
         *
         *      1. allocate raw memory for a Service
         *      2. write name = "broken"
         *      3. write id = 1
         *      4. publish: instance = that address
         *
         * Steps 2, 3 and 4 are plain writes to plain fields. The model lets
         * them become visible to other threads in ANY order. If step 4 lands
         * before steps 2 and 3, then a second thread running the FIRST check
         * -- outside the lock, so it synchronises with nothing -- sees a
         * non-null `instance` and returns it. It gets a Service with
         * name == null and id == 0.
         *
         * The lock does not save you. The whole point of the fast path is that
         * it skips the lock, and a lock only creates a happens-before edge
         * between threads that BOTH acquire it.
         */
    }

    // ------------------------------------------------------------------
    // 2. FIXED with volatile (correct since Java 5).
    // ------------------------------------------------------------------
    static class VolatileDcl {
        private static volatile Service instance;   // <-- the whole fix

        static Service get() {
            Service local = instance;               // read the volatile field ONCE
            if (local == null) {
                synchronized (VolatileDcl.class) {
                    local = instance;
                    if (local == null) {
                        local = new Service("volatile-dcl", 2);
                        instance = local;           // volatile write: everything above it
                    }                               // is published along with it
                }
            }
            return local;
        }
        /*
         * The volatile WRITE at the end of construction has "release" semantics:
         * no write before it can be moved after it. The volatile READ on the
         * fast path has "acquire" semantics: no read after it can be moved
         * before it. Pair them and the reader that sees the non-null reference
         * is guaranteed to see the fully-built object.
         *
         * The `local` variable is not cosmetic. It turns two volatile reads on
         * the hot path into one, which used to matter a lot and still matters
         * a little.
         */
    }

    // ------------------------------------------------------------------
    // 3. The initialization-on-demand holder idiom. Simpler and faster.
    // ------------------------------------------------------------------
    static class HolderIdiom {
        private static class Holder {
            static final Service INSTANCE = new Service("holder", 3);
        }
        static Service get() { return Holder.INSTANCE; }
        /*
         * The JVM already guarantees that a class is initialised exactly once,
         * with correct publication, under a lock it manages itself. Holder is
         * not loaded until get() is first called, so this is lazy -- and after
         * the first call the field access is a plain static read with zero
         * synchronisation cost. No volatile, no lock, no double check.
         *
         * If you take one practical thing from this file: use this, not DCL.
         */
    }

    // ------------------------------------------------------------------
    // 4. Eager static final. Not lazy, but bulletproof and free.
    // ------------------------------------------------------------------
    static class Eager {
        private static final Service INSTANCE = new Service("eager", 4);
        static Service get() { return INSTANCE; }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== EXPERIMENT 5: DOUBLE-CHECKED LOCKING =============================");
        System.out.println();

        // Hammer each version from many threads at once and report what they saw.
        report("BrokenDcl    (no volatile)", BrokenDcl::get);
        report("VolatileDcl  (correct)    ", VolatileDcl::get);
        report("HolderIdiom  (correct)    ", HolderIdiom::get);
        report("Eager        (correct)    ", Eager::get);

        System.out.println();
        System.out.println("RESULT: every version reported a fully-built object.");
        System.out.println("  Three of them are correct by the rules of the model.");
        System.out.println("  One of them is correct by accident of running on x86 today.");
        System.out.println("  Nothing you can run here tells the two apart. That is the point:");
        System.out.println("  memory-model bugs are found by reading, not by testing.");
        System.out.println("======================================================================");
    }

    interface Supplier { Service get(); }

    static void report(String label, Supplier s) throws Exception {
        final int n = 16;
        final int[] broken = new int[1];
        Thread[] ts = new Thread[n];
        for (int i = 0; i < n; i++) {
            ts[i] = new Thread(() -> {
                for (int k = 0; k < 200_000; k++) {
                    Service svc = s.get();
                    if (svc == null || svc.name == null || svc.id == 0) {
                        synchronized (broken) { broken[0]++; }
                    }
                }
            });
        }
        for (Thread t : ts) t.start();
        for (Thread t : ts) t.join();
        System.out.printf("  %s -> half-built objects seen: %d%n", label, broken[0]);
    }
}
