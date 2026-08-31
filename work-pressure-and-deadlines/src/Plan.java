/**
 * Everything a manager can decide before or during the project.
 *
 * Every field here is a lever someone can pull in a real conversation. The
 * simulation exists to answer one question: which of these levers actually
 * moves the ship date, and by how much?
 */
final class Plan {
    // --- the work itself -------------------------------------------------
    int tasks = 40;                  // number of feature tasks
    double medianTaskDays = 4.0;     // TRUE median duration, unknown to anyone
    double sigma = 0.7;              // spread of the duration distribution
    double estimateOptimism = 0.20;  // team's estimate = median * (1 - this)

    // --- the team --------------------------------------------------------
    int teamSize = 5;
    double hoursPerWeek = 40;
    double interruptFraction = 0.25; // support, meetings, incidents, reviews

    // --- how work is scheduled -------------------------------------------
    int wipLimit = 5;                // how many tasks are open at once
    boolean wipTracksTeam = false;   // if true, the WIP limit follows the team size

    // --- quality feedback loop -------------------------------------------
    double defectsPerEngineerDay = 0.09;
    int discoveryDelayDays = 15;     // defects surface long after they are made
    double reworkDaysPerDefect = 1.2;

    // --- interventions (day 0 = project start, 5 workdays per week) -------
    int addPeopleOnDay = -1;         // -1 = never
    int addPeopleCount = 0;
    int cutScopeOnDay = -1;
    double cutScopeFraction = 0.0;   // fraction of NOT-YET-STARTED tasks removed
    int overtimeStartsOnDay = 0;     // when hoursPerWeek above 40 kicks in

    // --- switches used to attribute the overrun to its causes -------------
    boolean skewOn = true;           // duration variability
    boolean interruptsOn = true;
    boolean reworkOn = true;
    boolean commOverheadOn = true;

    // --- what was promised -----------------------------------------------
    double promisedWeeks = 6.0;

    Plan copy() {
        Plan p = new Plan();
        p.tasks = tasks; p.medianTaskDays = medianTaskDays; p.sigma = sigma;
        p.estimateOptimism = estimateOptimism; p.teamSize = teamSize;
        p.hoursPerWeek = hoursPerWeek; p.interruptFraction = interruptFraction;
        p.wipLimit = wipLimit; p.wipTracksTeam = wipTracksTeam; p.defectsPerEngineerDay = defectsPerEngineerDay;
        p.discoveryDelayDays = discoveryDelayDays; p.reworkDaysPerDefect = reworkDaysPerDefect;
        p.addPeopleOnDay = addPeopleOnDay; p.addPeopleCount = addPeopleCount;
        p.cutScopeOnDay = cutScopeOnDay; p.cutScopeFraction = cutScopeFraction;
        p.overtimeStartsOnDay = overtimeStartsOnDay;
        p.skewOn = skewOn; p.interruptsOn = interruptsOn; p.reworkOn = reworkOn;
        p.commOverheadOn = commOverheadOn; p.promisedWeeks = promisedWeeks;
        return p;
    }

    /** The number everyone in the room believes on day one. */
    double pointEstimateWeeks() {
        double perTask = medianTaskDays * (1 - estimateOptimism);
        double engineerDays = tasks * perTask;
        return engineerDays / teamSize / 5.0;   // 5 workdays per week
    }
}
