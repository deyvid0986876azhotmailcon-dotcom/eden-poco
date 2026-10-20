package dev.poco.asistente.core;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Generated only on polling. Port.enqueue atomically commits the message and advances its source. */
public final class Proactive {
    public interface Port extends Store {
        long scheduleState(String key, long initial) throws Exception;
        boolean queued(String id) throws Exception;
        void enqueue(String id, String text, String kind, boolean night, long now, Row reminder, long next, String state) throws Exception;
        JSONArray lease(long now, boolean quiet) throws Exception;
        void acknowledge(List<String> ids, long now) throws Exception;
    }
    public interface Settings extends AssistantCore.Settings {
        boolean dailySummary();
        boolean weeklySummary();
        String summaryTime();
    }
    private final Port store;
    private final Settings settings;
    private final Cloud cloud;
    private final Clock clock;
    private final AssistantCore core;
    public Proactive(Port store, Settings settings, Cloud cloud, Clock clock, AssistantCore core) {
        this.store = store; this.settings = settings; this.cloud = cloud; this.clock = clock; this.core = core;
    }
    public synchronized JSONArray poll() throws Exception {
        long ticket = core.cancellationToken();
        BooleanSupplier cancelled = () -> core.proactivePaused() || ticket != core.cancellationToken();
        if (cancelled.getAsBoolean()) return new JSONArray();
        ZonedDateTime now = ZonedDateTime.now(clock);
        long millis = now.toInstant().toEpochMilli();
        for (Store.Row reminder : store.reminders()) {
            if (cancelled.getAsBoolean()) return new JSONArray();
            long due = reminder.data.getLong("proximo");
            boolean night = Reminders.explicitNight(reminder.data);
            if (due >= 0 && due <= millis && (!Reminders.quiet(now) || night)) {
                long next = Reminders.next(reminder.data, now.toInstant(), clock.getZone());
                core.proactiveAction(ticket, () -> { store.enqueue("r_" + reminder.id + "_" + due, reminder.data.getString("texto"), "recordatorio", night, millis, reminder, next, null); return null; }, null);
            }
        }
        long system = store.scheduleState("whatsapp", now.plusDays(10).toInstant().toEpochMilli());
        if (!Reminders.quiet(now)) {
            if (system <= millis && !cancelled.getAsBoolean()) core.proactiveAction(ticket, () -> { store.enqueue("s_" + system,
                    "Abrí el WhatsApp del Segundo espacio un momento, así el bot no se desconecta", "sistema", false,
                    millis, null, now.plusDays(10).toInstant().toEpochMilli(), "whatsapp"); return null; }, null);
            LocalTime hour = Reminders.time(settings.summaryTime());
            LocalDate daily = now.toLocalTime().isBefore(hour) ? now.toLocalDate().minusDays(1) : now.toLocalDate();
            LocalDate weekly = daily.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
            if (settings.dailySummary()) summary("d_", daily, false, millis, cancelled, ticket);
            if (settings.weeklySummary() && daily.getDayOfWeek() == DayOfWeek.SUNDAY) summary("w_", weekly, true, millis, cancelled, ticket);
        }
        if (cancelled.getAsBoolean()) return new JSONArray();
        return core.proactiveAction(ticket, () -> store.lease(clock.millis(), Reminders.quiet(ZonedDateTime.now(clock))), new JSONArray());
    }
    private void summary(String prefix, LocalDate date, boolean weekly, long now, BooleanSupplier cancelled, long ticket) throws Exception {
        String id = prefix + date;
        if (store.queued(id) || cancelled.getAsBoolean()) return;
        String report = report(store.rows(), date, weekly);
        if (report.length() > 12000) report = Memory.clip(report, 12000) + "\n[Resumen recortado; consultá la app para el detalle.]";
        String phrase = "Paso a paso, che. Mañana seguimos juntas con tus metas.";
        try {
            String key = settings.apiKey();
            if (key != null && !key.isEmpty()) {
                JSONObject body = GeminiProtocol.request("Escribí solo una frase breve de ánimo para cerrar este resumen. Sin cifras ni herramientas.",
                        Memory.clip(report, 6000), date, settings.currency(), store.conversation(), store.memories());
                body.remove("tools"); body.remove("toolConfig");
                body.getJSONObject("generationConfig").put("maxOutputTokens", 128);
                GeminiProtocol.Reply reply = cloud.generate(key, settings.model(), body, cancelled);
                if (reply.tool == null && reply.text != null && !reply.text.trim().isEmpty()) phrase = Memory.clip(reply.text, 300);
            }
        } catch (Exception ignored) { /* No key, no quota or no network: the computed report still goes out. */ }
        String finalText = report + "\n" + phrase;
        core.proactiveAction(ticket, () -> { store.enqueue(id, finalText, weekly ? "semanal" : "diario", false, now, null, 0, null); return null; }, null);
    }
    public static String report(List<Store.Row> rows, LocalDate date, boolean weekly) throws Exception {
        LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate from = weekly ? monday : date;
        Map<String, Long> income = new TreeMap<>(), expense = new TreeMap<>();
        StringBuilder weights = new StringBuilder();
        for (Store.Row row : rows) {
            String day = row.data.optString("fecha");
            if (row.kind.equals("movimiento") && day.compareTo(from.toString()) >= 0 && day.compareTo(date.toString()) <= 0) {
                Map<String, Long> totals = row.data.getString("tipo").equals("ingreso") ? income : expense;
                String currency = row.data.getString("moneda");
                totals.put(currency, Math.addExact(totals.getOrDefault(currency, 0L), row.data.getLong("centavos")));
            }
            if (row.kind.equals("peso") && day.compareTo(monday.toString()) >= 0 && day.compareTo(date.toString()) <= 0)
                weights.append(day).append(": ").append(row.data.getDouble("kg")).append(" kg; ");
        }
        Set<String> currencies = new TreeSet<>(income.keySet()); currencies.addAll(expense.keySet());
        StringBuilder text = new StringBuilder(weekly ? "Resumen semanal " : "Resumen diario ").append(date).append('\n');
        if (currencies.isEmpty()) text.append("Sin gastos ni ingresos en el período.\n");
        for (String currency : currencies) text.append("Ingresos: ").append(Receipt.money(income.getOrDefault(currency, 0L), currency))
                .append("; gastos: ").append(Receipt.money(expense.getOrDefault(currency, 0L), currency)).append('\n');
        text.append("Pesos de la semana: ").append(weights.length() == 0 ? "sin registros" : weights).append('\n');
        List<Store.Row> throughDate = new ArrayList<>();
        for (Store.Row row : rows) if (!row.data.has("fecha") || row.kind.equals("meta") || row.data.getString("fecha").compareTo(date.toString()) <= 0) throughDate.add(row);
        return text.append(Summary.render(throughDate, date, false)).toString();
    }
}
