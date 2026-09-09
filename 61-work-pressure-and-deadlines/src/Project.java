import java.util.ArrayList;
import java.util.List;







/**
 * A project simulator with the four things a Gantt chart leaves out:
 * variability, interrupts, the cost of adding people, and the quality loop
 * (pressure -> defects -> rework arriving later -> more pressure).
 */
final class Project {
    static final int RAMP_DAYS = 30;      // a new joiner reaches full speed here
    static final double MENTOR_DRAIN = 0.25;  // of one veteran-day, per joiner in ramp
    static final int MAX_DAYS = 3000;

    static Result run(Plan p, int worldSeed) {
        Rand work = Rand.stream(worldSeed, 1);
        Rand qa = Rand.stream(worldSeed, 2);
        Result r = new Result();
        r.promisedWeeks = p.promisedWeeks;
        r.pointEstimateWeeks = p.pointEstimateWeeks();

        // Task durations are drawn ONCE from the world seed, so two plans
        // compared under the same seed face the identical universe of work.
        double meanDays = p.medianTaskDays * Math.exp(p.sigma * p.sigma / 2);
        List<Task> backlog = new ArrayList<>();
        for (int i = 0; i < p.tasks; i++) {
            double d = p.skewOn ? work.lognormal(p.medianTaskDays, p.sigma) : meanDays;
            backlog.add(new Task(i, Math.max(0.25, d), false));
        }
        List<Task> active = new ArrayList<>();
        List<Defect> openDefects = new ArrayList<>();
        List<Integer> engineerStartDay = new ArrayList<>();
        for (int i = 0; i < p.teamSize; i++) engineerStartDay.add(0);

        int[] byWeek = new int[MAX_DAYS / 5 + 2];
        double defectAcc = 0;
        int nextTaskId = 10_000;
        int shipped = 0, day = 0;

        for (day = 0; day < MAX_DAYS; day++) {
            // ---- interventions ------------------------------------------
            if (day == p.addPeopleOnDay)
                for (int i = 0; i < p.addPeopleCount; i++) engineerStartDay.add(day);
            if (day == p.cutScopeOnDay && p.cutScopeFraction > 0) {
                int keep = (int) Math.round(backlog.size() * (1 - p.cutScopeFraction));
                int cut = backlog.size() - keep;
                for (int i = 0; i < cut; i++) backlog.remove(backlog.size() - 1);
                r.featuresCut += cut;
            }

            // ---- is it shipped? -----------------------------------------
            boolean workLeft = !backlog.isEmpty() || !active.isEmpty();
            boolean knownBugsOpen = false;
            for (Defect d : openDefects) if (d.discoverDay <= day) knownBugsOpen = true;
            if (!workLeft && !knownBugsOpen) break;

            // ---- today's capacity, one subtraction at a time -------------
            int n = engineerStartDay.size();
            double ot = (p.hoursPerWeek > 40 && day >= p.overtimeStartsOnDay) ? p.hoursPerWeek - 40 : 0;
            int otWeeks = ot > 0 ? (day - p.overtimeStartsOnDay) / 5 : 0;
            // Sustained overtime buys hours and spends energy. The floor (0.6)
            // is where a tired engineer's hour is worth 60% of a rested one.
            double energy = ot > 0 ? Math.max(0.60, 1 - 0.03 * otWeeks * (ot / 20.0)) : 1.0;
            double perPersonDays = ((40 + ot) / 5.0) * energy / 8.0;
            double raw = 0;
            int joinersInRamp = 0;
            for (int s : engineerStartDay) {
                if (s == 0) { raw += perPersonDays; continue; }
                double ramp = Math.min(1.0, Math.max(0.0, (day - s) / (double) RAMP_DAYS));
                if (ramp < 1.0) joinersInRamp++;
                raw += perPersonDays * ramp;
            }
            // Hours bought by overtime that fatigue immediately took back.
            r.fatigueLostDays += n * ((40 + ot) / 5.0) * (1 - energy) / 8.0;
            double afterRamp = raw;
            double mentor = MENTOR_DRAIN * joinersInRamp * perPersonDays;
            double afterMentor = Math.max(0, afterRamp - mentor);
            r.mentorLostDays += afterRamp - afterMentor;

            double comm = p.commOverheadOn ? Math.min(0.40, 0.02 * (n - 1)) : 0;
            double afterComm = afterMentor * (1 - comm);
            r.commLostDays += afterMentor - afterComm;

            double interrupts = p.interruptsOn ? p.interruptFraction : 0;
            double capacity = afterComm * (1 - interrupts);
            r.interruptLostDays += afterComm - capacity;
            r.totalEngineerDaysAvailable += capacity;

            // ---- pull work in, respecting the WIP limit ------------------
            // Rework preempts features: bugs jump the queue, which is exactly
            // why a "code complete" project can sit at 95% done for a month.
            for (Defect d : new ArrayList<>(openDefects)) {
                if (d.discoverDay <= day) {
                    backlog.add(0, new Task(nextTaskId++, d.reworkDays, true));
                    openDefects.remove(d);
                    r.defectsFixed++;
                }
            }
            int wip = p.wipTracksTeam ? n : p.wipLimit;
            while (active.size() < wip && !backlog.isEmpty()) {
                Task t = backlog.remove(0);
                t.startedDay = day;
                active.add(t);
            }

            // ---- spend the capacity -------------------------------------
            int a = active.size();
            double usable = capacity, idle = 0;
            if (a == 0) { r.idleEngineerDays += capacity; continue; }
            double perPerson = capacity / n;
            if (a < n) {                      // more people than open tasks: capacity evaporates
                usable = a * perPerson;
                idle = capacity - usable;
                r.idleEngineerDays += idle;
            } else if (a > n) {               // more open tasks than people: context switching
                double eff = 1.0 / (1 + 0.2 * ((double) a / n - 1));
                usable = capacity * eff;
                r.thrashLostDays += capacity - usable;
            }

            double share = usable / a;
            double spent = 0;
            for (Task t : new ArrayList<>(active)) {
                double give = Math.min(share, t.remaining);
                t.remaining -= give;
                spent += give;
                if (t.remaining <= 1e-9) {
                    t.doneDay = day;
                    active.remove(t);
                    if (t.rework) r.reworkEngineerDays += t.trueDays;
                    else { shipped++; r.engineerDaysSpentOnFeatures += t.trueDays; }
                }
            }
            byWeek[day / 5] = shipped;

            // ---- the quality loop ---------------------------------------
            if (p.reworkOn) {
                // Pressure does not remove work, it moves work into the future.
                double qualityMult = 1 + (ot / 20.0) * (1 + 0.08 * otWeeks);
                defectAcc += spent * p.defectsPerEngineerDay * qualityMult;
                while (defectAcc >= 1) {
                    defectAcc -= 1;
                    int disc = day + (int) Math.round(p.discoveryDelayDays * (0.5 + qa.next()));
                    openDefects.add(new Defect(day, disc, p.reworkDaysPerDefect * (0.5 + qa.next())));
                    r.defectsMade++;
                }
            }
        }

        r.shipDay = day;
        r.shipWeek = day / 5.0;
        r.featuresShipped = shipped;
        r.defectsEscaped = r.defectsMade - r.defectsFixed;
        int weeks = day / 5 + 1;
        r.shippedByWeek = new int[weeks];
        int running = 0;
        for (int w = 0; w < weeks; w++) { running = Math.max(running, byWeek[w]); r.shippedByWeek[w] = running; }
        return r;
    }
}
