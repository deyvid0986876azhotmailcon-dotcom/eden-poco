package dev.poco.asistente.core;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Aggregates are computed in code, always separated by currency. */
public final class Summary {
    private Summary() {}
    public static String money(long cents, String currency) {
        return Receipt.money(cents, currency);
    }
    public static String render(List<Store.Row> rows, LocalDate today, boolean includeIds) throws Exception {
        String month = YearMonth.from(today).toString();
        Map<String, Long> income = new TreeMap<>(), expense = new TreeMap<>(), categories = new TreeMap<>();
        List<Store.Row> budgets = new ArrayList<>(), weights = new ArrayList<>(), goals = new ArrayList<>();
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        int workouts = 0;
        long sets = 0;
        for (Store.Row row : rows) {
            String date = row.data.optString("fecha", "");
            if (row.kind.equals("movimiento") && date.startsWith(month)) {
                String currency = row.data.getString("moneda"); long amount = row.data.getLong("centavos");
                if (row.data.getString("tipo").equals("ingreso")) add(income, currency, amount);
                else { add(expense, currency, amount); add(categories, currency + ":" + row.data.getString("categoria"), amount); }
            }
            if (row.kind.equals("presupuesto") && row.data.getString("mes").equals(month)) budgets.add(row);
            if (row.kind.equals("peso")) weights.add(row);
            if (row.kind.equals("meta")) goals.add(row);
            if (row.kind.equals("entreno") && date.compareTo(monday.toString()) >= 0 && date.compareTo(today.toString()) <= 0) {
                workouts++; sets += row.data.getLong("series");
            }
        }
        StringBuilder out = new StringBuilder("Mes ").append(month).append("\n");
        Map<String, Long> currencies = new TreeMap<>(income); currencies.putAll(expense);
        if (currencies.isEmpty()) out.append("Sin movimientos este mes.\n");
        for (String currency : currencies.keySet()) {
            long in = income.getOrDefault(currency, 0L), spent = expense.getOrDefault(currency, 0L);
            out.append("Saldo: ").append(money(in - spent, currency)).append(" (ingresos ").append(money(in, currency))
                    .append("; gastos ").append(money(spent, currency)).append(")\n");
        }
        for (Map.Entry<String, Long> e : categories.entrySet())
            out.append("Gasto / ").append(e.getKey().substring(4)).append(": ").append(money(e.getValue(), e.getKey().substring(0, 3))).append('\n');
        for (Store.Row b : budgets) {
            String currency = b.data.getString("moneda"), category = b.data.getString("categoria");
            long limit = b.data.getLong("centavos"), spent = categories.getOrDefault(currency + ":" + category, 0L);
            out.append("Presupuesto / ").append(category).append(": quedan ").append(money(limit - spent, currency));
            if (spent >= limit) out.append(" — superaste o alcanzaste el tope");
            else if (spent >= limit * 0.8) out.append(" — ya usaste al menos el 80 %");
            out.append('\n');
        }
        if (budgets.isEmpty()) out.append("Sin presupuestos este mes.\n");
        // Stable sort retains insertion order within a day: the last entry is the latest measurement.
        weights.sort(Comparator.comparing((Store.Row r) -> r.data.optString("fecha")));
        if (weights.isEmpty()) out.append("Sin peso registrado.\n");
        else {
            Store.Row last = weights.get(weights.size() - 1);
            out.append("Último peso: ").append(last.data.getDouble("kg")).append(" kg (").append(last.data.getString("fecha")).append(")");
            // Compare distinct days; multiple weights on one day are not a meaningful trend.
            Store.Row previous = null;
            for (Store.Row w : weights) if (w.data.getString("fecha").compareTo(last.data.getString("fecha")) < 0) previous = w;
            if (previous == null) out.append("; falta otro día para ver tendencia");
            else {
                BigDecimal delta = BigDecimal.valueOf(last.data.getDouble("kg")).subtract(BigDecimal.valueOf(previous.data.getDouble("kg")));
                out.append("; cambio desde ").append(previous.data.getString("fecha")).append(": ").append(delta.signum() > 0 ? "+" : "").append(delta.stripTrailingZeros().toPlainString()).append(" kg");
            }
            out.append('\n');
        }
        out.append("Entrenos desde ").append(monday).append(": ").append(workouts).append(" ejercicios registrados, ").append(sets).append(" series.\n");
        for (Store.Row goal : goals) out.append("Meta: ").append(goal.data.getString("nombre")).append(" — ")
                .append(money(goal.data.getLong("centavos"), goal.data.getString("moneda"))).append("\n");
        if (includeIds) {
            out.append("\nRegistros recientes (hasta 30, con ID):\n");
            for (int i = Math.max(0, rows.size() - 30); i < rows.size(); i++) {
                Store.Row r = rows.get(i); out.append(r.kind).append(" ").append(r.id).append("\n").append(r.data).append('\n');
            }
        }
        return out.toString();
    }
    private static void add(Map<String, Long> map, String key, long amount) {
        map.put(key, Math.addExact(map.getOrDefault(key, 0L), amount));
    }
}
