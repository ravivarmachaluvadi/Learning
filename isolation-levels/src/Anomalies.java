import java.util.*;

/**
 * The eight things that can go wrong when two transactions touch the same data.
 *
 * Every one of them is two people doing something individually reasonable. None
 * of them involves a crash, a bug, or a network problem. That is what makes them
 * hard: the database did exactly what it was asked, twice.
 */
final class Anomalies {

    private static final String[] ACCOUNTS = {
        "DROP TABLE IF EXISTS acct",
        "CREATE TABLE acct(id INT PRIMARY KEY, name VARCHAR(20), balance INT)",
        "INSERT INTO acct VALUES (1, 'alice', 100)",
        "INSERT INTO acct VALUES (2, 'bob', 100)"
    };

    /** T2 scribbles over a change T1 has not committed yet — and T1 then takes it back. */
    static Scenario dirtyWrite() {
        return new Scenario("dirty-write", "Dirty write",
            "Two clerks edit Alice's balance at the same time; the first one then changes their mind.",
            "The first clerk's undo does not undo — the balance keeps a number nobody agreed on.",
            ACCOUNTS,
            List.of(
                Step.sql(1, "T1 set alice = 110", "UPDATE acct SET balance = 110 WHERE id = 1"),
                Step.sql(2, "T2 set alice = 120", "UPDATE acct SET balance = 120 WHERE id = 1"),
                Step.rollback(1),
                Step.commit(2)),
            "SELECT balance FROM acct WHERE id = 1",
            // The anomaly is T2 being ALLOWED to overwrite an uncommitted row without waiting.
            r -> r.outcomes.stream().anyMatch(o ->
                    o.label().equals("T2 set alice = 120") && o.kind() == Runner.Kind.OK));
    }

    /** T2 reads a number that never officially existed. */
    static Scenario dirtyRead() {
        return new Scenario("dirty-read", "Dirty read",
            "A clerk types a wrong balance, a report runs, then the clerk undoes the typo.",
            "The report contains a number that was never true.",
            ACCOUNTS,
            List.of(
                Step.sql(1, "T1 set alice = 999", "UPDATE acct SET balance = 999 WHERE id = 1"),
                Step.query(2, "T2 read alice", "SELECT balance FROM acct WHERE id = 1"),
                Step.rollback(1),
                Step.commit(2)),
            "SELECT balance FROM acct WHERE id = 1",
            r -> "999".equals(r.get("T2 read alice")));
    }

    /** T1 asks the same question twice and gets two different answers. */
    static Scenario nonRepeatableRead() {
        return new Scenario("non-repeatable-read", "Non-repeatable read",
            "A report reads Alice's balance, then reads it again a moment later to double-check.",
            "The two reads disagree, so the report contradicts itself.",
            ACCOUNTS,
            List.of(
                Step.query(1, "T1 read alice (first)", "SELECT balance FROM acct WHERE id = 1"),
                Step.sql(2, "T2 set alice = 200", "UPDATE acct SET balance = 200 WHERE id = 1"),
                Step.commit(2),
                Step.query(1, "T1 read alice (again)", "SELECT balance FROM acct WHERE id = 1"),
                Step.commit(1)),
            "SELECT balance FROM acct WHERE id = 1",
            r -> !Objects.equals(r.get("T1 read alice (first)"), r.get("T1 read alice (again)")));
    }

    /** Same question, same two reads — but this time a whole new ROW appears. */
    static Scenario phantomRead() {
        return new Scenario("phantom-read", "Phantom read",
            "A report counts every account holding more than 50, twice.",
            "A row that did not exist for the first count is there for the second.",
            ACCOUNTS,
            List.of(
                Step.query(1, "T1 count rich (first)", "SELECT COUNT(*) FROM acct WHERE balance > 50"),
                Step.sql(2, "T2 insert carol", "INSERT INTO acct VALUES (3, 'carol', 500)"),
                Step.commit(2),
                Step.query(1, "T1 count rich (again)", "SELECT COUNT(*) FROM acct WHERE balance > 50"),
                Step.commit(1)),
            "SELECT COUNT(*) FROM acct WHERE balance > 50",
            r -> !Objects.equals(r.get("T1 count rich (first)"), r.get("T1 count rich (again)")));
    }

