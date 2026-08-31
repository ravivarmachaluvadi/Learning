/**
 * DEMO 1 — Context that travels with the call, not with the arguments.
 *
 * A web request arrives; we know the request-id and the user at the top.
 * Three layers down, the repository wants them for logging — and NO method
 * in between mentions them. One binding at the top; get() anywhere below.
 *
 * Run:  java src/BasicFlow.java          (ScopedValue is FINAL in JDK 25 — no flags)
 */
public class BasicFlow {

    static final ScopedValue<String> REQUEST_ID = ScopedValue.newInstance();
    static final ScopedValue<String> USER = ScopedValue.newInstance();

    public static void main(String[] args) {
        System.out.println("Before any request: REQUEST_ID.isBound() = " + REQUEST_ID.isBound());
        try {
            REQUEST_ID.get();
        } catch (java.util.NoSuchElementException e) {
            System.out.println("Reading it anyway -> NoSuchElementException (unbound is an ERROR, not null)\n");
        }

        // The top of the request: bind once, run the whole request inside.
        ScopedValue.where(REQUEST_ID, "req-7f3a")
                   .where(USER, "ravi")
                   .run(BasicFlow::handleRequest);

        System.out.println("\nAfter the request: REQUEST_ID.isBound() = " + REQUEST_ID.isBound());
        System.out.println("The binding did not need a remove()/cleanup call — leaving the block IS the cleanup.");
        System.out.println("\norElse for optional context: theme = " +
                ScopedValue.newInstance().orElse("default-theme"));
    }

    // --- three layers, none of which passes requestId/user along ------------
    static void handleRequest()  { validate(); }
    static void validate()       { loadFromRepository(); }
    static void loadFromRepository() {
        System.out.printf("repository: SELECT ... /* request=%s user=%s */%n", REQUEST_ID.get(), USER.get());
        System.out.println("repository: three layers deep, zero parameters passed — the binding rode the call stack.");
    }
}
