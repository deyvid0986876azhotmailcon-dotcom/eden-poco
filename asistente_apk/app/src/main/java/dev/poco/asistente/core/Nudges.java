package dev.poco.asistente.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/**
 * What she says without being asked (point 10): the morning brief, a word about an unusual expense and a nudge
 * when the gym stops showing up. Only computes texts from David's own records; Proactive decides when and keeps
 * each one to a single message (queued ids). No Android, no model, no IDs in the texts.
 */
public final class Nudges {
    private Nudges() {}
    /** Below this, no expense is «big» however small the usual ones are (in cents: 300.000 guaraníes). */
    public static final long BIG_FLOOR_CENTS = 300_000L * 100;
    public static final int GYM_DAYS = 4;

    public static String morning(List<Store.Row> rows, List<Store.Row> reminders, LocalDate today, ZoneId zone, String currency) {
        StringBuilder out = new StringBuilder("Buen día, che.");
        List<String> due = new ArrayList<>();
        long start = today.atStartOfDay(zone).toInstant().toEpochMilli(), end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        for (Store.Row r : reminders) {
            long next = r.data.optLong("proximo", -1);
            if (next >= start && next < end && r.data.optBoolean("activo", true))
                due.add(Instant.ofEpochMilli(next).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")) + " — " + Memory.clip(r.data.optString("texto"), 120));
        }
        due.sort(null);
        if (due.isEmpty()) out.append(" Hoy no tenés recordatorios.");
        else out.append(" Hoy tenés:\n• ").append(String.join("\n• ", due));
        long yesterday = spent(rows, today.minusDays(1), today.minusDays(1), currency);
        if (yesterday > 0) out.append("\nAyer gastaste ").append(Receipt.money(yesterday, currency)).append('.');
        String month = today.toString().substring(0, 7);
        for (Store.Row r : rows) {
            if (!r.kind.equals("presupuesto") || !month.equals(r.data.optString("mes")) || !currency.equals(r.data.optString("moneda"))) continue;
            long limit = r.data.optLong("centavos"), used = spentIn(rows, today.withDayOfMonth(1), today, currency, r.data.optString("categoria"));
            if (limit <= 0) continue;
            out.append("\nEn ").append(r.data.optString("categoria")).append(" llevás ").append(Receipt.money(used, currency))
                    .append(" de ").append(Receipt.money(limit, currency)).append(" (").append(used * 100 / limit).append("%)")
                    .append(used > limit ? ": te pasaste." : used * 10 >= limit * 8 ? ": ojo que ya vas cerca." : ".");
        }
        return out.toString();
    }

    /** Expenses from today or yesterday that are at least three times the usual one and above the floor. */
    public static List<Store.Row> bigExpenses(List<Store.Row> rows, LocalDate today, String currency) {
        List<Store.Row> big = new ArrayList<>();
        for (Store.Row r : rows) {
            if (!isExpense(r, currency)) continue;
            LocalDate date = LocalDate.parse(r.data.optString("fecha"));
            if (date.isBefore(today.minusDays(1)) || date.isAfter(today)) continue;
            long amount = r.data.optLong("centavos"), total = 0; int n = 0;
            for (Store.Row o : rows) {
                if (o == r || !isExpense(o, currency)) continue;
                LocalDate d = LocalDate.parse(o.data.optString("fecha"));
                if (d.isBefore(today.minusDays(30)) || d.isAfter(today)) continue;
                total += o.data.optLong("centavos"); n++;
            }
            // with little history there is no «usual»: only the floor counts, raised so it does not nag
            long usual = n >= 5 ? total / n : 0;
            if (amount >= Math.max(BIG_FLOOR_CENTS, usual * 3) && (n >= 5 || amount >= BIG_FLOOR_CENTS * 2)) big.add(r);
        }
        return big;
    }

    public static String bigExpense(Store.Row r, String currency) {
        String note = r.data.optString("nota");
        return "Che, anoté un gasto de " + Receipt.money(r.data.optLong("centavos"), currency) + " en " + r.data.optString("categoria")
                + (note.isEmpty() ? "" : " (" + Memory.clip(note, 80) + ")")
                + ". Es bastante más de lo que solés gastar; si no lo reconocés, revisalo.";
    }

    /** True when David logged workouts before and none in the last GYM_DAYS days. */
    public static boolean gymDue(List<Store.Row> rows, LocalDate today) {
        LocalDate last = null;
        for (Store.Row r : rows) {
            if (!r.kind.equals("entreno")) continue;
            LocalDate d = LocalDate.parse(r.data.optString("fecha"));
            if (last == null || d.isAfter(last)) last = d;
        }
        return last != null && !last.isAfter(today.minusDays(GYM_DAYS));
    }

    public static final String GYM = "Che, hace unos días que no anotás gym. ¿Vamos mañana aunque sea un rato?";

    private static boolean isExpense(Store.Row r, String currency) {
        JSONObject d = r.data;
        return r.kind.equals("movimiento") && "gasto".equals(d.optString("tipo")) && currency.equals(d.optString("moneda"))
                && d.optString("fecha").matches("\\d{4}-\\d{2}-\\d{2}");
    }
    private static long spent(List<Store.Row> rows, LocalDate from, LocalDate to, String currency) { return spentIn(rows, from, to, currency, null); }
    private static long spentIn(List<Store.Row> rows, LocalDate from, LocalDate to, String currency, String category) {
        long total = 0;
        for (Store.Row r : rows) {
            if (!isExpense(r, currency) || category != null && !category.equals(r.data.optString("categoria"))) continue;
            LocalDate d = LocalDate.parse(r.data.optString("fecha"));
            if (!d.isBefore(from) && !d.isAfter(to)) total += r.data.optLong("centavos");
        }
        return total;
    }
}
