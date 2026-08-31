/**
 * DEMO 5 — The trap: virtual threads do NOT speed up CPU work.
 *
 * Virtual threads add nothing when tasks COMPUTE instead of WAIT: the number
 * of carriers equals the number of cores, and a computing thread never
 * unmounts. 16 CPU-burning tasks finish in the same wall time whether you
 * use a platform pool or virtual threads.
 *
 * Run:  java src/CpuBoundTrap.java
 */
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CpuBoundTrap {

    public static void main(String[] args) throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();
        int tasks = 16;
        System.out.printf("%d cores; %d tasks; each task is a pure computation (~300 ms of math).%n%n", cores, tasks);

        System.out.printf("A) Fixed pool of %d platform threads (one per core): %,d ms%n",
                cores, run(Executors.newFixedThreadPool(cores), tasks));
        System.out.printf("B) One virtual thread per task:                      %,d ms%n%n",
                run(Executors.newVirtualThreadPerTaskExecutor(), tasks));

        System.out.println("Roughly the same. Virtual threads multiply how many tasks can WAIT,");
        System.out.println("never how many can COMPUTE — that is fixed by your CPU cores.");
        System.out.println("Bonus trap: a long-running CPU loop on a virtual thread also hogs its");
        System.out.println("carrier — the scheduler does not forcibly interrupt (preempt) running code.");
    }

    static long run(ExecutorService executor, int tasks) throws Exception {
        long t0 = System.nanoTime();
        try (ExecutorService ex = executor) {
            for (int i = 0; i < tasks; i++) ex.submit(CpuBoundTrap::burnCpu);
        }
        return (System.nanoTime() - t0) / 1_000_000;
    }

    static volatile double sink;                 // defeat dead-code elimination
    static void burnCpu() {
        double x = 0;
        for (int i = 0; i < 60_000_000; i++) x += Math.sqrt(i);
        sink = x;
    }
}
