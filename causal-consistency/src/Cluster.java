import java.util.*;

/**
 * Three replicas of one key-value store, a network you can abuse, and clients
 * that read and write. Everything the lab measures happens in here.
 *
 * The one thing to understand before reading: a replica applies its OWN
 * clients' writes instantly (that is what makes the system available), and
 * receives everyone else's writes as messages. The whole question of this lab
 * is what a replica should wait for before showing a received write to anyone.
 */
public final class Cluster {

    // ------------------------------------------------------------- structure

    public static final class Replica {
        final int id;
        final Map<String, Write> store = new TreeMap<>();   // key -> the write currently visible
        final Set<Dot> applied = new TreeSet<>();
        final List<Write> pending = new ArrayList<>();      // received, not yet safe to show
        final long[] vv;                                    // one counter per replica
        final long[] lastLamportFrom;                       // for Lamport-order delivery
        long lamport = 0, seq = 0;
        Replica(int id, int n) { this.id = id; this.vv = new long[n]; this.lastLamportFrom = new long[n]; }
    }

    public static final class Client {
        public final String name;
        public int replica;
        final Set<Dot> context = new TreeSet<>();   // everything this client has seen or done
        final Set<Dot> readSet = new TreeSet<>();   // writes actually RETURNED to this client
        final List<Dot> myWrites = new ArrayList<>();
        Client(String name, int replica) { this.name = name; this.replica = replica; }
    }

    record Msg(int id, int from, int to, Write write, long arriveAt) {}

    public record Violation(long tick, String client, String kind, String detail) {}

    // ------------------------------------------------------------------ state

    public final Rule rule;
    private final Random rnd;
    public final Replica[] reps;
    public final Map<String, Client> clients = new LinkedHashMap<>();
    private final Map<Dot, Write> allWrites = new TreeMap<>();
    private final List<Msg> inFlight = new ArrayList<>();
    private final int[] group;
    private int msgId = 1;
    public long tick = 0;

    // network knobs
    public long minDelay = 1, maxDelay = 3;
    public double dropRate = 0.0;
    /**
     * When true, each link delivers in send order. Real TCP connections behave
     * this way; UDP, multi-path routing and per-key sharding do not. Whether
     * you have it changes which designs are actually correct — experiment 1.
     */
    public boolean fifoChannels = false;
    /** extra delay on the link from replica i to replica j (a slow route) */
    public long[][] linkDelay;
    /** set before a put() to give that one write's messages an exact delay */
    public long overrideDelay = -1;

    // results
    public final List<Violation> violations = new ArrayList<>();
    public int causalAnomalies, readYourWrites, monotonicReads, monotonicWrites, writesFollowReads;
    public long metadataCellsShipped, writesShipped, heldWrites, totalHoldTicks;

    public Cluster(int n, Rule rule, Random rnd) {
        this.rule = rule; this.rnd = rnd;
        this.reps = new Replica[n];
        for (int i = 0; i < n; i++) reps[i] = new Replica(i, n);
        this.group = new int[n];
        this.linkDelay = new long[n][n];
    }

    public Client client(String name, int replica) {
        Client c = new Client(name, replica);
        clients.put(name, c);
        return c;
    }

    public void partition(int replica, int g) { group[replica] = g; }
    public void heal() { Arrays.fill(group, 0); }
    private boolean canReach(int a, int b) { return group[a] == group[b]; }

    // ------------------------------------------------------------------ writes

    public Write put(String clientName, String key, String value) {
        Client c = clients.get(clientName);
        if (!rule.sticky()) c.replica = rnd.nextInt(reps.length);
        Replica r = reps[c.replica];

        r.lamport++;
        Dot dot = new Dot(r.id, ++r.seq);
        Set<Dot> deps = new TreeSet<>(c.context);          // the client's whole causal past
        Set<Dot> nearest = nearest(deps);
        r.vv[r.id] = r.seq;
        Write w = new Write(dot, key, value, deps, nearest, r.lamport, r.vv.clone());

        allWrites.put(dot, w);
        applyLocally(r, w);
        c.context.add(dot);
        c.myWrites.add(dot);

        for (int j = 0; j < reps.length; j++) {
            if (j == r.id) continue;
            if (!canReach(r.id, j)) continue;
            if (rnd.nextDouble() < dropRate) continue;
            long delay = overrideDelay >= 0 ? overrideDelay
                       : minDelay + (maxDelay > minDelay ? rnd.nextInt((int) (maxDelay - minDelay + 1)) : 0)
                         + linkDelay[r.id][j];
            inFlight.add(new Msg(msgId++, r.id, j, w, tick + delay));
            writesShipped++;
            metadataCellsShipped += w.metadataCells(rule);
        }
        overrideDelay = -1;
        return w;
    }

