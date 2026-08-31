import java.util.*;

/**
 * Produces real histories from real replication designs, so the checker has
 * something honest to judge. Time is simulated (integers), messages have random
 * delays, replicas keep (timestamp, value) and only accept a strictly newer
 * timestamp — the same rule the quorums-and-replication lab used.
 *
 * Nothing here knows what "linearizable" means. These are just stores. The
 * checker looks at the histories afterwards and decides.
 */
final class Sim {

    enum Mode {
        /** One machine, one copy. The thing every other design is pretending to be. */
        SINGLE,
        /** Write to the leader, ack immediately, replicate in the background; read any replica. */
        ASYNC,
        /** Read from the leader only. One copy again, but now with spare copies behind it. */
        LEADER,
        /** R + W > N. Writer picks its timestamp by asking a quorum first. Reader does NOT write back. */
        QUORUM_NAIVE,
        /** R + W > N, plus the reader writes what it read back to a quorum before returning. (ABD) */
        QUORUM_ABD,
        /** ABD, except the writer stamps writes with its own clock instead of asking. */
        QUORUM_CLOCK,
        /** ABD with a small client-side cache in front of reads. */
        CACHED
    }

    final int n, r, w;
    final Mode mode;
    final Random rng;
    int maxDelay = 8;
    int repDelay = 30;      // background replication lag in ASYNC
    int cacheTtl = 120;     // CACHED
    int writers = Integer.MAX_VALUE;  // clients below this index write; the rest only read
    /**
     * Planted bug. Two writers that query at the same moment get the SAME answer and
     * therefore pick the SAME next timestamp. Ordering by (timestamp, writer) makes one
     * of them unambiguously later on every replica. Turn the tiebreak off and different
     * replicas keep different values under the same timestamp — the register splits in two.
     */
    boolean noTiebreak = false;
    /**
     * Optimisation, not a bug: skip the reader's write-back when every replica in
     * the read quorum already agreed. If R replicas hold the value and every read
     * asks R replicas, then 2R > N means any later read must touch one of them —
     * so the write-back has nothing to add. (The round trip is still charged here,
     * to keep this a question about correctness rather than latency.)
     */
    boolean lazyWriteBack = false;
    int clockSkew = 0;      // QUORUM_CLOCK: how far one writer's clock is ahead
    double jitterProb = 0.0;// chance a single message is caught behind a hiccup
    int jitterDelay = 40;   // how long that hiccup lasts

    Sim(Mode mode, int n, int r, int w, long seed) {
        this.mode = mode; this.n = n; this.r = r; this.w = w; this.rng = new Random(seed);
    }

    // ---- replica state -------------------------------------------------
    private long[] ts; private int[] val; private int[] wid;

    // ---- one round trip to some replicas -------------------------------
    private final class Phase {
        final boolean write;
        final long[] applyT, arriveT;
        final long[] gotTs; final int[] gotVal; final int[] gotWid;
        final boolean[] counted;
        long decideT;
        long wTs; int wVal, wWid;     // for a write phase, decided just before it is sent
        Phase(boolean write, long start, int quorum, int[] targets) { this(write, start, quorum, targets, null); }
        Phase(boolean write, long start, int quorum, int[] targets, long[] offsets) {
            this.write = write;
            applyT = new long[n]; arriveT = new long[n];
            gotTs = new long[n]; gotVal = new int[n]; gotWid = new int[n];
            counted = new boolean[n];
            Arrays.fill(applyT, Long.MAX_VALUE); Arrays.fill(arriveT, Long.MAX_VALUE);
            for (int j : targets) {
                applyT[j] = start + (offsets == null ? 0 : offsets[j]) + 1 + rng.nextInt(maxDelay)
                          + (rng.nextDouble() < jitterProb ? jitterDelay : 0);
                arriveT[j] = applyT[j] + 1 + rng.nextInt(maxDelay);
            }
            long[] sorted = arriveT.clone(); Arrays.sort(sorted);
            decideT = sorted[quorum - 1];
            for (int j = 0; j < n; j++) if (arriveT[j] <= decideT) counted[j] = true;
        }
    }

    private static final class Task {           // one client operation, in flight
        Op op; List<Phase> phases = new ArrayList<>(); int done = 0;
    }

