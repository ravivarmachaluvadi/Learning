import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Break it on purpose, and measure the damage.
 *
 * Run all:      java src/Experiments.java
 * Run one:      java src/Experiments.java 3
 *
 * Every number the README quotes comes from this file. Numbers move a little
 * between machines; the ORDER OF MAGNITUDE and the direction are the lesson.
 */
public class Experiments {

    static final Loc CITY = new Loc(12.9716, 77.5946);          // Bengaluru
    static final double CITY_RADIUS_M = 20_000;                  // ~40 km across
    static final Random RNG = new Random(42);                    // fixed seed = repeatable

    static Loc randomInCity(Random r) {
        return CITY.moved((r.nextDouble() * 2 - 1) * CITY_RADIUS_M,
                          (r.nextDouble() * 2 - 1) * CITY_RADIUS_M);
    }

    static void title(int n, String s) {
        System.out.println("\n" + "=".repeat(76));
        System.out.printf("  EXPERIMENT %d - %s%n", n, s);
        System.out.println("=".repeat(76));
    }

    public static void main(String[] args) throws Exception {
        Set<Integer> only = new HashSet<>();
        for (String a : args) only.add(Integer.parseInt(a));
        java.util.function.IntPredicate run = n -> only.isEmpty() || only.contains(n);

        if (run.test(1)) scanVsIndex();
        if (run.test(2)) cellEdge();
        if (run.test(3)) doubleAssignment();
        if (run.test(4)) ghostDrivers();
        if (run.test(5)) hotCell();
        if (run.test(6)) greedyVsBatch();
        System.out.println();
    }

    // ------------------------------------------------------------------ 1
    /** Does the grid actually pay for itself? */
    static void scanVsIndex() {
        title(1, "full scan vs cell index: 100,000 drivers, 1,000 ride requests");
        int drivers = 100_000, queries = 1_000;
        Random r = new Random(1);
        List<Loc> locs = new ArrayList<>();
        for (int i = 0; i < drivers; i++) locs.add(randomInCity(r));

        NaiveIndex naive = new NaiveIndex();
        CellIndex cell = new CellIndex(500, true);
        for (int i = 0; i < drivers; i++) { naive.update("D" + i, locs.get(i)); cell.update("D" + i, locs.get(i)); }

        List<Loc> pickups = new ArrayList<>();
        for (int i = 0; i < queries; i++) pickups.add(randomInCity(r));

        long t0 = System.nanoTime(); int nFound = 0;
        for (Loc p : pickups) nFound += naive.near(p, 3000).size();
        long naiveMs = (System.nanoTime() - t0) / 1_000_000;

        t0 = System.nanoTime(); int cFound = 0; long examined = 0;
        for (Loc p : pickups) { cFound += cell.near(p, 3000).size(); examined += cell.lastCandidatesExamined; }
        long cellMs = (System.nanoTime() - t0) / 1_000_000;

        CellIndex prog = cell;
        t0 = System.nanoTime(); int pFound = 0; long pExamined = 0, pCells = 0;
        for (Loc p : pickups) {
            pFound += prog.nearProgressive(p, 3000, 10).size();
            pExamined += prog.lastCandidatesExamined; pCells += prog.lastCellsVisited;
        }
        long progMs = (System.nanoTime() - t0) / 1_000_000;

        System.out.printf("""
            
                                    total     per request    drivers measured   candidates
                                                                  per request   per request
              full scan        : %6d ms   %8.2f ms   %,15d   %,10d
              cell index       : %6d ms   %8.2f ms   %,15d   %,10d
              ring, stop at 10 : %6d ms   %8.2f ms   %,15d   %,10d
            
              speedup index/scan   : %.1fx
              speedup ring/scan    : %.1fx
            
            The scan measures every driver in the city to answer a question about
            3 km of it. The index measures only the drivers who could win. Note the
            third row: it finds FEWER drivers ON PURPOSE and is faster still --
            "give me ten good candidates" is a far cheaper question than "give me
            every candidate", and the matcher was only ever going to rank ten.%n""",
                naiveMs, naiveMs / (double) queries, (long) drivers, nFound / queries,
                cellMs, cellMs / (double) queries, examined / queries, cFound / queries,
                progMs, progMs / (double) queries, pExamined / queries, pFound / queries,
                naiveMs / (double) Math.max(1, cellMs), naiveMs / (double) Math.max(1, progMs));
    }

