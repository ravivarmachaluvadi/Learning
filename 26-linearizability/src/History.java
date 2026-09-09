import java.util.*;

/**
 * A history is just the list of operations an outside observer recorded,
 * with their start and end times. Nothing more. Every question this lab asks
 * is a question about a history.
 */
final class History {
    final List<Op> ops = new ArrayList<>();
    String name = "history";

    History add(Op o) { ops.add(o); return this; }
    int size() { return ops.size(); }
    Op get(int i) { return ops.get(i); }

    static History of(String name, Op... os) {
        History h = new History(); h.name = name;
        for (Op o : os) h.add(o);
        return h;
    }

    /** Only the operations on one object — used to demonstrate the locality theorem. */
    History project(String obj) {
        History h = new History(); h.name = name + "|" + obj;
        for (Op o : ops) if (o.obj.equals(obj)) h.add(o);
        return h;
    }

    /**
     * Draws the history as a timeline, one lane per client. This is the picture
     * you must be able to read to think about linearizability at all: each bar
     * is "the client asked here, and heard back here".
     */
    String render() { return render(null); }

    /** @param order optional linearization order (op indices); shown as numbered dots. */
    String render(int[] order) {
        boolean many = ops.stream().map(o -> o.obj).distinct().count() > 1;
        long min = Long.MAX_VALUE, max = 0;
        for (Op o : ops) { min = Math.min(min, o.invoke); max = Math.max(max, o.pending ? o.invoke + 4 : o.response); }
        if (ops.isEmpty()) return "(empty)\n";
        double span = Math.max(1, max - min);
        int width = 60;
        Map<Integer, Integer> pos = new HashMap<>();
        if (order != null) for (int i = 0; i < order.length; i++) pos.put(order[i], i + 1);

        Map<Integer, List<Op>> byClient = new TreeMap<>();
        for (Op o : ops) byClient.computeIfAbsent(o.client, k -> new ArrayList<>()).add(o);

        StringBuilder sb = new StringBuilder();
        sb.append("  ").append(name).append("   (time runs left to right)\n");
        for (var e : byClient.entrySet()) {
            char[] lane = new char[width + 1];
            Arrays.fill(lane, ' ');
            StringBuilder labels = new StringBuilder();
            for (Op o : e.getValue()) {
                int idx = ops.indexOf(o);
                int a = (int) Math.round((o.invoke - min) / span * width);
                int b = o.pending ? width : (int) Math.round((o.response - min) / span * width);
                if (b <= a) b = a + 1;
                if (b > width) b = width;
                lane[a] = '[';
                for (int i = a + 1; i < b; i++) lane[i] = '-';
                lane[b] = o.pending ? '?' : ']';
                String tag = (many ? o.obj + "." : "") + o.label() + (pos.containsKey(idx) ? " #" + pos.get(idx) : "");
                int mid = (a + b) / 2 - tag.length() / 2;
                while (labels.length() < Math.max(0, mid)) labels.append(' ');
                labels.append(tag).append(' ');
            }
            sb.append(String.format("  C%-2d %s%n", e.getKey(), new String(lane)));
            sb.append("      ").append(labels).append('\n');
        }
        return sb.toString();
    }
}
