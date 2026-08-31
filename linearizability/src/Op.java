import java.util.*;

/**
 * One operation, as seen from OUTSIDE the system: we know when the client
 * asked, when the client got an answer, and what the answer was. We do NOT
 * know what happened inside. That is the whole point — linearizability is a
 * property of what an outside observer can see.
 */
final class Op {
    enum Kind { PUT, GET, CAS }

    int client;              // which client issued it (its "process")
    final Kind kind;
    final int arg;           // PUT: value written. CAS: expected value.
    final int arg2;          // CAS: new value.
    int ret;                 // GET: value returned. PUT: 0. CAS: 1 = swapped, 0 = not.
    long invoke;             // real time the client sent the request
    long response;           // real time the client got the answer
    boolean pending;         // true = client never got an answer (timeout / crash)
    String obj = "x";        // which object this operates on (used only in the locality demo)

    Op(int client, Kind kind, int arg, int arg2, long invoke, long response, int ret) {
        this.client = client; this.kind = kind; this.arg = arg; this.arg2 = arg2;
        this.invoke = invoke; this.response = response; this.ret = ret;
    }

    static Op put(int c, int v, long inv, long res) { return new Op(c, Kind.PUT, v, 0, inv, res, 0); }
    static Op get(int c, int v, long inv, long res) { return new Op(c, Kind.GET, 0, 0, inv, res, v); }
    static Op cas(int c, int a, int b, boolean ok, long inv, long res) {
        return new Op(c, Kind.CAS, a, b, inv, res, ok ? 1 : 0);
    }
    /** An operation the client never got an answer for. It may or may not have taken effect. */
    static Op pendingPut(int c, int v, long inv) {
        Op o = new Op(c, Kind.PUT, v, 0, inv, Long.MAX_VALUE, 0);
        o.pending = true; return o;
    }

    long resp() { return pending ? Long.MAX_VALUE : response; }

    String label() {
        return switch (kind) {
            case PUT -> "put(" + arg + ")" + (pending ? "-?" : "");
            case GET -> "get()->" + ret;
            case CAS -> "cas(" + arg + "," + arg2 + ")->" + (ret == 1 ? "ok" : "no");
        };
    }

    @Override public String toString() { return "C" + client + " " + label() + " [" + invoke + "," + (pending ? "?" : response) + "]"; }
}
