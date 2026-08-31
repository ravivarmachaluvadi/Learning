// YourResolver.java — EXERCISES rung 5: implement the percolator read rule yourself.
//
// You are a reader at snapshot snapTs. You just hit a LOCK on the key you want.
// The dead-or-alive client who left it cannot help you. Decide what to do using
// only what you can observe. Fill in decide(), then run:
//
//   java src/YourResolver.java      (self-checks 6 scenarios; the blank version scores 1/6)
//
// The real rule you are reimplementing lives in Engines.resolveAndRead — don't peek
// until you've tried. TTL for an abandoned lock is 30 ticks.
public class YourResolver {

  enum Action {
    READ_BELOW_SNAPSHOT,     // ignore the lock; return the newest committed version <= snapTs
    ROLL_FORWARD_THEN_READ,  // finish the dead client's commit on this key, then read
    WAIT_AND_RETRY,          // someone is mid-commit; ask again in a moment
    ROLL_BACK_THEN_READ      // the txn is dead and never committed; clean its mess, then read
  }

  static final long TTL = 30;

  /**
   * @param lockStartTs     the startTs inside the lock on OUR key
   * @param snapTs          our snapshot timestamp
   * @param primaryCommitTs the primary's commitRecord entry for lockStartTs, or null if absent
   * @param primaryLockHeld is the primary key still locked by that same startTs?
   * @param lockAgeTicks    how long ago the lock on our key was created
   */
  static Action decide(long lockStartTs, long snapTs, Long primaryCommitTs,
                       boolean primaryLockHeld, long lockAgeTicks) {
    // ======== YOUR CODE HERE (replace the line below) ========
    return Action.WAIT_AND_RETRY;
    // =========================================================
  }

  // ---------------- self-check ----------------
  record Case(String story, long lockStart, long snap, Long cts, boolean pLock, long age, Action want) {}

  public static void main(String[] args) {
    Case[] cases = {
      new Case("lock's txn STARTED at ts 12, my snapshot is 10 — its commit must land above 12",
               12, 10, null, true, 3, Action.READ_BELOW_SNAPSHOT),
      new Case("primary says: committed at ts 8; my snapshot is 10",
               5, 10, 8L, false, 3, Action.ROLL_FORWARD_THEN_READ),
      new Case("primary says: committed at ts 12; my snapshot is 10",
               5, 10, 12L, false, 3, Action.READ_BELOW_SNAPSHOT),
      new Case("no commit record, primary still locked, lock is 3 ticks old",
               5, 10, null, true, 3, Action.WAIT_AND_RETRY),
      new Case("no commit record, primary still locked, lock is 100 ticks old",
               5, 10, null, true, 100, Action.ROLL_BACK_THEN_READ),
      new Case("no commit record AND the primary lock is gone",
               5, 10, null, false, 3, Action.ROLL_BACK_THEN_READ),
    };
    int ok = 0;
    for (Case c : cases) {
      Action got = decide(c.lockStart, c.snap, c.cts, c.pLock, c.age);
      boolean right = got == c.want;
      if (right) ok++;
      System.out.printf("  %s %s%n      you: %-22s expected: %s%n",
          right ? "ok " : "X  ", c.story, got, right ? "(right)" : c.want);
    }
    System.out.println("\n  " + ok + "/6 correct");
    if (ok < 6) System.out.println("  Hints: WHERE does the verdict live? WHEN is a lock none of your business?\n"
        + "  And what is the only situation where patience is the right answer?");
    else System.out.println("  You just implemented the heart of Percolator / TiDB's read path.");
  }
}
