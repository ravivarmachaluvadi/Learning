/** A defect: created on one day, discovered much later, then costs rework. */
final class Defect {
    final int madeDay; final int discoverDay; final double reworkDays;
    Defect(int madeDay, int discoverDay, double reworkDays) {
        this.madeDay = madeDay; this.discoverDay = discoverDay; this.reworkDays = reworkDays;
    }
}
