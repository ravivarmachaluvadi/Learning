import java.util.*;
import java.util.function.BinaryOperator;

/**
 * A whole collaborative editing session in one process: one server, N clients,
 * and a network you can delay, reorder, drop and partition by hand.
 *
 * Nothing here is asynchronous. Time only moves when you call deliver().
 * That is deliberate: every "race condition" in this lab is one you schedule on
 * purpose, so you can watch it instead of chasing it.
 */
public final class Collab {

    /** RAW = send the operations but never transform them (the naive design). */
    public enum Mode { RAW, OT }

    // ---------------------------------------------------------------- messages

    public static final class ToServer {
        final String from; final Ot.Op op; final int baseRevision; final int seq;
        ToServer(String from, Ot.Op op, int baseRevision, int seq) {
            this.from = from; this.op = op; this.baseRevision = baseRevision; this.seq = seq;
        }
        @Override public String toString() {
            return from + " -> server  " + op + "  (edit #" + seq + ", written against revision " + baseRevision + ")";
        }
    }

    public static final class ToClient {
        final String to; final Ot.Op op; final int revision; final String origin;
        ToClient(String to, Ot.Op op, int revision, String origin) {
            this.to = to; this.op = op; this.revision = revision; this.origin = origin;
        }
        @Override public String toString() {
            return "server -> " + to + "  " + op + "  (revision " + revision + (origin.equals(to) ? ", your own edit, confirmed" : "") + ")";
        }
    }

    // ------------------------------------------------------------------ server

    /**
     * The single owner of one document. Every edit in the world passes through
     * here, which is what turns "concurrent" into "ordered": the log order IS
     * the official history of the document.
     */
    public static final class Server {
        final Mode mode;
        /** Swappable so you can plug a broken transform in and watch — see BrokenOt.java. */
        final BinaryOperator<Ot.Op> xform;
        public String doc;
        public final List<Ot.Op> log = new ArrayList<>();

        /** Duplicate suppression, off by default so you can watch it hurt. */
        public boolean dedup = false;
        final Map<String, Integer> lastSeq = new HashMap<>();
        final Map<String, Ot.Op> lastAckOp = new HashMap<>();
        final Map<String, Integer> lastAckRev = new HashMap<>();

        public Server(String doc, Mode mode) { this(doc, mode, Ot::transform); }
        public Server(String doc, Mode mode, BinaryOperator<Ot.Op> xform) { this.doc = doc; this.mode = mode; this.xform = xform; }

        public int revision() { return log.size(); }

        public record Accepted(Ot.Op op, int revision, boolean duplicate) {}

        public Accepted receive(String from, int seq, Ot.Op op, int baseRevision) {
            if (dedup && seq <= lastSeq.getOrDefault(from, 0)) {
                // We already applied this exact edit. Do NOT apply it again —
                // re-send the SAME confirmation we sent the first time.
                // (Identical in spirit to an idempotency key on a payment.)
                return new Accepted(lastAckOp.get(from), lastAckRev.get(from), true);
            }
            Ot.Op o = op;
            if (mode == Mode.OT) {
                // The client wrote this against revision `baseRevision`. Everything
                // logged since then is news to it, so rewrite the op against each
                // of those, oldest first.
                for (int i = baseRevision; i < log.size(); i++) o = xform.apply(o, log.get(i));
            }
            log.add(o);
            doc = (mode == Mode.OT) ? Ot.apply(doc, o) : Ot.applyLenient(doc, o);
            lastSeq.put(from, seq);
            lastAckOp.put(from, o);
            lastAckRev.put(from, log.size());
            return new Accepted(o, log.size(), false);
        }
    }

    // ------------------------------------------------------------------ client

    /**
     * One person's browser tab. It applies your keystroke to the screen
     * immediately (that is the whole point — nobody waits for a round trip) and
     * reconciles with whatever the server says happened afterwards.
     *
     * Invariant: at most ONE operation is in flight to the server at a time.
     * Anything typed while waiting sits in `buffer`. Without that rule the
     * server would be transforming against a history the client is still
     * changing, and the two would drift apart.
     */
    public static final class Client {
        public final String site;
        final Mode mode;
        final BinaryOperator<Ot.Op> xform;
        public String doc;
        public int revision = 0;
        Ot.Op outstanding = null;                       // sent, not yet confirmed
        final Deque<Ot.Op> buffer = new ArrayDeque<>(); // typed since then
        public boolean online = true;
        int seq = 0;                                    // this client's edit counter
        public Ot.Op lastApplied = null;                // what a remote edit became on this screen

