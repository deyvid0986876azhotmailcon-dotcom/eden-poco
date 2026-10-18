package dev.poco.asistente.core;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.json.JSONObject;

public final class ToolExecutor {
    private final Store store;
    private final Clock clock;
    private final LongSupplier elapsedMillis;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new HashMap<>();
    private static final long TTL = 5 * 60 * 1000;
    private static final class Pending {
        final String code;
        final Store.Row row;
        final long expires;
        Pending(String code, Store.Row row, long expires) { this.code = code; this.row = row; this.expires = expires; }
    }
    public ToolExecutor(Store store, Clock clock) { this(store, clock, () -> System.nanoTime() / 1_000_000L); }
    public ToolExecutor(Store store, Clock clock, LongSupplier elapsedMillis) {
        this.store = store; this.clock = clock; this.elapsedMillis = elapsedMillis;
    }

    // Called only after AssistantCore authenticates the source. Model cannot set actor or operationId.
    public synchronized String execute(String actor, String operationId, String tool, JSONObject args) throws Exception {
        if (args == null) throw new IllegalArgumentException("Faltan los datos de la operación.");
        String receipt = store.receipt(operationId);
        if (receipt != null) return receipt;
        if (tool.equals("consultar_resumen")) {
            Validation.keys(args, "");
            return Summary.render(store.rows(), LocalDate.now(clock), true);
        }
        if (tool.equals("borrar_registro")) {
            Validation.keys(args, "id");
            Store.Row row = requireRow(Validation.id(args));
            // New proposal replaces the previous one for this actor. Snapshot/version binds its contents.
            byte[] bytes = new byte[8]; random.nextBytes(bytes);
            StringBuilder code = new StringBuilder();
            for (byte b : bytes) code.append(String.format(java.util.Locale.ROOT, "%02X", b & 255));
            pending.put(actor, new Pending(code.toString(), row, elapsedMillis.getAsLong() + TTL));
            return "Voy a borrar " + row.kind + " " + row.id + ": " + row.data
                    + ". Para confirmar en 5 minutos escribí SÍ " + code + ". PARAR cancela.";
        }
        if (tool.equals("corregir_registro")) {
            Validation.keys(args, "id,cambios");
            Store.Row before = requireRow(Validation.id(args));
            Object raw = args.opt("cambios");
            if (!(raw instanceof JSONObject) || ((JSONObject) raw).length() == 0)
                throw new IllegalArgumentException("¿Qué campos querés corregir?");
            JSONObject merged = Json.copy(before.data);
            Iterator<String> keys = ((JSONObject) raw).keys();
            while (keys.hasNext()) { String key = keys.next(); merged.put(key, ((JSONObject) raw).get(key)); }
            Store.Row after = new Store.Row(before.id, before.kind, before.version + 1,
                    Validation.record(before.kind, merged, LocalDate.now(clock)));
            unique(after);
            String result = "Corregido " + after.kind + " ID " + after.id + ": " + after.data;
            return store.mutate(operationId, before, after, result, clock.millis());
        }
        String kind;
        switch (tool) {
            case "registrar_movimiento": kind = "movimiento"; break;
            case "registrar_peso": kind = "peso"; break;
            case "registrar_comida": kind = "comida"; break;
            case "registrar_entreno": kind = "entreno"; break;
            case "fijar_presupuesto": kind = "presupuesto"; break;
            case "fijar_meta": kind = "meta"; break;
            default: throw new IllegalArgumentException("Esa herramienta no está permitida.");
        }
        Store.Row row = new Store.Row(UUID.randomUUID().toString(), kind, 1, Validation.record(kind, args, LocalDate.now(clock)));
        unique(row);
        String result = "Guardado " + kind + " ID " + row.id + ": " + row.data;
        if (kind.equals("comida") && (row.data.has("calorias") || row.data.has("proteina"))) result += " (nutrientes estimados)";
        return store.mutate(operationId, null, row, result, clock.millis());
    }
    /** Never exposed as a Gemini function: only an exact, direct user confirmation can call this. */
    public synchronized String confirm(String actor, String operationId, String code) throws Exception {
        String receipt = store.receipt(operationId);
        if (receipt != null) return receipt;
        Pending p = pending.get(actor);
        if (p == null || !p.code.equals(code)) return "Ese código no corresponde a un borrado pendiente tuyo.";
        pending.remove(actor); // consume even if expired, changed, or storage fails; never replay
        if (elapsedMillis.getAsLong() >= p.expires) return "El código venció. Pedí el borrado otra vez.";
        Store.Row current = store.find(p.row.id);
        if (current == null || current.version != p.row.version)
            return "El registro cambió o ya no existe. Pedí el borrado otra vez.";
        return store.mutate(operationId, p.row, null, "Borrado " + p.row.kind + " ID " + p.row.id + ".", clock.millis());
    }
    public synchronized void cancel() { pending.clear(); }
    private Store.Row requireRow(String id) throws Exception {
        Store.Row row = store.find(id);
        if (row == null) throw new IllegalArgumentException("No existe ese ID. Consultá resumen y copiá el ID completo.");
        return row;
    }
    private void unique(Store.Row candidate) throws Exception {
        if (!candidate.kind.equals("presupuesto")) return;
        for (Store.Row row : store.rows()) if (row.kind.equals("presupuesto") && !row.id.equals(candidate.id)
                && row.data.getString("mes").equals(candidate.data.getString("mes"))
                && row.data.getString("moneda").equals(candidate.data.getString("moneda"))
                && row.data.getString("categoria").equals(candidate.data.getString("categoria")))
            throw new IllegalArgumentException("Ya hay un presupuesto para esa categoría, moneda y mes. Corregí el ID " + row.id + ".");
    }
}