    /** The author's own replica never waits — this is what "available" means. */
    private void applyLocally(Replica r, Write w) {
        r.store.put(w.key(), w);
        r.applied.add(w.id());
        r.vv[w.id().replica()] = Math.max(r.vv[w.id().replica()], w.id().seq());
        r.lamport = Math.max(r.lamport, w.lamport());
    }

    // ------------------------------------------------------------------- reads

    public String get(String clientName, String key) {
        Client c = clients.get(clientName);
        if (!rule.sticky()) c.replica = rnd.nextInt(reps.length);
        Replica r = reps[c.replica];
        Write w = r.store.get(key);

        // --- session guarantee checks, evaluated against what this replica holds ---
        for (Dot mine : c.myWrites)
            if (!r.applied.contains(mine)) {
                readYourWrites++;
                record(c, "READ-YOUR-WRITES",
                    "read " + key + " at R" + r.id + " which has not applied this client's own write " + mine);
                break;
            }
        for (Dot seen : c.readSet)
            if (!r.applied.contains(seen)) {
                monotonicReads++;
                record(c, "MONOTONIC-READS",
                    "read " + key + " at R" + r.id + " which has gone backwards: " + seen + " was already visible to this client");
                break;
            }

        if (w != null) {
            // --- THE headline check: can this client see an effect whose cause is missing? ---
            for (Dot d : w.deps()) {
                if (r.applied.contains(d)) continue;
                Write cause = allWrites.get(d);
                Write shown = r.store.get(cause.key());
                boolean causeVisible = shown != null
                        && (shown.id().equals(d) || shown.deps().contains(d));
                if (!causeVisible) {
                    causalAnomalies++;
                    record(c, "CAUSALITY",
                        "sees " + w.shortLabel() + " (" + w.id() + ") but " + cause.key() + " still reads "
                        + (shown == null ? "<absent>" : shown.value()) + " — the cause " + cause.shortLabel()
                        + " (" + d + ") is invisible here");
                    break;
                }
            }
            c.readSet.add(w.id());
            c.context.add(w.id());
            c.context.addAll(w.deps());
        }
        return w == null ? null : w.value();
    }

    private void record(Client c, String kind, String detail) {
        violations.add(new Violation(tick, c.name, kind, detail));
    }

    // ----------------------------------------------------------------- network

    /** Advance one tick: deliver everything due, then retry anything held back. */
    public void step() {
        tick++;
        List<Msg> due = new ArrayList<>();
        if (fifoChannels) {
            // per link, deliver in send order and stop at the first one not yet due
            inFlight.sort(Comparator.comparingInt(Msg::from).thenComparingInt(Msg::to).thenComparingInt(Msg::id));
            Set<Long> blocked = new HashSet<>();
            for (Iterator<Msg> it = inFlight.iterator(); it.hasNext(); ) {
                Msg m = it.next();
                long link = m.from() * 1000L + m.to();
                if (blocked.contains(link) || m.arriveAt() > tick) { blocked.add(link); continue; }
                due.add(m); it.remove();
            }
        } else {
            for (Iterator<Msg> it = inFlight.iterator(); it.hasNext(); ) {
                Msg m = it.next();
                if (m.arriveAt() <= tick) { due.add(m); it.remove(); }
            }
            Collections.shuffle(due, rnd);      // same tick = no ordering promise
        }
        for (Msg m : due) receive(reps[m.to()], m.write());
        for (Replica r : reps) drainPending(r);
        totalHoldTicks += pendingCount();       // one "write-tick" of invisibility each
    }

