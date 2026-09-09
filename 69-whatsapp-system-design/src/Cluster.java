import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole toy deployment: N connection servers, one registry, one mailbox
 * store, one network -- wired together so a test can say
 * `c.user("asha").send(...)` and watch the packets fly.
 */
class Cluster {

    final Net net;
    final Registry registry;
    final MailboxStore boxes;
    private final Map<String, Server> servers = new LinkedHashMap<>();
    private final Map<String, Device> devices = new LinkedHashMap<>();
    private final Map<String, List<String>> userDevices = new LinkedHashMap<>();

    Cluster(int serverCount, long seed) {
        this.net = new Net(seed);
        this.registry = new Registry();
        this.boxes = new MailboxStore();
        for (int i = 1; i <= serverCount; i++) {
            String id = "cs" + i;
            servers.put(id, new Server(id, net, registry, boxes, this));
        }
    }

    Server server(String id) { return servers.get(id); }
    List<Server> servers() { return new ArrayList<>(servers.values()); }
    Device device(String deviceId) { return devices.get(deviceId); }

    /** Register a user with one or more devices, all initially offline. */
    Device addUser(String user, String... deviceSuffixes) {
        List<String> ids = new ArrayList<>();
        Device first = null;
        for (String suffix : deviceSuffixes) {
            String deviceId = user + ":" + suffix;
            Device d = new Device(deviceId, user, net, this);
            devices.put(deviceId, d);
            registry.addDevice(user, deviceId);
            ids.add(deviceId);
            if (first == null) first = d;
        }
        userDevices.put(user, ids);
        return first;
    }

    private final Map<String, List<String>> groups = new LinkedHashMap<>();

    /** A group is just a named list of users. The server stores no group mailbox. */
    void addGroup(String groupId, String... members) { groups.put(groupId, List.of(members)); }

    /** One recipient, or every member of a group. The fan-out list. */
    List<String> recipientsOf(String to) {
        return to.startsWith("#") ? groups.getOrDefault(to, List.of()) : List.of(to);
    }

    /** Where delivery receipts are sent. Real systems fan receipts to all sender devices. */
    String primaryDeviceOf(String user) {
        var ds = userDevices.get(user);
        return (ds == null || ds.isEmpty()) ? null : ds.get(0);
    }

    /**
     * Which connection server does a device land on?
     *
     * In this toy it is a hash of the device id -- the same idea as consistent
     * hashing, minus the ring. In production it is a load balancer plus the
     * client's own reconnect logic, and the ANSWER CHANGES on every reconnect,
     * which is exactly why the registry has to exist.
     */
    Server homeFor(String deviceId) {
        List<Server> alive = servers().stream().filter(s -> !net.isDown(s.id)).toList();
        int h = Math.floorMod(deviceId.hashCode(), alive.size());
        return alive.get(h);
    }

    void connectAll() { for (Device d : devices.values()) d.connect(homeFor(d.deviceId)); }

    /**
     * Turn on the delivery sweepers. `untilMs` exists only so that
     * `net.runUntilIdle()` terminates in a simulation; production sweepers
     * never stop.
     */
    void startSweepers(long periodMs, long untilMs) { for (Server s : servers.values()) s.startSweeper(periodMs, untilMs); }

    /** Test helper: which server does the registry currently believe holds this device? */
    String registryServerOf(String deviceId) { return registry.serverOf(deviceId); }

    String stats() {
        return """
               packets: sent=%d delivered=%d dropped=%d dup=%d partitioned=%d
               mailbox: appends=%d deletes=%d redeliveries=%d stillQueued=%d
               registry: lookups=%d onlineDevices=%d"""
                .formatted(net.sent, net.deliveredPackets, net.dropped, net.duplicated, net.partitionDropped,
                        boxes.appends, boxes.deletes, boxes.redeliveries, boxes.totalUndelivered(),
                        registry.lookups, registry.onlineDevices());
    }
}
