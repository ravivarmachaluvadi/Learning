import java.util.Arrays;

/**
 * Break the plan and watch. Every experiment answers a question you will
 * actually be asked in a room where someone is unhappy about a date.
 *
 *   java src/Experiments.java            # all of them
 *   java src/Experiments.java overtime   # just one
 */
public class Experiments {

    static final int SEEDS = 40;   // each run is one possible universe; average them

    record Agg(double shipWeek, double p10, double p90, double escaped, double rework,
               double idle, double thrash, double interrupt, double comm, double mentor,
               double fatigue, double featuresShipped, double featuresCut, double byWeek6) {}

    static Agg run(Plan p) {
        double[] weeks = new double[SEEDS];
        double esc = 0, rw = 0, idle = 0, th = 0, it = 0, cm = 0, mt = 0, ft = 0, fs = 0, fc = 0, b6 = 0;
        for (int s = 0; s < SEEDS; s++) {
            Result r = Project.run(p, 1000 + s);
            weeks[s] = r.shipWeek;
            esc += r.defectsEscaped; rw += r.reworkEngineerDays; idle += r.idleEngineerDays;
            th += r.thrashLostDays; it += r.interruptLostDays; cm += r.commLostDays;
            mt += r.mentorLostDays; ft += r.fatigueLostDays; fs += r.featuresShipped;
            fc += r.featuresCut; b6 += r.shippedByEndOfWeek(5);
        }
        Arrays.sort(weeks);
        double mean = 0;
        for (double w : weeks) mean += w;
        return new Agg(mean / SEEDS, weeks[(int) (0.1 * SEEDS)], weeks[(int) (0.9 * SEEDS)],
                esc / SEEDS, rw / SEEDS, idle / SEEDS, th / SEEDS, it / SEEDS, cm / SEEDS,
                mt / SEEDS, ft / SEEDS, fs / SEEDS, fc / SEEDS, b6 / SEEDS);
    }

    // ------------------------------------------------------------------
    static void estimate() {
        head("1. WHERE THE PROMISED DATE CAME FROM, AND WHY IT WAS ALREADY LOST");
        Plan p = new Plan();
        System.out.printf("The team estimated %d tasks at %.1f days each (their honest gut feel).%n",
                p.tasks, p.medianTaskDays * (1 - p.estimateOptimism));
        System.out.printf("Sum / %d engineers / 5 days   = %.1f weeks  <- the number in the deck%n",
                p.teamSize, p.pointEstimateWeeks());
        System.out.printf("Management rounded and committed to %.0f weeks.%n%n", p.promisedWeeks);

        Agg real = run(p);
        System.out.printf("Actual ship week, averaged over %d simulated universes: %.1f weeks (P10 %.0f, P90 %.0f)%n",
                SEEDS, real.shipWeek(), real.p10(), real.p90());
        System.out.printf("Overrun vs the promise: %.1fx%n%n", real.shipWeek() / p.promisedWeeks);

        System.out.println("Turn one cause off at a time. The drop is what that cause was costing:");
        System.out.printf("  %-46s %8s %8s%n", "world", "weeks", "saved");
        System.out.printf("  %-46s %8.1f %8s%n", "everything on (reality)", real.shipWeek(), "-");
        Plan a = p.copy(); a.skewOn = false;
        Plan b = p.copy(); b.interruptsOn = false;
        Plan c = p.copy(); c.reworkOn = false;
        Plan d = p.copy(); d.commOverheadOn = false;
        for (var e : new Object[][]{
                {"no task-to-task variability (all tasks average)", a},
                {"no interrupts (25% of capacity handed back)", b},
                {"no defects, so no rework", c},
                {"no communication overhead", d}}) {
            Agg g = run((Plan) e[1]);
            System.out.printf("  %-46s %8.1f %8.1f%n", e[0], g.shipWeek(), real.shipWeek() - g.shipWeek());
        }
        Plan perfect = p.copy();
        perfect.skewOn = false; perfect.interruptsOn = false; perfect.reworkOn = false; perfect.commOverheadOn = false;
        Agg pf = run(perfect);
        System.out.printf("  %-46s %8.1f %8.1f%n", "ALL of them off (pure coding time)", pf.shipWeek(), real.shipWeek() - pf.shipWeek());
        System.out.printf("%nEven in that fantasy world the estimate is wrong: %.1f weeks of pure work%n", pf.shipWeek());
        System.out.printf("against a %.1f-week estimate. That %.1f-week gap is pure optimism -%n",
                p.pointEstimateWeeks(), pf.shipWeek() - p.pointEstimateWeeks());
        System.out.println("people estimate the version of the task where nothing goes wrong.");

        System.out.println("\nAnd here is where the whole project's paid time actually went:");
        double days = real.shipWeek() * p.teamSize * 5;
        double[] parts = {days - real.interrupt() - real.comm() - real.mentor() - real.fatigue()
                          - real.idle() - real.thrash() - real.rework(), real.rework(),
                          real.interrupt(), real.comm(), real.idle(), real.thrash(), real.fatigue()};
        String[] names = {"building features", "fixing bugs the team made itself",
                          "support, meetings, interrupts", "coordination between people",
                          "nobody had anything to pick up", "context switching", "hours lost to tiredness"};
        for (int i = 0; i < parts.length; i++)
            System.out.printf("  %-34s %7.1f engineer-days  %5.1f%%%n", names[i], parts[i], 100 * parts[i] / days);
        System.out.printf("  %-34s %7.1f engineer-days%n", "TOTAL PAID FOR", days);
    }

