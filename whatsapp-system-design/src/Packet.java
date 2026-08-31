/** One thing in flight on the network. */
record Packet(Kind kind, String src, String dst, Msg msg, String refMsgId, String deviceId) {
    static Packet of(Kind k, String src, String dst, Msg m) { return new Packet(k, src, dst, m, m == null ? null : m.id(), null); }
    static Packet ack(Kind k, String src, String dst, String refMsgId, String deviceId) { return new Packet(k, src, dst, null, refMsgId, deviceId); }
    @Override public String toString() { return kind + " " + src + "->" + dst + (msg != null ? " " + msg : " ref=" + refMsgId); }
}

