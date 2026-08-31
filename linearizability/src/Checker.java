import java.util.*;

/**
 * THE CHECKER.
 *
 * Given a history (what clients asked and what they were told, with times),
 * decide whether there EXISTS an order of the operations such that:
 *   (1) running them one at a time in that order against a single copy of the
 *       data reproduces exactly the answers the clients actually got, and
 *   (2) the order never puts an operation before another one that had already
 *       finished before it started.
 *
 * (1) alone is sequential consistency. (1)+(2) is linearizability.
 *
 * The search: repeatedly pick an operation that is allowed to go next, apply
 * it to a single-copy model, and recurse. If the model's answer disagrees with
 * the client's recorded answer, this branch is impossible — back up and try
 * another. This is the Wing & Gong search, with the memo table that makes it
 * survive histories longer than about a dozen operations.
 */
final class Checker {

    static final class Result {
        final boolean ok;
        final int[] order;      // a witness order, when ok
        final long nodes;       // search states explored — the cost of checking
        Result(boolean ok, int[] order, long nodes) { this.ok = ok; this.order = order; this.nodes = nodes; }
    }

    /** Options let us build deliberately WRONG checkers too, which is how we prove what each rule buys. */
    static final class Opts {
        boolean realTime = true;    // false => this is a sequential-consistency checker
        boolean dropPending = false;// true  => planted bug: never linearize a timed-out op
        boolean memo = true;        // false => same answers, exponentially more work
        static Opts linearizable() { return new Opts(); }
        static Opts sequential()   { Opts o = new Opts(); o.realTime = false; return o; }
    }

    private final List<Op> ops;
    private final Opts opts;
    private final long full;
    private final HashSet<Key> seen = new HashSet<>();
    private final int[] stack;
    private long nodes = 0;
    private int[] witness = null;
    private final Map<String, Integer> objIdx = new LinkedHashMap<>();   // one register per object name

    private Checker(History h, Opts o) {
        this.ops = h.ops; this.opts = o;
        if (ops.size() > 60) throw new IllegalArgumentException("history too long for the bitmask search: " + ops.size());
        this.full = ops.isEmpty() ? 0 : (ops.size() == 64 ? -1L : (1L << ops.size()) - 1);
        this.stack = new int[ops.size()];
        for (Op op : ops) objIdx.computeIfAbsent(op.obj, k -> objIdx.size());
        if (objIdx.size() > 5) throw new IllegalArgumentException("this checker models at most 5 objects");
    }

    static Result check(History h) { return check(h, Opts.linearizable()); }

    static Result check(History h, Opts o) {
        Checker c = new Checker(h, o);
        boolean ok = c.search(c.full, new int[c.objIdx.size()], 0);
        return new Result(ok, ok ? c.witness.clone() : null, c.nodes);
    }

    /**
     * @param remaining bitmask of operations not yet placed in the order
     * @param state     the single copy's current value
     * @param depth     how many operations we have placed
     */
    private record Key(long remaining, long state) {}

    private boolean search(long remaining, int[] state, int depth) {
        nodes++;
        if (remaining == 0) { witness = stack.clone(); return true; }

        // A pending operation may simply be discarded — the client never learned
        // whether it happened, so a valid explanation is "it never happened".
        boolean allPending = true;
        for (int i = 0; i < ops.size(); i++)
            if ((remaining & (1L << i)) != 0 && !ops.get(i).pending) { allPending = false; break; }
        if (allPending) { witness = Arrays.copyOf(stack, depth); return true; }

        if (opts.memo && !seen.add(new Key(remaining, pack(state)))) return false;   // already failed from here

        for (int i : candidates(remaining)) {
            Op op = ops.get(i);
            int slot = objIdx.get(op.obj);
            int[] r = apply(state[slot], op);    // r[0] = the object's new value, r[1] = the answer one copy would give
            // A timed-out operation has no recorded answer, so there is nothing to contradict.
            if (!op.pending && r[1] != op.ret) continue;   // <-- the contradiction test
            int old = state[slot];
            state[slot] = r[0];
            stack[depth] = i;
            if (search(remaining & ~(1L << i), state, depth + 1)) return true;
            state[slot] = old;
        }
        return false;
    }

