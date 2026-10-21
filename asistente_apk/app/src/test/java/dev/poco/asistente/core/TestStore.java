package dev.poco.asistente.core;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory port for JVM behavior tests; real SQLite/Keystore are checked on Android. */
class TestStore implements Store {
    final Map<String, Row> data = new LinkedHashMap<>();
    final Map<String, String> receipts = new LinkedHashMap<>();
    private final Map<String, Memory.Exchange> exchanges = new LinkedHashMap<>();
    @Override public List<Memory.Exchange> conversation() { return new ArrayList<>(exchanges.values()); }
    @Override public void exchange(String id, String user, String model) { exchanges.putIfAbsent(id, new Memory.Exchange(user, model)); while (exchanges.size() > 10) exchanges.remove(exchanges.keySet().iterator().next()); }
    @Override public void clearConversation() { exchanges.clear(); }
    @Override public List<Row> memories() { List<Row> rows = new ArrayList<>(); for (Row r : data.values()) if (r.kind.equals("memoria")) rows.add(r); return rows; }
    @Override public List<Row> reminders() { List<Row> rows = new ArrayList<>(); for (Row r : data.values()) if (r.kind.equals("recordatorio") && r.data.optBoolean("activo")) rows.add(r); return rows; }
    @Override public List<Row> rows() { List<Row> rows = new ArrayList<>(); for (Row r : data.values()) if (!r.kind.equals("memoria") && !r.kind.equals("recordatorio")) rows.add(r); return rows; }
    @Override public Row find(String id) { return data.get(id); }
    @Override public String receipt(String op) { return receipts.get(op); }
    @Override public String mutate(String op, Row before, Row after, String result, long time) throws Exception {
        if (receipts.containsKey(op)) return receipts.get(op);
        if (before != null && (data.get(before.id) == null || data.get(before.id).version != before.version))
            throw new IllegalArgumentException("El registro cambió");
        if (after == null) data.remove(before.id);
        else data.put(after.id, new Row(after.id, after.kind, after.version, Json.copy(after.data)));
        receipts.put(op, result); return result;
    }
    @Override public String history(String date) { return String.join("\n", receipts.values()); }
    @Override public void beginAction(String id, String tool, long time) {
        if (receipts.putIfAbsent(id, "Pedido iniciado; resultado incierto.") != null) throw new IllegalStateException("Pedido repetido");
    }
    @Override public void finishAction(String id, String result) { receipts.put(id, result); }
    static final class MutableClock extends Clock {
        long millis = Instant.parse("2026-10-17T12:00:00Z").toEpochMilli();
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }
}
