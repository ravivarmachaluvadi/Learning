/**
 * DEMO 4 — Which threads inherit a binding?
 *
 * Rule: bindings flow into subtasks forked in a StructuredTaskScope
 * (the child is part of the same request — see the structured-concurrency
 * lab), and do NOT flow into threads you start by hand (the JVM cannot
 * know how long those live, and an inherited binding must not outlive
 * its scope).
 *
 * Run:  java --enable-preview --source 25 src/Inheritance.java
 *       (the flags are for StructuredTaskScope, not for ScopedValue)
 */
import java.util.concurrent.StructuredTaskScope;

public class Inheritance {

    static final ScopedValue<String> REQUEST_ID = ScopedValue.newInstance();

    public static void main(String[] args) {
        ScopedValue.where(REQUEST_ID, "req-42").run(() -> {
            System.out.println("owner thread        : REQUEST_ID = " + REQUEST_ID.get());

            try (var scope = StructuredTaskScope.<Void>open()) {
                scope.fork(() -> {
                    System.out.println("structured fork     : REQUEST_ID = "
                            + (REQUEST_ID.isBound() ? REQUEST_ID.get() : "(not bound)")
                            + "   <- inherited: the fork lives INSIDE the binding's scope");
                    return null;
                });
                scope.join();
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            Thread bare = Thread.ofVirtual().start(() ->
                System.out.println("hand-started thread : REQUEST_ID is bound? " + REQUEST_ID.isBound()
                        + "           <- NOT inherited: this thread could outlive the request"));
            try { bare.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
    }
}