    // ------------------------------------------------------------------
    static void overtime() {
        head("2. THE CRUNCH: DOES WORKING LONGER FINISH SOONER?");
        System.out.println("Same work, same team, same universe. Only the weekly hours change.");
        System.out.printf("%n  %-14s %10s %10s %14s %16s%n", "hours/week", "ship week", "vs 40h", "escaped bugs", "hours burnt");
        for (double h : new double[]{40, 45, 50, 60, 70}) {
            Plan p = new Plan();
            p.hoursPerWeek = h;
            Agg g = run(p);
            double base = h == 40 ? 0 : run(new Plan()).shipWeek();
            System.out.printf("  %-14.0f %10.1f %10s %14.0f %16.0f%n", h, g.shipWeek(),
                    h == 40 ? "-" : String.format("%+.1f", g.shipWeek() - base),
                    g.escaped(), g.shipWeek() * h * 5);
        }
        System.out.println("\nWhen does a 60-hour week stop being ahead? Track both from week 0:");
        System.out.printf("  %-8s %14s %14s%n", "by week", "features @40h", "features @60h");
        Plan p40 = new Plan(), p60 = new Plan();
        p60.hoursPerWeek = 60;
        for (int w : new int[]{4, 6, 8, 10, 12, 14, 16, 20}) {
            double f40 = 0, f60 = 0;
            for (int s = 0; s < SEEDS; s++) {
                f40 += Project.run(p40, 1000 + s).shippedByEndOfWeek(w - 1);
                f60 += Project.run(p60, 1000 + s).shippedByEndOfWeek(w - 1);
            }
            System.out.printf("  %-8d %14.1f %14.1f%s%n", w, f40 / SEEDS, f60 / SEEDS,
                    f60 < f40 ? "   <- crunch is now behind" : "");
        }
    }

