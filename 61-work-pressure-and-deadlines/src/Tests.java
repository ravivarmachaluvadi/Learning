import java.util.Arrays;

/**
 * Executable documentation. Every test is named after a claim the README
 * makes. If a claim is not tested here, the README is not allowed to state it
 * as a measured fact.
 *
 *   java src/Tests.java
 */
public class Tests {
    static int passed = 0, failed = 0;
    static final int SEEDS = 40;

    static double meanShipWeek(Plan p) {
        double t = 0;
        for (int s = 0; s < SEEDS; s++) t += Project.run(p, 1000 + s).shipWeek;
        return t / SEEDS;
    }

    static double meanShippedByWeek(Plan p, int week) {
        double t = 0;
        for (int s = 0; s < SEEDS; s++) t += Project.run(p, 1000 + s).shippedByEndOfWeek(week - 1);
        return t / SEEDS;
    }

    static void check(String name, boolean ok, String detail) {
        System.out.printf("  %s %-56s %s%n", ok ? "PASS" : "FAIL", name, detail);
        if (ok) passed++; else failed++;
    }

    // ---- estimation ---------------------------------------------------
    static void the_promised_date_is_missed_in_almost_every_universe() {
        Plan p = new Plan();
        int missed = 0;
        for (int s = 0; s < SEEDS; s++) if (Project.run(p, 1000 + s).shipWeek > p.promisedWeeks) missed++;
        check("the_promised_date_is_missed_in_almost_every_universe", missed >= SEEDS - 1,
                missed + "/" + SEEDS + " universes ship after week " + (int) p.promisedWeeks);
    }

    static void summing_most_likely_estimates_understates_the_total() {
        // A right-skewed task has a mean above its median. Estimating the
        // median of each task and adding them up is a systematic undercount.
        Rand r = new Rand(7);
        double median = 4, sigma = 0.7, sum = 0;
        int n = 100_000;
        for (int i = 0; i < n; i++) sum += r.lognormal(median, sigma);
        double actualMean = sum / n, predicted = median * Math.exp(sigma * sigma / 2);
        check("summing_most_likely_estimates_understates_the_total",
                actualMean > median * 1.15 && Math.abs(actualMean - predicted) < 0.05,
                String.format("median %.1fd but mean %.2fd (theory %.2fd) = %.0f%% undercount",
                        median, actualMean, predicted, 100 * (actualMean / median - 1)));
    }

    static void even_a_perfect_world_beats_the_estimate() {
        Plan p = new Plan();
        p.skewOn = false; p.interruptsOn = false; p.reworkOn = false; p.commOverheadOn = false;
        double pure = meanShipWeek(p);
        check("even_a_perfect_world_beats_the_estimate", pure > p.pointEstimateWeeks() * 1.5,
                String.format("%.1f weeks of pure coding vs a %.1f-week estimate", pure, p.pointEstimateWeeks()));
    }

    // ---- the crunch ---------------------------------------------------
    static void overtime_is_ahead_early_and_behind_at_the_end() {
        Plan p40 = new Plan(), p60 = new Plan();
        p60.hoursPerWeek = 60;
        double early40 = meanShippedByWeek(p40, 6), early60 = meanShippedByWeek(p60, 6);
        double ship40 = meanShipWeek(p40), ship60 = meanShipWeek(p60);
        check("overtime_is_ahead_early_and_behind_at_the_end", early60 > early40 && ship60 > ship40,
                String.format("wk6: %.1f vs %.1f features; ship: %.1f vs %.1f weeks", early60, early40, ship60, ship40));
    }

    static void overtime_pushes_defects_into_production() {
        double d40 = 0, d60 = 0;
        Plan p40 = new Plan(), p60 = new Plan();
        p60.hoursPerWeek = 60;
        for (int s = 0; s < SEEDS; s++) {
            d40 += Project.run(p40, 1000 + s).defectsEscaped;
            d60 += Project.run(p60, 1000 + s).defectsEscaped;
        }
        check("overtime_pushes_defects_into_production", d60 > d40 * 1.5,
                String.format("%.1f escaped at 60h vs %.1f at 40h", d60 / SEEDS, d40 / SEEDS));
    }

