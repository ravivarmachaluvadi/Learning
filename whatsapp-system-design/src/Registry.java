import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Which of our 10,000 connection servers is holding this device's socket
 *  right now?"
 *
 * This is the piece that does not exist in a single-server chat toy and is
 * unavoidable the moment you have two servers. It is small, hot, and volatile:
 * one tiny row per ONLINE device, rewritten on every connect and disconnect.
 */
class Registry {

    /** deviceId -> connection server id. Absent means "we believe it is offline". */
    private final Map<String, String> sessions = new HashMap<>();

    /** userId -> all its logged-in devices. Changes rarely; this is durable config. */
    private final Map<String, List<String>> devicesOfUser = new HashMap<>();

    int lookups = 0, staleHits = 0;

    void addDevice(String user, String deviceId) {
        devicesOfUser.computeIfAbsent(user, u -> new ArrayList<>()).add(deviceId);
    }

    List<String> devicesOf(String user) { return devicesOfUser.getOrDefault(user, List.of()); }

    void bind(String deviceId, String serverId) { sessions.put(deviceId, serverId); }

    void unbind(String deviceId) { sessions.remove(deviceId); }

    /** null = offline as far as we know. That "as far as we know" is load-bearing. */
    String serverOf(String deviceId) { lookups++; return sessions.get(deviceId); }

    /**
     * A server crashed. Its sockets died with it, but the registry still points
     * at it until something notices. Those rows are now LIES -- and a lie here
     * means a message is routed into a void. Failure scenario 2 in the README.
     */
    void leaveStaleEntriesFor(String deadServerId) {
        long n = sessions.values().stream().filter(s -> s.equals(deadServerId)).count();
        staleHits += (int) n;
    }

    void purge(String deadServerId) { sessions.values().removeIf(s -> s.equals(deadServerId)); }

    int onlineDevices() { return sessions.size(); }
}
