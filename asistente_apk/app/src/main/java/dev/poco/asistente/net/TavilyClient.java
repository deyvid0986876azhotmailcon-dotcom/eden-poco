package dev.poco.asistente.net;

import dev.poco.asistente.core.SearchProtocol;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** One fixed HTTPS endpoint, no credentials, redirects or server errors in the UI. */
public final class TavilyClient {
    private static final String ENDPOINT = "https://api.tavily.com/search";
    interface Connections { HttpURLConnection open(URL url) throws IOException; }
    private final Connections connections;
    public TavilyClient() { this(url -> (HttpURLConnection) url.openConnection()); }
    TavilyClient(Connections connections) { this.connections = connections; }
    public String search(String query) throws Exception {
        byte[] request = SearchProtocol.tavilyRequest(query).toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = null;
        try {
            connection = connections.open(new URL(ENDPOINT));
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("X-Tavily-Access-Mode", "keyless");
            connection.setFixedLengthStreamingMode(request.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(request); }
            if (connection.getResponseCode() != 200 || connection.getContentLengthLong() > SearchProtocol.MAX_BYTES)
                throw new IOException();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            long deadline = System.nanoTime() + 10_000_000_000L;
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                for (;;) {
                    long left = (deadline - System.nanoTime()) / 1_000_000L;
                    if (left <= 0) throw new IOException();
                    connection.setReadTimeout((int) Math.min(10000, left));
                    int n = in.read(buffer);
                    if (n == -1) break;
                    if (n > SearchProtocol.MAX_BYTES - out.size()) throw new IOException();
                    out.write(buffer, 0, n);
                }
            }
            return SearchProtocol.parseTavily(out.toString("UTF-8"));
        } catch (Exception e) {
            throw new IllegalArgumentException(SearchProtocol.FAILURE);
        } finally { if (connection != null) connection.disconnect(); }
    }
}