        public Client(String site, String doc, Mode mode) { this(site, doc, mode, Ot::transform); }
        public Client(String site, String doc, Mode mode, BinaryOperator<Ot.Op> xform) {
            this.site = site; this.doc = doc; this.mode = mode; this.xform = xform;
        }

        public int pendingCount() { return (outstanding == null ? 0 : 1) + buffer.size(); }
        public boolean waiting() { return outstanding != null; }

        /** You typed something. Screen updates now; the network catches up later. */
        public ToServer edit(Ot.Op op) {
            doc = (mode == Mode.OT) ? Ot.apply(doc, op) : Ot.applyLenient(doc, op);
            if (outstanding == null) {
                outstanding = op;
                return new ToServer(site, op, revision, ++seq);
            }
            buffer.addLast(op);
            return null;
        }

        /** Send the un-confirmed edit again — same edit number, so it is a retry, not a new edit. */
        public ToServer resend() {
            return outstanding == null ? null : new ToServer(site, outstanding, revision, seq);
        }

        /** A broadcast arrived: either the server confirming my own edit, or somebody else's. */
        public ToServer receive(ToClient msg) {
            if (msg.origin.equals(site)) {
                // Confirmation. The server may have rewritten my op before applying
                // it; my screen already shows my own version, and the whole promise
                // of transformation is that both end at the same text.
                revision = msg.revision;
                outstanding = buffer.pollFirst();
                return outstanding == null ? null : new ToServer(site, outstanding, revision, ++seq);
            }

            Ot.Op incoming = msg.op;
            if (mode == Mode.OT) {
                // This op was written against a document that does not contain my
                // un-confirmed edits. Push it through them, and push them through it.
                if (outstanding != null) {
                    Ot.Op newOutstanding = xform.apply(outstanding, incoming);
                    incoming = xform.apply(incoming, outstanding);
                    outstanding = newOutstanding;
                }
                if (!buffer.isEmpty()) {
                    List<Ot.Op> rebuilt = new ArrayList<>(buffer.size());
                    for (Ot.Op b : buffer) {
                        rebuilt.add(xform.apply(b, incoming));
                        incoming = xform.apply(incoming, b);
                    }
                    buffer.clear();
                    buffer.addAll(rebuilt);
                }
                doc = Ot.apply(doc, incoming);
            } else {
                doc = Ot.applyLenient(doc, incoming); // naive mode: apply it exactly as sent
            }
            lastApplied = incoming;
            revision = msg.revision;
            return null;
        }
    }

    // -------------------------------------------------------------- simulation

    public static final class Sim {
        public final Server server;
        public final Map<String, Client> clients = new LinkedHashMap<>();
        public final List<Object> inFlight = new ArrayList<>();
        public final List<String> events = new ArrayList<>();
        final Mode mode;
        final BinaryOperator<Ot.Op> xform;

        public Sim(String startingDoc, Mode mode, String... sites) { this(startingDoc, mode, Ot::transform, sites); }

        public Sim(String startingDoc, Mode mode, BinaryOperator<Ot.Op> xform, String... sites) {
            this.mode = mode;
            this.xform = xform;
            this.server = new Server(startingDoc, mode, xform);
            for (String s : sites) clients.put(s, new Client(s, startingDoc, mode, xform));
        }

        public Client c(String site) { return clients.get(site); }

        /**
         * Somebody opens the document for the first time. They get the server's
         * current text and its current revision number — exactly what a real
         * client gets: a snapshot, plus a place in the log to subscribe from.
         */
        public Client join(String site) {
            Client cl = new Client(site, server.doc, mode, xform);
            cl.revision = server.revision();
            clients.put(site, cl);
            events.add(site + " opened the document at revision " + cl.revision + ": \"" + cl.doc + "\"");
            return cl;
        }

        public void type(String site, int pos, char ch) { doEdit(site, Ot.Op.insert(pos, ch, site)); }
        public void erase(String site, int pos)         { doEdit(site, Ot.Op.delete(pos, site)); }

        private void doEdit(String site, Ot.Op op) {
            Client cl = clients.get(site);
            send(cl.edit(op));
            events.add(site + " typed " + op + "   their screen now reads \"" + cl.doc + "\"");
        }

        /** Retry the un-confirmed edit — what a client does when a reply never came. */
        public void resend(String site) {
            ToServer m = clients.get(site).resend();
            send(m);
            events.add(site + " heard nothing back and re-sent " + (m == null ? "nothing" : m.op.toString()));
        }

