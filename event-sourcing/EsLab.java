import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Event sourcing learning lab — a bank whose ONLY database is an append-only
 * event log.
 *
 * There is no `accounts` table with a `balance` column anywhere in this file.
 * An account's balance exists only as the result of REPLAYING its events:
 *
 *     events:  AccountOpened -> MoneyDeposited(100) -> MoneyWithdrawn(30)
 *     state:   fold(apply, events) == Account(balance=70, version=3)
 *
 * Everything else in the pattern — optimistic concurrency, time travel,
 * compensating events, snapshots, upcasting — follows from that one decision.
 *
 * Run the narrated demo:   java -cp ../lib/h2.jar EsLab.java
 * Run the tests:           java -cp ../lib/h2.jar EsTest.java
 * H2 (../lib/h2.jar) is the only dependency.
 */
public class EsLab {

    /** The aggregate said no. Nothing was appended. */
    static class CommandRejected extends RuntimeException {
        CommandRejected(String message) { super(message); }
    }

    /** Someone else appended to this stream since you loaded it. Reload and retry. */
    static class ConcurrencyConflict extends RuntimeException {
        ConcurrencyConflict(String message) { super(message); }
    }

    /** One fact: a type name and its payload. Proposed by decide, stored by append. */
    record Event(String type, Map<String, Object> payload) {}

    /** One fact as it came back out of the store, with the version it landed at. */
    record Recorded(int version, String type, Map<String, Object> payload) {}

    /** (state, eventsReplayed) — the pair load() returns so the lab can SHOW the replay cost. */
    record Loaded(Account account, int replayed) {}

    // ----------------------------------------------------------------------
    // The event store: streams of immutable events. THE only truth.
    // ----------------------------------------------------------------------
    static class EventStore implements AutoCloseable {

        /** An embedded H2 file database; running the lab creates eventstore.mv.db beside it. */
        static final String DB_URL = "jdbc:h2:./eventstore";

        final Connection db;

        EventStore() {
            try {
                db = DriverManager.getConnection(DB_URL, "sa", "");
                db.setAutoCommit(false);   // every write below ends in an explicit commit or rollback
                try (Statement s = db.createStatement()) {
                    // The event log is the only durable thing here, but it is not
                    // meant to survive between RUNS of the lab: dropping first keeps
                    // every scenario and every test starting from an empty book.
                    s.execute("DROP TABLE IF EXISTS events");
                    s.execute("DROP TABLE IF EXISTS snapshots");
                    s.execute("""
                            CREATE TABLE events (
                                global_seq BIGINT  AUTO_INCREMENT PRIMARY KEY,
                                stream     VARCHAR NOT NULL,       -- e.g. 'account-7'
                                version    INT     NOT NULL,       -- 1,2,3,... within the stream
                                type       VARCHAR NOT NULL,
                                schema_v   INT     NOT NULL DEFAULT 2,
                                payload    VARCHAR NOT NULL,
                                UNIQUE (stream, version))""");   // the line optimistic concurrency stands on
                    s.execute("CREATE TABLE snapshots (stream VARCHAR PRIMARY KEY, version INT, state VARCHAR)");
                }
                db.commit();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }

        int append(String stream, int expectedVersion, List<Event> events) {
            return append(stream, expectedVersion, events, 2);
        }

        /** Append events, but ONLY if the stream is still at expectedVersion.
         *  This compare-and-append is the entire write-concurrency story. */
        int append(String stream, int expectedVersion, List<Event> events, int schemaV) {
            try {
                int current = streamVersion(stream);
                if (current != expectedVersion) {
                    db.rollback();
                    throw new ConcurrencyConflict(
                            stream + ": you loaded version " + expectedVersion + ", but it is now " + current);
                }
                try (PreparedStatement p = db.prepareStatement(
                        "INSERT INTO events (stream, version, type, schema_v, payload) VALUES (?,?,?,?,?)")) {
                    int i = 1;
                    for (Event e : events) {
                        p.setString(1, stream);
                        p.setInt(2, expectedVersion + i);
                        p.setString(3, e.type());
                        p.setInt(4, schemaV);
                        p.setString(5, toJson(e.payload()));
                        p.executeUpdate();
                        i++;
                    }
                }
                db.commit();   // all of the events, or none of them
                return expectedVersion + events.size();
            } catch (SQLException e) {
                rollbackQuietly();
                throw new RuntimeException(e);
            }
        }

        List<Recorded> read(String stream) {
            return read(stream, 0, null);
        }

        /** Events of one stream, upcast to the current schema on the way out. */
        List<Recorded> read(String stream, int fromVersion, Integer toVersion) {
            String q = "SELECT version, type, schema_v, payload FROM events WHERE stream=? AND version>?";
            if (toVersion != null) q += " AND version<=?";
            List<Recorded> out = new ArrayList<>();
            try (PreparedStatement p = db.prepareStatement(q + " ORDER BY version")) {
                p.setString(1, stream);
                p.setInt(2, fromVersion);
                if (toVersion != null) p.setInt(3, toVersion);
                try (ResultSet rs = p.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Recorded(rs.getInt(1), rs.getString(2),
                                upcast(rs.getString(2), rs.getInt(3), fromJson(rs.getString(4)))));
                    }
                }
                return out;
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }

