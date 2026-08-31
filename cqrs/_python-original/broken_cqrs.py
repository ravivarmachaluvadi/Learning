"""
DEBUG EXERCISE D1 — finance reports negative revenue on quiet days.

Someone "cleaned up" the OrderCancelled projection. It passes a quick manual
test (place, cancel, look: revenue 0 — correct!). In production, weeks
later, daily_revenue occasionally goes NEGATIVE. The event feed redelivers
sometimes, like every real feed.

Find the bug. It is an ordering bug of two lines. Which test in test_cqrs.py
would have caught it? (Answer in SOLUTIONS.md.)

Run:  python3 broken_cqrs.py
"""
from cqrs_lab import Projector, ReadSide, WriteSide


class CleanedUpProjector(Projector):
    def _apply(self, type_: str, e: dict) -> None:
        db = self.read.db
        if type_ == "OrderCancelled":
            # refactored version: "do the revenue math up front, then the row update"
            db.execute("UPDATE daily_revenue SET revenue = revenue - ? WHERE day=?",
                       (e["total"], e["day"]))
            db.execute("UPDATE customer_dashboard SET status='CANCELLED' "
                       "WHERE order_id=? AND status!='CANCELLED'", (e["order_id"],))
        else:
            super()._apply(type_, e)


if __name__ == "__main__":
    w, r = WriteSide(), ReadSide()
    p = CleanedUpProjector(w, r)

    w.place_order("ord-1", "priya", [("keyboard", 1, 80.0)], day="2026-08-27")
    w.cancel_order("ord-1", day="2026-08-27")
    p.run_once()
    print(f"after place+cancel:              revenue = {r.revenue('2026-08-27')}   (looks right!)")

    # ...weeks later, the feed redelivers, as at-least-once feeds do:
    r.db.execute("UPDATE projector_checkpoint SET last_seq=0"); r.db.commit()
    p.run_once()
    print(f"after a routine redelivery:      revenue = {r.revenue('2026-08-27')}   <- NEGATIVE?!")
