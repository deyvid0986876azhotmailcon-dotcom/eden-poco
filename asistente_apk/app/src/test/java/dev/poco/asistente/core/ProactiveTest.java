package dev.poco.asistente.core;

import java.time.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProactiveTest {
    static class ClockAt extends Clock {
        Instant value;
        ClockAt(String local) { set(local); }
        void set(String local) { value = LocalDateTime.parse(local).atZone(Reminders.ZONE).toInstant(); }
        public ZoneId getZone() { return Reminders.ZONE; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(value, zone); }
        public Instant instant() { return value; }
    }
    static class Settings implements Proactive.Settings {
        boolean daily, weekly; String hour = "21:30", key = "";
        public boolean dailySummary() { return daily; } public boolean weeklySummary() { return weekly; }
        public String summaryTime() { return hour; } public String apiKey() { return key; }
        public String model() { return GeminiProtocol.MODELS[0]; } public String currency() { return "PYG"; }
    }
    static class Port extends TestStore implements Proactive.Port {
        final Map<String, JSONObject> out = new LinkedHashMap<>();
        final Map<String, Long> state = new HashMap<>();
        public void bankTransaction(java.util.concurrent.Callable<Void> action) throws Exception {
            Map<String, Row> oldData = new LinkedHashMap<>(data);
            Map<String, String> oldReceipts = new LinkedHashMap<>(receipts);
            Map<String, JSONObject> oldOut = new LinkedHashMap<>(out);
            try { action.call(); }
            catch (Exception e) {
                data.clear(); data.putAll(oldData); receipts.clear(); receipts.putAll(oldReceipts);
                out.clear(); out.putAll(oldOut); throw e;
            }
        }
        public long scheduleState(String key, long initial) { state.putIfAbsent(key, initial); return state.get(key); }
        public boolean queued(String id) { return out.containsKey(id); }
        public void enqueue(String id, String text, String kind, boolean night, long now, Store.Row reminder, long next, String key) throws Exception {
            if (out.containsKey(id)) return;
            out.put(id, Json.object("id", id, "texto", text, "night", night));
            if (reminder != null) reminder.data.put("proximo", next);
            if (key != null) state.put(key, next);
        }
        public JSONArray lease(long now, boolean quiet) throws Exception {
            JSONArray a = new JSONArray();
            for (JSONObject item : out.values()) if (!quiet || item.getBoolean("night")) a.put(item);
            return a;
        }
        public void acknowledge(List<String> ids, long now) {}
    }
    private Proactive service(Port store, Settings settings, ClockAt clock, Cloud cloud) {
        return new Proactive(store, settings, cloud, clock, new AssistantCore(store, settings, cloud, clock));
    }
    private final Cloud noCloud = (k,m,b) -> { throw new AssertionError("Must not call Gemini"); };
    @Test public void reminderCallsOnlyOnceAndOnlyAfterSuccessfulEnqueue() throws Exception {
        ClockAt clock = new ClockAt("2026-10-20T12:00");
        Settings settings = new Settings() { @Override public boolean callReminders() { return true; } };
        Port store = new Port();
        new ToolExecutor(store, clock).execute("local", "one", "programar_recordatorio",
                Json.object("texto", "agua", "cuando", "2026-10-20T12:01"));
        Store.Row reminder = store.reminders().get(0);
        long due = reminder.data.getLong("proximo");
        Proactive service = service(store, settings, clock, noCloud);
        List<String> calls = new ArrayList<>(); service.onReminderCall = calls::add;
        clock.set("2026-10-20T12:02");
        service.poll(); service.poll(); assertEquals(1, calls.size());
        reminder.data.put("proximo", due); // stale source with an already queued occurrence
        service.poll(); assertEquals(1, calls.size());

        Port rejected = new Port() {
            @Override public void enqueue(String id, String text, String kind, boolean night, long now,
                    Store.Row row, long next, String state) { /* concurrently cancelled source */ }
        };
        clock.set("2026-10-20T12:00");
        new ToolExecutor(rejected, clock).execute("local", "two", "programar_recordatorio",
                Json.object("texto", "cancelado", "cuando", "2026-10-20T12:01"));
        Proactive skipped = service(rejected, settings, clock, noCloud); skipped.onReminderCall = calls::add;
        clock.set("2026-10-20T12:02"); skipped.poll(); assertEquals(1, calls.size());
    }
    @Test public void pollRespectsSilenceAndOnlyExplicitNightReminderEscapes() throws Exception {
        Port store = new Port(); Settings settings = new Settings(); settings.daily = true;
        ClockAt clock = new ClockAt("2026-10-20T22:00");
        ToolExecutor executor = new ToolExecutor(store, clock);
        executor.execute("local", "one", "programar_recordatorio", Json.object("texto", "de día", "cuando", "2026-10-20T22:30"));
        executor.execute("local", "two", "programar_recordatorio", Json.object("texto", "de noche", "cuando", "2026-10-20T23:05"));
        Proactive service = service(store, settings, clock, noCloud);
        clock.set("2026-10-20T23:06"); JSONArray night = service.poll();
        assertEquals(1, night.length()); assertEquals("de noche", night.getJSONObject(0).getString("texto"));
        clock.set("2026-10-21T07:00"); service.poll();
        assertEquals(3, store.out.size()); assertTrue(store.out.containsKey("d_2026-10-20"));
        service.poll(); assertEquals(3, store.out.size());
    }
    @Test public void systemReminderEveryTenLocalDaysAndSundaySummaryAreIdempotent() throws Exception {
        Port store = new Port(); Settings settings = new Settings(); settings.weekly = true;
        ClockAt clock = new ClockAt("2026-10-20T12:00");
        Proactive service = service(store, settings, clock, noCloud); service.poll(); assertEquals(0, store.out.size());
        clock.set("2026-10-25T21:30"); service.poll(); assertTrue(store.out.containsKey("w_2026-10-25"));
        clock.set("2026-10-30T12:00"); service.poll(); service.poll(); assertEquals(2, store.out.size());
        // Monday noon: the 10-day system reminder, and Sunday's weekly summary that 21:30 missed (it catches up the next day)
        clock.set("2026-11-09T12:00"); service.poll(); assertEquals(4, store.out.size()); assertTrue(store.out.containsKey("w_2026-11-08"));
    }
    @Test public void quotaFailureFallsBackAndStopPreventsLateSummaryCommit() throws Exception {
        Port store = new Port(); Settings settings = new Settings(); settings.daily = true; settings.key = "key";
        ClockAt clock = new ClockAt("2026-10-20T21:30");
        Proactive service = service(store, settings, clock, (k,m,b) -> { throw new IllegalArgumentException("429"); });
        assertTrue(service.poll().getJSONObject(0).getString("texto").contains("Paso a paso"));
        clock.set("2026-10-21T21:30");
        AssistantCore[] core = new AssistantCore[1];
        Cloud stops = (k,m,b) -> { assertFalse(b.has("tools")); core[0].stop(); return new GeminiProtocol.Reply("Frase tardía", null, null, ""); };
        core[0] = new AssistantCore(store, settings, stops, clock);
        Proactive stopped = new Proactive(store, settings, stops, clock, core[0]);
        assertEquals(0, stopped.poll().length()); assertFalse(store.out.containsKey("d_2026-10-21"));
    }
    @Test public void reportComputesMoneyAndHealthWithoutCloudAndExcludesLaterDates() throws Exception {
        TestStore store = new TestStore(); ClockAt clock = new ClockAt("2026-10-20T21:30");
        ToolExecutor executor = new ToolExecutor(store, clock);
        executor.execute("local", "one", "registrar_movimiento", Json.object("tipo", "ingreso", "centavos", 100000, "moneda", "PYG", "categoria", "sueldo", "fecha", "2026-10-20"));
        executor.execute("local", "two", "registrar_peso", Json.object("kg", 58, "fecha", "2026-10-20"));
        executor.execute("local", "three", "registrar_entreno", Json.object("ejercicio", "sentadilla", "kg", 40, "series", 3, "repeticiones", 10, "fecha", "2026-10-20"));
        String report = Proactive.report(store.rows(), LocalDate.of(2026,10,20), false);
        assertTrue(report.contains("Ingresos: ₲ 1.000")); assertTrue(report.contains("58.0 kg")); assertTrue(report.contains("3 series"));
        String yesterday = Proactive.report(store.rows(), LocalDate.of(2026,10,19), false);
        assertFalse(yesterday.contains("58.0 kg")); assertFalse(yesterday.contains("₲ 1.000"));
    }
}