        int streamVersion(String stream) {
            try (PreparedStatement p = db.prepareStatement(
                    "SELECT COALESCE(MAX(version),0) FROM events WHERE stream=?")) {
                p.setString(1, stream);
                try (ResultSet rs = p.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }

        // -- snapshots -----------------------------------------------------
        /** A cached fold at a version. Delete the whole table and nothing is lost but time. */
        record Snapshot(int version, Map<String, Object> state) {}

        void saveSnapshot(String stream, int version, Map<String, Object> state) {
            try (PreparedStatement p = db.prepareStatement(
                    "MERGE INTO snapshots (stream, version, state) KEY(stream) VALUES (?,?,?)")) {
                p.setString(1, stream);
                p.setInt(2, version);
                p.setString(3, toJson(state));
                p.executeUpdate();
                db.commit();
            } catch (SQLException e) {
                rollbackQuietly();
                throw new RuntimeException(e);
            }
        }

        Snapshot loadSnapshot(String stream) {
            try (PreparedStatement p = db.prepareStatement(
                    "SELECT version, state FROM snapshots WHERE stream=?")) {
                p.setString(1, stream);
                try (ResultSet rs = p.executeQuery()) {
                    return rs.next() ? new Snapshot(rs.getInt(1), fromJson(rs.getString(2))) : null;
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }

        private void rollbackQuietly() {
            try { db.rollback(); } catch (SQLException ignored) { }
        }

        /** The demo and every test close their store, so H2 releases eventstore.mv.db. */
        @Override public void close() {
            try { db.close(); } catch (SQLException ignored) { }
        }
    }

    // ----------------------------------------------------------------------
    // Schema evolution: events live forever; code changes. Upcasters bridge.
    // ----------------------------------------------------------------------
    /** v1 MoneyDeposited had no 'channel' field (the concept didn't exist).
     *  Rather than migrating stored history (never!), old events are upgraded
     *  IN MEMORY as they are read. */
    static Map<String, Object> upcast(String type, int schemaV, Map<String, Object> payload) {
        if ((type.equals("MoneyDeposited") || type.equals("MoneyWithdrawn")) && schemaV < 2) {
            payload = new LinkedHashMap<>(payload);
            payload.putIfAbsent("channel", "branch");   // every pre-v2 transaction was at a branch
        }
        return payload;
    }

    // ----------------------------------------------------------------------
    // The aggregate: state = fold(apply, events); commands decide, never mutate.
    // ----------------------------------------------------------------------
    /** Immutable by construction — a record, so every "change" is a new Account. */
    record Account(String accountId, String owner, double balance, boolean isOpen, int version) {

        Account(String accountId) { this(accountId, "", 0.0, false, 0); }

        // -- apply: how each event changes state. NO validation here — the event
        //    already happened; apply must accept it. Pure and total.
        Account apply(String type, Map<String, Object> e) {
            return switch (type) {
                case "AccountOpened" ->
                        new Account(accountId, (String) e.get("owner"), balance, true, version + 1);
                case "MoneyDeposited" ->
                        new Account(accountId, owner, balance + amount(e), isOpen, version + 1);
                case "MoneyWithdrawn" ->
                        new Account(accountId, owner, balance - amount(e), isOpen, version + 1);
                case "DepositReversed" ->                              // the compensating event
                        new Account(accountId, owner, balance - amount(e), isOpen, version + 1);
                default ->
                        new Account(accountId, owner, balance, isOpen, version + 1);   // unknown events: tolerated
            };
        }

        private static double amount(Map<String, Object> e) {
            return ((Number) e.get("amount")).doubleValue();
        }

        static Loaded load(EventStore store, String accountId) {
            return load(store, accountId, false, null);
        }

        /** Replay the stream (optionally from a snapshot / up to a past version).
         *  Returns (state, eventsReplayed) so the lab can SHOW the replay cost. */
        static Loaded load(EventStore store, String accountId, boolean useSnapshot, Integer asOfVersion) {
            String stream = "account-" + accountId;
            Account acct = new Account(accountId);
            int start = 0;
            if (useSnapshot && asOfVersion == null) {
                EventStore.Snapshot snap = store.loadSnapshot(stream);
                if (snap != null) {
                    Map<String, Object> state = snap.state();
                    acct = new Account(accountId, (String) state.get("owner"),
                            ((Number) state.get("balance")).doubleValue(),
                            (Boolean) state.get("is_open"), snap.version());
                    start = snap.version();
                }
            }
            List<Recorded> events = store.read(stream, start, asOfVersion);
            for (Recorded e : events) acct = acct.apply(e.type(), e.payload());
            return new Loaded(acct, events.size());
        }

        // -- decide: command -> events (or rejection). Validation lives HERE,
        //    against the replayed state. Never mutates; only proposes facts.
        List<Event> decideOpen(String owner) {
            if (isOpen) throw new CommandRejected("account already open");
            return List.of(new Event("AccountOpened", payload("owner", owner)));
        }

        List<Event> decideDeposit(double amount) { return decideDeposit(amount, "app"); }

        List<Event> decideDeposit(double amount, String channel) {
            if (!isOpen) throw new CommandRejected("no such account");
            if (amount <= 0) throw new CommandRejected("deposit must be positive");
            return List.of(new Event("MoneyDeposited", payload("amount", amount, "channel", channel)));
        }

        List<Event> decideWithdraw(double amount) { return decideWithdraw(amount, "app"); }

        List<Event> decideWithdraw(double amount, String channel) {
            if (!isOpen) throw new CommandRejected("no such account");
            if (amount <= 0) throw new CommandRejected("withdrawal must be positive");
            if (amount > balance)
                throw new CommandRejected("insufficient funds: balance " + balance + ", requested " + amount);
            return List.of(new Event("MoneyWithdrawn", payload("amount", amount, "channel", channel)));
        }
    }

    // ----------------------------------------------------------------------
    // The command handler: load -> decide -> append(expectedVersion) -> retry.
    // ----------------------------------------------------------------------
    /** A command, expressed the only way an event-sourced system allows: as a
     *  function from replayed state to proposed facts. */
    interface Decider {
        List<Event> decide(Account account);
    }

    static int handle(EventStore store, String accountId, Decider decide) {
        return handle(store, accountId, decide, 3);
    }

    /** The universal event-sourced write path. */
    static int handle(EventStore store, String accountId, Decider decide, int retries) {
        for (int attempt = 0; ; attempt++) {
            Account acct = Account.load(store, accountId).account();
            List<Event> events = decide.decide(acct);            // may throw CommandRejected
            try {
                return store.append("account-" + accountId, acct.version(), events);
            } catch (ConcurrencyConflict conflict) {
                if (attempt == retries) throw conflict;
                // loop: reload the fresher state and re-decide — the invariant is
                // re-checked against what ACTUALLY happened meanwhile.
            }
        }
    }

    static final int SNAPSHOT_EVERY = 100;

    static void maybeSnapshot(EventStore store, String accountId) {
        String stream = "account-" + accountId;
        int version = store.streamVersion(stream);
        EventStore.Snapshot snap = store.loadSnapshot(stream);
        if (version - (snap != null ? snap.version() : 0) >= SNAPSHOT_EVERY) {
            Account acct = Account.load(store, accountId).account();
            store.saveSnapshot(stream, acct.version(),
                    payload("owner", acct.owner(), "balance", acct.balance(), "is_open", acct.isOpen()));
        }
    }

    // ----------------------------------------------------------------------
    // Payloads. A hand-rolled codec for flat, escape-free maps keeps H2 the
    // only dependency; a real system would reach for a JSON library here.
    // ----------------------------------------------------------------------
    /** Map literal: payload("amount", 100.0, "channel", "app"). Insertion-ordered. */
    static Map<String, Object> payload(Object... keysAndValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) m.put((String) keysAndValues[i], keysAndValues[i + 1]);
        return m;
    }

    static String toJson(Map<String, Object> payload) {
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            if (sb.length() > 1) sb.append(", ");
            sb.append('"').append(e.getKey()).append("\": ");
            if (e.getValue() instanceof String s) sb.append('"').append(s).append('"');
            else sb.append(e.getValue());
        }
        return sb.append('}').toString();
    }

    static Map<String, Object> fromJson(String json) {
        Map<String, Object> out = new LinkedHashMap<>();
        int i = json.indexOf('{') + 1;
        while (i < json.length() && json.charAt(i) != '}') {
            int keyStart = json.indexOf('"', i) + 1;
            if (keyStart == 0) break;
            int keyEnd = json.indexOf('"', keyStart);
            String key = json.substring(keyStart, keyEnd);
            int v = json.indexOf(':', keyEnd) + 1;
            while (json.charAt(v) == ' ') v++;
            if (json.charAt(v) == '"') {
                int end = json.indexOf('"', v + 1);
                out.put(key, json.substring(v + 1, end));
                i = end + 1;
            } else {
                int end = v;
                while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
                String literal = json.substring(v, end).trim();
                if (literal.equals("true") || literal.equals("false")) out.put(key, Boolean.valueOf(literal));
                else if (literal.contains(".")) out.put(key, Double.valueOf(literal));
                else out.put(key, Integer.valueOf(literal));
                i = end;
            }
            while (i < json.length() && (json.charAt(i) == ' ' || json.charAt(i) == ',')) i++;
        }
        return out;
    }

    /** How the demo has always printed payloads and streams; the transcripts in
     *  README.md quote these lines, so the format is part of the lesson. */
    static String render(Map<String, Object> payload) {
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            if (sb.length() > 1) sb.append(", ");
            sb.append('\'').append(e.getKey()).append("': ");
            if (e.getValue() instanceof String s) sb.append('\'').append(s).append('\'');
            else sb.append(e.getValue());
        }
        return sb.append('}').toString();
    }

