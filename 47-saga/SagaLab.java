import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Saga pattern — single-process learning lab.
 *
 * The story: a travel agency books a trip = flight + hotel + car, in that
 * order. Each booking lives in a DIFFERENT service with its OWN database, so
 * no single ACID transaction can cover the trip. A saga runs the three local
 * transactions in sequence and, when one fails, runs COMPENSATIONS (cancel
 * bookings) in reverse order for the ones that already committed.
 *
 * What is REAL here:      the coordination logic — saga log, step ordering,
 *                         reverse-order compensation, idempotency, recovery.
 * What is SIMULATED here: the services' databases (maps inside objects that
 *                         survive "crashes"), the crash itself (an exception
 *                         at a chosen point; "restart" = a new orchestrator
 *                         object over the same durable saga log), and the
 *                         event bus for the choreography variant.
 *
 * Run the guided experiments:
 *   java SagaLab.java happy-path
 *   java SagaLab.java car-fails-compensate
 *   java SagaLab.java orchestrator-crash-recovery
 *   java SagaLab.java compensation-retry
 *   java SagaLab.java dirty-read
 *   java SagaLab.java choreography
 *   java SagaLab.java all
 */
public class SagaLab {

    /** Simulates a process dying at an exact point. */
    public static class Crash extends RuntimeException {
        public Crash(String message) { super(message); }
    }

    /** A service refused the request (business failure, e.g. sold out). */
    public static class StepFailed extends RuntimeException {
        public StepFailed(String message) { super(message); }
    }

    /**
     * A transient network failure — the call never got an answer. (Named after
     * Python's built-in ConnectionError, which the original lab raised here;
     * it is an ordinary RuntimeException, not a java.lang.Error.)
     */
    public static class ConnectionError extends RuntimeException {
        public ConnectionError(String message) { super(message); }
    }

    // -----------------------------------------------------------------------
    // Three services. Each owns its state (its "database") and offers a
    // forward action (book) and a compensation (cancel). Both are IDEMPOTENT:
    // they remember which booking-ids they have already handled.
    // -----------------------------------------------------------------------
    public static class Service {
        public final String name;
        public int capacity;                              // e.g. seats / rooms / cars left
        public final Map<String, String> bookings = new LinkedHashMap<>();  // booking_id -> "BOOKED"/"CANCELLED"
        public boolean failNextBook = false;              // experiment knob: refuse next book
        public int failNextCancel = 0;                    // experiment knob: fail N cancels

        public Service(String name, int capacity) {
            this.name = name;
            this.capacity = capacity;
        }

        public String book(String bookingId) {
            if ("BOOKED".equals(bookings.get(bookingId)))
                return "already-booked (idempotent: no double charge)";
            if (failNextBook) {
                failNextBook = false;
                throw new StepFailed(name + ": sold out");
            }
            if (capacity <= 0)
                throw new StepFailed(name + ": sold out");
            capacity -= 1;
            bookings.put(bookingId, "BOOKED");
            return "booked (capacity now " + capacity + ")";
        }

        public String cancel(String bookingId) {
            if (failNextCancel > 0) {
                failNextCancel -= 1;
                throw new ConnectionError(name + ": cancel endpoint timed out");
            }
            if ("BOOKED".equals(bookings.get(bookingId))) {
                bookings.put(bookingId, "CANCELLED");
                capacity += 1;
                return "cancelled (capacity back to " + capacity + ")";
            }
            // cancelling something never booked (or already cancelled) is a no-op
            return "nothing to cancel (idempotent no-op)";
        }
    }

    // -----------------------------------------------------------------------
    // Orchestration: one coordinator drives the steps and owns a DURABLE log.
    // The log is the saga's memory: after any crash, a new orchestrator can
    // read it and know exactly where to resume.
    // -----------------------------------------------------------------------

    /**
     * Durable append-only log. Survives orchestrator crashes (in real life:
     * a database table; here: an object that outlives the orchestrator).
     */
    public static class SagaLog {
        record Entry(String sagaId, String text) {}

        public final List<Entry> records = new ArrayList<>();

        public void append(String sagaId, String entry) {
            records.add(new Entry(sagaId, entry));
        }

        public List<String> entries(String sagaId) {
            List<String> found = new ArrayList<>();
            for (Entry r : records)
                if (r.sagaId().equals(sagaId)) found.add(r.text());
            return found;
        }
    }

    public static class Orchestrator {
        public static final List<String> STEPS = List.of("flight", "hotel", "car");  // forward order

