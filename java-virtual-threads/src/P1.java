/** Exercise P1 — predict before running! (see EXERCISES.md)
 *  Run:  java -Djdk.virtualThreadScheduler.parallelism=1 src/P1.java */
public class P1 {
    public static void main(String[] a) throws Exception {
        long t0 = System.nanoTime();
        Thread[] ts = new Thread[3];
        for (int i = 0; i < 3; i++)
            ts[i] = Thread.startVirtualThread(() -> {
                try { Thread.sleep(100); } catch (InterruptedException e) {}
                long end = System.nanoTime() + 100_000_000L;   // ~100 ms of CPU spinning
                while (System.nanoTime() < end) { }
            });
        for (Thread t : ts) t.join();
        System.out.println((System.nanoTime() - t0) / 1_000_000 + " ms");
    }
}
