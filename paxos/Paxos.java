import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * Paxos.java — single-decree ("basic") Paxos you can step through, break, and watch.
 *
 * Everything runs in ONE process. The "network" is a message queue you control:
 * you decide which message is delivered next, which is dropped, and which node
 * is crashed. That is the whole point — in a real distributed system you do not
 * control the schedule, so the protocol must be correct under EVERY schedule.
 * Here you get to play the adversary.
 *
 * Read README.md alongside this file. Run Experiments.java to see it move.
 *
 * Everything lives inside this one class as static nested types (Message,
 * Acceptor, Proposer, Cluster) so that `java PaxosTest.java` can compile the
 * whole lab straight from source, with no build tool.
 */
public final class Paxos {

    private Paxos() {
    }

    /**
     * A majority of the acceptors.
     *
     * Any two majorities of the same group always share at least one member.
     * That overlap is the entire reason Paxos is safe: a later attempt cannot
     * avoid meeting somebody who remembers the earlier decision.
     */
    public static int quorumSize(int numAcceptors) {
        return numAcceptors / 2 + 1;
    }

    // ----------------------------------------------------------------------
    // Rendering
    //
    // Every trace line, table and verdict this lab prints is quoted verbatim in
    // README.md, EXERCISES.md and SOLUTIONS.md — the printed text IS the
    // teaching content. These helpers reproduce Python's repr()/str() spelling
    // ('Zoo', None, ['A', 'B'], {'Zoo'}, True) so those transcripts still match
    // this port character for character. Do not "fix" them to Java spelling
    // without also editing the docs.
    // ----------------------------------------------------------------------

    /** Python's repr(): strings in single quotes, null as None. */
    static String repr(Object value) {
        return value == null ? "None" : "'" + value + "'";
    }

    /** Python's repr() of a list: ['A', 'B', 'C']. */
    static String reprList(Iterable<?> items) {
        StringBuilder sb = new StringBuilder("[");
        for (Object item : items) {
            if (sb.length() > 1) sb.append(", ");
            sb.append(repr(item));
        }
        return sb.append("]").toString();
    }

    /** Python's repr() of a set: {'Zoo'} — and the bare word set() when empty. */
    static String reprSet(Set<?> items) {
        if (items.isEmpty()) return "set()";
        TreeSet<String> sorted = new TreeSet<>();
        for (Object item : items) sorted.add(String.valueOf(item));
        StringBuilder sb = new StringBuilder("{");
        for (String item : sorted) {
            if (sb.length() > 1) sb.append(", ");
            sb.append("'").append(item).append("'");
        }
        return sb.append("}").toString();
    }

    /** Python's str(bool): True / False. */
    static String reprBool(boolean b) {
        return b ? "True" : "False";
    }

    /** Python's str.center(width) — same off-by-one padding rule, so tables line up. */
    static String center(String s, int width) {
        int marg = width - s.length();
        if (marg <= 0) return s;
        int left = marg / 2 + (marg & width & 1);
        return " ".repeat(left) + s + " ".repeat(marg - left);
    }

    // ----------------------------------------------------------------------
    // Messages
    // ----------------------------------------------------------------------

    /**
     * One network message. Five kinds, and that is the entire protocol.
     *
     * @param src      sender
     * @param dst      recipient
     * @param kind     PREPARE | PROMISE | ACCEPT | ACCEPTED | NACK
     * @param n        proposal number this message is about
     * @param value    ACCEPT / ACCEPTED: the value being proposed
     * @param lastN    PROMISE: acceptor's highest ACCEPTED number
     *                 NACK: the promise number that beat us
     * @param lastValue PROMISE: the value that went with lastN
     */
    public record Message(String src, String dst, String kind, int n,
                          Object value, int lastN, Object lastValue) {

        public static Message prepare(String src, String dst, int n) {
            return new Message(src, dst, "PREPARE", n, null, 0, null);
        }

        public static Message promise(String src, String dst, int n, int lastN, Object lastValue) {
            return new Message(src, dst, "PROMISE", n, null, lastN, lastValue);
        }

        public static Message accept(String src, String dst, int n, Object value) {
            return new Message(src, dst, "ACCEPT", n, value, 0, null);
        }

        public static Message accepted(String src, String dst, int n, Object value) {
            return new Message(src, dst, "ACCEPTED", n, value, 0, null);
        }

        public static Message nack(String src, String dst, int n, int promisedN) {
            return new Message(src, dst, "NACK", n, null, promisedN, null);
        }

        @Override
        public String toString() {
            switch (kind) {
                case "PREPARE":
                    return src + "->" + dst + "  PREPARE(n=" + n + ")";
                case "PROMISE": {
                    String tail = lastValue == null
                            ? "nothing accepted yet"
                            : "already accepted (n=" + lastN + ", " + repr(lastValue) + ")";
                    return src + "->" + dst + "  PROMISE(n=" + n + ") [" + tail + "]";
                }
                case "ACCEPT":
                    return src + "->" + dst + "  ACCEPT(n=" + n + ", " + repr(value) + ")";
                case "ACCEPTED":
                    return src + "->" + dst + "  ACCEPTED(n=" + n + ", " + repr(value) + ")";
                case "NACK":
                    return src + "->" + dst + "  NACK(n=" + n + "; already promised " + lastN + ")";
                default:
                    return src + "->" + dst + "  " + kind;
            }
        }
    }

