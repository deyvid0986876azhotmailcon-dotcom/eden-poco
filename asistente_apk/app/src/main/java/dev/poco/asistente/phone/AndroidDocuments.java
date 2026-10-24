package dev.poco.asistente.phone;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
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
        String date = java.time.LocalDate.now(dev.poco.asistente.core.Reminders.ZONE)
                .format(java.time.format.DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", new Locale("es", "PY")));
        List<List<java.util.function.Consumer<Canvas>>> pages = layout(title, date, Documents.blocks(content), cancelled);
        PdfDocument pdf = new PdfDocument(); // not AutoCloseable
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages.size(); i++) {
                check(cancelled);
                PdfDocument.Page page = pdf.startPage(new PdfDocument.PageInfo.Builder(W, H, i + 1).create());
                Canvas canvas = page.getCanvas();
                for (java.util.function.Consumer<Canvas> draw : pages.get(i)) draw.accept(canvas);
                footer(canvas, date, i + 1, pages.size());
                pdf.finishPage(page);
            }
            pdf.writeTo(bytes); check(cancelled);
            return new Documents.Prepared(Documents.filename(title), "application/pdf", bytes.toByteArray());
        } finally { pdf.close(); }
    }
    // A4 in points; colors of a plain report: dark blue band, blue section titles, light zebra rows.
    private static final int W = 595, H = 842, M = 48, BOTTOM = H - 64;
    private static final int ACCENT = 0xFF1F4E79, INK = 0xFF222222, MUTED = 0xFF7A7A7A, ZEBRA = 0xFFF1F5FA, RULE = 0xFFD5DEE8;
    private static Paint paint(int color, float size, boolean bold) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(color); p.setTextSize(size);
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, bold ? Typeface.BOLD : Typeface.NORMAL));
        return p;
    }
    /** Positions everything first so the footer can say «n de total»; drawing happens afterwards. */
    private static List<List<java.util.function.Consumer<Canvas>>> layout(String title, String date, List<Documents.Block> blocks, BooleanSupplier cancelled) {
        Paint band = paint(ACCENT, 0, false), white = paint(0xFFFFFFFF, 22, true), light = paint(0xFFD6E4F2, 11, false);
        Paint head = paint(ACCENT, 14, true), body = paint(INK, 11, false), amount = paint(INK, 11, true), small = paint(MUTED, 9, false);
        Paint zebra = paint(ZEBRA, 0, false), rule = paint(RULE, 0, false), dot = paint(ACCENT, 0, false);
        rule.setStrokeWidth(1);
        List<List<java.util.function.Consumer<Canvas>>> pages = new ArrayList<>();
        List<java.util.function.Consumer<Canvas>> page = new ArrayList<>(); pages.add(page);
        // First page: band with the title (up to two lines) and the date.
        List<String> titleLines = Documents.wrap(title, white::measureText, W - 2 * M);
        if (titleLines.size() > 2) titleLines = titleLines.subList(0, 2);
        int bandHeight = 62 + 26 * titleLines.size();
        final List<String> shownTitle = titleLines;
        page.add(c -> {
            c.drawRect(0, 0, W, bandHeight, band);
            int y = 52;
            for (String line : shownTitle) { c.drawText(line, M, y, white); y += 26; }
            c.drawText("Asistente · " + date, M, y + 2, light);
        });
        float y = bandHeight + 34;
        int zebraRow = 0;
        for (Documents.Block block : blocks) {
            check(cancelled);
            List<String> lines; float need;
            switch (block.kind) {
                case ' ': y += 8; zebraRow = 0; continue;
                case 'h': lines = Documents.wrap(block.text, head::measureText, W - 2 * M); need = 14 + 20 * lines.size() + 8; break;
                case 'b': lines = Documents.wrap(block.text, body::measureText, W - 2 * M - 16); need = 16 * lines.size() + 2; break;
                case 'r': lines = Documents.wrap(block.text, body::measureText, W - 2 * M - 150); need = Math.max(22, 16 * lines.size() + 6); break;
                default: lines = Documents.wrap(block.text, body::measureText, W - 2 * M); need = 16 * lines.size() + 2;
            }
            int offset = 0;
            do {
                float overhead = block.kind == 'h' ? 22 : block.kind == 'r' ? 6 : 2;
                float lineHeight = block.kind == 'h' ? 20 : 16;
                int count = Documents.fittingLines(BOTTOM - y, overhead, lineHeight, lines.size() - offset);
                if (count == 0 || (offset == 0 && need <= BOTTOM - 76 && y + need > BOTTOM && y > 76)) {
                    if (pages.size() >= 100) throw new IllegalArgumentException("El PDF supera 100 páginas.");
                    page = new ArrayList<>(); pages.add(page);
                    // Following pages: the title small, above a rule.
                    page.add(c -> { c.drawText(title.length() > 90 ? title.substring(0, 90) + "…" : title, M, 40, small); c.drawLine(M, 48, W - M, 48, rule); });
                    y = 76; zebraRow = 0;
                }
                count = Documents.fittingLines(BOTTOM - y, overhead, lineHeight, lines.size() - offset);
                if (count == 0) throw new IllegalArgumentException("El texto no cabe en la página.");
                need = overhead + lineHeight * count;
                final float top = y; final List<String> text = lines.subList(offset, offset + count);
                final boolean first = offset == 0;
                switch (block.kind) {
                    case 'h':
                        page.add(c -> {
                            float ty = top + 14 + 14;
                            for (String line : text) { c.drawText(line, M, ty, head); ty += 20; }
                            c.drawLine(M, ty - 12, W - M, ty - 12, rule);
                        });
                        zebraRow = 0; break;
                    case 'b':
                        page.add(c -> {
                            if (first) c.drawCircle(M + 4, top + 7, 2.2f, dot);
                            float ty = top + 11;
                            for (String line : text) { c.drawText(line, M + 16, ty, body); ty += 16; }
                        });
                        break;
                    case 'r':
                        final boolean shade = zebraRow++ % 2 == 0; final float height = need; final String value = block.amount;
                        page.add(c -> {
                            if (shade) c.drawRect(M - 6, top, W - M + 6, top + height, zebra);
                            float ty = top + 15;
                            for (String line : text) { c.drawText(line, M, ty, body); ty += 16; }
                            if (first) c.drawText(value, W - M - amount.measureText(value), top + 15, amount);
                        });
                        break;
                    default:
                        page.add(c -> {
                            float ty = top + 11;
                            for (String line : text) { c.drawText(line, M, ty, body); ty += 16; }
                        });
                        zebraRow = 0;
                }
                y += need;
                offset += count;
                } while (offset < lines.size());
        }
        return pages;
    }
    private static void footer(Canvas c, String date, int number, int total) {
        Paint rule = paint(RULE, 0, false), small = paint(MUTED, 9, false);
        rule.setStrokeWidth(1);
        c.drawLine(M, H - 44, W - M, H - 44, rule);
        c.drawText("Hecho por tu asistente · " + date, M, H - 30, small);
        String pageText = "Página " + number + " de " + total;
        c.drawText(pageText, W - M - small.measureText(pageText), H - 30, small);
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