    // ---- people -------------------------------------------------------
    static void extra_engineers_with_no_extra_open_work_do_nothing() {
        Plan five = new Plan();
        Plan sevenStuck = new Plan(); sevenStuck.teamSize = 7;              // WIP still 5
        Plan sevenReal = new Plan(); sevenReal.teamSize = 7; sevenReal.wipTracksTeam = true;
        double a = meanShipWeek(five), b = meanShipWeek(sevenStuck), c = meanShipWeek(sevenReal);
        check("extra_engineers_with_no_extra_open_work_do_nothing", b >= a && c < a * 0.9,
                String.format("5 eng %.1f wk / 7 eng stuck at WIP 5 %.1f wk / 7 eng WIP 7 %.1f wk", a, b, c));
    }

    static void hiring_early_helps_and_hiring_late_hurts() {
        Plan base = new Plan();
        double none = meanShipWeek(base);
        Plan early = new Plan(); early.wipTracksTeam = true; early.addPeopleOnDay = 10; early.addPeopleCount = 2;
        Plan late = new Plan(); late.wipTracksTeam = true; late.addPeopleOnDay = 60; late.addPeopleCount = 2;
        double e = meanShipWeek(early), l = meanShipWeek(late);
        check("hiring_early_helps_and_hiring_late_hurts", e < none && l > none,
                String.format("nothing %.1f / +2 at wk2 %.1f / +2 at wk12 %.1f", none, e, l));
    }

    // ---- scope --------------------------------------------------------
    static void cutting_scope_early_saves_more_than_cutting_late() {
        Plan early = new Plan(); early.cutScopeOnDay = 5; early.cutScopeFraction = 0.4;
        Plan late = new Plan(); late.cutScopeOnDay = 60; late.cutScopeFraction = 0.4;
        double e = meanShipWeek(early), l = meanShipWeek(late), none = meanShipWeek(new Plan());
        check("cutting_scope_early_saves_more_than_cutting_late", e < l && l < none,
                String.format("no cut %.1f / cut at wk12 %.1f / cut at wk1 %.1f", none, l, e));
    }

    static void cutting_scope_beats_every_other_lever() {
        Plan cut = new Plan(); cut.cutScopeOnDay = 20; cut.cutScopeFraction = 0.4;
        Plan crunch = new Plan(); crunch.hoursPerWeek = 60; crunch.overtimeStartsOnDay = 20;
        Plan hire = new Plan(); hire.wipTracksTeam = true; hire.addPeopleOnDay = 20; hire.addPeopleCount = 2;
        double c = meanShipWeek(cut), o = meanShipWeek(crunch), h = meanShipWeek(hire);
        check("cutting_scope_beats_every_other_lever", c < o && c < h,
                String.format("at week 4: cut 40%% -> %.1f / crunch -> %.1f / hire 2 -> %.1f", c, o, h));
    }

    // ---- flow ---------------------------------------------------------
    static void wip_has_a_sweet_spot_at_about_team_size() {
        double best = Double.MAX_VALUE;
        int bestWip = -1;
        for (int w = 1; w <= 12; w++) {
            Plan p = new Plan(); p.wipLimit = w;
            double s = meanShipWeek(p);
            if (s < best) { best = s; bestWip = w; }
        }
        check("wip_has_a_sweet_spot_at_about_team_size", bestWip >= 4 && bestWip <= 6,
                "best WIP = " + bestWip + " for a team of 5, at " + String.format("%.1f", best) + " weeks");
    }

    static void high_wip_means_nothing_is_finished_early() {
        Plan good = new Plan(); good.wipLimit = 5;
        Plan bad = new Plan(); bad.wipLimit = 40;
        double g = meanShippedByWeek(good, 6), b = meanShippedByWeek(bad, 6);
        check("high_wip_means_nothing_is_finished_early", b < g / 5,
                String.format("by week 6: %.1f features done at WIP 5, %.1f at WIP 40", g, b));
    }

    // ---- queueing -----------------------------------------------------
    static void a_busier_team_is_a_slower_team() {
        double at80 = Queueing.simulate(0.8, 1.0, 200_000, 3);
        double at95 = Queueing.simulate(0.95, 1.0, 200_000, 3);
        check("a_busier_team_is_a_slower_team", at95 > at80 * 3,
                String.format("cycle time %.1fx service at 80%% busy, %.1fx at 95%% busy", at80, at95));
    }

