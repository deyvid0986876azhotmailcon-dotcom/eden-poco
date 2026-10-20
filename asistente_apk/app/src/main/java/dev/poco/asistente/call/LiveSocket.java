package dev.poco.asistente.call;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A minimal WebSocket client (RFC 6455) over TLS for the Gemini Live API: Android has none built in and
 * the app takes no libraries. Text frames only (Live speaks JSON); client frames are masked as the RFC asks.
 * Not thread-safe for sending: callers serialize sends (LiveSession does).
 */
public final class LiveSocket implements AutoCloseable {
    public interface Listener { void onText(String text); void onClosed(String reason); }

    private static final int MAX_MESSAGE = 8 * 1024 * 1024;
    private final SSLSocket socket;
    private final InputStream in;
    private final OutputStream out;
    private final SecureRandom random = new SecureRandom();
    private volatile boolean closed;

    private LiveSocket(SSLSocket socket) throws IOException {
        this.socket = socket; this.in = socket.getInputStream(); this.out = socket.getOutputStream();
    }

    /** Opens wss://host/path (path includes the query), checking the server's handshake answer. */
    public static LiveSocket connect(String host, String path) throws IOException {
        SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket(host, 443);
        SSLParameters params = socket.getSSLParameters();
        params.setServerNames(java.util.Collections.singletonList(new SNIHostName(host)));
        params.setEndpointIdentificationAlgorithm("HTTPS"); // certificate must match the host
        socket.setSSLParameters(params);
        socket.setSoTimeout(30000);
        socket.startHandshake();
        LiveSocket ws = new LiveSocket(socket);
        byte[] nonce = new byte[16]; ws.random.nextBytes(nonce);
        String key = Base64.getEncoder().encodeToString(nonce);
        String request = "GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
        ws.out.write(request.getBytes(StandardCharsets.US_ASCII)); ws.out.flush();
        String status = ws.line(), accept = null;
        if (!status.startsWith("HTTP/1.1 101")) { ws.close(); throw new IOException("Live rechazó la conexión (" + status.replaceAll("[^ -~]", "") + ")"); }
        for (String line = ws.line(); !line.isEmpty(); line = ws.line()) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Sec-WebSocket-Accept")) accept = line.substring(colon + 1).trim();
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII));
            if (!Base64.getEncoder().encodeToString(digest).equals(accept)) { ws.close(); throw new IOException("Respuesta de Live inválida"); }
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
        socket.setSoTimeout(0); // the call can stay silent for a while; closing the socket ends the reader
        return ws;
    }

    private String line() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int c; (c = in.read()) != '\n'; ) {
            if (c < 0) throw new EOFException();
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("Cabecera demasiado larga");
        }
        return b.toString("US-ASCII");
    }

    public synchronized void sendText(String text) throws IOException {
        if (closed) throw new IOException("Conexión cerrada");
        frame(0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    private synchronized void frame(int opcode, byte[] payload) throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream(payload.length + 14);
        f.write(0x80 | opcode);
        if (payload.length < 126) f.write(0x80 | payload.length);
        else if (payload.length < 65536) { f.write(0x80 | 126); f.write(payload.length >> 8); f.write(payload.length); }
        else { f.write(0x80 | 127); for (int i = 7; i >= 0; i--) f.write((int) ((long) payload.length >> (8 * i))); }
        byte[] mask = new byte[4]; random.nextBytes(mask); f.write(mask, 0, 4);
        byte[] masked = new byte[payload.length];
        for (int i = 0; i < payload.length; i++) masked[i] = (byte) (payload[i] ^ mask[i & 3]);
        f.write(masked, 0, masked.length);
        out.write(f.toByteArray()); out.flush();
    }

    /** Reads until the connection ends, handing every complete text (or binary-as-text) message to the listener. */
    public void readLoop(Listener listener) {
        String reason = "";
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        try {
            while (!closed) {
                int b0 = in.read(), b1 = in.read();
                if (b0 < 0 || b1 < 0) { reason = "fin"; break; }
                int opcode = b0 & 0x0f; boolean fin = (b0 & 0x80) != 0;
                long length = b1 & 0x7f;
                if (length == 126) length = (in.read() << 8) | in.read();
                else if (length == 127) { length = 0; for (int i = 0; i < 8; i++) length = (length << 8) | in.read(); }
                if ((b1 & 0x80) != 0) throw new IOException("El servidor no debe enmascarar");
                if (length < 0 || length > MAX_MESSAGE) throw new IOException("Mensaje demasiado grande");
                byte[] payload = new byte[(int) length];
                for (int read = 0; read < payload.length; ) {
                    int n = in.read(payload, read, payload.length - read);
                    if (n < 0) throw new EOFException();
                    read += n;
                }
                if (opcode == 0x8) { // close: the reason, if any, after a 2-byte code
                    reason = payload.length > 2 ? new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8) : "cerrado";
                    break;
                }
                if (opcode == 0x9) { frame(0xA, payload); continue; } // ping -> pong
                if (opcode == 0xA) continue;
                if (opcode == 0x1 || opcode == 0x2 || opcode == 0x0) {
                    if (message.size() + payload.length > MAX_MESSAGE) throw new IOException("Mensaje demasiado grande");
                    message.write(payload, 0, payload.length);
                    if (fin) { listener.onText(message.toString("UTF-8")); message.reset(); }
                }
            }
        } catch (IOException e) {
            if (!closed) reason = "conexión interrumpida";
        } finally {
            close();
            listener.onClosed(reason);
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try { frame(0x8, new byte[]{0x03, (byte) 0xE8}); } catch (IOException ignored) { }
        try { socket.close(); } catch (IOException ignored) { }
    }
}
