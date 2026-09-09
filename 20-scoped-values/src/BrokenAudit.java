/**
 * DEBUG EXERCISE D1 — "the audit log worked in the unit test and crashes
 * in production."
 *
 * The request binds REQUEST_ID and, to keep the request fast, hands the
 * audit write to a background executor (fire-and-forget). The audit lambda
 * reads REQUEST_ID... sometimes AFTER the request is over.
 *
 * Run it, read the stack trace, explain exactly why this cannot work, and
 * fix it WITHOUT making the audit write synchronous. (SOLUTIONS.md)
 *
 * Run:  java src/BrokenAudit.java
 */
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BrokenAudit {

    static final ScopedValue<String> REQUEST_ID = ScopedValue.newInstance();
    static final ExecutorService auditPool = Executors.newSingleThreadExecutor();

    public static void main(String[] args) throws Exception {
        ScopedValue.where(REQUEST_ID, "req-7f3a").run(BrokenAudit::handleRequest);
        System.out.println("request finished, response sent.");
        Thread.sleep(300);                       // let the background write run
        auditPool.shutdown();
    }

    static void handleRequest() {
        System.out.println("handling " + REQUEST_ID.get() + "; queueing audit write in the background...");
        auditPool.execute(() -> {
            sleep(150);                          // the queue was busy for a moment
            // The lambda runs on the audit thread, later. What is bound HERE?
            System.out.println("audit: writing entry for " + REQUEST_ID.get());
        });
    }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}