    static void the_interrupt_tax_is_a_division_not_a_subtraction() {
        Plan zero = new Plan(); zero.interruptFraction = 0;
        Plan half = new Plan(); half.interruptFraction = 0.5;
        double ratio = meanShipWeek(half) / meanShipWeek(zero);
        check("the_interrupt_tax_is_a_division_not_a_subtraction", ratio > 1.75 && ratio < 2.25,
                String.format("giving away 50%% of capacity costs %.2fx the time (1/(1-0.5) = 2.00x)", ratio));
    }

    // ---- forecasting --------------------------------------------------
    static void average_velocity_is_a_coin_flip_not_a_commitment() {
        int[] history = {4, 6, 3, 7, 5, 2, 6, 5, 4, 8};
        double avg = Forecast.averageVelocityWeeks(history, 60);
        double[] sim = Forecast.simulate(history, 60, 0, 20_000, 42);
        double chance = Forecast.chanceOfHitting(sim, avg);
        check("average_velocity_is_a_coin_flip_not_a_commitment", chance > 0.3 && chance < 0.7,
                String.format("average velocity says week %.0f; chance of making it = %.0f%%", avg, 100 * chance));
    }

    static void p85_is_later_than_the_average_answer() {
        int[] history = {4, 6, 3, 7, 5, 2, 6, 5, 4, 8};
        double[] sim = Forecast.simulate(history, 60, 0, 20_000, 42);
        check("p85_is_later_than_the_average_answer",
                Forecast.percentile(sim, 0.85) > Forecast.averageVelocityWeeks(history, 60),
                String.format("average %.0f, P85 %.0f, P95 %.0f", Forecast.averageVelocityWeeks(history, 60),
                        Forecast.percentile(sim, 0.85), Forecast.percentile(sim, 0.95)));
    }

    static void scope_creep_moves_the_date_more_than_anything_else() {
        int[] history = {4, 6, 3, 7, 5, 2, 6, 5, 4, 8};
        double clean = Forecast.percentile(Forecast.simulate(history, 60, 0, 20_000, 42), 0.5);
        double creep = Forecast.percentile(Forecast.simulate(history, 60, 1.5, 20_000, 42), 0.5);
        check("scope_creep_moves_the_date_more_than_anything_else", creep >= clean * 1.4,
                String.format("1.5 new items a week moves P50 from week %.0f to week %.0f", clean, creep));
    }

    // ---- the quality loop ---------------------------------------------
    static void rework_arrives_long_after_the_code_that_caused_it() {
        Result r = Project.run(new Plan(), 1000);
        check("rework_arrives_long_after_the_code_that_caused_it",
                r.reworkEngineerDays > 0 && r.defectsFixed > 0,
                String.format("%.1f engineer-days of rework from %d defects, %d escaped to production",
                        r.reworkEngineerDays, r.defectsFixed, r.defectsEscaped));
    }

    public static void main(String[] args) {
        System.out.println("\nScenario checks (each one is a claim the README makes):\n");
        the_promised_date_is_missed_in_almost_every_universe();
        summing_most_likely_estimates_understates_the_total();
        even_a_perfect_world_beats_the_estimate();
        overtime_is_ahead_early_and_behind_at_the_end();
        overtime_pushes_defects_into_production();
        extra_engineers_with_no_extra_open_work_do_nothing();
        hiring_early_helps_and_hiring_late_hurts();
        cutting_scope_early_saves_more_than_cutting_late();
        cutting_scope_beats_every_other_lever();
        wip_has_a_sweet_spot_at_about_team_size();
        high_wip_means_nothing_is_finished_early();
        a_busier_team_is_a_slower_team();
        the_interrupt_tax_is_a_division_not_a_subtraction();
        average_velocity_is_a_coin_flip_not_a_commitment();
        p85_is_later_than_the_average_answer();
        scope_creep_moves_the_date_more_than_anything_else();
        rework_arrives_long_after_the_code_that_caused_it();
        System.out.printf("%n  %d passed, %d failed%n%n", passed, failed);
        if (failed > 0) System.exit(1);
    }
}
