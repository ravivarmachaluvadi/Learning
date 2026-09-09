/** Exercise P1 — predict all four lines before running! (see EXERCISES.md)
 *  Run:  java src/P1.java */
public class P1 {
    static final ScopedValue<String> CTX = ScopedValue.newInstance();

    public static void main(String[] args) throws Exception {
        ScopedValue.where(CTX, "A").run(() -> {
            ScopedValue.where(CTX, "B").run(() ->
                System.out.println("1: " + CTX.get()));
            System.out.println("2: " + CTX.get());
            Thread t = Thread.ofVirtual().start(() ->
                System.out.println("3: " + CTX.orElse("none")));
            try { t.join(); } catch (InterruptedException e) { }
        });
        System.out.println("4: " + CTX.orElse("none"));
    }
}
