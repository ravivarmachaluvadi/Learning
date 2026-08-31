import java.util.List;

/**
 * DEBUG EXERCISE D1 — "our retry logic is battle-tested: it never fails."
 *
 * Someone found ConcurrencyConflict annoying, so they wrote a handler that
 * "just retries harder": on conflict it fetches the CURRENT stream version and
 * appends the same events again. No failed requests! The bank is delighted —
 * until the auditors find accounts below zero.
 *
 * Reproduce it, explain the exact sequence, name the test in EsTest.java that
 * pins the correct behavior, and fix the handler. (Answers in SOLUTIONS.md.)
 *
 * Run:  java -cp ../lib/h2.jar BrokenEs.java
 */
public class BrokenEs {

    /** Load once, decide once — and on conflict, slam the same events in at
     *  whatever version the stream is at now. What could go wrong? */
    static int handleWithMoreDetermination(EsLab.EventStore store, String accountId, EsLab.Decider decide) {
        String stream = "account-" + accountId;
        EsLab.Account acct = EsLab.Account.load(store, accountId).account();
        List<EsLab.Event> events = decide.decide(acct);    // decided against the ORIGINAL state...
        while (true) {
            try {
                return store.append(stream, acct.version(), events);
            } catch (EsLab.ConcurrencyConflict conflict) {
                acct = new EsLab.Account(accountId, "", 0.0, false, store.streamVersion(stream));  // ...never re-decided
            }
        }
    }

    public static void main(String[] args) {
        // Leave the directory as we found it (see EsLab.deleteDatabaseFiles).
        Runtime.getRuntime().addShutdownHook(new Thread(EsLab::deleteDatabaseFiles));
        try (EsLab.EventStore store = new EsLab.EventStore()) {
            EsLab.handle(store, "7", a -> a.decideOpen("priya"));
            EsLab.handle(store, "7", a -> a.decideDeposit(70.0));

            // two tellers, both see balance 70, both withdraw 50 "successfully":
            EsLab.Account t1 = EsLab.Account.load(store, "7").account();
            List<EsLab.Event> t2EventsDecidedAtV2 =
                    EsLab.Account.load(store, "7").account().decideWithdraw(50.0);
            store.append("account-7", t1.version(), t1.decideWithdraw(50.0));
            handleWithMoreDetermination(store, "7", a -> t2EventsDecidedAtV2);

            EsLab.Account acct = EsLab.Account.load(store, "7").account();
            System.out.println("two withdrawals of 50 from a balance of 70 both 'succeeded'.");
            System.out.println("balance = " + acct.balance()
                    + "   <- the overdraft rule exists... and was never consulted the second time");
        }
    }
}