    private record Ev(long t, int seq, int type, int rep, Task task, int phase) {}   // type 0 = apply, 1 = decide

    /**
     * Runs `clients` clients, each issuing `perClient` operations back to back.
     * Every 3rd operation is a read; the rest are writes.
     */
    History run(int clients, int perClient, long baseSeed) {
        ts = new long[n]; val = new int[n]; wid = new int[n];
        // -1, not 0: a replica that has never been written must lose to every real
        // write, including one whose timestamp happens to be 0.
        Arrays.fill(ts, -1); Arrays.fill(wid, -1);
        History h = new History(); h.name = mode + " N=" + n + " R=" + r + " W=" + w;

        int[] all = new int[n]; for (int j = 0; j < n; j++) all[j] = j;
        List<Ev> evs = new ArrayList<>();
        List<Task> tasks = new ArrayList<>();
        long[] writerCounter = new long[clients];
        Map<Integer, long[]> cache = new HashMap<>();   // client -> {expiry, value}
        int seq = 0;
        int nextValue = 1;

        for (int c = 0; c < clients; c++) {
            long t = rng.nextInt(6);
            for (int k = 0; k < perClient; k++) {
                boolean isRead = (c >= writers) || (k % 3 == 2);
                Task task = new Task();
                int value = isRead ? 0 : (nextValue++);
                task.op = isRead ? Op.get(c, -1, t, -1) : Op.put(c, value, t, -1);

                switch (mode) {
                    case SINGLE, LEADER -> {
                        Phase p = new Phase(!isRead, t, 1, new int[]{0});
                        if (!isRead) { p.wTs = p.applyT[0]; p.wVal = value; p.wWid = c; }  // the leader's own arrival order IS the order
                        task.phases.add(p);
                    }
                    case ASYNC -> {
                        if (isRead) task.phases.add(new Phase(false, t, 1, new int[]{rng.nextInt(n)}));
                        else {
                            // leader acks immediately; followers only hear about it repDelay later
                            long[] off = new long[n];
                            for (int j = 1; j < n; j++) off[j] = repDelay;
                            Phase p = new Phase(true, t, 1, all, off);
                            p.wTs = p.applyT[0]; p.wVal = value; p.wWid = c;
                            task.phases.add(p);
                        }
                    }
                    case CACHED -> {
                        long[] cc = cache.get(c);
                        if (isRead && cc != null && t < cc[0]) {
                            task.op.ret = (int) cc[1];
                            task.op.response = t + 1;
                            h.add(task.op);
                            t = task.op.response + rng.nextInt(4);
                            continue;                       // served from cache, no messages at all
                        }
                        addQuorumPhases(task, t, isRead, all, true, true);
                    }
                    case QUORUM_NAIVE -> addQuorumPhases(task, t, isRead, all, false, true);
                    case QUORUM_ABD   -> addQuorumPhases(task, t, isRead, all, true, true);
                    case QUORUM_CLOCK -> addQuorumPhases(task, t, isRead, all, true, false);
                }
                tasks.add(task);
                Phase p0 = task.phases.get(0);
                for (int j = 0; j < n; j++) if (p0.applyT[j] != Long.MAX_VALUE) evs.add(new Ev(p0.applyT[j], seq++, 0, j, task, 0));
                evs.add(new Ev(p0.decideT, seq++, 1, -1, task, 0));
                t = lastDecide(task) + rng.nextInt(4);
                task.op.response = lastDecide(task);
                if (mode == Mode.CACHED && isRead) cache.put(c, new long[]{ task.op.response + cacheTtl, 0 });
                task.op.pending = false;
            }
        }

        evs.sort(Comparator.comparingLong(Ev::t).thenComparingInt(Ev::seq));
        // process in time order; later phases are appended as earlier ones decide
        PriorityQueue<Ev> q = new PriorityQueue<>(Comparator.comparingLong(Ev::t).thenComparingInt(Ev::seq));
        q.addAll(evs);
        while (!q.isEmpty()) {
            Ev e = q.poll();
            Task task = e.task(); Phase p = task.phases.get(e.phase());
            if (e.type() == 0) {                                  // a replica handles the message
                int j = e.rep();
                if (p.write) {
                    boolean newer = p.wTs > ts[j] || (!noTiebreak && p.wTs == ts[j] && p.wWid > wid[j]);
                    if (newer) { ts[j] = p.wTs; val[j] = p.wVal; wid[j] = p.wWid; }
                } else {
                    p.gotTs[j] = ts[j]; p.gotVal[j] = val[j]; p.gotWid[j] = wid[j];
                }
            } else {                                              // the coordinator has heard from enough replicas
                int next = e.phase() + 1;
                if (next < task.phases.size()) {
                    Phase np = task.phases.get(next);
                    if (np.write) {
                        long bestTs = -1; int bestVal = 0, bestWid = -1;
                        for (int j = 0; j < n; j++) if (p.counted[j] && (p.gotTs[j] > bestTs || (p.gotTs[j] == bestTs && p.gotWid[j] > bestWid)))
                            { bestTs = p.gotTs[j]; bestVal = p.gotVal[j]; bestWid = p.gotWid[j]; }
                        if (task.op.kind == Op.Kind.PUT && next == 1) {   // writer's second phase: its own value, one tick newer
                            np.wTs = bestTs + 1; np.wVal = task.op.arg; np.wWid = task.op.client;
                        } else {                                          // reader's write-back: what it just read
                            boolean allAgree = true;
                            for (int j = 0; j < n; j++)
                                if (p.counted[j] && (p.gotTs[j] != bestTs || p.gotWid[j] != bestWid)) allAgree = false;
                            if (lazyWriteBack && allAgree) { np.wTs = -1; np.wVal = 0; np.wWid = -1; }
                            else { np.wTs = bestTs; np.wVal = bestVal; np.wWid = bestWid; }
                        }
                    }
                    for (int j = 0; j < n; j++) if (np.applyT[j] != Long.MAX_VALUE) q.add(new Ev(np.applyT[j], seq++, 0, j, task, next));
                    q.add(new Ev(np.decideT, seq++, 1, -1, task, next));
                } else if (task.op.kind == Op.Kind.GET) {
                    Phase readPhase = task.phases.get(0);
                    long bestTs = -1; int bestVal = 0, bestWid = -1;
                    for (int j = 0; j < n; j++) if (readPhase.counted[j] && (readPhase.gotTs[j] > bestTs || (readPhase.gotTs[j] == bestTs && readPhase.gotWid[j] > bestWid)))
                        { bestTs = readPhase.gotTs[j]; bestVal = readPhase.gotVal[j]; bestWid = readPhase.gotWid[j]; }
                    task.op.ret = bestVal;
                }
            }
        }
        for (Task t : tasks) if (t.op.response >= 0) h.add(t.op);
        // fix up cached reads whose value we stubbed: replay the cache with real returns
        h.ops.sort(Comparator.comparingLong(o -> o.invoke));
        if (mode == Mode.CACHED) applyCache(h);
        return h;
    }