    /** Packs the per-object values into one long so the memo table can key on them. */
    private static long pack(int[] state) {
        long p = 0;
        for (int v : state) {
            if (v < 0 || v > 1_000_000) throw new IllegalArgumentException("value out of range for the memo key: " + v);
            p = p * 1_000_003L + v;
        }
        return p;
    }

    /**
     * Which operations are ALLOWED to be next?
     *
     * With the real-time rule (linearizability): any operation that had already
     * started before the earliest still-unplaced finish. Anything that finished
     * before this one started must go first — that is rule (2), and it is the
     * only difference between the two checkers.
     *
     * Without it (sequential consistency): each client's own next operation,
     * in the order that client issued them. Nothing more.
     */
    private List<Integer> candidates(long remaining) {
        List<Integer> out = new ArrayList<>();
        if (opts.realTime) {
            long minResp = Long.MAX_VALUE;
            for (int i = 0; i < ops.size(); i++)
                if ((remaining & (1L << i)) != 0) minResp = Math.min(minResp, ops.get(i).resp());
            for (int i = 0; i < ops.size(); i++) {
                if ((remaining & (1L << i)) == 0) continue;
                Op o = ops.get(i);
                if (opts.dropPending && o.pending) continue;      // planted bug
                if (o.invoke < minResp) out.add(i);
            }
        } else {
            Set<Integer> taken = new HashSet<>();
            for (int i = 0; i < ops.size(); i++) {
                if ((remaining & (1L << i)) == 0) continue;
                Op o = ops.get(i);
                if (opts.dropPending && o.pending) continue;
                if (taken.add(o.client)) out.add(i);              // first unplaced op of this client
            }
        }
        return out;
    }

    /** The single-copy model: one value, one operation at a time. This is "the notebook". */
    private static int[] apply(int state, Op op) {
        return switch (op.kind) {
            case PUT -> new int[]{ op.arg, 0 };
            case GET -> new int[]{ state, state };
            case CAS -> state == op.arg ? new int[]{ op.arg2, 1 } : new int[]{ state, 0 };
        };
    }

    // ---------------------------------------------------------------- explaining a failure

    /**
     * When a history is NOT linearizable, most of it is usually innocent. Throw
     * operations away one at a time, keeping only removals that leave it still
     * broken. What survives is a small history that is broken on its own — which
     * is what you actually paste into a bug report.
     */
    /**
     * Only keep candidate reductions where every value a read returned was
     * actually written by some operation still in the history. Otherwise the
     * minimiser "wins" by deleting the write and leaving a read of a value out
     * of thin air — true, but not the violation we were explaining.
     */
    private static boolean selfContained(List<Op> ops) {
        for (Op o : ops) {
            if (o.kind == Op.Kind.GET && o.ret != 0) {
                boolean written = false;
                for (Op p : ops) if (p.kind == Op.Kind.PUT && p.arg == o.ret) { written = true; break; }
                if (!written) return false;
            }
        }
        return true;
    }

    static History minimize(History h, Checker.Opts o) {
        List<Op> keep = new ArrayList<>(h.ops);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < keep.size(); i++) {
                List<Op> trial = new ArrayList<>(keep);
                trial.remove(i);
                History t = new History(); t.name = h.name + " (minimised)";
                t.ops.addAll(trial);
                if (!trial.isEmpty() && selfContained(trial) && !check(t, o).ok) { keep = trial; changed = true; break; }
            }
        }
        History out = new History(); out.name = h.name + " (minimised)";
        out.ops.addAll(keep);
        return out;
    }
}
