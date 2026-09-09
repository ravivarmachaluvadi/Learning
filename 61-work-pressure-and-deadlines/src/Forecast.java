import java.util.Arrays;

/**
 * Forecasting from history instead of from hope.
 *
 * The whole idea: stop asking "how long will each task take?" (a question
 * nobody can answer) and start asking "how many items has this team actually
 * finished per week?" (a question the board already answered). Then replay
 * that history thousands of times to get a RANGE, not a date.
 */
final class Forecast {

    /** The answer you get from "average velocity" — one number, no range. */
    static double averageVelocityWeeks(int[] history, int remaining) {
        double sum = 0;
        for (int h : history) sum += h;
        return remaining / (sum / history.length);
    }

    /**
     * Bootstrap Monte Carlo: each simulated week draws a real past week's
     * throughput at random. creepPerWeek models the scope that arrives after
     * the plan is signed — the single most-forgotten term in any forecast.
     */
    static double[] simulate(int[] history, int remaining, double creepPerWeek, int trials, int seed) {
        double[] out = new double[trials];
        Rand r = new Rand(seed);
        for (int t = 0; t < trials; t++) {
            double left = remaining;
            int week = 0;
            while (left > 0 && week < 300) {
                int tp = history[(int) (r.next() * history.length)];
                left -= tp;
                left += creepPerWeek;
                week++;
            }
            out[t] = week;
        }
        Arrays.sort(out);
        return out;
    }

    static double percentile(double[] sorted, double p) {
        int i = (int) Math.floor(p * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    /** The number you take into the meeting: P(we finish by week W). */
    static double chanceOfHitting(double[] sorted, double weeks) {
        int c = 0;
        for (double v : sorted) if (v <= weeks) c++;
        return c / (double) sorted.length;
    }

    static void histogram(double[] sorted, double promised) {
        int lo = (int) sorted[0], hi = (int) sorted[sorted.length - 1];
        int[] bins = new int[hi - lo + 1];
        for (double v : sorted) bins[(int) v - lo]++;
        int max = 0;
        for (int b : bins) max = Math.max(max, b);
        for (int i = 0; i < bins.length; i++) {
            int width = max == 0 ? 0 : (int) Math.round(40.0 * bins[i] / max);
            String mark = (lo + i == (int) Math.round(promised)) ? "  <== the promised date" : "";
            System.out.printf("  week %3d | %-40s %5.1f%%%s%n",
                    lo + i, "#".repeat(width), 100.0 * bins[i] / sorted.length, mark);
        }
    }
}