    // ------------------------------------------------------------------ 2
    /** Mechanism v1 -- "just look in the rider's own cell" -- how wrong is it? */
    static void cellEdge() {
        title(2, "the cell-edge bug: searching only the rider's own cell");
        int drivers = 5_000, queries = 5_000;
        Random r = new Random(7);
        NaiveIndex truth = new NaiveIndex();
        CellIndex ownCell = new CellIndex(500, false);
        CellIndex ring = new CellIndex(500, true);
        Map<String, Loc> loc = new HashMap<>();
        for (int i = 0; i < drivers; i++) {
            Loc l = randomInCity(r); loc.put("D" + i, l);
            truth.update("D" + i, l); ownCell.update("D" + i, l); ring.update("D" + i, l);
        }

        int noneFound = 0, worse = 0; double totalExtraM = 0, worstExtraM = 0;
        for (int q = 0; q < queries; q++) {
            Loc p = randomInCity(r);
            String best = nearest(truth.near(p, 3000), loc, p);
            String got  = nearest(ownCell.near(p, 3000), loc, p);
            if (best == null) continue;
            if (got == null) { noneFound++; continue; }
            if (!got.equals(best)) {
                double extra = p.distanceTo(loc.get(got)) - p.distanceTo(loc.get(best));
                worse++; totalExtraM += extra; worstExtraM = Math.max(worstExtraM, extra);
            }
        }
        // The ring version must agree with the oracle every time.
        int ringWrong = 0;
        Random r2 = new Random(99);
        for (int q = 0; q < 1000; q++) {
            Loc p = randomInCity(r2);
            String best = nearest(truth.near(p, 3000), loc, p);
            String got = nearest(ring.near(p, 3000), loc, p);
            if (!Objects.equals(best, got)) ringWrong++;
        }

        System.out.printf("""
            
              %,d requests, %,d drivers, 500 m cells
            
              own cell only : %,5d requests found NO driver at all (%.1f%%)
                              %,5d requests got a worse driver than the true nearest (%.1f%%)
                              average extra distance when wrong: %.0f m, worst: %.0f m
              ring search   : %d disagreements with the full-scan oracle out of 1,000
            
            Half the map inside a cell is closer to a neighbouring cell's contents
            than to its own. The bug is invisible in a demo -- it always returns
            SOMETHING -- and it silently sends the second-best car forever.%n""",
                queries, drivers, noneFound, 100.0 * noneFound / queries,
                worse, 100.0 * worse / queries,
                worse == 0 ? 0 : totalExtraM / worse, worstExtraM, ringWrong);
    }

    static String nearest(List<String> ids, Map<String, Loc> loc, Loc p) {
        String best = null; double bd = Double.MAX_VALUE;
        for (String id : ids) {
            double d = p.distanceTo(loc.get(id));
            if (d < bd) { bd = d; best = id; }
        }
        return best;
    }

