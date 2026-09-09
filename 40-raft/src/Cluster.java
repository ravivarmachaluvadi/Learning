import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * The simulated cluster: a logical clock, N servers, one abusable network, and
 * -- the most useful part -- a safety checker that runs after EVERY tick.
 *
 * The checker is what turns "I think Raft is correct" into "these 4 properties
 * held for 30,000 consecutive ticks, and here is the exact tick where turning
 * off one rule broke one of them".
 */
class Cluster {
    final RaftNode[] nodes;
    final Network net;
    final Random rng;
    final Bugs bugs;
    long now = 0;

    final List<String> events = new ArrayList<>();
    final List<String> violations = new ArrayList<>();
    /** Worst number of servers that were leader in the SAME term at the same instant. Must always be 1. */
    int worstLeadersInOneTerm = 0;
    boolean verbose = false;

    /** index -> the entry that some server has already committed there. Must never change. */
    private final Map<Integer, Entry> committedEver = new HashMap<>();

    Cluster(int n, long seed, Bugs bugs) {
        this.bugs = bugs;
        this.rng = new Random(seed);
        this.net = new Network(seed + 977);
        int[] ids = new int[n];
        for (int i = 0; i < n; i++) ids[i] = i;
        this.nodes = new RaftNode[n];
        for (int i = 0; i < n; i++) this.nodes[i] = new RaftNode(i, ids, this, bugs);
    }

    Cluster(int n, long seed) { this(n, seed, new Bugs()); }

    RaftNode node(int id) { return nodes[id]; }
    void send(int from, int to, Msg m) { net.send(from, to, m); }

    void event(int id, String s) {
        String line = String.format("t=%-6d S%d %s", now, id, s);
        events.add(line);
        if (verbose) System.out.println("   " + line);
    }

    void note(String s) {
        String line = String.format("t=%-6d ** %s", now, s);
        events.add(line);
        if (verbose) System.out.println("   " + line);
    }

    // ================= driving time =================
    void tick() {
        now++;
        net.deliverDue(now, this);
        for (RaftNode n : nodes) n.tick(now);
        checkSafety();
    }

    void run(long ticks) { for (long i = 0; i < ticks; i++) tick(); }

    /** Run until cond is true. Returns ticks taken, or -1 if it never happened. */
    long runUntil(BooleanSupplier cond, long maxTicks) {
        long start = now;
        for (long i = 0; i < maxTicks; i++) {
            if (cond.getAsBoolean()) return now - start;
            tick();
        }
        return cond.getAsBoolean() ? now - start : -1;
    }

    // ================= faults =================
    void crash(int id) { nodes[id].alive = false; note("S" + id + " CRASHES (stops sending, stops receiving)"); }

    /**
     * Restart a crashed server. It keeps currentTerm, votedFor and its log -- the
     * three things real Raft writes to disk -- and loses everything volatile.
     */
    void restart(int id) {
        RaftNode n = nodes[id];
        n.alive = true;
        n.role = RaftNode.Role.FOLLOWER;
        n.commitIndex = 0;
        n.lastApplied = 0;
        n.applied.clear();
        n.votesReceived.clear();
        n.leaderId = -1;
        n.resetElectionTimer(now);
        note("S" + id + " RESTARTS (kept term=" + n.currentTerm + ", votedFor=" + n.votedFor
                + ", log=" + n.logString() + "; lost commitIndex and its state machine)");
    }

    void partition(int[]... sides) {
        net.partition(sides);
        StringBuilder sb = new StringBuilder("NETWORK PARTITION: ");
        for (int i = 0; i < sides.length; i++) sb.append(i > 0 ? " | " : "").append(Arrays.toString(sides[i]));
        note(sb.toString());
    }

    void heal() { net.heal(); note("NETWORK HEALS: everyone can talk to everyone again"); }

    /**
     * TEST SCAFFOLDING. Decide WHICH server times out next, by freezing every
     * other election timer. This steers the clock only -- votes are still granted
     * or refused by the real rules in RaftNode. Some scenarios (Figure 8) take
     * millions of random runs to appear by luck; this makes them reproducible.
     */
    void letTimeout(int id, long inTicks) {
        for (RaftNode n : nodes) n.electionDeadline = now + 1_000_000;
        nodes[id].electionDeadline = now + inTicks;
    }

    // ================= queries =================
    RaftNode leader() {
        RaftNode best = null;
        for (RaftNode n : nodes)
            if (n.alive && n.role == RaftNode.Role.LEADER && (best == null || n.currentTerm > best.currentTerm)) best = n;
        return best;
    }

    /** All alive leaders, whatever their term. More than one is normal (an old leader that has not noticed yet). */
    List<RaftNode> allLeaders() {
        List<RaftNode> out = new ArrayList<>();
        for (RaftNode n : nodes) if (n.alive && n.role == RaftNode.Role.LEADER) out.add(n);
        return out;
    }

    /** The leader inside one side of a partition. */
    RaftNode leaderAmong(int... ids) {
        for (int id : ids) if (nodes[id].alive && nodes[id].role == RaftNode.Role.LEADER) return nodes[id];
        return null;
    }

