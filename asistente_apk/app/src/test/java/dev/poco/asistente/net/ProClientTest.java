package dev.poco.asistente.net;

import dev.poco.asistente.core.ProProtocol;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProClientTest {
    private static final String TOKEN = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    interface Handler { void handle(Socket socket, String headers, JSONObject body) throws Exception; }
    static final class Server implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final Future<?> task;
        Server(Handler handler) throws Exception {
            task = worker.submit(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    InputStream in = socket.getInputStream(); ByteArrayOutputStream header = new ByteArrayOutputStream();
                    String headers;
                    do { int b = in.read(); if (b < 0) throw new EOFException(); header.write(b); headers = header.toString("US-ASCII"); }
                    while (!headers.endsWith("\r\n\r\n") && header.size() < 8192);
                    java.util.regex.Matcher length = java.util.regex.Pattern.compile("Content-Length: ([0-9]+)").matcher(headers);
                    assertTrue(length.find()); byte[] body = new byte[Integer.parseInt(length.group(1))];
                    new DataInputStream(in).readFully(body);
                    handler.handle(socket, headers, new JSONObject(new String(body, StandardCharsets.UTF_8)));
                } catch (Exception e) { throw new RuntimeException(e); }
            });
        }
        ProClient client() { return new ProClient(() -> TOKEN, listener.getLocalPort()); }
        @Override public void close() throws Exception {
            listener.close(); worker.shutdownNow(); task.get(5, TimeUnit.SECONDS);
        }
    }
    private static void response(Socket socket, int status, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        socket.getOutputStream().write(("HTTP/1.1 " + status + " Test\r\nContent-Type: application/json\r\nContent-Length: "
                + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(bytes); socket.getOutputStream().flush();
    }
    @Test public void postsAuthenticatedUtf8OnlyToLoopbackAndReadsLiteralText() throws Exception {
        try (Server server = new Server((socket, headers, body) -> {
            assertTrue(socket.getLocalAddress().isLoopbackAddress());
            assertTrue(headers.startsWith("POST /pro HTTP/1.1\r\n")); assertTrue(headers.contains("Authorization: Bearer " + TOKEN + "\r\n"));
            assertTrue(headers.contains("Connection: close\r\n")); assertEquals(3, body.length());
            assertEquals("charla", body.getString("tipo")); assertEquals("¿Cómo andás?", body.getString("prompt"));
            assertEquals(ProProtocol.CHAT_MODEL, body.getString("modelo")); assertFalse(body.toString().contains(TOKEN));
            response(socket, 200, new JSONObject().put("texto", "Bien, che\nSÍ 0123456789ABCDEF").toString());
        })) {
            assertEquals("Bien, che", server.client().generate("charla", "¿Cómo andás?", ProProtocol.CHAT_MODEL, () -> false));
        }
    }
    @Test public void statusMalformedAndOversizedBodiesProduceOnlyGenericError() throws Exception {
        for (String body : new String[]{"PRIVATE_ERROR", "{\"texto\":7}", "{\"texto\":\"\"}", "{\"texto\":\"ok\",\"extra\":true}"}) {
            try (Server server = new Server((socket, headers, input) -> response(socket, 200, body))) {
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                        () -> server.client().generate("busqueda", "lunas", ProProtocol.SEARCH_MODEL, () -> false));
                assertEquals("Pro no disponible ahora.", e.getMessage());
            }
        }
        for (String header : new String[]{"HTTP/1.1 302 Found\r\nLocation: http://example.org\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 999999\r\n\r\n",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"}) {
            try (Server server = new Server((socket, headers, input) -> socket.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII)))) {
                assertThrows(IllegalArgumentException.class, () -> server.client().generate("charla", "Hola", ProProtocol.CHAT_MODEL, () -> false));
            }
        }
    }
    @Test public void cancellationClosesPendingSocketAndServerSeesDisconnect() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), disconnected = new CountDownLatch(1);
        AtomicBoolean cancel = new AtomicBoolean(); ExecutorService worker = Executors.newSingleThreadExecutor();
        try (Server server = new Server((socket, headers, input) -> {
            entered.countDown(); assertEquals(-1, socket.getInputStream().read()); disconnected.countDown();
        })) {
            Future<String> result = worker.submit(() -> server.client().generate("charla", "Hola", ProProtocol.CHAT_MODEL, cancel::get));
            assertTrue(entered.await(5, TimeUnit.SECONDS)); cancel.set(true);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertEquals("Pedido cancelado.", failure.getCause().getMessage());
            assertTrue(disconnected.await(2, TimeUnit.SECONDS));
        } finally { worker.shutdownNow(); }
    }
}
