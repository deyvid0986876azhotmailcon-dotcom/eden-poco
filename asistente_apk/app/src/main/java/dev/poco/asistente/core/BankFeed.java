package dev.poco.asistente.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Movements from the Banco Familiar mail reader (asistente_finanzas, an Apps Script with gmail.readonly in David's
 * account). That script already drops accounts, names, references and security mail; this side trusts nothing:
 * every field is validated again, and a movement is recorded through the same executor as a WhatsApp expense,
 * once per mail («banco:<id>» receipt). One message per batch tells David what was noted.
 */
public final class BankFeed {
    /** The raw JSON body from the reader, or an exception. */
    public interface Source { String fetch() throws Exception; }
    public static final long INTERVAL = 30 * 60 * 1000L;
    public static final int FAILURE_NOTICE_THRESHOLD = 6;
    public static final String FAILURE_NOTICE = "No pude leer los correos del banco desde hace unas horas; los movimientos de ese rato los anoto cuando vuelva.";
    private int failures;
    private boolean failureNotified;
    private final Proactive.Port store;
    private final AssistantCore core;
    private final Source source;
    private long last = Long.MIN_VALUE / 2;
    private final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean();

    /** A stalled bank endpoint must not hold the shared inbox or delay reminder delivery. */
    public void schedule(long now, long ticket, BooleanSupplier cancelled) {
        if (!running.compareAndSet(false, true)) return;
        Thread worker = new Thread(() -> {
            try { run(now, ticket, cancelled); }
            catch (Exception ignored) { /* Retry next interval. */ }
            finally { running.set(false); }
        }, "bank-feed");
        worker.setDaemon(true);
        try { worker.start(); }
        catch (RuntimeException e) { running.set(false); throw e; }
    }

    public BankFeed(Proactive.Port store, AssistantCore core, Source source) {
        this.store = store; this.core = core; this.source = source;
    }

    static final class Item {
        final String id, type, date, detail;
        final long amount;
        Item(String id, String type, long amount, String date, String detail) {
            this.id = id; this.type = type; this.amount = amount; this.date = date; this.detail = detail;
        }
    }

    /** Called from Proactive.poll. Network outside the action gate; nothing happens while paused (PARAR). */
    public synchronized void run(long now, long ticket, BooleanSupplier cancelled) throws Exception {
        if (now - last < INTERVAL || cancelled.getAsBoolean()) return;
        last = now;
        List<Item> items;
        try { items = parse(source.fetch()); }
        catch (Exception e) {
            if (cancelled.getAsBoolean()) return;
            failures = Math.min(FAILURE_NOTICE_THRESHOLD, failures + 1);
            if (failures >= FAILURE_NOTICE_THRESHOLD && !failureNotified) {
                core.proactiveAction(ticket, () -> {
                    if (cancelled.getAsBoolean()) return null;
                    store.enqueue("b_" + java.util.UUID.randomUUID(), FAILURE_NOTICE, "banco", false, now, null, 0, null);
                    failureNotified = true;
                    return null;
                }, null);
            }
            return;
        }
        if (cancelled.getAsBoolean()) return;
        failures = 0; failureNotified = false;
        core.proactiveAction(ticket, () -> {
            store.bankTransaction(() -> {
                List<String> lines = new ArrayList<>();
                for (Item item : items) {
                    String line;
                    try { line = record(item, now); }
                    catch (IllegalArgumentException e) { continue; } // invalid movement, keep the others
                    if (line != null) lines.add(line);
                }
                if (lines.isEmpty()) return null;
                String text = lines.size() == 1 ? "Del banco: " + lines.get(0) : "Del banco anoté:\n• " + String.join("\n• ", lines);
                store.enqueue("b_" + java.util.UUID.randomUUID(), text, "banco", false, now, null, 0, null);
                return null;
            });
            return null;
        }, null);
    }