    /** Both clients read 100, both add 10, and the account ends on 110. */
    static Scenario lostUpdate() {
        return new Scenario("lost-update", "Lost update",
            "Two deposits of 10 arrive for Alice at the same time. Each one reads the balance, adds 10, and writes it back.",
            "One deposit vanishes: the balance ends at 110 instead of 120, and no error is raised anywhere.",
            ACCOUNTS,
            List.of(
                Step.query(1, "T1 read alice", "SELECT balance FROM acct WHERE id = 1"),
                Step.query(2, "T2 read alice", "SELECT balance FROM acct WHERE id = 1"),
                Step.using(1, "T1 write read+10", "T1 read alice",
                        v -> "UPDATE acct SET balance = " + (Integer.parseInt(v) + 10) + " WHERE id = 1"),
                Step.using(2, "T2 write read+10", "T2 read alice",
                        v -> "UPDATE acct SET balance = " + (Integer.parseInt(v) + 10) + " WHERE id = 1"),
                Step.commit(1),
                Step.commit(2)),
            "SELECT balance FROM acct WHERE id = 1",
            r -> "110".equals(r.finalState));
    }

    /** T1 reads two rows either side of someone else's transfer and sees money that is not there. */
    static Scenario readSkew() {
        return new Scenario("read-skew", "Read skew",
            "An audit reads Alice's balance, then Bob's. Between the two reads, 50 moves from Alice to Bob.",
            "The audit adds them up and gets 250 instead of 200 — money that exists in no account.",
            ACCOUNTS,
            List.of(
                Step.query(1, "T1 read alice", "SELECT balance FROM acct WHERE id = 1"),
                Step.sql(2, "T2 alice -= 50", "UPDATE acct SET balance = balance - 50 WHERE id = 1"),
                Step.sql(2, "T2 bob += 50", "UPDATE acct SET balance = balance + 50 WHERE id = 2"),
                Step.commit(2),
                Step.query(1, "T1 read bob", "SELECT balance FROM acct WHERE id = 2"),
                Step.commit(1)),
            "SELECT SUM(balance) FROM acct",
            r -> r.intOf("T1 read alice") + r.intOf("T1 read bob") != 200);
    }

    /**
     * Nobody touches the same row, and the rule is still broken. This is the one
     * that snapshot isolation cannot see, and the reason SERIALIZABLE exists.
     */
    static Scenario writeSkew() {
        return new Scenario("write-skew", "Write skew",
            "Two doctors are on call. The rule is that at least one must stay. Each checks the rule, sees the other is on call, and signs off.",
            "Both sign off. Nobody is on call, and each doctor's decision was individually correct.",
            new String[]{
                "DROP TABLE IF EXISTS doctors",
                "CREATE TABLE doctors(id INT PRIMARY KEY, name VARCHAR(20), on_call BOOLEAN)",
                "INSERT INTO doctors VALUES (1, 'alice', TRUE)",
                "INSERT INTO doctors VALUES (2, 'bob', TRUE)"},
            List.of(
                Step.query(1, "T1 count on call", "SELECT COUNT(*) FROM doctors WHERE on_call = TRUE"),
                Step.query(2, "T2 count on call", "SELECT COUNT(*) FROM doctors WHERE on_call = TRUE"),
                Step.sql(1, "T1 alice signs off", "UPDATE doctors SET on_call = FALSE WHERE id = 1"),
                Step.sql(2, "T2 bob signs off", "UPDATE doctors SET on_call = FALSE WHERE id = 2"),
                Step.commit(1),
                Step.commit(2)),
            "SELECT COUNT(*) FROM doctors WHERE on_call = TRUE",
            r -> "0".equals(r.finalState));
    }

