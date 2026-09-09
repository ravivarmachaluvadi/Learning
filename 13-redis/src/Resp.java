import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * RESP (REdis Serialization Protocol) -- the wire format every Redis client speaks.
 *
 * WHY THIS FILE EXISTS AT ALL: bytes arriving on a TCP socket are a STREAM, not
 * messages. "This socket is readable" means "some bytes arrived", never "a whole
 * command arrived" -- the framing problem from the io-multiplexing lab. RESP is
 * Redis's answer: every message declares its own length up front, so a reader
 * always knows exactly how many more bytes it still needs, and never has to guess.
 *
 * Five types, each identified by its first byte:
 *   +OK\r\n               simple string  (a short status)
 *   -ERR bad thing\r\n    error          (same shape, but it means failure)
 *   :42\r\n               integer
 *   $5\r\nhello\r\n       bulk string    (length-prefixed; $-1\r\n means "nil")
 *   *2\r\n<a><b>          array          (count-prefixed; nests)
 *
 * A client REQUEST is always an array of bulk strings:
 *   *3\r\n$3\r\nSET\r\n$4\r\nname\r\n$5\r\nRedis\r\n     ==  SET name Redis
 *
 * SIMPLIFICATION (flagged): real Redis values are binary-safe byte arrays. We use
 * java.lang.String throughout so this lab's output is readable in a terminal.
 * The framing itself is faithful.
 */
final class Resp {

    /** One fully-parsed command, plus how many bytes of the buffer it consumed. */
    record Parsed(List<String> args, int consumed) {}

    /**
     * Try to parse ONE command out of b[off..limit).
     *
     * INVARIANT -- the whole point of framing: returns null when the bytes on hand
     * are not yet a complete command. The caller must then wait for more bytes and
     * try again with the same buffer. Never partially consumes.
     */
    static Parsed parse(byte[] b, int off, int limit) {
        if (off >= limit) return null;
        if (b[off] == '*') return parseArray(b, off, limit);
        return parseInline(b, off, limit);
    }

    /** The normal client path: an array of bulk strings. */
    private static Parsed parseArray(byte[] b, int off, int limit) {
        int p = off;
        int lineEnd = findCrlf(b, p, limit);
        if (lineEnd < 0) return null;                      // not even the header yet
        int n = (int) readLong(b, p + 1, lineEnd);
        p = lineEnd + 2;
        if (n < 0) return new Parsed(List.of(), p - off);
        List<String> args = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (p >= limit || b[p] != '$') return null;
            int le = findCrlf(b, p, limit);
            if (le < 0) return null;
            int len = (int) readLong(b, p + 1, le);
            p = le + 2;
            if (len < 0) { args.add(null); continue; }
            if (p + len + 2 > limit) return null;          // body not fully arrived
            args.add(new String(b, p, len, StandardCharsets.UTF_8));
            p += len + 2;
        }
        return new Parsed(args, p - off);
    }

    /**
     * Inline commands: a bare line like "PING\r\n". Real Redis supports these so a
     * human with `telnet` or `nc` can talk to it -- and so can you, in this lab.
     */
    private static Parsed parseInline(byte[] b, int off, int limit) {
        int lineEnd = findCrlf(b, off, limit);
        int consumed, end;
        if (lineEnd < 0) {
            int nl = -1;
            for (int i = off; i < limit; i++) if (b[i] == '\n') { nl = i; break; }
            if (nl < 0) return null;
            end = nl; consumed = nl - off + 1;
        } else {
            end = lineEnd; consumed = lineEnd - off + 2;
        }
        String line = new String(b, off, end - off, StandardCharsets.UTF_8).trim();
        List<String> args = new ArrayList<>();
        for (String s : line.split("\\s+")) if (!s.isEmpty()) args.add(s);
        return new Parsed(args, consumed);
    }

    private static int findCrlf(byte[] b, int from, int limit) {
        for (int i = from; i + 1 < limit; i++) if (b[i] == '\r' && b[i + 1] == '\n') return i;
        return -1;
    }

    private static long readLong(byte[] b, int from, int to) {
        long v = 0; boolean neg = false; int i = from;
        if (i < to && b[i] == '-') { neg = true; i++; }
        for (; i < to; i++) v = v * 10 + (b[i] - '0');
        return neg ? -v : v;
    }

    // ---------- encoders: what the server writes back ----------

    static byte[] simple(String s)  { return (("+" + s + "\r\n")).getBytes(StandardCharsets.UTF_8); }
    static byte[] error(String s)   { return (("-" + s + "\r\n")).getBytes(StandardCharsets.UTF_8); }
    static byte[] integer(long v)   { return ((":" + v + "\r\n")).getBytes(StandardCharsets.UTF_8); }
    static byte[] nil()             { return "$-1\r\n".getBytes(StandardCharsets.UTF_8); }
    static byte[] nilArray()        { return "*-1\r\n".getBytes(StandardCharsets.UTF_8); }

    static byte[] bulk(String s) {
        if (s == null) return nil();
        byte[] body = s.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + 16);
        write(out, "$" + body.length + "\r\n");
        out.writeBytes(body);
        write(out, "\r\n");
        return out.toByteArray();
    }

    /** An array of bulk strings -- the shape of LRANGE, HGETALL, KEYS, EXEC ... */
    static byte[] array(List<String> items) {
        if (items == null) return nilArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "*" + items.size() + "\r\n");
        for (String s : items) out.writeBytes(bulk(s));
        return out.toByteArray();
    }

    /** An array whose elements are already-encoded RESP values (EXEC replies nest). */
    static byte[] arrayRaw(List<byte[]> items) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "*" + items.size() + "\r\n");
        for (byte[] s : items) out.writeBytes(s);
        return out.toByteArray();
    }

    /** Encode a command as a client would send it -- used to replicate and to log to the AOF. */
    static byte[] command(List<String> args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "*" + args.size() + "\r\n");
        for (String a : args) out.writeBytes(bulk(a));
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream o, String s) {
        o.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    private Resp() {}
}
