package dev.poco.asistente.net;

import dev.poco.asistente.core.Pro;
import dev.poco.asistente.core.ProProtocol;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Fixed loopback HTTP, no proxy/redirects. Closing the socket cancels the CLI on the bridge. */
public final class ProClient implements Pro {
    public interface Token { String get() throws Exception; }
    private final Token token;
    private final int port;
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "pro-deadline"); t.setDaemon(true); return t;
    });
    public ProClient(Token token) { this(token, 8766); }
    public ProClient(Token token, int port) {
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("Puerto Pro inválido.");
        this.token = token; this.port = port;
    }
    @Override public String generate(String type, String prompt, String model, BooleanSupplier cancelled) throws Exception {
        if (!"charla".equals(type) && !"busqueda".equals(type)) throw new IllegalArgumentException("Pedido Pro inválido.");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos("charla".equals(type) ? 70 : 45);
        Thread caller = Thread.currentThread();
        try (Socket socket = new Socket(Proxy.NO_PROXY)) {
            ScheduledFuture<?> watch = DEADLINES.scheduleAtFixedRate(() -> {
                if (caller.isInterrupted() || cancelled.getAsBoolean() || System.nanoTime() >= deadline) close(socket);
            }, 0, 100, TimeUnit.MILLISECONDS);
            try {
                if (cancelled.getAsBoolean()) throw new IOException();
                String secret = token.get();
                if (secret == null || !secret.matches("[A-Za-z0-9_-]{43}")) throw new IOException();
                byte[] body = new JSONObject().put("tipo", type).put("prompt", ProProtocol.bounded(prompt)).put("modelo", model)
                        .toString().getBytes(StandardCharsets.UTF_8);
                if (body.length > 32768) throw new IOException();
                socket.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port), 2000);
                socket.setSoTimeout("charla".equals(type) ? 70000 : 45000);
                OutputStream out = socket.getOutputStream();
                out.write(("POST /pro HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nAuthorization: Bearer " + secret
                        + "\r\nContent-Type: application/json; charset=utf-8\r\nConnection: close\r\nContent-Length: " + body.length + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.write(body); out.flush();
                BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
                String status = line(in);
                if (!status.matches("HTTP/1\\.[01] 200(?: .*)?")) throw new IOException();
                int length = -1, total = status.length(); boolean json = false;
                for (;;) {
                    String header = line(in); total += header.length() + 2;
                    if (total > 8192) throw new IOException();
                    if (header.isEmpty()) break;
                    int colon = header.indexOf(':'); if (colon <= 0) throw new IOException();
                    String name = header.substring(0, colon).trim(), value = header.substring(colon + 1).trim();
                    if (name.equalsIgnoreCase("Content-Length")) {
                        if (length >= 0 || !value.matches("[0-9]{1,6}")) throw new IOException();
                        length = Integer.parseInt(value);
                    }
                    if (name.equalsIgnoreCase("Transfer-Encoding")) throw new IOException();
                    if (name.equalsIgnoreCase("Content-Type")) json = value.toLowerCase(java.util.Locale.ROOT).matches("application/json(?:;\\s*charset=utf-8)?");
                }
                // JSON escaping may expand the 64 KiB CLI text. Keep an independent wire bound.
                if (!json || length <= 0 || length > 256 * 1024) throw new IOException();
                byte[] bytes = new byte[length]; int read = 0;
                while (read < length) {
                    int n = in.read(bytes, read, length - read); if (n < 0) throw new IOException(); read += n;
                }
                if (cancelled.getAsBoolean() || System.nanoTime() >= deadline) throw new IOException();
                JSONObject response = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                if (response.length() != 1 || !(response.opt("texto") instanceof String)) throw new IOException();
                return ProProtocol.answer(response.getString("texto"));
            } finally { watch.cancel(false); }
        } catch (Exception e) { throw new IllegalArgumentException(cancelled.getAsBoolean() ? "Pedido cancelado." : "Pro no disponible ahora."); }
    }
    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int i = 0; i < 8192; i++) {
            int b = in.read(); if (b < 0) throw new IOException();
            if (b == '\r') { if (in.read() != '\n') throw new IOException(); return line.toString("US-ASCII"); }
            if (b < 32 || b > 126) throw new IOException();
            line.write(b);
        }
        throw new IOException();
    }
    private static void close(Socket socket) { try { socket.close(); } catch (IOException ignored) { } }
}