    /** Write skew where the conflict is over rows that do not exist yet. */
    static Scenario phantomWriteSkew() {
        return new Scenario("double-booking", "Write skew on rows that do not exist yet",
            "Two people book meeting room 1 for Monday. Each checks that the slot is free, sees nothing, and books it.",
            "The room is booked twice. The row they conflicted over did not exist when either of them looked.",
            new String[]{
                "DROP TABLE IF EXISTS bookings",
                "CREATE TABLE bookings(id INT PRIMARY KEY, room INT, day_no INT)"},
            List.of(
                Step.query(1, "T1 slot free?", "SELECT COUNT(*) FROM bookings WHERE room = 1 AND day_no = 1"),
                Step.query(2, "T2 slot free?", "SELECT COUNT(*) FROM bookings WHERE room = 1 AND day_no = 1"),
                Step.sql(1, "T1 books it", "INSERT INTO bookings VALUES (101, 1, 1)"),
                Step.sql(2, "T2 books it", "INSERT INTO bookings VALUES (102, 1, 1)"),
                Step.commit(1),
                Step.commit(2)),
            "SELECT COUNT(*) FROM bookings WHERE room = 1 AND day_no = 1",
            r -> "2".equals(r.finalState));
    }

    /** The same race, with the arithmetic done by the database instead of by the application. */
    static Scenario lostUpdateAtomic() {
        return new Scenario("lost-update-atomic", "Lost update, arithmetic in SQL",
            "The same two deposits, but each is a single statement: add 10 to whatever is there.",
            "The balance ends at 110 instead of 120.",
            ACCOUNTS,
            List.of(
                Step.query(1, "T1 read alice", "SELECT balance FROM acct WHERE id = 1"),
                Step.query(2, "T2 read alice", "SELECT balance FROM acct WHERE id = 1"),
                Step.sql(1, "T1 add 10", "UPDATE acct SET balance = balance + 10 WHERE id = 1"),
                Step.sql(2, "T2 add 10", "UPDATE acct SET balance = balance + 10 WHERE id = 1"),
                Step.commit(1),
                Step.commit(2)),
            "SELECT balance FROM acct WHERE id = 1",
            r -> "110".equals(r.finalState));
    }

    /** The same race, with each reader claiming the row before deciding. */
    static Scenario lostUpdateForUpdate() {
        return new Scenario("lost-update-for-update", "Lost update, with SELECT ... FOR UPDATE",
            "The same two deposits, but each one locks the row as it reads it.",
            "The balance ends at 110 instead of 120.",
            ACCOUNTS,
            List.of(
                Step.query(1, "T1 read alice FOR UPDATE", "SELECT balance FROM acct WHERE id = 1 FOR UPDATE"),
                Step.query(2, "T2 read alice FOR UPDATE", "SELECT balance FROM acct WHERE id = 1 FOR UPDATE"),
                Step.using(1, "T1 write read+10", "T1 read alice FOR UPDATE",
                        v -> "UPDATE acct SET balance = " + (Integer.parseInt(v) + 10) + " WHERE id = 1"),
                Step.using(2, "T2 write read+10", "T2 read alice FOR UPDATE",
                        v -> "UPDATE acct SET balance = " + (Integer.parseInt(v) + 10) + " WHERE id = 1"),
                Step.commit(1),
                Step.commit(2)),
            "SELECT balance FROM acct WHERE id = 1",
            r -> "110".equals(r.finalState));
    }

    static List<Scenario> all() {
        return List.of(dirtyWrite(), dirtyRead(), nonRepeatableRead(), phantomRead(),
                       lostUpdate(), lostUpdateAtomic(), lostUpdateForUpdate(),
                       readSkew(), writeSkew(), phantomWriteSkew());
    }
}
