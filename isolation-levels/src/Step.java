import java.sql.*;
import java.util.*;

/**
 * One action taken by one transaction, at one moment in a scripted interleaving.
 * `obs` gives the action access to what earlier steps saw — which is what makes
 * a genuine read-modify-write ("add 10 to the number I read") expressible.
 */
interface Act { String run(Connection c, Map<String, String> obs) throws SQLException; }

record Step(int txn, String label, Act act) {

    static Step sql(int txn, String label, String sql) {
        return new Step(txn, label, (c, o) -> { Db.exec(c, sql); return "ok"; });
    }
    static Step query(int txn, String label, String sql) {
        return new Step(txn, label, (c, o) -> Db.one(c, sql));
    }
    /** A statement built from something an earlier step read. */
    static Step using(int txn, String label, String from, java.util.function.Function<String, String> sql) {
        return new Step(txn, label, (c, o) -> { Db.exec(c, sql.apply(o.get(from))); return "ok"; });
    }
    static Step commit(int txn)   { return new Step(txn, "T" + txn + " COMMIT",   (c, o) -> { c.commit();   return "committed"; }); }
    static Step rollback(int txn) { return new Step(txn, "T" + txn + " ROLLBACK", (c, o) -> { c.rollback(); return "rolled back"; }); }
}
