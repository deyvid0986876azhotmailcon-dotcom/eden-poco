package dev.poco.asistente.core;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class WeatherProtocolTest {
    private static final String GEOCODE = "{\"results\":[{\"name\":\"Asunción\",\"latitude\":-25.28646,\"longitude\":-57.647,\"country\":\"Paraguay\"}]}";
    private static final String FORECAST = "{\"current\":{\"temperature_2m\":31.4,\"apparent_temperature\":33.2,\"weather_code\":2,\"wind_speed_10m\":11.6},"
            + "\"daily\":{\"time\":[\"2026-10-21\",\"2026-10-22\",\"2026-10-23\"],\"weather_code\":[2,61,95],"
            + "\"temperature_2m_max\":[34.1,29.0,27.5],\"temperature_2m_min\":[22.2,21.0,19.4],\"precipitation_probability_max\":[10,80,null]}}";

    @Test public void onlyThePlaceNameGoesOutAndTheForecastReadsNaturally() throws Exception {
        assertEquals("https://geocoding-api.open-meteo.com/v1/search?count=1&language=es&format=json&name=Asunci%C3%B3n+Paraguay",
                WeatherProtocol.geocodeUrl("Asunción Paraguay"));
        WeatherProtocol.Place place = WeatherProtocol.place(new JSONObject(GEOCODE));
        assertEquals("Asunción, Paraguay", place.name);
        assertTrue(WeatherProtocol.forecastUrl(place, 3).startsWith("https://api.open-meteo.com/v1/forecast?latitude=-25.2865&longitude=-57.6470&"));
        assertTrue(WeatherProtocol.forecastUrl(place, 3).endsWith("&forecast_days=3"));
        String text = WeatherProtocol.render(place, new JSONObject(FORECAST), 3);
        assertTrue(text, text.startsWith("Ahora en Asunción, Paraguay: 31 °C (sensación 33 °C), parcialmente nublado, viento 12 km/h."));
        assertTrue(text, text.contains("Hoy: parcialmente nublado, máxima 34 °C, mínima 22 °C, lluvia 10 %."));
        assertTrue(text, text.contains("Mañana: con lluvia, máxima 29 °C, mínima 21 °C, lluvia 80 %."));
        assertTrue(text, text.contains("Viernes 23: con tormenta, máxima 28 °C, mínima 19 °C."));
        assertEquals(2, WeatherProtocol.render(place, new JSONObject(FORECAST), 1).split("\n").length);
    }
    @Test public void unknownPlacesAndBadAnswersFailClearly() {
        assertThrows(IllegalArgumentException.class, () -> WeatherProtocol.place(new JSONObject("{}")));
        assertThrows(IllegalArgumentException.class, () -> WeatherProtocol.place(new JSONObject("{\"results\":[{\"name\":\"x\",\"latitude\":120,\"longitude\":0}]}")));
    }
    @Test public void weatherArgumentsAreBounded() throws Exception {
        JSONObject defaults = PhoneTools.validate("clima", new JSONObject());
        assertEquals("Asunción", defaults.getString("lugar"));
        assertEquals(1, defaults.getInt("dias"));
        assertEquals(7, PhoneTools.validate("clima", new JSONObject().put("lugar", "Encarnación").put("dias", 7)).getInt("dias"));
        assertThrows(IllegalArgumentException.class, () -> PhoneTools.validate("clima", new JSONObject().put("dias", 8)));
        assertThrows(IllegalArgumentException.class, () -> PhoneTools.validate("clima", new JSONObject().put("ciudad", "Luque")));
        assertTrue(PhoneTools.external("clima"));
    }
}