    // ----------------------------------------------------------------------
    // Acceptor — the memory of the system
    // ----------------------------------------------------------------------

    /**
     * An acceptor votes. It never talks to another acceptor.
     *
     * It keeps exactly three facts, and in production all three MUST survive a
     * crash (fsync before replying). `durable=false` models the classic bug of
     * keeping them in memory only — see Experiments.java, experiment 5.
     */
    public static final class Acceptor {

        public final String id;
        public final boolean durable;
        public boolean alive = true;
        public int promisedN = 0;           // highest PREPARE answered
        public int acceptedN = 0;           // highest proposal accepted
        public Object acceptedValue = null; // the value that went with it

        public Acceptor(String nodeId) {
            this(nodeId, true);
        }

        public Acceptor(String nodeId, boolean durable) {
            this.id = nodeId;
            this.durable = durable;
        }

        // -- failure injection ---------------------------------------------
        public void crash() {
            alive = false;
            if (!durable) {
                // It "forgot" — its promises were only ever in RAM.
                promisedN = 0;
                acceptedN = 0;
                acceptedValue = null;
            }
        }

        public void recover() {
            alive = true;
        }

        // -- protocol ------------------------------------------------------
        public List<Message> receive(Message msg) {
            if (msg.kind().equals("PREPARE")) {
                if (msg.n() > promisedN) {
                    // Promise: "I will ignore anything numbered below n from now on."
                    promisedN = msg.n();
                    return List.of(Message.promise(id, msg.src(), msg.n(), acceptedN, acceptedValue));
                }
                return List.of(Message.nack(id, msg.src(), msg.n(), promisedN));
            }

            if (msg.kind().equals("ACCEPT")) {
                // >= not > : the proposer that got our promise at exactly n may proceed.
                if (msg.n() >= promisedN) {
                    promisedN = msg.n();
                    acceptedN = msg.n();
                    acceptedValue = msg.value();
                    return List.of(Message.accepted(id, msg.src(), msg.n(), msg.value()));
                }
                return List.of(Message.nack(id, msg.src(), msg.n(), promisedN));
            }

            return List.of();
        }

        @Override
        public String toString() {
            String state = alive ? "up" : "down";
            String acc = acceptedValue == null ? "-" : repr(acceptedValue) + "@" + acceptedN;
            return "<" + id + " " + state + " promised=" + promisedN + " accepted=" + acc + ">";
        }
    }

    // ----------------------------------------------------------------------
    // Proposer — drives one attempt to get a value chosen
    // ----------------------------------------------------------------------

    /**
     * A proposer runs the two phases. Any number of them may run at once.
     *
     * The rule that makes Paxos work lives in `pickValue`: a proposer does NOT
     * get to insist on its own value. If any acceptor reports an already-accepted
     * value, the proposer must abandon its own idea and re-propose that one.
     */
    public static final class Proposer {

        /** One phase-1 reply, as the proposer remembers it. */
        public record Promise(int lastN, Object lastValue) {
        }

        public final String id;
        public final int rank;              // makes proposal numbers globally unique
        public final Object ownValue;
        public final List<String> acceptorIds;
        public int round = 0;
        public int n = 0;
        public String phase = "idle";       // idle | prepare | accept | done
        public final Map<String, Promise> promises = new LinkedHashMap<>();
        public final Set<String> accepts = new LinkedHashSet<>();
        public Object proposedValue = null; // what it actually ended up proposing
        public Integer adoptedFrom = null;  // set if it had to abandon ownValue
        public int highestNack = 0;

        public Proposer(String pid, int rank, Object value, List<String> acceptorIds) {
            this.id = pid;
            this.rank = rank;
            this.ownValue = value;
            this.acceptorIds = new ArrayList<>(acceptorIds);
        }

        /**
         * Unique, increasing proposal numbers: round*10 + rank.
         *
         * The rank digit guarantees two proposers can never pick the same number.
         * Real systems use the same trick with (round, server_id) pairs.
         */
        public int nextNumber() {
            round += 1;
            return round * 10 + rank;
        }

