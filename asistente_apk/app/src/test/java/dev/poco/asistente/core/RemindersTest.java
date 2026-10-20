package dev.poco.asistente.core;

import java.time.*;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RemindersTest {
    private Clock at(String local) { return Clock.fixed(LocalDateTime.parse(local).atZone(Reminders.ZONE).toInstant(), Reminders.ZONE); }
    private JSONObject once(String time) throws Exception { return Json.object("texto", "Tomar agua", "cuando", time); }
    private JSONObject repeat(String days, String time) throws Exception { return Json.object("texto", "Entrenar", "repeticion", days, "hora", time); }
    @Test public void oneShotRequiresStrictFutureLocalDateAndNoMixedSchedule() throws Exception {
        Clock now = at("2026-10-20T12:00");
        JSONObject d = Reminders.validate(once("2026-10-20T12:01"), now);
        assertEquals(now.millis() + 60000, d.getLong("proximo"));
        for (String when : new String[]{"2026-10-20T12:00", "2026-10-19T12:00", "2026-02-30T12:00", "2026-10-20T25:00", "2026-10-20T12:30Z"})
            assertThrows(IllegalArgumentException.class, () -> Reminders.validate(once(when), now));
        assertThrows(IllegalArgumentException.class, () -> Reminders.validate(once("2026-10-21T12:00").put("hora", "12:00"), now));
        assertEquals(-1, Reminders.next(d, Instant.ofEpochMilli(d.getLong("proximo")), Reminders.ZONE));
    }
    @Test public void dailyAndWeekdaysAdvanceFromNowInsteadOfReplayingMissedDays() throws Exception {
        Clock now = at("2026-10-20T12:00"); // Tuesday
        JSONObject daily = Reminders.validate(repeat("diaria", "12:00"), now);
        assertEquals(at("2026-10-21T12:00").millis(), daily.getLong("proximo"));
        JSONObject weekdays = Reminders.validate(repeat("1,3,5", "08:00"), now);
        assertEquals(at("2026-10-21T08:00").millis(), weekdays.getLong("proximo"));
        assertEquals(at("2026-10-26T08:00").millis(), Reminders.next(weekdays, at("2026-10-23T09:00").instant(), Reminders.ZONE));
        for (String repetition : new String[]{"", "0", "8", "1,1", "lunes", "1, 2"})
            assertThrows(IllegalArgumentException.class, () -> Reminders.validate(repeat(repetition, "08:00"), now));
        assertThrows(IllegalArgumentException.class, () -> Reminders.validate(repeat("diaria", "24:00"), now));
    }
    @Test public void quietHoursAndExplicitNightExceptionsArePrecise() throws Exception {
        for (String hour : new String[]{"23:00", "00:00", "06:59"}) {
            assertTrue(Reminders.quiet(ZonedDateTime.now(at("2026-10-20T" + hour))));
            assertTrue(Reminders.explicitNight(repeat("diaria", hour)));
            assertTrue(Reminders.explicitNight(once("2026-10-20T" + hour)));
        }
        for (String hour : new String[]{"07:00", "22:59"}) {
            assertFalse(Reminders.quiet(ZonedDateTime.now(at("2026-10-20T" + hour))));
            assertFalse(Reminders.explicitNight(repeat("diaria", hour)));
        }
    }
    @Test public void scheduleUsesAsuncionRulesIncludingHistoricalDstTransition() throws Exception {
        // On 2024-10-06 Asuncion skipped 00:00; atZone resolves the gap to 01:00 local.
        Clock before = at("2024-10-05T23:00");
        JSONObject d = Reminders.validate(repeat("diaria", "00:30"), before);
        ZonedDateTime next = Instant.ofEpochMilli(d.getLong("proximo")).atZone(Reminders.ZONE);
        assertEquals(LocalDate.of(2024, 10, 6), next.toLocalDate()); assertEquals(1, next.getHour());
        assertEquals(30, next.getMinute());
        JSONObject future = Reminders.validate(once("2026-10-21T09:00"), at("2026-10-20T12:00"));
        assertEquals(Instant.parse("2026-10-21T12:00:00Z").toEpochMilli(), future.getLong("proximo"));
    }
    @Test public void executorLimitsActiveRemindersAndCancelNeedsConfirmation() throws Exception {
        TestStore store = new TestStore(); ToolExecutor executor = new ToolExecutor(store, at("2026-10-20T12:00"));
        for (int i = 0; i < 50; i++) executor.execute("local", "r" + i, "programar_recordatorio", repeat("diaria", "21:00"));
        assertThrows(IllegalArgumentException.class, () -> executor.execute("local", "full", "programar_recordatorio", repeat("diaria", "21:00")));
        String id = store.reminders().get(0).id;
        assertTrue(executor.execute("local", "cancel", "cancelar_recordatorio", Json.object("id", id)).contains("SÍ "));
        assertEquals(50, store.reminders().size()); executor.cancel();
    }
}
