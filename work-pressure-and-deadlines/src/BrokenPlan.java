/**
 * A real-looking capacity spreadsheet with THREE assumptions that are wrong,
 * and one bonus mistake in the recovery plan. Exercise 6 asks you to find them
 * before you look at SOLUTIONS.md.
 *
 *   java src/BrokenPlan.java
 *
 * Nothing here throws an exception. That is the point: a broken plan does not
 * crash, it just quietly promises a date the world will not deliver.
 */
public class BrokenPlan {

    public static void main(String[] args) {
        int tasks = 40;
        double estimatePerTask = 3.2;    // what the team said in planning poker
        int engineers = 5;
        double daysPerWeek = 5;

        System.out.println("""
                =========================================================
                  Q3 PLANNING - CAPACITY SHEET (as presented to the VP)
                =========================================================""");
        double totalEstimate = tasks * estimatePerTask;
        System.out.printf("  Scope                       %d tasks x %.1f days = %.0f engineer-days%n",
                tasks, estimatePerTask, totalEstimate);
        double capacityPerWeek = engineers * daysPerWeek;
        System.out.printf("  Capacity                    %d engineers x %.0f days   = %.0f engineer-days/week%n",
                engineers, daysPerWeek, capacityPerWeek);
        double build = totalEstimate / capacityPerWeek;
        System.out.printf("  Build                       %.0f / %.0f                  = %.1f weeks%n",
                totalEstimate, capacityPerWeek, build);
        System.out.printf("  Test & fix                  fixed allowance         = 1.0 weeks%n");
        System.out.printf("  Contingency                 10%%                     = %.1f weeks%n", build * 0.1);
        System.out.printf("  ---------------------------------------------------------%n");
        System.out.printf("  COMMITTED DATE                                      = %.0f weeks%n%n",
                Math.ceil(build + 1 + build * 0.1));
        System.out.println("  Recovery option if we slip: 2 contractors from week 8,");
        System.out.printf("  adding %.0f engineer-days/week from the day they start.%n%n", 2 * daysPerWeek);

        System.out.println("Now run the same project through the simulator:\n");
        Plan p = new Plan();
        p.promisedWeeks = Math.ceil(build + 1 + build * 0.1);
        double total = 0;
        for (int s = 0; s < 40; s++) total += Project.run(p, 1000 + s).shipWeek;
        System.out.printf("  Simulated ship week (40 universes): %.1f weeks against a %.0f-week commitment.%n",
                total / 40, p.promisedWeeks);

        Plan withContractors = p.copy();
        withContractors.wipTracksTeam = true;
        withContractors.addPeopleOnDay = 40;
        withContractors.addPeopleCount = 2;
        double t2 = 0;
        for (int s = 0; s < 40; s++) t2 += Project.run(withContractors, 1000 + s).shipWeek;
        System.out.printf("  With the week-8 contractors:        %.1f weeks.%n%n", t2 / 40);
        System.out.println("Three assumptions in the sheet are wrong, and the recovery plan");
        System.out.println("contains a fourth. Name all four before opening SOLUTIONS.md.");
    }
}
