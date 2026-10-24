package dev.poco.asistente.core;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PhoneToolsTest {
    @Test public void alarmHoursAreStrictAndDoNotAcceptInjectedExtras() throws Exception {
        for (String hour : new String[]{"00:00", "23:59", "07:30"}) assertEquals(hour, PhoneTools.validate("alarma", Json.object("hora", hour)).getString("hora"));
        for (String hour : new String[]{"24:00", "23:60", "-1:00", "7:30", "mañana", "12:34:56"})
            assertThrows(hour, IllegalArgumentException.class, () -> PhoneTools.validate("alarma", Json.object("hora", hour)));
        assertThrows(IllegalArgumentException.class, () -> PhoneTools.validate("alarma", Json.object("hora", "07:30", "paquete", "bank.app")));
        assertThrows(IllegalArgumentException.class, () -> PhoneTools.validate("alarma", Json.object("hora", 730)));
    }
    @Test public void timerIsBoundedIntegerAndHasExactlyOneUnit() throws Exception {
        assertEquals(120, PhoneTools.validate("temporizador", Json.object("minutos", 2)).getInt("segundos"));
        assertEquals(86400, PhoneTools.validate("temporizador", Json.object("minutos", 1440)).getInt("segundos"));
        for (Object bad : new Object[]{0, -1, 86401, 1.5, "60", Long.MAX_VALUE, JSONObject.NULL})
            assertThrows(IllegalArgumentException.class, () -> PhoneTools.validate("temporizador", Json.object("segundos", bad)));
        assertThrows(IllegalArgumentException.class, () -> PhoneTools.validate("temporizador", Json.object("minutos", 1, "segundos", 1)));
        assertThrows(IllegalArgumentException.class, () -> PhoneTools.validate("temporizador", new JSONObject()));
    }
    @Test public void calendarAndMusicCannotBecomeWritesOrArbitraryIntents() throws Exception {
        PhoneTools.validate("consultar_agenda", Json.object("desde", "2026-10-21", "hasta", "2026-10-21"));
        assertThrows(IllegalArgumentException.class, () -> PhoneTools.validate("consultar_agenda", Json.object("desde", "2026-10-22", "hasta", "2026-10-21")));
        assertThrows(IllegalArgumentException.class, () -> PhoneTools.validate("consultar_agenda", Json.object("desde", "2026-10-01", "hasta", "2026-12-01")));
        assertThrows(IllegalArgumentException.class, () -> PhoneTools.validate("musica", Json.object("accion", "buscar", "app", "intent://evil", "consulta", "song")));
        assertThrows(IllegalArgumentException.class, () -> PhoneTools.validate("musica", Json.object("accion", "pausar", "app", "spotify")));
        // «poné kilómetros de los kaligaris en yt music» (2026-10-23): a song with reproducir is a search, YT Music by default
        org.json.JSONObject song = PhoneTools.validate("musica", Json.object("accion", "reproducir", "app", "yt music", "consulta", "Kilómetros Los Caligaris"));
        assertEquals("buscar", song.getString("accion")); assertEquals("youtube music", song.getString("app"));
        assertEquals("youtube music", PhoneTools.validate("musica", Json.object("accion", "buscar", "consulta", "Kilómetros")).getString("app"));
        assertThrows(IllegalArgumentException.class, () -> PhoneTools.validate("agendar", new JSONObject()));
    }
    @Test public void calendarLimitsCountBothDatesAndSeparateAllDayFromLocalInstants() throws Exception {
        PhoneTools.validate("consultar_agenda", Json.object("desde", "2026-10-01", "hasta", "2026-10-31"));
        assertThrows(IllegalArgumentException.class, () -> PhoneTools.validate("consultar_agenda", Json.object("desde", "2026-10-01", "hasta", "2026-11-01")));
        PhoneTools.CalendarWindow day = new PhoneTools.CalendarWindow("2026-10-21", "2026-10-21");
        long today = java.time.Instant.parse("2026-10-21T00:00:00Z").toEpochMilli();
        long tomorrow = today + 86400000L;
        assertTrue(day.includes(today, tomorrow, true));
        assertFalse(day.includes(tomorrow, tomorrow + 86400000L, true));
        assertFalse(day.includes(today - 86400000L, today, true));
        assertTrue(day.includes(today - 86400000L, tomorrow, true));
        // Midnight UTC on the 22nd is still the evening of the 21st in Asunción.
        assertTrue(day.includes(tomorrow, tomorrow + 3600000L, false));
        long localEnd = java.time.LocalDate.parse("2026-10-22").atStartOfDay(Reminders.ZONE).toInstant().toEpochMilli();
        assertFalse(day.includes(localEnd, localEnd + 3600000L, false));
        assertTrue(day.includes(tomorrow, tomorrow, false));
    }
}
