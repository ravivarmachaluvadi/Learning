/**
 * The design under test. Every experiment in this lab is "same workload,
 * different Rule", which is the only honest way to compare consistency models.
 *
 *   ordering    what a replica waits for before it applies a write it received
 *   nearestOnly ship only the dependencies that are not implied by others
 *   convergent  when two writes to one key are concurrent, resolve them the
 *               same way everywhere (otherwise replicas can disagree forever)
 *   sticky      a client always talks to the same replica
 */
public record Rule(Ordering ordering, boolean nearestOnly, boolean convergent, boolean sticky) {

    public enum Ordering {
        /** Apply on arrival. Fastest, and the source of every anomaly here. */
        NONE,
        /** Apply in Lamport-timestamp order, holding anything out of order. */
        LAMPORT,
        /** Apply only once every write in `deps` has been applied. */
        DEPS,
        /** Apply only when one counter per replica says nothing is missing. */
        VV
    }

    public static Rule async()       { return new Rule(Ordering.NONE,    false, false, true); }
    public static Rule asyncLww()    { return new Rule(Ordering.NONE,    false, true,  true); }
    public static Rule lamport()     { return new Rule(Ordering.LAMPORT, false, true,  true); }
    public static Rule causalDeps()  { return new Rule(Ordering.DEPS,    false, true,  true); }
    public static Rule causalNear()  { return new Rule(Ordering.DEPS,    true,  true,  true); }
    public static Rule causalVv()    { return new Rule(Ordering.VV,      false, true,  true); }
    /** Causal ordering, but no agreed rule for concurrent writes: causal, NOT causal+. */
    public static Rule causalNoConverge() { return new Rule(Ordering.VV, false, false, true); }
    /** Causal ordering, but the client is allowed to hop between replicas. */
    public static Rule causalRoaming()    { return new Rule(Ordering.VV, false, true,  false); }

    public String label() {
        return switch (ordering) {
            case NONE -> convergent ? "async + last-writer-wins" : "async (apply on arrival)";
            case LAMPORT -> "Lamport-timestamp order";
            case DEPS -> nearestOnly ? "causal, nearest dependencies" : "causal, full dependencies";
            case VV -> (convergent ? (sticky ? "causal+, one counter per replica" : "causal+, roaming client")
                                   : "causal only (no convergence rule)");
        };
    }
}
