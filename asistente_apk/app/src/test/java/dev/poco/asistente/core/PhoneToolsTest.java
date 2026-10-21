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
        assertThrows(IllegalArgumentException.class, () -> PhoneTools.validate("agendar", new JSONObject()));
    }
}
