import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

/**
 * A fake network you can abuse on purpose.
 *
 * It is a single-threaded discrete-event simulator: instead of real time and
 * real threads, there is a queue of "do this at millisecond T" jobs. That
 * makes every run deterministic for a given seed, which is the whole point --
 * you can drop 30% of packets, re-run, and get the same story twice.
 */
class Net {

    private record Ev(long at, long tie, Runnable run) {}

    private final PriorityQueue<Ev> queue = new PriorityQueue<>(
            (a, b) -> a.at != b.at ? Long.compare(a.at, b.at) : Long.compare(a.tie, b.tie));
    private long tie = 0;
    long now = 0;

    private final Random rng;

    /** Probability a packet is silently lost. Real mobile networks: not zero. */
    double dropRate = 0.0;
    /** Probability a packet is delivered twice. TCP prevents this; retries do not. */
    double dupRate = 0.0;
    long baseLatency = 20;
    long jitter = 30;

    /**
     * A real socket is a BYTE STREAM: whatever you write first arrives first.
     * With this on (the default) packets on the same link cannot overtake each
     * other, which is where per-conversation ordering actually comes from.
     * Turn it off to see what a design that sprays messages over independent
     * connections looks like -- `java src/Experiments.java ordering`.
     */
    boolean orderedLinks = true;
    private final java.util.Map<String, Long> lastArrival = new java.util.HashMap<>();

    /** Nodes on opposite sides of a partition cannot talk. Empty = healthy network. */
    private final Set<String> sideA = new HashSet<>();
    private final Set<String> sideB = new HashSet<>();

    /** Nodes that are simply dead: they receive nothing. */
    private final Set<String> down = new HashSet<>();

    long sent = 0, dropped = 0, duplicated = 0, deliveredPackets = 0, partitionDropped = 0;

    final List<String> log = new ArrayList<>();
    boolean verbose = false;

    Net(long seed) { this.rng = new Random(seed); }

    void note(String s) {
        log.add("[" + String.format("%5d", now) + "ms] " + s);
        if (verbose) System.out.println(log.get(log.size() - 1));
    }

    // ---- fault injection -------------------------------------------------

    void partition(Set<String> a, Set<String> b) { sideA.clear(); sideB.clear(); sideA.addAll(a); sideB.addAll(b); }
    void healPartition() { sideA.clear(); sideB.clear(); }
    void kill(String node) { down.add(node); }
    void revive(String node) { down.remove(node); }
    boolean isDown(String node) { return down.contains(node); }

    private boolean cut(String from, String to) {
        if (sideA.isEmpty() || sideB.isEmpty()) return false;
        return (sideA.contains(from) && sideB.contains(to)) || (sideB.contains(from) && sideA.contains(to));
    }

    // ---- scheduling ------------------------------------------------------

    /** Run something later on the same node (a timer). Never dropped. */
    void after(long delayMs, Runnable r) { queue.add(new Ev(now + delayMs, tie++, r)); }

    /**
     * Send a packet from `from` to `to`. This is the ONLY place faults happen,
     * which is why every failure experiment in this lab is a one-line change.
     */
    void send(String from, String to, Runnable deliver) {
        sent++;
        if (cut(from, to)) { partitionDropped++; note("PARTITIONED  " + from + " -x-> " + to); return; }
        if (rng.nextDouble() < dropRate) { dropped++; note("DROPPED      " + from + " -x-> " + to); return; }
        long latency = baseLatency + (long) (rng.nextDouble() * jitter);
        long arrival = now + latency;
        if (orderedLinks) {                        // FIFO per link: no overtaking on one socket
            String link = from + ">" + to;
            arrival = Math.max(arrival, lastArrival.getOrDefault(link, 0L) + 1);
            lastArrival.put(link, arrival);
        }
        long at = arrival;
        queue.add(new Ev(at, tie++, () -> {
            if (down.contains(to)) { dropped++; note("LOST (node down) -> " + to); return; }
            deliveredPackets++;
            deliver.run();
        }));
        if (rng.nextDouble() < dupRate) {
            duplicated++;
            queue.add(new Ev(at + 5, tie++, () -> {
                if (down.contains(to)) return;
                deliveredPackets++;
                note("DUPLICATE of a packet to " + to);
                deliver.run();
            }));
        }
    }

    // ---- the clock -------------------------------------------------------

    void runUntilIdle() { while (!queue.isEmpty()) step(); }

    void runFor(long ms) {
        long deadline = now + ms;
        while (!queue.isEmpty() && queue.peek().at <= deadline) step();
        now = deadline;
    }

    private void step() {
        Ev e = queue.poll();
        now = Math.max(now, e.at);
        e.run.run();
    }
}