    private String record(Item item, long now) throws Exception {
        String operation = "banco:" + item.id;
        if (store.receipt(operation) != null) return null; // already noted (or already asked about)
        if (item.type.equals("desconocido")) {
            store.beginAction(operation, "banco", now);
            store.finishAction(operation, "Correo del banco sin entender.");
            return "me llegó un correo que no entendí (" + item.detail + "). Si fue un gasto o un ingreso, contámelo y lo anoto.";
        }
        JSONObject args = Json.object("tipo", item.type, "centavos", item.amount * 100, "moneda", "PYG",
                "categoria", category(item), "fecha", item.date.substring(0, 10), "nota", "Banco Familiar: " + item.detail);
        core.recordBank(operation, args);
        return (item.type.equals("gasto") ? "gasto de " : "ingreso de ") + Receipt.money(item.amount * 100, "PYG")
                + " — " + item.detail + " (" + item.date.substring(8, 10) + "/" + item.date.substring(5, 7) + " " + item.date.substring(11) + ").";
    }

    static String category(Item item) {
        if (item.type.equals("ingreso")) return "ingresos";
        String d = Rules.normalize(item.detail);
        if (d.startsWith("transferencia")) return "transferencias";
        if (d.matches(".*\\b(tigo|personal|claro|copaco|vox)\\b.*")) return "telefono";
        if (d.matches(".*\\b(ande|essap|servicio|servicios)\\b.*")) return "servicios";
        return "compras";
    }

    /** Strict: unknown fields, bad ids, amounts or dates throw the whole batch away (the reader is code, not a person). */
    static List<Item> parse(String body) throws Exception {
        if (body == null || body.length() > 256 * 1024) throw new IllegalArgumentException("Respuesta del banco inválida.");
        JSONObject root = new JSONObject(body);
        if (root.has("error") || root.optInt("version") != 1) throw new IllegalArgumentException("Lector del banco sin acceso.");
        JSONArray raw = root.getJSONArray("movimientos");
        if (raw.length() > 200) throw new IllegalArgumentException("Demasiados movimientos.");
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < raw.length(); i++) {
            JSONObject m = raw.getJSONObject(i);
            String id = m.optString("id"), type = m.optString("tipo");
            if (!id.matches("[A-Za-z0-9]{8,40}")) throw new IllegalArgumentException("Movimiento sin identificador válido.");
            if (type.equals("desconocido")) {
                Validation.keys(m, "id,tipo,asunto");
                items.add(new Item(id, type, 0, "", clean(m.optString("asunto"), "correo del banco")));
                continue;
            }
            Validation.keys(m, "id,tipo,monto,moneda,fecha,detalle");
            if (!type.equals("gasto") && !type.equals("ingreso")) throw new IllegalArgumentException("Tipo de movimiento inválido.");
            if (!"PYG".equals(m.optString("moneda"))) throw new IllegalArgumentException("Moneda inválida.");
            Object amount = m.opt("monto");
            if (!(amount instanceof Number) || ((Number) amount).doubleValue() != ((Number) amount).longValue()) throw new IllegalArgumentException("Monto inválido.");
            long value = ((Number) amount).longValue();
            if (value < 1 || value >= 10_000_000_000L) throw new IllegalArgumentException("Monto inválido.");
            String date = m.optString("fecha");
            if (!date.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}")) throw new IllegalArgumentException("Fecha inválida.");
            Validation.date(date.substring(0, 10));
            items.add(new Item(id, type, value, date, clean(m.optString("detalle"), type.equals("gasto") ? "gasto" : "ingreso")));
        }
        return items;
    }

    /** Short, printable, no long numbers (accounts, references) even if the reader ever let one through. */
    private static String clean(String value, String fallback) {
        String text = value == null ? "" : value.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").replaceAll("\\d{5,}", "").replaceAll("\\s+", " ").trim();
        return text.isEmpty() ? fallback : Memory.clip(text, 80);
    }
}
