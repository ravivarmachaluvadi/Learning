import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One narrated evening in Indiranagar, Bengaluru.
 *
 * Run:  java src/Sim.java
 *
 * Time is virtual (an AtomicLong we advance by hand) so that offer expiry and
 * ping staleness -- tens of seconds in real life -- happen while you watch, and
 * happen identically on every run.
 */
public class Sim {

    static final AtomicLong CLOCK = new AtomicLong(1_700_000_000_000L);

    static void advance(long seconds) {
        CLOCK.addAndGet(seconds * 1000);
        System.out.printf("%n  [clock +%ds]%n", seconds);
    }

    static void scene(String title) {
        System.out.println("\n" + "=".repeat(76));
        System.out.println("  " + title);
        System.out.println("=".repeat(76));
    }

    /** Print exactly what the matcher sees, before it decides. */
    static void showCandidates(Dispatch d, Loc pickup, String label) {
        System.out.println("\n    " + label);
        System.out.printf("    %-5s %9s %8s %9s  %s%n", "id", "distance", "roads", "ETA", "verdict");
        for (Dispatch.Candidate c : d.inspect(pickup)) {
            Driver drv = d.drivers.get(c.driverId());
            System.out.printf("    %-5s %8.0fm %7.1fx %8.0fs  %s%n",
                    c.driverId(), c.distM(), drv.roadFactor, c.etaSec(), c.reason());
        }
    }

    public static void main(String[] args) {
        Loc metro = new Loc(12.97840, 77.64080);       // 100 Ft Road, Indiranagar

        CellIndex index = new CellIndex(500, true);
        Dispatch dispatch = new Dispatch(index, CLOCK::get);
        dispatch.verbose = true;

        scene("SCENE 1 - supply comes online");
        // roadFactor = how much longer the drive is than the crow's path.
        // D2 is close in a straight line but on the far side of a flyover.
        dispatch.goOnline(new Driver("D1", metro.moved(  600,   200), 1.3));
        dispatch.goOnline(new Driver("D2", metro.moved(  250,  -150), 3.5));
        dispatch.goOnline(new Driver("D3", metro.moved(  900,   700), 1.2));
        dispatch.goOnline(new Driver("D4", metro.moved(  350,   250), 1.1));
        dispatch.goOnline(new Driver("D5", metro.moved( 2400,  1900), 1.2));
        System.out.printf("    %d drivers online, %d non-empty cells, index = %s%n",
                dispatch.availableCount(), index.cellCount(), index.name());

        scene("SCENE 2 - Riya opens the app: what the matcher actually sees");
        // D4 has not pinged since it came online, and it is about to matter.
        dispatch.ping("D1", dispatch.drivers.get("D1").loc);
        dispatch.ping("D2", dispatch.drivers.get("D2").loc);
        dispatch.ping("D3", dispatch.drivers.get("D3").loc);
        dispatch.ping("D5", dispatch.drivers.get("D5").loc);
        showCandidates(dispatch, metro, "candidates near the pickup, ranked by ETA:");
        System.out.println("""
            
            D2 is the NEAREST car (292 m) and the WORST choice (122 s): the flyover
            makes its 292 m into a kilometre of driving. Rank by distance and you
            send Riya the car that arrives last.""");

        scene("SCENE 3 - what the search costs");
        List<String> full = index.near(metro, 3000);
        long fullCells = index.lastCellsVisited;
        List<String> prog = index.nearProgressive(metro, 3000, 3);
        System.out.printf("""
            
                full 3 km cover : %2d drivers found, %3d cells visited
                ring expansion  : %2d drivers found, %3d cells visited (stopped once it had 3)
            
            Same answer here, 20x less work -- but the ring version is allowed to
            miss a slightly better driver one ring further out. That is the trade.%n""",
                full.size(), fullCells, prog.size(), index.lastCellsVisited);

        scene("SCENE 4 - Riya requests, the driver declines, the trip re-matches");
        Trip t1 = dispatch.requestRide("riya", metro, metro.moved(6000, 3000), "req-riya-001");
        String first = t1.triedDrivers.iterator().next();
        dispatch.decline(first);
        System.out.println("""
            
            A decline is not an error path, it is the normal path. The claim was
            released and the trip took its next-best candidate in the same breath.""");

        scene("SCENE 5 - nobody taps accept, so the reservation dies on its own");
        advance(20);                       // offerTtlMs is 15 s
        dispatch.expireOffers();
        System.out.println("""
            
            Nothing crashed here. But note what this same code path also repairs:
            if the matcher PROCESS had died between offer and accept, the driver
            would sit in OFFERED forever. One deadline recovers both failures.""");

        scene("SCENE 6 - accept, and a stale accept is refused");
        Driver holder = dispatch.drivers.values().stream()
                .filter(d -> d.state.get() == DriverState.OFFERED).findFirst().orElseThrow();
        dispatch.accept(holder.id, "O-from-an-old-popup");     // fencing check
        dispatch.accept(holder.id, holder.offer.offerId());
        dispatch.startTrip(t1.id);

        scene("SCENE 7 - a driver's phone loses signal");
        advance(45);                                            // > pingTtlMs (30 s)
        for (String id : List.of("D1", "D2", "D3", "D5")) dispatch.ping(id, dispatch.drivers.get(id).loc);
        Loc arjunPickup = metro.moved(300, 300);
        showCandidates(dispatch, arjunPickup, "candidates for Arjun, 60 m from D4's last known pin:");
        Trip t2 = dispatch.requestRide("arjun", arjunPickup, metro.moved(-4000, 500), "req-arjun-001");
        System.out.println("""
            
            D4's pin is the closest thing on the map and the matcher refused it.
            A location is not a fact, it is a fact WITH AN EXPIRY DATE -- dispatch
            that pin and Arjun watches a car that was never there.""");

        scene("SCENE 8 - the rider double-taps the button");
        Trip again = dispatch.requestRide("arjun", arjunPickup, metro.moved(-4000, 500), "req-arjun-001");
        System.out.printf("    same trip object returned: %s (%s, driver %s)%n",
                again == t2, again.id, again.driverId);

        scene("SCENE 9 - the concert lets out: demand spikes, price responds");
        for (int i = 0; i < 8; i++) {
            dispatch.requestRide("fan" + i, metro.moved(50 * i, 40 * i),
                    metro.moved(-3000, 2000), "req-fan-" + i);
        }
        System.out.printf("%n    surge at the venue is now x%.1f  (%d drivers claimable, %d marked available"
                        + " -- the difference is one ghost)%n",
                dispatch.surgeAt(metro), dispatch.availableFreshCount(), dispatch.availableCount());
        System.out.println("""
            
            Surge is not a fee bolted onto the fare. It is the only lever the system
            has to CHANGE the two numbers on that line: some riders leave, some
            drivers come. A marketplace with no price lever just queues forever.""");

        scene("SCENE 10 - the trip ends");
        dispatch.completeTrip(t1.id, 240.00);
        dispatch.completeTrip(t1.id, 240.00);        // retry of the same completion
        System.out.printf("    %s is %s again and back in the index%n", holder.id, holder.state.get());

        System.out.println("\n" + "-".repeat(76));
        System.out.printf("  offers expired: %d   ghost dispatches: %d   double assignments: %d%n",
                dispatch.offersExpired.get(), dispatch.ghostDispatches.get(),
                dispatch.doubleAssignments.get());
        System.out.println("  Next:  java src/Experiments.java   -- break each of these on purpose");
    }
}
