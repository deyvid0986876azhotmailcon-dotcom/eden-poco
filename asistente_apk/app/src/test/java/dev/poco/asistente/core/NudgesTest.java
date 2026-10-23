package dev.poco.asistente.core;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class NudgesTest {
    private static int n;
    private static Store.Row row(String kind, Object... kv) throws Exception {
        return new Store.Row("r" + (++n), kind, 1, Json.object(kv));
    }
    private static Store.Row gasto(String fecha, long guaranies, String categoria) throws Exception {
        return row("movimiento", "tipo", "gasto", "centavos", guaranies * 100, "moneda", "PYG", "categoria", categoria, "fecha", fecha);
    }
    private static final LocalDate HOY = LocalDate.parse("2026-10-23");

    @Test public void morningListsTodaysRemindersYesterdaysSpendingAndBudgets() throws Exception {
        long at = LocalDate.parse("2026-10-23").atTime(15, 0).atZone(Reminders.ZONE).toInstant().toEpochMilli();
        long tomorrow = LocalDate.parse("2026-10-24").atTime(9, 0).atZone(Reminders.ZONE).toInstant().toEpochMilli();
        List<Store.Row> reminders = Arrays.asList(row("recordatorio", "texto", "Dentista", "proximo", at, "activo", true),
                row("recordatorio", "texto", "Mañana no", "proximo", tomorrow, "activo", true));
        List<Store.Row> rows = Arrays.asList(gasto("2026-10-22", 25_000, "comida"), gasto("2026-10-22", 10_000, "telefono"),
                gasto("2026-10-10", 700_000, "comida"), row("presupuesto", "mes", "2026-10", "categoria", "comida", "centavos", 800_000L * 100, "moneda", "PYG"));
        String text = Nudges.morning(rows, reminders, HOY, Reminders.ZONE, "PYG");
        assertTrue(text, text.startsWith("Buen día, che. Hoy tenés:\n• 15:00 — Dentista"));
        assertFalse(text.contains("Mañana no"));
        assertTrue(text, text.contains("Ayer gastaste " + Receipt.money(35_000L * 100, "PYG")));
        assertTrue(text, text.contains("En comida llevás " + Receipt.money(725_000L * 100, "PYG") + " de " + Receipt.money(800_000L * 100, "PYG") + " (90%): ojo que ya vas cerca."));
        assertEquals("Buen día, che. Hoy no tenés recordatorios.", Nudges.morning(Collections.emptyList(), Collections.emptyList(), HOY, Reminders.ZONE, "PYG"));
    }

    @Test public void bigExpenseNeedsHistoryOrAVeryLargeAmountAndOnlyRecentOnes() throws Exception {
        List<Store.Row> rows = new ArrayList<>();
        for (int d = 1; d <= 6; d++) rows.add(gasto("2026-10-" + (10 + d), 40_000, "comida"));
        Store.Row big = gasto("2026-10-23", 350_000, "compras"), usual = gasto("2026-10-23", 60_000, "comida"), old = gasto("2026-09-01", 900_000, "compras"); // outside the 30-day window
        rows.add(big); rows.add(usual); rows.add(old);
        assertEquals(Collections.singletonList(big), Nudges.bigExpenses(rows, HOY, "PYG"));
        assertTrue(Nudges.bigExpense(big, "PYG").contains("bastante más de lo que solés gastar"));
        // without five expenses of history, only twice the floor counts
        assertTrue(Nudges.bigExpenses(Arrays.asList(gasto("2026-10-23", 400_000, "compras")), HOY, "PYG").isEmpty());
        assertEquals(1, Nudges.bigExpenses(Arrays.asList(gasto("2026-10-23", 650_000, "compras")), HOY, "PYG").size());
    }

    @Test public void gymNudgeOnlyAfterFourQuietDaysAndOnlyIfThereWasAGym() throws Exception {
        assertFalse(Nudges.gymDue(Collections.emptyList(), HOY));
        assertFalse(Nudges.gymDue(Arrays.asList(row("entreno", "fecha", "2026-10-20", "ejercicio", "sentadilla")), HOY));
        assertTrue(Nudges.gymDue(Arrays.asList(row("entreno", "fecha", "2026-10-19", "ejercicio", "sentadilla")), HOY));
    }

    @Test public void proactiveSendsTheMorningOnceBetweenItsHourAndNoonAndTheGymEveryThreeDays() throws Exception {
        ProactiveTest.Port store = new ProactiveTest.Port();
        ProactiveTest.ClockAt clock = new ProactiveTest.ClockAt("2026-10-23T07:00");
        ProactiveTest.Settings settings = new ProactiveTest.Settings() {
            @Override public boolean morningBrief() { return true; }
            @Override public boolean nudges() { return true; }
        };
        Cloud noCloud = (k, m, b) -> { throw new AssertionError("Must not call Gemini"); };
        Proactive service = new Proactive(store, settings, noCloud, clock, new AssistantCore(store, settings, noCloud, clock));
        store.mutate("op1", null, row("entreno", "fecha", "2026-10-18", "ejercicio", "press"), "ok", 0);
        service.poll();
        assertFalse(store.out.containsKey("m_2026-10-23")); // before 07:30
        clock.set("2026-10-23T07:45"); service.poll(); service.poll();
        assertTrue(store.out.containsKey("m_2026-10-23")); assertEquals(1, store.out.keySet().stream().filter(k -> k.startsWith("m_")).count());
        assertFalse(store.out.containsKey("gym_2026-10-23")); // gym waits for 18:00
        clock.set("2026-10-23T18:30"); service.poll();
        assertTrue(store.out.containsKey("gym_2026-10-23"));
        clock.set("2026-10-24T18:30"); service.poll();
        assertFalse(store.out.containsKey("gym_2026-10-24")); // not again for three days
        clock.set("2026-10-25T13:00"); service.poll();
        assertFalse(store.out.containsKey("m_2026-10-25")); // a missed morning is not sent in the afternoon
    }
    @Test public void malformedLegacyDatesDoNotBlockPollingOrHideValidWorkouts() throws Exception {
        ProactiveTest.Port store = new ProactiveTest.Port();
        ProactiveTest.ClockAt clock = new ProactiveTest.ClockAt("2026-10-23T18:30");
        ProactiveTest.Settings settings = new ProactiveTest.Settings() {
            @Override public boolean morningBrief() { return true; }
            @Override public boolean nudges() { return true; }
        };
        List<Store.Row> rows = Arrays.asList(gasto("2026-02-30", 900_000, "compras"),
                row("entreno", "fecha", ""), row("entreno", "fecha", "2027-01-01"),
                row("entreno", "fecha", "2026-10-18"), gasto("2026-10-23", 650_000, "compras"));
        int op = 0;
        for (Store.Row r : rows) store.mutate("legacy" + (++op), null, r, "ok", 0);
        Cloud noCloud = (k, m, b) -> { throw new AssertionError("Must not call Gemini"); };
        AssistantCore core = new AssistantCore(store, settings, noCloud, clock);
        Proactive service = new Proactive(store, settings, noCloud, clock, core);
        assertEquals(2, service.poll().length()); // expense and gym still delivered
        assertTrue(store.out.containsKey("gym_2026-10-23"));
        clock.set("2026-10-24T07:45");
        service.poll();
        assertTrue(store.out.containsKey("m_2026-10-24"));
        assertFalse(Nudges.morning(rows, Collections.emptyList(), HOY, Reminders.ZONE, "PYG").contains("900.000"));
    }

    @Test public void totalsPercentagesAndAverageDoNotOverflowLong() throws Exception {
        List<Store.Row> rows = new ArrayList<>();
        for (int i = 0; i < 6; i++) rows.add(row("movimiento", "tipo", "gasto", "centavos", Long.MAX_VALUE,
                "moneda", "PYG", "categoria", "compras", "fecha", "2026-10-22"));
        rows.add(row("presupuesto", "mes", "2026-10", "categoria", "compras", "centavos", Long.MAX_VALUE, "moneda", "PYG"));
        String text = Nudges.morning(rows, Collections.emptyList(), HOY, Reminders.ZONE, "PYG");
        assertTrue(text, text.contains("(600%): te pasaste."));
        assertTrue(text, text.contains(Receipt.money(java.math.BigInteger.valueOf(Long.MAX_VALUE).multiply(java.math.BigInteger.valueOf(6)), "PYG")));
        assertTrue(Nudges.bigExpenses(rows, HOY, "PYG").isEmpty());
    }

    @Test public void guaraniFloorIsNotAppliedToOtherCurrenciesAndCurrenciesStaySeparate() throws Exception {
        Store.Row usd = row("movimiento", "tipo", "gasto", "centavos", 65_000_000L,
                "moneda", "USD", "categoria", "compras", "fecha", "2026-10-22");
        assertTrue(Nudges.bigExpenses(Collections.singletonList(usd), HOY, "USD").isEmpty());
        assertTrue(Nudges.bigExpenses(Collections.singletonList(usd), HOY, "PYG").isEmpty());
        assertFalse(Nudges.morning(Collections.singletonList(usd), Collections.emptyList(), HOY, Reminders.ZONE, "PYG").contains("Ayer gastaste"));
    }

    @Test public void morningExpiresAtNoonAndAcrossMidnightButNotBeforeNoon() {
        ProactiveTest.ClockAt clock = new ProactiveTest.ClockAt("2026-10-23T07:45");
        long created = clock.millis();
        clock.set("2026-10-23T11:59");
        assertTrue(created > Nudges.morningExpiryCutoff(clock.millis(), clock.getZone()));
        clock.set("2026-10-23T12:00");
        assertTrue(created <= Nudges.morningExpiryCutoff(clock.millis(), clock.getZone()));
        clock.set("2026-10-24T07:30");
        assertTrue(created <= Nudges.morningExpiryCutoff(clock.millis(), clock.getZone()));
    }

    @Test public void morningIsBoundedAndRestartSilenceAndStopPreserveDeduplication() throws Exception {
        ProactiveTest.Port store = new ProactiveTest.Port();
        ProactiveTest.ClockAt clock = new ProactiveTest.ClockAt("2026-10-23T07:45");
        ProactiveTest.Settings settings = new ProactiveTest.Settings() {
            @Override public boolean morningBrief() { return true; }
            @Override public boolean nudges() { return true; }
        };
        for (int i = 0; i < 2000; i++) store.mutate("rem" + i, null,
                row("recordatorio", "texto", "x".repeat(120), "proximo", clock.millis() + 3600000, "activo", true), "ok", 0);
        Cloud noCloud = (k, m, b) -> { throw new AssertionError("Must not call Gemini"); };
        AssistantCore core = new AssistantCore(store, settings, noCloud, clock);
        Proactive service = new Proactive(store, settings, noCloud, clock, core);
        service.poll();
        assertTrue(store.out.get("m_2026-10-23").getString("texto").length() <= 12000);
        new Proactive(store, settings, noCloud, clock, core).poll();
        assertEquals(1, store.out.size());
        clock.set("2026-10-24T00:01");
        assertEquals(0, service.poll().length());
        clock.set("2026-10-24T07:45");
        core.stop();
        assertEquals(0, service.poll().length());
        assertFalse(store.out.containsKey("m_2026-10-24"));
    }

}