        public void disconnect(String site) { clients.get(site).online = false; events.add(site + " went offline"); }
        public void reconnect(String site)  { clients.get(site).online = true;  events.add(site + " came back online"); }

        void send(Object msg) { if (msg != null) inFlight.add(msg); }

        /** Deliver the oldest deliverable message. Returns false if none can move. */
        public boolean deliverOne() { int i = nextDeliverable(); return i >= 0 && deliverAt(i); }

        public int nextDeliverable() {
            for (int i = 0; i < inFlight.size(); i++) if (canDeliver(i)) return i;
            return -1;
        }

        public boolean canDeliver(int i) {
            Object m = inFlight.get(i);
            if (m instanceof ToServer ts) return clients.get(ts.from).online;
            ToClient tc = (ToClient) m;
            if (!clients.get(tc.to).online) return false;
            // Per-client ordering: one WebSocket delivers in order, so a later
            // broadcast to the same client may not overtake an earlier one.
            for (int j = 0; j < i; j++)
                if (inFlight.get(j) instanceof ToClient earlier && earlier.to.equals(tc.to)) return false;
            return true;
        }

        /** Deliver a specific message. Bypasses the ordering rule on purpose — see the out-of-order experiment. */
        public boolean deliverAt(int i) {
            if (i < 0 || i >= inFlight.size()) return false;
            Object m = inFlight.remove(i);
            if (m instanceof ToServer ts) {
                Server.Accepted a = server.receive(ts.from, ts.seq, ts.op, ts.baseRevision);
                if (a.duplicate()) {
                    events.add("server recognised edit #" + ts.seq + " from " + ts.from
                            + " as a REPEAT, did not apply it again, and re-sent the original confirmation"
                            + "   server doc still \"" + server.doc + "\"");
                    send(new ToClient(ts.from, a.op(), a.revision(), ts.from));
                    return true;
                }
                String note = (a.op().pos == ts.op.pos && a.op().kind == ts.op.kind) ? "" : "  (server rewrote it to " + a.op() + ")";
                events.add("server accepted " + ts.op + " as revision " + a.revision() + note
                        + "   server doc now \"" + server.doc + "\"");
                for (String site : clients.keySet()) send(new ToClient(site, a.op(), a.revision(), ts.from));
            } else {
                ToClient tc = (ToClient) m;
                Client cl = clients.get(tc.to);
                send(cl.receive(tc));
                String rewrite = "";
                if (!tc.origin.equals(tc.to) && cl.lastApplied != null
                        && (cl.lastApplied.pos != tc.op.pos || cl.lastApplied.kind != tc.op.kind))
                    rewrite = "  (their own editor rewrote it to " + cl.lastApplied + ", against their un-confirmed edit)";
                events.add(tc.to + " received " + tc.op + (tc.origin.equals(tc.to) ? " (confirmation of their own edit)" : " from " + tc.origin)
                        + rewrite + "   their screen now reads \"" + cl.doc + "\"");
            }
            return true;
        }

        /** Throw a message away forever — the network ate it. */
        public void dropAt(int i) {
            Object m = inFlight.remove(i);
            events.add("NETWORK DROPPED: " + m);
        }

        /** Index of the first in-flight message matching a description, or -1. */
        public int find(String contains) {
            for (int i = 0; i < inFlight.size(); i++) if (inFlight.get(i).toString().contains(contains)) return i;
            return -1;
        }

        public void settle() {
            int guard = 0;
            while (deliverOne()) if (++guard > 100_000) throw new IllegalStateException("network will not settle");
        }

        /** Deliver in a random but legal order — the everyday case. */
        public void settleRandom(Random rnd) {
            int guard = 0;
            while (true) {
                List<Integer> ok = new ArrayList<>();
                for (int i = 0; i < inFlight.size(); i++) if (canDeliver(i)) ok.add(i);
                if (ok.isEmpty()) return;
                deliverAt(ok.get(rnd.nextInt(ok.size())));
                if (++guard > 100_000) throw new IllegalStateException("network will not settle");
            }
        }

        public boolean converged() {
            for (Client cl : clients.values()) if (!cl.doc.equals(server.doc)) return false;
            return true;
        }

        public String docs() {
            StringBuilder sb = new StringBuilder("server=\"" + server.doc + "\"");
            for (Client cl : clients.values()) sb.append("  ").append(cl.site).append("=\"").append(cl.doc).append("\"");
            return sb.toString();
        }

        public void printEvents() { for (String e : events) System.out.println("   " + e); }
        public void printNewEvents(int from) { for (int i = from; i < events.size(); i++) System.out.println("   " + events.get(i)); }
    }

    private Collab() {}
}
