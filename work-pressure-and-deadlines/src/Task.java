/** One task. trueDays is reality; nobody in the project can see it. */
final class Task {
    final int id; final double trueDays; final boolean rework;
    double remaining; int startedDay = -1; int doneDay = -1;
    Task(int id, double trueDays, boolean rework) {
        this.id = id; this.trueDays = trueDays; this.remaining = trueDays; this.rework = rework;
    }
}