    // ------------------------------------------------------------------ 3
    /** Two riders, one car: read-then-write vs compare-and-set. */
    static void doubleAssignment() throws Exception {
        title(3, "two riders, one car: is a match a query result or a claim?");
        for (boolean unsafe : new boolean[]{true, false}) {
            AtomicLong clock = new AtomicLong(System.currentTimeMillis());
            CellIndex idx = new CellIndex(500, true);
            Dispatch d = new Dispatch(idx, clock::get);
            d.unsafeClaim = unsafe;
            int nDrivers = 200, nRiders = 200, threads = 8;

            Random r = new Random(5);
            for (int i = 0; i < nDrivers; i++)
                d.goOnline(new Driver("D" + i, CITY.moved(r.nextInt(2000) - 1000, r.nextInt(2000) - 1000), 1.0));

            // Every rider stands on the same corner, so every thread ranks the cars
            // in the SAME order and they all reach for the same car first. That is
            // not a contrived case -- it is a taxi rank, a stadium gate, an airport.
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < nRiders; i++) {
                final int n = i;
                fs.add(pool.submit(() -> {
                    try { start.await(); } catch (InterruptedException e) { return; }
                    d.requestRide("R" + n, CITY, CITY.moved(3000, 0), "k" + n);
                }));
            }
            start.countDown();
            for (Future<?> f : fs) f.get();
            pool.shutdown();

            // Independent check, computed from the trips and not from the counter:
            // how many trips think a car is coming, and how many CARS is that really?
            List<String> promised = d.trips.values().stream()
                    .map(x -> x.pendingOfferDriver).filter(Objects::nonNull).toList();
            long distinctCars = new HashSet<>(promised).size();

            System.out.printf("""
                
                  %-18s %d riders on one corner racing for %d cars, %d threads
                      riders told "your car is on the way" : %d
                      cars actually reserved               : %d
                      riders promised a car someone else has: %d
                      duplicate offers detected at match time: %d%n""",
                    unsafe ? "READ-THEN-WRITE:" : "COMPARE-AND-SET:", nRiders, nDrivers, threads,
                    promised.size(), distinctCars, promised.size() - distinctCars,
                    d.doubleAssignments.get());
        }
        System.out.println("""
            
            The unsafe version has no bug you can point at in a code review: it
            checks the state, then sets it. The check is simply not part of the
            same action -- and a scheduler, a GC pause or a network hop is very
            happy to stand in the gap. Every double assignment is two riders shown
            the same car.""");
    }

    // ------------------------------------------------------------------ 4
    /** A pin on the map is not a car in the street. */
    static void ghostDrivers() {
        title(4, "ghost cars: dispatching a location that stopped being true");
        for (boolean ignore : new boolean[]{true, false}) {
            AtomicLong clock = new AtomicLong(1_700_000_000_000L);
            CellIndex idx = new CellIndex(500, true);
            Dispatch d = new Dispatch(idx, clock::get);
            d.ignorePingFreshness = ignore;

            Random r = new Random(11);
            int n = 300;
            Set<String> dead = new HashSet<>();
            for (int i = 0; i < n; i++) {
                Driver drv = new Driver("D" + i, CITY.moved(r.nextInt(4000) - 2000, r.nextInt(4000) - 2000), 1.0);
                d.goOnline(drv);
                if (i % 3 == 0) dead.add(drv.id);          // a third of the fleet will go dark
            }
            clock.addAndGet(120_000);                       // two minutes pass
            for (int i = 0; i < n; i++)                     // only the living keep pinging
                if (!dead.contains("D" + i)) d.ping("D" + i, d.drivers.get("D" + i).loc);

            int dispatchedToGhost = 0, unmatched = 0;
            for (int i = 0; i < 200; i++) {
                Trip t = d.requestRide("R" + i, CITY.moved(r.nextInt(3000) - 1500, r.nextInt(3000) - 1500),
                        CITY.moved(5000, 0), "g" + i);
                if (t.state == TripState.NO_DRIVERS) { unmatched++; continue; }
                String assigned = d.drivers.values().stream()
                        .filter(x -> x.offer != null && x.offer.tripId().equals(t.id))
                        .map(x -> x.id).findFirst().orElse(null);
                if (assigned != null && dead.contains(assigned)) dispatchedToGhost++;
            }
            System.out.printf("""
                
                  %-28s 200 requests, 100 of 300 drivers dark for 2 minutes
                      offers sent to a dark driver : %d
                      requests with no driver      : %d%n""",
                    ignore ? "NO freshness rule:" : "30 s freshness rule:", dispatchedToGhost, unmatched);
        }
        System.out.println("""
            
            The ghost dispatch does not fail loudly. The rider watches a car that
            never moves, waits out the accept timeout, and re-requests -- so the
            cost lands as wait time and cancellations, not as an error rate. That
            is why supply is modelled as a LEASE that must be renewed, not a row
            that stays true until someone updates it.""");
    }

    // ------------------------------------------------------------------ 5
    /** The airport at midnight: one cell holds the whole fleet. */
    static void hotCell() {
        title(5, "the hot cell: an index is only as good as its worst key");
        int drivers = 20_000;
        Random r = new Random(3);
        CellIndex spread = new CellIndex(500, true);
        CellIndex hot = new CellIndex(500, true);
        Loc airport = CITY.moved(12_000, 9_000);
        for (int i = 0; i < drivers; i++) {
            spread.update("D" + i, randomInCity(r));
            hot.update("D" + i, airport.moved(r.nextInt(300) - 150, r.nextInt(300) - 150));
        }
        int q = 300;
        long t0 = System.nanoTime(); long ex1 = 0;
        for (int i = 0; i < q; i++) { spread.near(randomInCity(r), 3000); ex1 += spread.lastCandidatesExamined; }
        long spreadUs = (System.nanoTime() - t0) / 1000 / q;
        t0 = System.nanoTime(); long ex2 = 0;
        for (int i = 0; i < q; i++) { hot.near(airport, 3000); ex2 += hot.lastCandidatesExamined; }
        long hotUs = (System.nanoTime() - t0) / 1000 / q;

        System.out.printf("""
            
              fleet spread over the city : %,6d us per query, %,8d drivers measured per query
              fleet parked at one airport: %,6d us per query, %,8d drivers measured per query
              slowdown: %.0fx     drivers in the busiest 500 m cell: %,d
            
            The grid did not break -- it just stopped narrowing anything, because
            "narrow to nearby drivers" is only cheap when nearby is a small set.
            This is the same hot-key shape as a hot Redis key or a hot shard: the
            average is fine and one key carries the whole load.%n""",
                spreadUs, ex1 / q, hotUs, ex2 / q, hotUs / (double) Math.max(1, spreadUs),
                hot.sizeOfCellAt(airport));
    }

    // ------------------------------------------------------------------ 6
    /** First-come-first-served vs waiting a moment and matching the whole batch. */
    static void greedyVsBatch() {
        title(6, "greedy now vs batched in a moment: whose ETA are we minimising?");
        int n = 300;
        Random r = new Random(21);
        List<Loc> riders = new ArrayList<>(), cars = new ArrayList<>();
        for (int i = 0; i < n; i++) { riders.add(randomInCity(r)); cars.add(randomInCity(r)); }

        // Greedy: serve riders in arrival order; each takes the best car still free.
        boolean[] taken = new boolean[n];
        double greedyTotal = 0;
        for (int i = 0; i < n; i++) {
            int best = -1; double bd = Double.MAX_VALUE;
            for (int j = 0; j < n; j++) {
                if (taken[j]) continue;
                double d = riders.get(i).distanceTo(cars.get(j));
                if (d < bd) { bd = d; best = j; }
            }
            taken[best] = true; greedyTotal += bd;
        }

        // Batched: hold requests for a few seconds, then take globally-shortest pairs first.
        record Pair(int rider, int car, double d) {}
        List<Pair> pairs = new ArrayList<>();
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++)
            pairs.add(new Pair(i, j, riders.get(i).distanceTo(cars.get(j))));
        pairs.sort(Comparator.comparingDouble(Pair::d));
        boolean[] rUsed = new boolean[n], cUsed = new boolean[n];
        double batchTotal = 0; int matched = 0;
        for (Pair p : pairs) {
            if (rUsed[p.rider()] || cUsed[p.car()]) continue;
            rUsed[p.rider()] = cUsed[p.car()] = true; batchTotal += p.d(); matched++;
            if (matched == n) break;
        }

        System.out.printf("""
            
              %d riders, %d cars, same positions both times
                greedy, in arrival order : mean pickup distance %,7.0f m
                batched, shortest first  : mean pickup distance %,7.0f m
                improvement              : %.1f%%
            
            Nobody made a mistake in the greedy run: every rider got the best car
            AVAILABLE AT THAT INSTANT. The loss is structural -- an early rider
            takes a car that was the only good option for someone two seconds
            behind them. Waiting a few seconds buys the system a choice it cannot
            otherwise have; it costs every rider those seconds. Both are honest
            answers to "whose wait are we optimising?".%n""",
                n, n, greedyTotal / n, batchTotal / n,
                100 * (greedyTotal - batchTotal) / greedyTotal);
    }
}