    // ------------------------------------------------------------------
    static void people() {
        head("3. ADDING PEOPLE TO A LATE PROJECT");
        System.out.println("Two separate traps live here. Keep them apart.");
        System.out.println("\nTrap A - more people than there are things to work on.");
        System.out.printf("  %-44s %10s%n", "setup", "ship week");
        Agg base = run(new Plan());
        System.out.printf("  %-44s %10.1f%n", "5 engineers, 5 tasks open at once (baseline)", base.shipWeek());
        Plan idle = new Plan(); idle.teamSize = 7;
        System.out.printf("  %-44s %10.1f%n", "7 engineers, still only 5 tasks open", run(idle).shipWeek());
        Plan planned = new Plan(); planned.teamSize = 7; planned.wipTracksTeam = true;
        Agg pl = run(planned);
        System.out.printf("  %-44s %10.1f%n", "7 engineers, 7 tasks open (planned from day 0)", pl.shipWeek());
        System.out.println("  Two extra engineers are worth nothing until someone splits the work for them.");

        System.out.println("\nTrap B - the same 2 engineers, arriving later and later.");
        System.out.println("(WIP is raised with the team, so this is the pure cost of joining late.)");
        System.out.printf("%n  %-34s %10s %12s %14s%n", "intervention", "ship week", "vs nothing", "vs day-0 hire");
        for (int week : new int[]{0, 2, 4, 8, 12}) {
            Plan p = new Plan();
            p.wipTracksTeam = true;
            p.addPeopleOnDay = week * 5;
            p.addPeopleCount = 2;
            Agg g = run(p);
            System.out.printf("  %-34s %10.1f %+12.1f %+14.1f%n", "+2 engineers at week " + week,
                    g.shipWeek(), g.shipWeek() - base.shipWeek(), g.shipWeek() - pl.shipWeek());
        }
        Plan big = new Plan(); big.wipTracksTeam = true; big.addPeopleOnDay = 40; big.addPeopleCount = 5;
        Agg bg = run(big);
        System.out.printf("  %-34s %10.1f %+12.1f %14s%n", "+5 engineers at week 8", bg.shipWeek(),
                bg.shipWeek() - base.shipWeek(), "-");
        System.out.println("\nA hire that pays off in week 2 is a hire that loses money in week 12:");
        System.out.println("30 days of ramp-up plus a quarter of a mentor's day, spent from a budget");
        System.out.println("that has weeks left in it, not months.");
    }

    // ------------------------------------------------------------------
    static void scope() {
        head("4. CUTTING SCOPE: THE ONLY LEVER THAT MOVES FAST");
        Agg base = run(new Plan());
        System.out.printf("%n  %-38s %10s %12s %10s%n", "intervention", "ship week", "features cut", "vs nothing");
        System.out.printf("  %-38s %10.1f %12d %10s%n", "nothing", base.shipWeek(), 0, "-");
        for (int week : new int[]{1, 4, 8, 12}) {
            for (double f : new double[]{0.25, 0.40}) {
                Plan p = new Plan();
                p.cutScopeOnDay = week * 5;
                p.cutScopeFraction = f;
                Agg g = run(p);
                System.out.printf("  cut %2.0f%% of unstarted work at week %-8d %10.1f %12.1f %+10.1f%n",
                        f * 100, week, g.shipWeek(), g.featuresCut(), g.shipWeek() - base.shipWeek());
            }
        }
        System.out.println("\nSame 40% cut, made at different moments. Late cuts save less because");
        System.out.println("the work you cut at week 12 is work you have already half-built.");
    }

    // ------------------------------------------------------------------
    static void wip() {
        head("5. HOW MANY THINGS AT ONCE (WIP)");
        System.out.println("A 5-person team. Same work. Only the number of simultaneously open tasks changes.");
        System.out.printf("%n  %-6s %10s %16s %12s %12s%n", "WIP", "ship week", "shipped by wk 6", "idle days", "thrash days");
        for (int w : new int[]{1, 2, 3, 5, 8, 12, 20, 40}) {
            Plan p = new Plan();
            p.wipLimit = w;
            Agg g = run(p);
            System.out.printf("  %-6d %10.1f %16.1f %12.1f %12.1f%n", w, g.shipWeek(), g.byWeek6(), g.idle(), g.thrash());
        }
        System.out.println("\nThe last column is the cost of 'everything is a priority'. The middle");
        System.out.println("column is what you can hand over if the date cannot move.");
    }

    // ------------------------------------------------------------------
    static void utilization() {
        head("6. WHY A BUSY TEAM IS A SLOW TEAM");
        System.out.println("One team, requests arriving randomly. Service time = 1 unit on average.");
        System.out.println("Cycle time = how long from asking to done, including the wait.");
        System.out.printf("%n  %-14s %14s %14s   %s%n", "how busy", "cycle time", "Kingman est.", "");
        for (double rho : new double[]{0.5, 0.7, 0.8, 0.9, 0.95, 0.98}) {
            double sim = Queueing.simulate(rho, 1.0, 400_000, 7);
            int bar = (int) Math.min(60, Math.round(sim * 1.2));
            System.out.printf("  %-14s %14.2f %14.2f   %s%n",
                    String.format("%.0f%% busy", rho * 100), sim, Queueing.kingman(rho, 1.0), "#".repeat(bar));
        }
        System.out.println("\nGoing from 80% to 95% allocated does not buy 15% more output.");
        System.out.println("It multiplies how long everything sits waiting.");
    }

