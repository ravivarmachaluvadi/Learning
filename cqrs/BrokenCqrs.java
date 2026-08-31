import java.util.List;

/**
 * DEBUG EXERCISE D1 — finance reports negative revenue on quiet days.
 *
 * Someone "cleaned up" the OrderCancelled projection. It passes a quick manual
 * test (place, cancel, look: revenue 0 — correct!). In production, weeks
 * later, daily_revenue occasionally goes NEGATIVE. The event feed redelivers
 * sometimes, like every real feed.
 *
 * Find the bug. It is an ordering bug of two lines. Which test in CqrsTest.java
 * would have caught it? (Answer in SOLUTIONS.md.)
 *
 * <pre>
 *   java -cp ../lib/h2.jar BrokenCqrs.java
 * </pre>
 */
public class BrokenCqrs {

    static class CleanedUpProjector extends CqrsLab.Projector {

        CleanedUpProjector(CqrsLab.WriteSide write, CqrsLab.ReadSide read) {
            super(write, read);
        }

        @Override
        void apply(String type, CqrsLab.Payload e) {
            CqrsLab.Db db = read.db;
            if (type.equals("OrderCancelled")) {
                // refactored version: "do the revenue math up front, then the row update"
                db.update("UPDATE daily_revenue SET revenue = revenue - ? WHERE \"day\"=?",
                        e.num("total"), e.str("day"));
                db.update("UPDATE customer_dashboard SET status='CANCELLED' "
                        + "WHERE order_id=? AND status!='CANCELLED'", e.str("order_id"));
            } else {
                super.apply(type, e);
            }
        }
    }

    public static void main(String[] args) {
        CqrsLab.WriteSide w = new CqrsLab.WriteSide();
        CqrsLab.ReadSide r = new CqrsLab.ReadSide();
        CqrsLab.Projector p = new CleanedUpProjector(w, r);

        w.placeOrder("ord-1", "priya", List.of(new CqrsLab.Item("keyboard", 1, 80.0)), "2026-08-27");
        w.cancelOrder("ord-1", "2026-08-27");
        p.runOnce();
        System.out.println("after place+cancel:              revenue = " + r.revenue("2026-08-27") + "   (looks right!)");

        // ...weeks later, the feed redelivers, as at-least-once feeds do:
        r.db.update("UPDATE projector_checkpoint SET last_seq=0"); r.db.commit();
        p.runOnce();
        System.out.println("after a routine redelivery:      revenue = " + r.revenue("2026-08-27") + "   <- NEGATIVE?!");
    }
}
