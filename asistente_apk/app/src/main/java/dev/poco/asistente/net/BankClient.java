package dev.poco.asistente.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * GET to David's bank mail reader (Apps Script web app). Only script.google.com, and the one redirect Apps Script
 * always answers with, to script.googleusercontent.com. Bounded time and size; errors never carry the URL or token.
 */
public final class BankClient implements dev.poco.asistente.core.BankFeed.Source {
    public interface Config { String[] reader() throws Exception; } // {deployment id, token} or null
    private static final int MAX = 256 * 1024;
    private final Config config;
    public BankClient(Config config) { this.config = config; }

    @Override public String fetch() throws Exception {
        String[] reader = config.reader();
        if (reader == null) throw new IllegalStateException("Lector del banco sin configurar.");
        URL url = new URL("https://script.google.com/macros/s/" + reader[0] + "/exec?d=3&t=" + reader[1]);
        for (int hop = 0; hop < 2; hop++) {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            try {
                connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
                connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307) {
                    URL next = new URL(url, connection.getHeaderField("Location"));
                    if (!"https".equals(next.getProtocol()) || !"script.googleusercontent.com".equals(next.getHost()))
                        throw new IOException("Redirección inesperada.");
                    url = next; continue;
                }
                if (status != 200) throw new IOException("Lector del banco no disponible.");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = connection.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    for (int n; (n = in.read(buffer)) != -1; ) {
                        out.write(buffer, 0, n);
                        if (out.size() > MAX) throw new IOException("Respuesta del banco demasiado grande.");
                    }
                }
                return out.toString("UTF-8");
            } finally { connection.disconnect(); }
        }
        throw new IOException("Demasiadas redirecciones.");
    }
}
