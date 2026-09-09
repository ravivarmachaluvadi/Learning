/**
 * Why a busy team is a slow team.
 *
 * A team that is 95% allocated is not 5% away from a team that is 90%
 * allocated. Waiting time grows with rho/(1-rho), so the last few points of
 * "efficiency" cost more delay than everything before them combined. This is
 * the mechanism behind "we are all flat out and nothing is moving".
 */
final class Queueing {

    /** Mean time from request arriving to request finished, in units of service time. */
    static double simulate(double rho, double cv, int jobs, int seed) {
        Rand r = new Rand(seed);
        double meanService = 1.0;
        double meanGap = meanService / rho;
        double sigma = Math.sqrt(Math.log(1 + cv * cv));
        double median = meanService * Math.exp(-sigma * sigma / 2);

        double clock = 0, free = 0, totalCycle = 0;
        int counted = 0, warmup = jobs / 10;
        for (int i = 0; i < jobs; i++) {
            clock += r.exp(meanGap);                 // arrival
            double start = Math.max(clock, free);    // waits if the team is busy
            double service = r.lognormal(median, sigma);
            free = start + service;
            if (i >= warmup) { totalCycle += free - clock; counted++; }
        }
        return totalCycle / counted;
    }

    /** Kingman's approximation, for comparison with the simulation. */
    static double kingman(double rho, double cv) {
        return 1 + (rho / (1 - rho)) * ((1 + cv * cv) / 2);
    }
}