    public void run(int ticks) { for (int i = 0; i < ticks; i++) step(); }

    /**
     * Push anything a peer is missing. Causal delivery says *when* it is safe to
     * show a write; it does not resend one that was lost or blocked by a
     * partition. That is anti-entropy's job — the same background loop the
     * gossip-protocols lab built. Without it, a healed partition heals nothing.
     */
    public void antiEntropy() {
        for (int i = 0; i < reps.length; i++) {
            for (int j = 0; j < reps.length; j++) {
                if (i == j || !canReach(i, j)) continue;
                final int dest = j;
                for (Write w : allWrites.values()) {
                    if (!reps[i].applied.contains(w.id())) continue;
                    if (reps[dest].applied.contains(w.id())) continue;
                    if (reps[dest].pending.stream().anyMatch(p -> p.id().equals(w.id()))) continue;
                    if (inFlight.stream().anyMatch(m -> m.to() == dest && m.write().id().equals(w.id()))) continue;
                    inFlight.add(new Msg(msgId++, i, dest, w, tick + minDelay));
                }
            }
        }
    }

    /** Deliver everything, ignoring delays — used to settle at the end of a run. */
    public int settle(int maxTicks) {
        for (int i = 0; i < maxTicks; i++) {
            step();
            if (inFlight.isEmpty() && Arrays.stream(reps).allMatch(r -> r.pending.isEmpty())) return i + 1;
        }
        return -1;
    }

    private void receive(Replica r, Write w) {
        r.lastLamportFrom[w.id().replica()] = Math.max(r.lastLamportFrom[w.id().replica()], w.lamport());
        if (r.applied.contains(w.id())) return;                     // duplicate
        if (!deliverable(r, w)) heldWrites++;                       // it had to wait
        r.pending.add(w);
        drainPending(r);
    }

    /** Apply every held write that is now safe, repeatedly until nothing more is. */
    private void drainPending(Replica r) {
        boolean progress = true;
        while (progress) {
            progress = false;
            r.pending.sort(Comparator.comparingLong(Write::lamport).thenComparing(Write::id));
            for (Iterator<Write> it = r.pending.iterator(); it.hasNext(); ) {
                Write w = it.next();
                if (!deliverable(r, w)) continue;
                it.remove();
                applyReceived(r, w);
                progress = true;
                break;
            }
        }
    }

    /**
     * One of three plausible, wrong versions of the rule — for exercise 6.
     * Set with `cluster.bug = "..."`. Each is a mistake that ships regularly.
     */
    public String bug = null;

    /** Exercise 5 plugs its own delivery rule in here. */
    public java.util.function.BiPredicate<Set<Dot>, Write> customDeliverable = null;

    /** THE rule under test. Everything else in this file is scaffolding. */
    private boolean deliverable(Replica r, Write w) {
        if (customDeliverable != null) return customDeliverable.test(r.applied, w);
        if (bug != null) {
            switch (bug) {
                // BUG A — "we use TCP, so messages arrive in order, so we're causal."
                // True per sender. Says nothing about a chain that crossed replicas.
                case "same-replica-only" -> {
                    for (Dot d : w.deps())
                        if (d.replica() == w.id().replica() && !r.applied.contains(d)) return false;
                    return true;
                }
                // BUG B — per-KEY causality mistaken for causal consistency. This is
                // exactly what a version vector per object gives you, and it is not
                // this property: causality is a statement ACROSS keys.
                case "same-key-only" -> {
                    for (Dot d : w.deps()) {
                        Write cause = allWrites.get(d);
                        if (cause != null && cause.key().equals(w.key()) && !r.applied.contains(d)) return false;
                    }
                    return true;
                }
                // BUG C — the vector-clock check without the "exactly the next one"
                // clause, so a gap in the author's own sequence goes unnoticed.
                case "vv-no-gap-check" -> {
                    int src = w.id().replica();
                    for (int j = 0; j < r.vv.length; j++)
                        if (j != src && w.vv()[j] > r.vv[j]) return false;
                    return true;
                }
                default -> throw new IllegalArgumentException("unknown bug: " + bug);
            }
        }
        return switch (rule.ordering()) {
            case NONE -> true;                                       // show it the instant it lands
            case LAMPORT ->
                // safe only when every replica has been heard from at a timestamp
                // at least this high — otherwise something older may still arrive
                allHeardAtLeast(r, w.lamport());
            case DEPS -> {
                Set<Dot> need = rule.nearestOnly() ? w.nearest() : w.deps();
                for (Dot d : need) if (!r.applied.contains(d)) yield false;
                yield true;
            }
            case VV -> {
                int src = w.id().replica();
                // exactly the next write from its author...
                if (w.vv()[src] != r.vv[src] + 1) yield false;
                // ...and nothing it had seen from anyone else is still missing
                for (int j = 0; j < r.vv.length; j++)
                    if (j != src && w.vv()[j] > r.vv[j]) yield false;
                yield true;
            }
        };
    }

