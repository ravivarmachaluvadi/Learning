import java.util.*;

final class Result {
    int shipDay; double shipWeek;
    double promisedWeeks, pointEstimateWeeks;
    int featuresShipped, featuresCut;
    int defectsMade, defectsFixed, defectsEscaped;
    double reworkEngineerDays, idleEngineerDays, thrashLostDays,
           interruptLostDays, commLostDays, mentorLostDays, fatigueLostDays;
    double engineerDaysSpentOnFeatures, totalEngineerDaysAvailable;
    int[] shippedByWeek = new int[0];
    /** Cumulative features shipped by the end of the given week (0-based). */
    int shippedByEndOfWeek(int w) {
        if (shippedByWeek.length == 0) return 0;
        return shippedByWeek[Math.min(w, shippedByWeek.length - 1)];
    }
}