    // ------------------------------------------------------------------
    static void interrupts() {
        head("7. THE TAX ON A TEAM THAT IS ALSO ON SUPPORT");
        System.out.printf("%n  %-24s %10s %14s %14s%n", "unplanned work", "ship week", "vs 0%", "1/(1-f) says");
        double base = 0;
        for (double f : new double[]{0.0, 0.10, 0.20, 0.25, 0.35, 0.50}) {
            Plan p = new Plan();
            p.interruptFraction = f;
            Agg g = run(p);
            if (f == 0) base = g.shipWeek();
            System.out.printf("  %-24s %10.1f %13.2fx %13.2fx%n",
                    String.format("%.0f%% of capacity", f * 100), g.shipWeek(), g.shipWeek() / base, 1 / (1 - f));
        }
        System.out.println("\nThe simulation tracks 1/(1-f) almost exactly, and that formula is the");
        System.out.println("thing people get wrong in planning: giving 25% of a team to support is");
        System.out.println("not a 25% delay, it is a 33% delay - you divide by what is left, you do");
        System.out.println("not subtract what is gone. At 50% the project takes twice as long.");
    }

    // ------------------------------------------------------------------
    static void forecast() {
        head("8. FORECASTING WITH HISTORY INSTEAD OF HOPE");
        int[] history = {4, 6, 3, 7, 5, 2, 6, 5, 4, 8};   // items finished per week, last 10 weeks
        int remaining = 60;
        System.out.println("Last 10 weeks the team finished: " + Arrays.toString(history));
        System.out.printf("There are %d items left. The date already promised is week 10.%n%n", remaining);
        System.out.printf("Average velocity says: %.1f weeks. One number, no range, no scope creep.%n%n",
                Forecast.averageVelocityWeeks(history, remaining));

        double[] sim = Forecast.simulate(history, remaining, 0, 20_000, 42);
        System.out.println("Replaying the team's own history 20,000 times, with NO new scope:");
        Forecast.histogram(sim, 10);
        System.out.printf("%n  P50 = week %.0f    P85 = week %.0f    P95 = week %.0f%n",
                Forecast.percentile(sim, 0.5), Forecast.percentile(sim, 0.85), Forecast.percentile(sim, 0.95));
        System.out.printf("  Chance of hitting the promised week 10: %.0f%%%n", 100 * Forecast.chanceOfHitting(sim, 10));

        double[] creep = Forecast.simulate(history, remaining, 1.5, 20_000, 42);
        System.out.printf("%nNow add the 1.5 items per week that keep getting 'just added':%n");
        System.out.printf("  P50 = week %.0f    P85 = week %.0f    P95 = week %.0f%n",
                Forecast.percentile(creep, 0.5), Forecast.percentile(creep, 0.85), Forecast.percentile(creep, 0.95));
        System.out.printf("  Chance of hitting week 10: %.0f%%%n", 100 * Forecast.chanceOfHitting(creep, 10));

        System.out.println("\nHow much scope must go to make week 10 an 85% bet (no creep)?");
        for (int items : new int[]{60, 50, 40, 30, 25, 20}) {
            double[] s = Forecast.simulate(history, items, 0, 20_000, 42);
            System.out.printf("  %2d items left -> P85 = week %.0f, chance of week 10 = %3.0f%%%n",
                    items, Forecast.percentile(s, 0.85), 100 * Forecast.chanceOfHitting(s, 10));
        }
    }

    static void head(String s) {
        System.out.println("\n" + "=".repeat(78) + "\n" + s + "\n" + "=".repeat(78));
    }

    public static void main(String[] args) {
        String which = args.length > 0 ? args[0] : "all";
        if (which.equals("all") || which.equals("estimate")) estimate();
        if (which.equals("all") || which.equals("overtime")) overtime();
        if (which.equals("all") || which.equals("people")) people();
        if (which.equals("all") || which.equals("scope")) scope();
        if (which.equals("all") || which.equals("wip")) wip();
        if (which.equals("all") || which.equals("utilization")) utilization();
        if (which.equals("all") || which.equals("interrupts")) interrupts();
        if (which.equals("all") || which.equals("forecast")) forecast();
    }
}
