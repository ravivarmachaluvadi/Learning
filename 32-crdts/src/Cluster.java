import java.util.*;
import java.util.function.BiFunction;

/**
 * A hostile little network, in one process.
 *
 * Replicas hold their own copy. "Syncing" means one replica mails a snapshot of
 * its whole state to another, and the receiver merges it in. The queue can
 * drop messages, duplicate them, deliver them wildly out of order, and be cut
 * in half — every abuse a real network commits. The whole claim of this lab is
 * that none of it matters, and this class is how we test that claim rather
 * than assert it.
 */
public final class Cluster<T extends Replicated<T>> {

    public record Msg<T>(int id, String from, String to, T payload) {}

    private final Map<String, T> replicas = new LinkedHashMap<>();
    private final List<Msg<T>> queue = new ArrayList<>();
    private final Random rnd;
    private int nextMsgId = 1;

    // network knobs
    public double dropRate = 0.0;
    public double duplicateRate = 0.0;
    public boolean shuffle = false;
    /** replicas in different groups cannot reach each other (a partition) */
    private Map<String, Integer> partitionGroup = new HashMap<>();

    // counters, for the experiments
    public int sent, delivered, dropped, duplicated, blockedByPartition;

    public Cluster(Random rnd) { this.rnd = rnd; }

    public void addReplica(String name, T initial) {
        replicas.put(name, initial);
        partitionGroup.put(name, 0);
    }

    public Set<String> names() { return replicas.keySet(); }
    public T state(String name) { return replicas.get(name); }

    /** Local update: the replica changes its own copy, immediately, with no coordination. */
    public void update(String name, java.util.function.UnaryOperator<T> op) {
        replicas.put(name, op.apply(replicas.get(name)));
    }

    public void partition(String name, int group) { partitionGroup.put(name, group); }
    public void healPartition() { partitionGroup.replaceAll((k, v) -> 0); }
    public boolean canReach(String a, String b) {
        return partitionGroup.get(a).equals(partitionGroup.get(b));
    }

    /** Mail a snapshot of `from`'s current state to `to`. */
    public void sync(String from, String to) {
        if (from.equals(to)) return;
        if (!canReach(from, to)) { blockedByPartition++; return; }
        queue.add(new Msg<>(nextMsgId++, from, to, replicas.get(from)));
        sent++;
        if (rnd.nextDouble() < duplicateRate) {
            queue.add(new Msg<>(nextMsgId++, from, to, replicas.get(from)));
            duplicated++;
        }
    }

    /** Everyone mails everyone — the crude version of the anti-entropy round from the gossip lab. */
    public void gossipRound() {
        List<String> all = new ArrayList<>(replicas.keySet());
        for (String a : all) for (String b : all) sync(a, b);
    }

    /** Deliver the whole queue, dropping / reordering as configured. */
    public void deliverAll() {
        List<Msg<T>> batch = new ArrayList<>(queue);
        queue.clear();
        if (shuffle) Collections.shuffle(batch, rnd);
        for (Msg<T> m : batch) {
            if (rnd.nextDouble() < dropRate) { dropped++; continue; }
            if (!canReach(m.from(), m.to())) { blockedByPartition++; continue; }
            replicas.put(m.to(), replicas.get(m.to()).merge(m.payload()));
            delivered++;
        }
    }

    /** Keep gossiping until nothing changes, or we give up. Returns rounds taken. */
    public int settle(int maxRounds) {
        double savedDrop = dropRate;
        dropRate = 0.0;                       // healing means the network works again
        for (int r = 1; r <= maxRounds; r++) {
            gossipRound();
            deliverAll();
            if (converged()) { dropRate = savedDrop; return r; }
        }
        dropRate = savedDrop;
        return -1;
    }

    public boolean converged() {
        Iterator<T> it = replicas.values().iterator();
        T first = it.next();
        while (it.hasNext()) if (!first.equals(it.next())) return false;
        return true;
    }

    public <R> Set<R> distinctViews(java.util.function.Function<T, R> read) {
        Set<R> views = new LinkedHashSet<>();
        replicas.values().forEach(t -> views.add(read.apply(t)));
        return views;
    }

    public int queueSize() { return queue.size(); }

    /** Apply every pending message in a caller-chosen order — used to prove commutativity. */
    public void deliverInOrder(List<Integer> order) {
        Map<Integer, Msg<T>> byId = new LinkedHashMap<>();
        queue.forEach(m -> byId.put(m.id(), m));
        queue.clear();
        for (int id : order) {
            Msg<T> m = byId.get(id);
            if (m == null) continue;
            replicas.put(m.to(), replicas.get(m.to()).merge(m.payload()));
            delivered++;
        }
    }

    public List<Integer> pendingIds() { return queue.stream().map(Msg::id).toList(); }

    public String describe(BiFunction<String, T, String> render) {
        StringBuilder sb = new StringBuilder();
        replicas.forEach((n, t) -> sb.append("    ").append(render.apply(n, t)).append('\n'));
        return sb.toString();
    }
}
