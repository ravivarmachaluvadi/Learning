/**
 * Executable documentation for scoped values: every rule the README states,
 * asserted against JDK 25 (where ScopedValue is final — JEP 506).
 *
 * Run:  java --enable-preview --source 25 src/ScenarioTests.java
 *       (preview flags are only for the two StructuredTaskScope tests)
 */
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class ScenarioTests {

    static final ScopedValue<String> CTX = ScopedValue.newInstance();
    static final ScopedValue<List<String>> ITEMS = ScopedValue.newInstance();

    public static void main(String[] args) {
        run("value_is_visible_all_the_way_down_the_call_chain", ScenarioTests::value_is_visible_all_the_way_down_the_call_chain);
        run("binding_vanishes_the_moment_the_scope_exits", ScenarioTests::binding_vanishes_the_moment_the_scope_exits);
        run("reading_an_unbound_value_throws_instead_of_returning_null", ScenarioTests::reading_an_unbound_value_throws_instead_of_returning_null);
        run("or_else_gives_a_default_instead_of_throwing", ScenarioTests::or_else_gives_a_default_instead_of_throwing);
        run("rebinding_shadows_for_the_sub_call_then_restores", ScenarioTests::rebinding_shadows_for_the_sub_call_then_restores);
        run("binding_is_restored_even_when_the_inner_block_throws", ScenarioTests::binding_is_restored_even_when_the_inner_block_throws);
        run("each_thread_sees_only_its_own_binding", ScenarioTests::each_thread_sees_only_its_own_binding);
        run("one_carrier_can_bind_several_values_at_once", ScenarioTests::one_carrier_can_bind_several_values_at_once);
        run("bindings_flow_into_structured_forks", ScenarioTests::bindings_flow_into_structured_forks);
        run("bindings_do_not_flow_into_hand_started_threads", ScenarioTests::bindings_do_not_flow_into_hand_started_threads);
        run("the_binding_is_immutable_but_the_object_it_names_is_not", ScenarioTests::the_binding_is_immutable_but_the_object_it_names_is_not);

        System.out.printf("%n%d passed, %d failed.%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    static void value_is_visible_all_the_way_down_the_call_chain() {
        String seen = ScopedValue.where(CTX, "req-1").call(ScenarioTests::layer1);
        assertThat(seen.equals("req-1"), "deep layer saw " + seen);
    }
    static String layer1() { return layer2(); }
    static String layer2() { return layer3(); }
    static String layer3() { return CTX.get(); }

    static void binding_vanishes_the_moment_the_scope_exits() {
        ScopedValue.where(CTX, "req-2").run(() -> assertThat(CTX.isBound(), "bound inside"));
        assertThat(!CTX.isBound(), "still bound after the block — the defining bug of ThreadLocal, reproduced!");
    }

    static void reading_an_unbound_value_throws_instead_of_returning_null() {
        boolean threw = false;
        try { CTX.get(); } catch (NoSuchElementException e) { threw = true; }
        assertThat(threw, "get() on an unbound value returned instead of throwing");
    }

    static void or_else_gives_a_default_instead_of_throwing() {
        assertThat(CTX.orElse("anonymous").equals("anonymous"), "orElse did not supply the default");
        String inside = ScopedValue.where(CTX, "ravi").call(() -> CTX.orElse("anonymous"));
        assertThat(inside.equals("ravi"), "orElse ignored a real binding");
    }

    static void rebinding_shadows_for_the_sub_call_then_restores() {
        ScopedValue.where(CTX, "outer").run(() -> {
            String innerSaw = ScopedValue.where(CTX, "inner").call(CTX::get);
            assertThat(innerSaw.equals("inner"), "inner block saw " + innerSaw);
            assertThat(CTX.get().equals("outer"), "outer binding not restored: " + CTX.get());
        });
    }

    static void binding_is_restored_even_when_the_inner_block_throws() {
        ScopedValue.where(CTX, "outer").run(() -> {
            try {
                ScopedValue.where(CTX, "inner").run(() -> { throw new RuntimeException("boom"); });
            } catch (RuntimeException expected) { }
            assertThat(CTX.get().equals("outer"), "exception path did not restore the outer binding");
        });
    }

    static void each_thread_sees_only_its_own_binding() throws Exception {
        AtomicReference<String> aSaw = new AtomicReference<>(), bSaw = new AtomicReference<>();
        CountDownLatch bothInside = new CountDownLatch(2);
        Thread a = Thread.ofVirtual().start(() -> ScopedValue.where(CTX, "thread-A").run(() -> {
            bothInside.countDown(); await(bothInside); aSaw.set(CTX.get());
        }));
        Thread b = Thread.ofVirtual().start(() -> ScopedValue.where(CTX, "thread-B").run(() -> {
            bothInside.countDown(); await(bothInside); bSaw.set(CTX.get());
        }));
        a.join(); b.join();
        assertThat("thread-A".equals(aSaw.get()) && "thread-B".equals(bSaw.get()),
                "cross-thread contamination: A saw " + aSaw.get() + ", B saw " + bSaw.get());
    }

    static void one_carrier_can_bind_several_values_at_once() {
        ScopedValue<Integer> tenant = ScopedValue.newInstance();
        String combined = ScopedValue.where(CTX, "req-9").where(tenant, 42)
                .call(() -> CTX.get() + "/" + tenant.get());
        assertThat(combined.equals("req-9/42"), "multi-binding carrier produced " + combined);
    }

    static void bindings_flow_into_structured_forks() throws Exception {
        String seen = ScopedValue.where(CTX, "req-42").call(() -> {
            try (var scope = StructuredTaskScope.<String>open()) {
                var st = scope.fork(CTX::get);
                scope.join();
                return st.get();
            } catch (InterruptedException e) { throw new RuntimeException(e); }
        });
        assertThat(seen.equals("req-42"), "fork saw " + seen);
    }

    static void bindings_do_not_flow_into_hand_started_threads() throws Exception {
        AtomicBoolean boundInBareThread = new AtomicBoolean(true);
        ScopedValue.where(CTX, "req-42").run(() -> {
            Thread t = Thread.ofVirtual().start(() -> boundInBareThread.set(CTX.isBound()));
            try { t.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertThat(!boundInBareThread.get(), "a hand-started thread inherited the binding");
    }

    static void the_binding_is_immutable_but_the_object_it_names_is_not() {
        // The API stops REbinding-in-place; it cannot stop you mutating a bound
        // mutable object. Bind immutable types (records, List.of) in real code.
        List<String> mutable = new ArrayList<>(List.of("a"));
        ScopedValue.where(ITEMS, mutable).run(() -> ITEMS.get().add("sneaky"));
        assertThat(mutable.size() == 2, "expected the mutation to be visible — it is, which is the caveat");
    }

    // ---- tiny harness ----------------------------------------------------
    interface Scenario { void run() throws Exception; }
    static int passed, failed;
    static void run(String name, Scenario s) {
        try { s.run(); passed++; System.out.println("PASS  " + name); }
        catch (Throwable e) { failed++; System.out.println("FAIL  " + name + "\n      " + e); }
    }
    static void assertThat(boolean cond, String msgIfFalse) { if (!cond) throw new AssertionError(msgIfFalse); }
    static void await(CountDownLatch l) { try { l.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}