    int submit(String cmd) {
        RaftNode l = leader();
        return l == null ? -1 : l.clientRequest(cmd);
    }

    int maxTerm() { int t = 0; for (RaftNode n : nodes) t = Math.max(t, n.currentTerm); return t; }

    /** How many servers have this exact command committed and applied? */
    int committedCount(String cmd) {
        int c = 0;
        for (RaftNode n : nodes) if (n.applied.contains(cmd)) c++;
        return c;
    }

    // ================= the safety checker =================
    private final Set<String> seenViolations = new HashSet<>();

    /** Record each DISTINCT violation once, stamped with the tick it first appeared. */
    private void violate(String s) {
        if (!seenViolations.add(s)) return;
        String line = "t=" + now + "  !! " + s;
        violations.add(line);
        events.add(line);
        if (verbose) System.out.println("   " + line);
    }

    void checkSafety() {
        // 1. ELECTION SAFETY -- at most one leader per term.
        Map<Integer, List<Integer>> byTerm = new HashMap<>();
        for (RaftNode n : nodes)
            if (n.alive && n.role == RaftNode.Role.LEADER)
                byTerm.computeIfAbsent(n.currentTerm, k -> new ArrayList<>()).add(n.id);
        for (var e : byTerm.entrySet()) {
            worstLeadersInOneTerm = Math.max(worstLeadersInOneTerm, e.getValue().size());
            if (e.getValue().size() > 1)
                violate("ELECTION SAFETY broken: term " + e.getKey() + " has " + e.getValue().size()
                        + " leaders at once " + e.getValue());
        }

        // 2. STATE MACHINE SAFETY -- an index, once committed anywhere, holds that
        //    entry forever, on every server.
        for (RaftNode n : nodes) {
            int upto = Math.min(n.commitIndex, n.lastLogIndex());
            for (int i = 1; i <= upto; i++) {
                Entry cur = n.log.get(i);
                Entry seen = committedEver.get(i);
                if (seen == null) committedEver.put(i, cur);
                else if (!seen.equals(cur))
                    violate("STATE MACHINE SAFETY broken: index " + i + " was committed as " + seen
                            + " but S" + n.id + " has committed " + cur + " there");
            }
        }
        // 2b. a committed entry must not be silently erased even before recommit
        for (RaftNode n : nodes)
            for (var e : committedEver.entrySet())
                if (e.getKey() <= n.commitIndex && e.getKey() <= n.lastLogIndex()
                        && !n.log.get(e.getKey()).equals(e.getValue()))
                    violate("DURABILITY broken: S" + n.id + " committed index " + e.getKey()
                            + " but now holds " + n.log.get(e.getKey()) + " instead of " + e.getValue());

        // 3. LOG MATCHING -- if two logs agree on (index, term), the whole prefix agrees.
        for (int a = 0; a < nodes.length; a++)
            for (int b = a + 1; b < nodes.length; b++) {
                List<Entry> la = nodes[a].log, lb = nodes[b].log;
                int min = Math.min(la.size(), lb.size()) - 1;
                for (int i = min; i >= 1; i--) {
                    if (la.get(i).term() == lb.get(i).term()) {
                        for (int j = 1; j <= i; j++)
                            if (!la.get(j).equals(lb.get(j))) {
                                violate("LOG MATCHING broken: S" + a + " and S" + b + " both have term "
                                        + la.get(i).term() + " at index " + i + ", but differ at index " + j
                                        + " (" + la.get(j) + " vs " + lb.get(j) + ")");
                                break;
                            }
                        break;
                    }
                }
            }

        // 4. APPLIED PREFIXES -- no two state machines may diverge.
        for (int a = 0; a < nodes.length; a++)
            for (int b = a + 1; b < nodes.length; b++) {
                List<String> xa = nodes[a].applied, xb = nodes[b].applied;
                int min = Math.min(xa.size(), xb.size());
                for (int i = 0; i < min; i++)
                    if (!xa.get(i).equals(xb.get(i))) {
                        violate("STATE MACHINES DIVERGED: at position " + i + " S" + a + " applied \""
                                + xa.get(i) + "\" but S" + b + " applied \"" + xb.get(i) + "\"");
                        break;
                    }
            }
    }

    // ================= reporting =================
    void report(String title) {
        System.out.println("  --- " + title + " (t=" + now + ") ---");
        for (RaftNode n : nodes)
            System.out.printf("    S%d %-9s term=%-3d commit=%-3d log=%-42s applied=%s%s%n",
                    n.id, n.alive ? n.role : "DEAD", n.currentTerm, n.commitIndex,
                    n.logString(), n.applied, n.alive ? "" : "  (crashed)");
    }

    void printEventsAfter(long tick) {
        for (String e : events) {
            int eq = e.indexOf('=');
            if (eq < 0) continue;
            String num = e.substring(eq + 1).trim().split("\\s+")[0];
            try { if (Long.parseLong(num) >= tick) System.out.println("   " + e); } catch (NumberFormatException ignored) {}
        }
    }
}