        private final Map<String, Service> services;      // name -> Service
        private final SagaLog log;
        private final boolean narrate;

        public Orchestrator(Map<String, Service> services, SagaLog log) {
            this(services, log, true);
        }

        public Orchestrator(Map<String, Service> services, SagaLog log, boolean narrate) {
            this.services = services;
            this.log = log;
            this.narrate = narrate;
        }

        private void say(String msg) {
            if (narrate) System.out.println("    " + msg);
        }

        public String run(String sagaId) {
            return run(sagaId, null, null);
        }

        /**
         * Execute the saga, resuming from whatever the log already holds.
         *
         * crashAfterBook: name of a step; the orchestrator 'dies' after the
         * service booked but BEFORE the log records the completion — the gap
         * that forces services to be idempotent.
         * stopAfter: name of a step; pause the saga (cleanly, log intact)
         * right after that step — used to peek at mid-saga state.
         */
        public String run(String sagaId, String crashAfterBook, String stopAfter) {
            List<String> done = log.entries(sagaId);
            if (!done.contains("SAGA_STARTED")) {
                log.append(sagaId, "SAGA_STARTED");
                say("saga " + sagaId + ": STARTED");
            }

            for (String step : STEPS) {
                if (done.contains(step + ":done")) {
                    say("saga log says '" + step + "' already done — skipping");
                    continue;
                }
                Service svc = services.get(step);
                try {
                    String result = svc.book(sagaId);
                    say(step + ".book(" + sagaId + ") -> " + result);
                    if (step.equals(crashAfterBook))
                        throw new Crash(
                                "orchestrator died after " + step + ".book(), before logging it");
                    log.append(sagaId, step + ":done");
                    if (step.equals(stopAfter)) {
                        say("saga " + sagaId + ": PAUSED after '" + step + "' (still in flight)");
                        return "PAUSED";
                    }
                } catch (StepFailed e) {
                    say(step + ".book(" + sagaId + ") -> FAILED: " + e.getMessage());
                    log.append(sagaId, step + ":failed");
                    compensate(sagaId);
                    log.append(sagaId, "SAGA_ABORTED");
                    say("saga " + sagaId + ": ABORTED (all completed steps undone)");
                    return "ABORTED";
                }
            }
            log.append(sagaId, "SAGA_COMPLETED");
            say("saga " + sagaId + ": COMPLETED");
            return "COMPLETED";
        }

