package dev.poco.asistente.phone;

import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import dev.poco.asistente.core.Documents;
import dev.poco.asistente.core.Validation;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** No ambient cookies/authentication; redirects are followed only after validation. */
public final class AndroidDocuments implements Documents.Port {
    private static final ScheduledExecutorService CANCEL = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "document-cancel"); t.setDaemon(true); return t;
    });
    public Documents.Prepared prepare(String tool, JSONObject args, BooleanSupplier cancelled) throws Exception {
        check(cancelled);
        if (tool.equals("descargar")) return download(Validation.string(args, "url", 2048), cancelled);
        String title = Validation.string(args, "titulo", 150), content = Documents.content(args);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setTextSize(12);
        String text = title + "\n" + java.time.LocalDate.now(dev.poco.asistente.core.Reminders.ZONE) + "\n\n" + content;
        List<List<String>> pages = Documents.pages(text, paint::measureText, 499, 44);
        try (PdfDocument pdf = new PdfDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            int number = 0;
            for (List<String> lines : pages) {
                check(cancelled);
                PdfDocument.Page page = pdf.startPage(new PdfDocument.PageInfo.Builder(595, 842, ++number).create());
                int y = 55;
                for (String line : lines) { page.getCanvas().drawText(line, 48, y, paint); y += 16; }
                page.getCanvas().drawText(number + " / " + pages.size(), 48, 805, paint);
                pdf.finishPage(page);
            }
            pdf.writeTo(bytes); check(cancelled);
            return new Documents.Prepared(Documents.filename(title), "application/pdf", bytes.toByteArray());
        }
    }
    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IllegalArgumentException("Pedido cancelado.");
    }
    private Documents.Prepared download(String value, BooleanSupplier cancelled) throws Exception {
        // Refuse an ambient cookie jar instead of risking inherited session cookies.
        if (CookieHandler.getDefault() != null) throw new IllegalArgumentException("La descarga sin cookies no está disponible en esta sesión.");
        URI uri = Documents.url(value);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        BooleanSupplier stop = () -> cancelled.getAsBoolean() || System.nanoTime() >= deadline;
        for (int redirects = 0; redirects <= 5; redirects++) {
            check(stop);
            // Avoid fetching local services with a URL received in chat, including redirect targets.
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress())
                    throw new IllegalArgumentException("La descarga necesita una dirección pública.");
            }
            HttpsURLConnection connection = (HttpsURLConnection) uri.toURL().openConnection(Proxy.NO_PROXY);
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
            connection.setUseCaches(false); connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Cookie", ""); connection.setRequestProperty("Authorization", "");
            ScheduledFuture<?> watcher = CANCEL.scheduleWithFixedDelay(() -> { if (stop.getAsBoolean()) connection.disconnect(); }, 0, 100, TimeUnit.MILLISECONDS);
            try {
                check(stop); int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Missing redirect");
                    uri = Documents.url(uri.resolve(location).toString()); continue;
                }
                if (status != 200) throw new IOException("Download failed");
                String raw = connection.getContentType();
                String mime = raw == null ? "" : raw.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                String extension = Documents.extension(mime);
                long length = connection.getContentLengthLong();
                if (length != -1) Documents.size(length);
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[16384]; int n;
                    while ((n = input.read(buffer)) != -1) {
                        check(stop); Documents.size((long) bytes.size() + n); bytes.write(buffer, 0, n);
                    }
                    check(stop);
                    return new Documents.Prepared("Descarga" + extension, mime, bytes.toByteArray());
                }
            } finally { watcher.cancel(false); connection.disconnect(); }
        }
        throw new IllegalArgumentException("La descarga tiene demasiadas redirecciones.");
    }
}