        // -- phase 1 -------------------------------------------------------
        public List<Message> propose() {
            return propose(null);
        }

        public List<Message> propose(Integer n) {
            this.n = (n == null) ? nextNumber() : n;
            this.phase = "prepare";
            this.promises.clear();
            this.accepts.clear();
            this.proposedValue = null;
            this.adoptedFrom = null;
            List<Message> out = new ArrayList<>();
            for (String a : acceptorIds) out.add(Message.prepare(id, a, this.n));
            return out;
        }

        /** Try again above the highest number that has rejected us. */
        public List<Message> retry() {
            int floor = Math.max(n, highestNack);
            round = Math.max(round, floor / 10);
            return propose();
        }

        // -- value selection: the heart of the algorithm --------------------
        private Object pickValue() {
            int bestN = 0;
            Object bestValue = null;
            for (Promise p : promises.values()) {
                if (p.lastValue() != null && p.lastN() > bestN) {
                    bestN = p.lastN();
                    bestValue = p.lastValue();
                }
            }
            if (bestValue == null) {
                return ownValue;            // nobody had accepted anything: free choice
            }
            adoptedFrom = bestN;            // somebody had: we must carry THEIR value
            return bestValue;
        }

        // -- phase 2 -------------------------------------------------------
        public List<Message> receive(Message msg) {
            if (msg.kind().equals("PROMISE") && msg.n() == n && phase.equals("prepare")) {
                promises.put(msg.src(), new Promise(msg.lastN(), msg.lastValue()));
                if (promises.size() == quorumSize(acceptorIds.size())) {
                    proposedValue = pickValue();
                    phase = "accept";
                    List<Message> out = new ArrayList<>();
                    for (String a : acceptorIds) out.add(Message.accept(id, a, n, proposedValue));
                    return out;
                }

            } else if (msg.kind().equals("ACCEPTED") && msg.n() == n) {
                accepts.add(msg.src());
                if (accepts.size() >= quorumSize(acceptorIds.size())) {
                    phase = "done";
                }

            } else if (msg.kind().equals("NACK")) {
                highestNack = Math.max(highestNack, msg.lastN());
            }

            return List.of();
        }

        @Override
        public String toString() {
            return "<" + id + " n=" + n + " phase=" + phase
                    + " promises=" + promises.size() + " accepts=" + accepts.size()
                    + " proposing=" + repr(proposedValue) + ">";
        }
    }

    // ----------------------------------------------------------------------
    // Cluster — the schedule is yours
    // ----------------------------------------------------------------------

    /** Acceptors, proposers, and an in-memory network you drive by hand. */
    public static final class Cluster {

        public static final String NAMES = "ABCDEFG";

        /** The (n, value) pair a learner counts acceptances against. */
        public record Decision(int n, Object value) {
        }

        public final Map<String, Acceptor> acceptors = new LinkedHashMap<>();
        public final Map<String, Proposer> proposers = new LinkedHashMap<>();
        public final List<Message> queue = new ArrayList<>();
        private final Random rng;
        public double dropRate = 0.0;
        public double dupRate = 0.0;
        public boolean trace;
        public final List<String> log = new ArrayList<>();

        // what a learner would observe
        public final Map<Decision, Set<String>> acceptedBy = new LinkedHashMap<>();
        public final List<Decision> chosen = new ArrayList<>();
        public Set<String> chosenQuorum = null;

        public Cluster(int numAcceptors) {
            this(numAcceptors, 0, true, false);
        }

        public Cluster(int numAcceptors, int seed, boolean durable, boolean trace) {
            for (int i = 0; i < numAcceptors; i++) {
                String name = String.valueOf(NAMES.charAt(i));
                acceptors.put(name, new Acceptor(name, durable));
            }
            this.rng = new Random(seed);
            this.trace = trace;
        }

        // -- setup ---------------------------------------------------------
        public int quorum() {
            return quorumSize(acceptors.size());
        }

        public Proposer addProposer(String pid, Object value) {
            Proposer p = new Proposer(pid, proposers.size() + 1, value, List.copyOf(acceptors.keySet()));
            proposers.put(pid, p);
            return p;
        }

        public void propose(String pid) {
            queue.addAll(proposers.get(pid).propose());
        }

        public void propose(String pid, int n) {
            queue.addAll(proposers.get(pid).propose(n));
        }

        public void retry(String pid) {
            queue.addAll(proposers.get(pid).retry());
        }

        // -- driving the network -------------------------------------------
        private void say(String line) {
            log.add(line);
            if (trace) System.out.println(line);
        }