        /**
         * Undo completed steps in REVERSE order. Compensations may fail
         * transiently; they are retried until they succeed — a compensation
         * is not allowed to give up.
         */
        private void compensate(String sagaId) {
            List<String> done = log.entries(sagaId);
            List<String> completed = new ArrayList<>();
            for (String s : STEPS)
                if (done.contains(s + ":done")) completed.add(s);
            for (String step : completed.reversed()) {
                Service svc = services.get(step);
                while (true) {
                    try {
                        String result = svc.cancel(sagaId);
                        say("COMPENSATE " + step + ".cancel(" + sagaId + ") -> " + result);
                        log.append(sagaId, step + ":compensated");
                        break;
                    } catch (ConnectionError e) {
                        say("COMPENSATE " + step + ".cancel(" + sagaId + ") -> "
                                + e.getMessage() + " — RETRYING");
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Choreography: no coordinator. Services subscribe to each other's events
    // on a bus and react. The saga's flow lives in the subscriptions.
    // -----------------------------------------------------------------------
    public static class Bus {
        public final Map<String, List<Consumer<String>>> handlers = new LinkedHashMap<>();  // event type -> [fn]
        public final List<String> history = new ArrayList<>();
        private final boolean narrate;

        public Bus() { this(true); }

        public Bus(boolean narrate) { this.narrate = narrate; }

        public void subscribe(String eventType, Consumer<String> fn) {
            handlers.computeIfAbsent(eventType, k -> new ArrayList<>()).add(fn);
        }

        public void publish(String eventType, String sagaId) {
            history.add(eventType);
            if (narrate)
                System.out.println("    event: " + eventType + " (" + sagaId + ")");
            for (Consumer<String> fn : new ArrayList<>(handlers.getOrDefault(eventType, List.of())))
                fn.accept(sagaId);
        }
    }

    /** book, then announce success or failure — the reaction each service registers. */
    private static Consumer<String> tryBook(Bus bus, Service svc, String okEvent, String failEvent) {
        return sagaId -> {
            try {
                svc.book(sagaId);
                bus.publish(okEvent, sagaId);
            } catch (StepFailed e) {
                bus.publish(failEvent, sagaId);
            }
        };
    }

    /**
     * The whole trip-flow, expressed only as reactions to events.
     * TripRequested -> flight books -> FlightBooked -> hotel books ->
     * HotelBooked -> car books -> CarBooked (done).
     * Any *Failed event triggers the cancellations that undo prior steps.
     */
    public static void wireChoreography(Bus bus, Map<String, Service> services) {
        bus.subscribe("TripRequested",
                tryBook(bus, services.get("flight"), "FlightBooked", "FlightFailed"));
        bus.subscribe("FlightBooked",
                tryBook(bus, services.get("hotel"), "HotelBooked", "HotelFailed"));
        bus.subscribe("HotelBooked",
                tryBook(bus, services.get("car"), "CarBooked", "CarFailed"));

        // compensation chain: each failure event undoes the step before it
        bus.subscribe("CarFailed", sid -> {
            services.get("hotel").cancel(sid);
            bus.publish("HotelCancelled", sid);
        });
        bus.subscribe("HotelFailed", sid -> {
            services.get("flight").cancel(sid);
            bus.publish("FlightCancelled", sid);
        });
        bus.subscribe("HotelCancelled", sid -> {
            services.get("flight").cancel(sid);
            bus.publish("FlightCancelled", sid);
        });
    }

    // -----------------------------------------------------------------------
    // Guided experiments
    // -----------------------------------------------------------------------

    /** The three services plus the saga log they are coordinated by. */
    public record World(Map<String, Service> services, SagaLog log) {}

    public static World freshWorld() { return freshWorld(3); }

    public static World freshWorld(int capacity) {
        Map<String, Service> services = new LinkedHashMap<>();
        services.put("flight", new Service("flight", capacity));
        services.put("hotel", new Service("hotel", capacity));
        services.put("car", new Service("car", capacity));
        return new World(services, new SagaLog());
    }

    static void showState(Map<String, Service> services) {
        for (Service s : services.values())
            System.out.printf("    %-6s capacity=%d bookings=%s%n", s.name, s.capacity, s.bookings);
    }

    static void experimentHappyPath() {
        System.out.println("=".repeat(72));
        System.out.println("EXPERIMENT 1: happy path — three local transactions in sequence");
        System.out.println("=".repeat(72));
        World world = freshWorld();
        new Orchestrator(world.services(), world.log()).run("trip-1");
        showState(world.services());
        System.out.println();
        System.out.println("VERDICT: no distributed transaction anywhere — three ordinary");
        System.out.println("local commits, glued together by the orchestrator and its log.");
        System.out.println();
    }

    static void experimentCarFails() {
        System.out.println("=".repeat(72));
        System.out.println("EXPERIMENT 2: the car rental is sold out — compensate backwards");
        System.out.println("=".repeat(72));
        World world = freshWorld();
        world.services().get("car").failNextBook = true;
        String result = new Orchestrator(world.services(), world.log()).run("trip-2");
        showState(world.services());
        System.out.println();
        System.out.println("VERDICT: " + result + ". Flight and hotel were already COMMITTED — they");
        System.out.println("cannot be rolled back, only cancelled by new transactions, in");
        System.out.println("reverse order. The trip ends atomically-in-effect: all or nothing.");
        System.out.println();
    }

    static void experimentOrchestratorCrash() {
        System.out.println("=".repeat(72));
        System.out.println("EXPERIMENT 3: orchestrator dies mid-saga; a new one resumes");
        System.out.println("=".repeat(72));
        World world = freshWorld();
        System.out.println("First run — crashes after hotel.book() succeeds but BEFORE the");
        System.out.println("log records it:");
        try {
            new Orchestrator(world.services(), world.log()).run("trip-3", "hotel", null);
        } catch (Crash e) {
            System.out.println("    CRASH: " + e.getMessage());
        }
        System.out.println("Recovery — a NEW orchestrator reads the durable saga log:");
        new Orchestrator(world.services(), world.log()).run("trip-3");
        showState(world.services());
        Service hotel = world.services().get("hotel");
        System.out.println();
        System.out.println("VERDICT: the log said 'flight done' (skipped) but knew nothing of");
        System.out.println("the hotel, so the new orchestrator retried hotel.book(). The");
        System.out.println("service recognized the booking id -> no double charge. The saga");
        System.out.println("log gives durability; SERVICE IDEMPOTENCY absorbs the retry.");
        System.out.println("Hotel capacity is " + hotel.capacity + " — booked once, not twice.");
        System.out.println();
    }

    static void experimentCompensationRetry() {
        System.out.println("=".repeat(72));
        System.out.println("EXPERIMENT 4: a compensation fails — it must be retried, forever");
        System.out.println("=".repeat(72));
        World world = freshWorld();
        world.services().get("car").failNextBook = true;
        world.services().get("hotel").failNextCancel = 2;      // first two cancels time out
        new Orchestrator(world.services(), world.log()).run("trip-4");
        showState(world.services());
        System.out.println();
        System.out.println("VERDICT: a forward step may fail permanently ('sold out') and the");
        System.out.println("saga aborts. A COMPENSATION is different: it is not allowed to");
        System.out.println("give up, because the saga is already half-done. Compensations must");
        System.out.println("be retryable (and therefore idempotent) until they succeed.");
        System.out.println();
    }

    static void experimentDirtyRead() {
        System.out.println("=".repeat(72));
        System.out.println("EXPERIMENT 5: the missing I in ACID — outsiders see mid-saga state");
        System.out.println("=".repeat(72));
        World world = freshWorld(1);
        world.services().get("car").failNextBook = true;
        Orchestrator orch = new Orchestrator(world.services(), world.log());

        orch.run("trip-5", null, "hotel");     // pause mid-saga, log intact
        System.out.println("  >>> Meanwhile another customer asks: any hotel rooms left?");
        System.out.println("      hotel capacity right now: " + world.services().get("hotel").capacity
                + "  (they are told: SOLD OUT)");
        String result = orch.run("trip-5");    // resume: car step fails
        System.out.println("  >>> After the saga " + result.toLowerCase(Locale.ROOT)
                + ", hotel capacity is back to " + world.services().get("hotel").capacity + ".");
        System.out.println();
        System.out.println("VERDICT: the other customer observed state that was later undone —");
        System.out.println("a dirty read at the business level. ACID transactions hide");
        System.out.println("intermediate state (Isolation); sagas do NOT. Real systems soften");
        System.out.println("this with 'PENDING' statuses (semantic lock) instead of hiding it.");
        System.out.println();
    }

    static void experimentChoreography() {
        System.out.println("=".repeat(72));
        System.out.println("EXPERIMENT 6: choreography — same saga, no coordinator");
        System.out.println("=".repeat(72));
        World world = freshWorld();
        Map<String, Service> services = world.services();
        Bus bus = new Bus();
        wireChoreography(bus, services);
        System.out.println("Happy path (watch the event chain):");
        bus.publish("TripRequested", "trip-6a");
        showState(services);
        System.out.println("Failure path — car sold out (watch compensation events ripple):");
        services.get("car").failNextBook = true;
        bus.publish("TripRequested", "trip-6b");
        showState(services);
        System.out.println();
        System.out.println("VERDICT: the flow works, but notice it is written NOWHERE — it");
        System.out.println("emerges from subscriptions spread across services. Adding a step");
        System.out.println("means touching several services; answering 'where is trip-6b");
        System.out.println("stuck?' means correlating events. That is the orchestration vs");
        System.out.println("choreography tradeoff in one screen.");
        System.out.println();
    }

    static final Map<String, Runnable> EXPERIMENTS = new LinkedHashMap<>();
    static {
        EXPERIMENTS.put("happy-path", SagaLab::experimentHappyPath);
        EXPERIMENTS.put("car-fails-compensate", SagaLab::experimentCarFails);
        EXPERIMENTS.put("orchestrator-crash-recovery", SagaLab::experimentOrchestratorCrash);
        EXPERIMENTS.put("compensation-retry", SagaLab::experimentCompensationRetry);
        EXPERIMENTS.put("dirty-read", SagaLab::experimentDirtyRead);
        EXPERIMENTS.put("choreography", SagaLab::experimentChoreography);
    }

    public static void main(String[] args) {
        String choice = args.length > 0 ? args[0] : "all";
        if (choice.equals("all")) {
            for (Runnable fn : EXPERIMENTS.values()) fn.run();
        } else if (EXPERIMENTS.containsKey(choice)) {
            EXPERIMENTS.get(choice).run();
        } else {
            System.out.println("usage: java SagaLab.java ["
                    + String.join("|", EXPERIMENTS.keySet()) + "|all]");
            System.exit(1);
        }
    }
}
