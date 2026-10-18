package dev.poco.asistente.core;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SummaryTest {
    private Store.Row row(String kind, JSONObject data) { return new Store.Row(UUID.randomUUID().toString(), kind, 1, data); }
    @Test public void monthlyBalanceCurrenciesBudgetsAndWeeklyTraining() throws Exception {
        List<Store.Row> rows = new ArrayList<>();
        rows.add(row("movimiento", Json.object("tipo", "ingreso", "centavos", 100000, "moneda", "ARS", "categoria", "sueldo", "fecha", "2026-10-01")));
        rows.add(row("movimiento", Json.object("tipo", "gasto", "centavos", 80000, "moneda", "ARS", "categoria", "super", "fecha", "2026-10-17")));
        rows.add(row("movimiento", Json.object("tipo", "gasto", "centavos", 100, "moneda", "USD", "categoria", "super", "fecha", "2026-10-17")));
        rows.add(row("movimiento", Json.object("tipo", "ingreso", "centavos", 99999, "moneda", "ARS", "categoria", "sueldo", "fecha", "2026-09-01")));
        rows.add(row("presupuesto", Json.object("centavos", 100000, "moneda", "ARS", "categoria", "super", "mes", "2026-10")));
        rows.add(row("entreno", Json.object("fecha", "2026-10-12", "series", 3)));
        rows.add(row("entreno", Json.object("fecha", "2026-10-11", "series", 4)));
        String summary = Summary.render(rows, LocalDate.of(2026, 10, 17), false);
        assertTrue(summary.contains("Saldo: ARS 200,00")); assertTrue(summary.contains("Saldo: USD -1,00"));
        assertTrue(summary.contains("quedan ARS 200,00")); assertTrue(summary.contains("80 %"));
        assertTrue(summary.contains("1 ejercicios registrados, 3 series"));
    }
    @Test public void trendUsesLatestDateAndPreviousDistinctDay() throws Exception {
        List<Store.Row> rows = new ArrayList<>();
        rows.add(row("peso", Json.object("fecha", "2026-10-17", "kg", 58.4)));
        rows.add(row("peso", Json.object("fecha", "2026-10-10", "kg", 58.0)));
        rows.add(row("peso", Json.object("fecha", "2026-10-17", "kg", 58.5)));
        String summary = Summary.render(rows, LocalDate.of(2026, 10, 17), false);
        assertTrue(summary.contains("Último peso: 58.5")); assertTrue(summary.contains("+0.5 kg"));
        assertFalse(summary.contains("Registros recientes"));
    }
}
