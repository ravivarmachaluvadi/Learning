import java.util.*;

/**
 * An in-memory network you can abuse. Messages take a variable number of ticks
 * to arrive, can be dropped, duplicated, or blocked by a partition.
 *
 * Everything is driven by one seeded Random, so every run of every experiment
 * is byte-for-byte reproducible.
 */
class Network {
    record Packet(int from, int to, Msg msg, long deliverAt, long seq) {}

    private final PriorityQueue<Packet> inFlight = new PriorityQueue<>(
            Comparator.comparingLong(Packet::deliverAt).thenComparingLong(Packet::seq));
    private long seq = 0;

    final Random rng;
    int latencyMin = 1, latencyMax = 6;
    double dropRate = 0.0;
    double duplicateRate = 0.0;

    /** node id -> partition side. Same side = can talk. Empty map = fully connected. */
    private final Map<Integer, Integer> side = new HashMap<>();

    long sent = 0, dropped = 0, blocked = 0, delivered = 0;

    Network(long seed) { this.rng = new Random(seed); }

    /** Split the cluster. Each argument is one side; nodes in different sides cannot talk. */
    void partition(int[]... sides) {
        side.clear();
        for (int s = 0; s < sides.length; s++) for (int id : sides[s]) side.put(id, s);
    }

    void heal() { side.clear(); }

    boolean connected(int a, int b) {
        if (side.isEmpty()) return true;
        return Objects.equals(side.get(a), side.get(b));
    }

    void send(int from, int to, Msg msg) {
        sent++;
        if (!connected(from, to)) { blocked++; return; }
        if (rng.nextDouble() < dropRate) { dropped++; return; }
        enqueue(from, to, msg);
        if (rng.nextDouble() < duplicateRate) enqueue(from, to, msg);
    }

    long currentTick = 0;

    private void enqueue(int from, int to, Msg msg) {
        long delay = latencyMin + rng.nextInt(Math.max(1, latencyMax - latencyMin + 1));
        inFlight.add(new Packet(from, to, msg, currentTick + delay, seq++));
    }

    /** Hand every packet whose time has come to the cluster. */
    void deliverDue(long now, Cluster c) {
        currentTick = now;
        while (!inFlight.isEmpty() && inFlight.peek().deliverAt() <= now) {
            Packet p = inFlight.poll();
            // A partition that appears while a packet is in flight still eats it.
            if (!connected(p.from(), p.to())) { blocked++; continue; }
            delivered++;
            c.node(p.to()).receive(p.msg(), p.from());
        }
    }

    int inFlightCount() { return inFlight.size(); }

    /** Snapshot for the visualisation / debugging. */
    List<Packet> peek() { return new ArrayList<>(inFlight); }
}
