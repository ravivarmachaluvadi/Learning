import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One replica: a machine holding a copy of some of the keys.
 *
 * It is deliberately dumb. It does not know about quorums, it does not talk to
 * other replicas, and it never rejects a write for being "old" unless it can
 * PROVE it is old from the version information carried with it. All the
 * cleverness lives in the coordinator; this thing just stores bytes.
 */
class Node {

    final String id;
    boolean alive = true;
    /** Applies writes normally, but its reply arrives after the coordinator gives up. */
    boolean slow = false;
    /** Milliseconds this node's clock is ahead of (or behind) real time. */
    long clockSkew = 0;

    /** key -> the value(s) held. More than one means unresolved concurrent writes. */
    private final Map<String, List<Val>> store = new HashMap<>();

    /** Values this node is holding ON BEHALF OF a replica that was unreachable. */
    final List<Object[]> hints = new ArrayList<>();   // {homeNodeId, key, Val}

    Node(String id) { this.id = id; }

    List<Val> read(String key) { return store.getOrDefault(key, List.of()); }

    /**
     * Merge a write in. The rule that matters:
     *
     *   - if the incoming value is provably newer than a stored one, replace it;
     *   - if it is provably older, ignore it (this is what makes re-delivering
     *     an old write harmless);
     *   - if neither -- a genuine concurrent write -- KEEP BOTH. Choosing here
     *     would be guessing, and guessing is how updates get silently lost.
     */
    void write(String key, Val incoming, boolean lastWriteWins) {
        List<Val> cur = store.getOrDefault(key, List.of());
        if (lastWriteWins) {
            // Last-write-wins: one value, highest timestamp survives. Cheap,
            // always converges, and quietly destroys one of any two concurrent
            // writes -- measured in the `concurrent` experiment.
            Val best = incoming;
            for (Val v : cur) if (v.ts() > best.ts()) best = v;
            store.put(key, List.of(best));
            return;
        }
        List<Val> next = new ArrayList<>();
        boolean dominated = false;
        for (Val v : cur) {
            switch (v.vv().compare(incoming.vv())) {
                case AFTER, EQUAL -> { next.add(v); dominated = true; }   // we already have this or better
                case BEFORE -> { }                                        // superseded: drop it
                case CONCURRENT -> next.add(v);                           // a real conflict: keep both
            }
        }
        if (!dominated) next.add(incoming);
        store.put(key, List.copyOf(next));
    }

    void wipe() { store.clear(); hints.clear(); }        // simulates a rebuilt/empty node
    int keyCount() { return store.size(); }
}
