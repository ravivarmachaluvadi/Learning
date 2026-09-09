import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * A tiny redis-cli. Two jobs:
 *   1. let you talk to MiniRedis by hand:   java src/Cli.java 6380
 *   2. be the reusable client the tests and experiments drive
 *
 * It is deliberately synchronous: send a command, read the reply, repeat. That
 * shape is what makes Experiment 3's point land -- every single command costs one
 * network round trip, and the round trip is almost always bigger than the work.
 */
public final class Cli implements Closeable {

    private final Socket sock;
    private final OutputStream out;
    private final PushbackInputStream in;

    public Cli(String host, int port) throws IOException {
        sock = new Socket(host, port);
        sock.setTcpNoDelay(true);
        out = new BufferedOutputStream(sock.getOutputStream());
        in = new PushbackInputStream(new BufferedInputStream(sock.getInputStream()), 8);
    }
    public Cli(int port) throws IOException { this("127.0.0.1", port); }

    /** Send one command, wait for one reply. */
    public Object cmd(String... args) throws IOException {
        send(args);
        out.flush();
        return readReply();
    }

    /** PIPELINING: push N commands without waiting, then collect N replies. One round trip. */
    public List<Object> pipeline(List<String[]> cmds) throws IOException {
        for (String[] c : cmds) send(c);
        out.flush();
        List<Object> replies = new ArrayList<>(cmds.size());
        for (int i = 0; i < cmds.size(); i++) replies.add(readReply());
        return replies;
    }

    public void sendOnly(String... args) throws IOException { send(args); out.flush(); }

    private void send(String[] args) throws IOException {
        out.write(("*" + args.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        for (String a : args) {
            byte[] b = a.getBytes(StandardCharsets.UTF_8);
            out.write(("$" + b.length + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(b);
            out.write('\r'); out.write('\n');
        }
    }

    /** Decode one RESP value. Mirrors Resp.parse, in the other direction. */
    public Object readReply() throws IOException {
        int t = in.read();
        if (t < 0) throw new EOFException("server closed");
        switch (t) {
            case '+': return line();
            case '-': return new Err(line());
            case ':': return Long.parseLong(line());
            case '$': {
                int len = Integer.parseInt(line());
                if (len < 0) return null;
                byte[] b = in.readNBytes(len);
                in.readNBytes(2);
                return new String(b, StandardCharsets.UTF_8);
            }
            case '*': {
                int n = Integer.parseInt(line());
                if (n < 0) return null;
                List<Object> l = new ArrayList<>(n);
                for (int i = 0; i < n; i++) l.add(readReply());
                return l;
            }
            default: throw new IOException("bad RESP type byte: " + (char) t);
        }
    }

    private String line() throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') { in.read(); break; }
            sb.append((char) c);
        }
        return sb.toString();
    }

    public record Err(String message) { public String toString() { return "(error) " + message; } }

    @Override public void close() { try { sock.close(); } catch (IOException ignored) {} }

    // ---------------- interactive mode ----------------

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 6380;
        try (Cli c = new Cli(port); BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in))) {
            System.out.println("connected to 127.0.0.1:" + port + "   (type commands, or 'exit')");
            String line;
            while (true) {
                System.out.print("127.0.0.1:" + port + "> ");
                System.out.flush();
                line = stdin.readLine();
                if (line == null || line.trim().equalsIgnoreCase("exit")) break;
                if (line.isBlank()) continue;
                try { System.out.println(render(c.cmd(line.trim().split("\\s+")), "")); }
                catch (Exception e) { System.out.println("(error) " + e.getMessage()); break; }
            }
        }
    }

    static String render(Object o, String indent) {
        if (o == null) return "(nil)";
        if (o instanceof Long l) return "(integer) " + l;
        if (o instanceof Err e) return e.toString();
        if (o instanceof List<?> l) {
            if (l.isEmpty()) return "(empty array)";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append('\n').append(indent);
                sb.append(indent).append(i + 1).append(") ").append(render(l.get(i), indent + "   "));
            }
            return sb.toString();
        }
        return "\"" + o + "\"";
    }
}