    static String render(List<Recorded> events) {
        StringBuilder sb = new StringBuilder("[");
        for (Recorded e : events) {
            if (sb.length() > 1) sb.append(", ");
            sb.append('(').append(e.version()).append(", '").append(e.type()).append("')");
        }
        return sb.append(']').toString();
    }

    // ----------------------------------------------------------------------
    // Narrated demo
    // ----------------------------------------------------------------------
    /**
     * Remove the H2 files this lab created, so a finished run leaves the
     * directory as it found it. Correctness does not depend on this — the
     * EventStore constructor drops and recreates its tables, so every run
     * starts from an empty book either way. This is housekeeping.
     */
    static void deleteDatabaseFiles() {
        new java.io.File("./eventstore.mv.db").delete();
        new java.io.File("./eventstore.trace.db").delete();
    }

    public static void main(String[] args) {
        Runtime.getRuntime().addShutdownHook(new Thread(EsLab::deleteDatabaseFiles));
        try (EventStore store = new EventStore()) {

            System.out.println("== 1. State is a replay, not a row ==");
            handle(store, "7", a -> a.decideOpen("priya"));
            handle(store, "7", a -> a.decideDeposit(100.0));
            handle(store, "7", a -> a.decideWithdraw(30.0));
            Loaded loaded = Account.load(store, "7");
            Account acct = loaded.account();
            System.out.println("  events: " + render(store.read("account-7")));
            System.out.println("  balance = " + acct.balance() + " (computed by replaying "
                    + loaded.replayed() + " events; no balance column exists)");

            System.out.println();
            System.out.println("== 2. Time travel: the state at ANY past version ==");
            for (int v = 1; v <= acct.version(); v++) {
                Account past = Account.load(store, "7", /* useSnapshot */ false, /* asOfVersion */ v).account();
                System.out.println("  as of version " + v + ": balance = " + past.balance());
            }

            System.out.println();
            System.out.println("== 3. The invariant is checked against replayed truth ==");
            try {
                handle(store, "7", a -> a.decideWithdraw(1000.0));
            } catch (CommandRejected e) {
                System.out.println("  withdraw 1000 rejected: " + e.getMessage());
            }

            System.out.println();
            System.out.println("== 4. Two tellers, one account: optimistic concurrency ==");
            Account a1 = Account.load(store, "7").account();   // teller 1 loads version 3 (balance 70)
            Account a2 = Account.load(store, "7").account();   // teller 2 loads the same version 3
            store.append("account-7", a1.version(), a1.decideWithdraw(50.0));
            System.out.println("  teller 1: withdrew 50 at expected_version=" + a1.version()
                    + " — OK (balance 70 -> 20)");
            try {
                store.append("account-7", a2.version(), a2.decideWithdraw(40.0));
            } catch (ConcurrencyConflict e) {
                System.out.println("  teller 2's blind append: REJECTED — " + e.getMessage());
            }
            try {
                handle(store, "7", a -> a.decideWithdraw(40.0));    // retry loop: reload + RE-DECIDE
            } catch (CommandRejected e) {
                System.out.println("  teller 2's retry re-decided against FRESH state and the rule said no: "
                        + e.getMessage());
            }
            acct = Account.load(store, "7").account();
            System.out.println("  balance stands at " + acct.balance()
                    + ". Without expected_version, both withdrawals");
            System.out.println("  would have 'succeeded' against the stale balance of 70 — overdrawing to -20.");

            System.out.println();
            System.out.println("== 5. Mistakes are corrected, never erased ==");
            handle(store, "7", a -> List.of(new Event("MoneyDeposited",
                    payload("amount", 500.0, "channel", "wire"))));
            System.out.println("  a 500 deposit landed in the wrong account. UPDATE? DELETE? Neither exists.");
            handle(store, "7", a -> List.of(new Event("DepositReversed",
                    payload("amount", 500.0, "reason", "posted to wrong account",
                            "reverses_version", a.version()))));
            acct = Account.load(store, "7").account();
            System.out.println("  a DepositReversed event compensates: balance back to " + acct.balance() + ";");
            System.out.println("  the audit trail keeps BOTH facts — the mistake and its correction.");

            System.out.println();
            System.out.println("== 6. Snapshots: replay cost, capped ==");
            for (int i = 0; i < 250; i++) {
                handle(store, "7", a -> a.decideDeposit(1.0));
                maybeSnapshot(store, "7");
            }
            int replayedFull = Account.load(store, "7").replayed();
            Loaded snapLoaded = Account.load(store, "7", /* useSnapshot */ true, /* asOfVersion */ null);
            Account fullAcct = Account.load(store, "7").account();
            System.out.println("  stream has " + store.streamVersion("account-7") + " events");
            System.out.println("  cold load replays " + replayedFull
                    + "; snapshot load replays only " + snapLoaded.replayed());
            System.out.println("  identical state either way: " + snapLoaded.account().balance()
                    + " == " + fullAcct.balance());

            System.out.println();
            System.out.println("== 7. Old events, new code: upcasting ==");
            store.append("account-9", 0, List.of(new Event("AccountOpened", payload("owner", "amit"))));
            store.append("account-9", 1, List.of(new Event("MoneyDeposited",
                    payload("amount", 10.0))), 1);                          // 2019-era event
            List<Recorded> stream9 = store.read("account-9");
            Map<String, Object> old = stream9.get(stream9.size() - 1).payload();
            System.out.println("  a schema-v1 event (no 'channel' field) reads back as: " + render(old));
            System.out.println("  history was never migrated; it is upgraded in memory, on read.");
        }
    }
}