    private boolean allHeardAtLeast(Replica r, long stamp) {
        for (int j = 0; j < reps.length; j++) {
            if (j == r.id) continue;
            if (r.lastLamportFrom[j] < stamp) return false;
        }
        return true;
    }

    private void applyReceived(Replica r, Write w) {
        // --- guarantee checks at apply time ---
        for (Dot d : w.deps())
            if (!r.applied.contains(d)) { writesFollowReads++; break; }
        Dot prevBySameAuthor = new Dot(w.id().replica(), w.id().seq() - 1);
        if (w.id().seq() > 1 && !r.applied.contains(prevBySameAuthor)) monotonicWrites++;

        Write current = r.store.get(w.key());
        if (current == null || wins(w, current)) r.store.put(w.key(), w);
        r.applied.add(w.id());
        r.vv[w.id().replica()] = Math.max(r.vv[w.id().replica()], w.id().seq());
        r.lamport = Math.max(r.lamport, w.lamport());
    }

    /**
     * Which of two writes to the same key should be visible.
     * With convergence off, whatever arrived last wins — and since arrival order
     * differs per replica, the replicas can disagree forever. That is the gap
     * between "causal" and "causal+".
     */
    private boolean wins(Write incoming, Write current) {
        if (current.deps().contains(incoming.id())) return false;   // incoming is in the past
        if (incoming.deps().contains(current.id())) return true;    // incoming is strictly later
        if (!rule.convergent()) return true;                        // concurrent: arrival order
        long a = incoming.lamport(), b = current.lamport();          // concurrent: agreed tie-break
        return a != b ? a > b : incoming.id().replica() > current.id().replica();
    }

    // -------------------------------------------------------------- inspection

    /** The set of dots a dep is redundant against — COPS's "nearest" optimisation. */
    private Set<Dot> nearest(Set<Dot> deps) {
        Set<Dot> out = new TreeSet<>();
        for (Dot d : deps) {
            boolean covered = false;
            for (Dot other : deps) {
                if (other.equals(d)) continue;
                Write ow = allWrites.get(other);
                if (ow != null && ow.deps().contains(d)) { covered = true; break; }
            }
            if (!covered) out.add(d);
        }
        return out;
    }

    public Map<String, String> view(int replica) {
        Map<String, String> m = new TreeMap<>();
        reps[replica].store.forEach((k, w) -> m.put(k, w.value()));
        return m;
    }

    public boolean converged() {
        Map<String, String> first = view(0);
        for (int i = 1; i < reps.length; i++) if (!view(i).equals(first)) return false;
        return true;
    }

    public int pendingCount() {
        int n = 0;
        for (Replica r : reps) n += r.pending.size();
        return n;
    }

    public int inFlightCount() { return inFlight.size(); }

    public Write writeOf(Dot d) { return allWrites.get(d); }

    public Set<Dot> appliedAt(int replica) { return new TreeSet<>(reps[replica].applied); }

    public Collection<Write> allWrites() { return allWrites.values(); }

    public String describeReplicas() {
        StringBuilder sb = new StringBuilder();
        for (Replica r : reps)
            sb.append(String.format("    R%d %-34s applied=%d held=%d%n",
                    r.id, view(r.id).toString(), r.applied.size(), r.pending.size()));
        return sb.toString();
    }
}
