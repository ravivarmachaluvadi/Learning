/**
 * DEMO 2 — Rebinding: shadow for the sub-call, restored automatically.
 *
 * The request runs as user "ravi". One step — an audit write — must run as
 * "system". We REBIND for exactly that call. There is no set(): you cannot
 * change a binding, only run more code under a different one. When the
 * sub-call ends (even by exception!), the old value is simply there again.
 *
 * Run:  java src/Rebinding.java
 */
public class Rebinding {

    static final ScopedValue<String> USER = ScopedValue.newInstance();

    public static void main(String[] args) {
        ScopedValue.where(USER, "ravi").run(() -> {
            System.out.println("request        : running as " + USER.get());

            ScopedValue.where(USER, "system").run(() ->
                System.out.println("  audit write  : running as " + USER.get() + "   (inner binding shadows the outer)"));

            System.out.println("request        : running as " + USER.get() + "   (outer binding restored, nothing to undo)");

            try {
                ScopedValue.where(USER, "system").run(() -> {
                    System.out.println("  audit write 2: running as " + USER.get() + ", about to crash...");
                    throw new RuntimeException("audit table locked");
                });
            } catch (RuntimeException e) {
                System.out.println("request        : caught '" + e.getMessage() + "', and USER is already back to: " + USER.get());
            }
        });
        System.out.println("\nCompare with ThreadLocal, where this needs: Object old = TL.get(); TL.set(sys);");
        System.out.println("try { ... } finally { TL.set(old); }  — four lines, and everyone must remember all four.");
    }
}
