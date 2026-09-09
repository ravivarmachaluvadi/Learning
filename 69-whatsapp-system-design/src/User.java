import java.util.List;

/** A user's set of logged-in devices. In real WhatsApp: phone + up to 4 companions. */
record User(String name, List<String> deviceIds) {}