        /** Deliver the oldest in-flight message. false when nothing is pending. */
        public boolean deliverNext() {
            if (queue.isEmpty()) return false;
            Message msg = queue.remove(0);
            if (dropRate > 0 && rng.nextDouble() < dropRate) {
                say("  x  LOST      " + msg);
                return true;
            }
            deliver(msg);
            if (dupRate > 0 && rng.nextDouble() < dupRate) {
                say("  =  DUPLICATE " + msg);
                deliver(msg);
            }
            return true;
        }

        private int find(String dst, String kind, Integer n) {
            for (int i = 0; i < queue.size(); i++) {
                Message m = queue.get(i);
                if (m.dst().equals(dst) && m.kind().equals(kind) && (n == null || m.n() == n)) {
                    return i;
                }
            }
            return -1;
        }

        public boolean deliverTo(String dst, String kind) {
            return deliverTo(dst, kind, null);
        }

        /**
         * Deliver the first in-flight message matching dst+kind (+n). Lets you
         * build an exact schedule by hand — that is how you reproduce a specific
         * race instead of hoping to stumble into it.
         */
        public boolean deliverTo(String dst, String kind, Integer n) {
            int i = find(dst, kind, n);
            if (i < 0) return false;
            deliver(queue.remove(i));
            return true;
        }

        public boolean dropTo(String dst, String kind) {
            return dropTo(dst, kind, null);
        }

        public boolean dropTo(String dst, String kind, Integer n) {
            int i = find(dst, kind, n);
            if (i < 0) return false;
            say("  x  LOST      " + queue.remove(i));
            return true;
        }

        public void deliverMany(String dsts, String kind) {
            deliverMany(dsts, kind, null);
        }

        /** deliverMany("ABC", "PREPARE") — one call per acceptor, in order. */
        public void deliverMany(String dsts, String kind, Integer n) {
            for (char d : dsts.toCharArray()) {
                deliverTo(String.valueOf(d), kind, n);
            }
        }

        private void deliver(Message msg) {
            Acceptor acceptor = acceptors.get(msg.dst());
            Proposer proposer = proposers.get(msg.dst());
            if (acceptor == null && proposer == null) return;
            if (acceptor != null && !acceptor.alive) {
                say("  o  " + msg.dst() + " IS DOWN, message lost: " + msg);
                return;
            }
            say("  -> " + msg);
            List<Message> replies = acceptor != null ? acceptor.receive(msg) : proposer.receive(msg);
            for (Message reply : replies) {
                if (reply.kind().equals("ACCEPTED")) {
                    recordAccept(reply.src(), reply.n(), reply.value());
                }
                queue.add(reply);
            }
        }

        public int run() {
            return run(500);
        }

        public int run(int maxSteps) {
            int steps = 0;
            while (steps < maxSteps && deliverNext()) {
                steps += 1;
            }
            return steps;
        }

        // -- learner -------------------------------------------------------
        private void recordAccept(String acceptorId, int n, Object value) {
            Set<String> seen = acceptedBy.computeIfAbsent(new Decision(n, value), k -> new LinkedHashSet<>());
            seen.add(acceptorId);
            if (seen.size() == quorum()) {
                chosen.add(new Decision(n, value));
                if (chosenQuorum == null) {
                    chosenQuorum = new LinkedHashSet<>(seen);
                }
                say("  *  CHOSEN " + repr(value) + " (n=" + n + ") by majority "
                        + reprList(new TreeSet<>(seen)));
            }
        }

        public Set<Object> chosenValues() {
            Set<Object> values = new LinkedHashSet<>();
            for (Decision d : chosen) values.add(d.value());
            return values;
        }

        /** THE invariant: at most one value is ever chosen, for all time. */
        public boolean safetyHolds() {
            return chosenValues().size() <= 1;
        }

        // -- inspection ----------------------------------------------------
        public void crash(String... ids) {
            for (String i : ids) {
                acceptors.get(i).crash();
                say("  !  " + i + " CRASHED");
            }
        }

        public void recover(String... ids) {
            for (String i : ids) {
                acceptors.get(i).recover();
                say("  +  " + i + " RECOVERED " + acceptors.get(i));
            }
        }

        public String table() {
            String head = String.format("%9s | %s | %8s | accepted", "acceptor", center("up?", 4), "promised");
            List<String> rows = new ArrayList<>();
            rows.add(head);
            rows.add("-".repeat(head.length()));
            for (Acceptor a : acceptors.values()) {
                String acc = a.acceptedValue == null ? "-" : repr(a.acceptedValue) + " (n=" + a.acceptedN + ")";
                rows.add(String.format("%9s | %s | %8d | %s",
                        a.id, center(a.alive ? "up" : "DOWN", 4), a.promisedN, acc));
            }
            return String.join("\n", rows);
        }
    }
}