    /** For a quorum design, build the one or two round trips the operation needs. */
    private void addQuorumPhases(Task task, long t, boolean isRead, int[] all, boolean secondPhase, boolean writerQuery) {
        if (isRead) {
            Phase p1 = new Phase(false, t, r, all);
            task.phases.add(p1);
            if (secondPhase) task.phases.add(new Phase(true, p1.decideT, w, all));   // write-back
        } else if (writerQuery) {
            Phase p1 = new Phase(false, t, r, all);
            task.phases.add(p1);
            task.phases.add(new Phase(true, p1.decideT, w, all));
        } else {
            Phase p1 = new Phase(true, t, w, all);
            p1.wTs = t + (task.op.client == 0 ? clockSkew : 0);      // the writer's own clock
            p1.wVal = task.op.arg; p1.wWid = task.op.client;
            task.phases.add(p1);
        }
    }

    private long lastDecide(Task t) { return t.phases.get(t.phases.size() - 1).decideT; }

    /** CACHED mode serves some reads from a per-client cache; fill in what those reads returned. */
    private void applyCache(History h) {
        Map<Integer, Integer> lastRead = new HashMap<>();
        for (Op o : h.ops) {
            if (o.kind == Op.Kind.GET) {
                if (o.response == o.invoke + 1) o.ret = lastRead.getOrDefault(o.client, 0);
                else lastRead.put(o.client, o.ret);
            }
        }
    }
}
