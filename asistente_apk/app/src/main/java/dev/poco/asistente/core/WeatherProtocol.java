package dev.poco.asistente.core;

import java.net.URLEncoder;
import java.time.LocalDate;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Weather from Open-Meteo (free, no key): only the place name leaves the phone. Google Search grounding
 * answers 429 with the free Gemini key, so «clima en Asunción» goes here instead.
 */
public final class WeatherProtocol {
    private WeatherProtocol() {}
    public static final class Place {
        public final String name; public final double latitude, longitude;
        Place(String name, double latitude, double longitude) { this.name = name; this.latitude = latitude; this.longitude = longitude; }
    }
    public static String geocodeUrl(String place) {
        return "https://geocoding-api.open-meteo.com/v1/search?count=1&language=es&format=json&name="
                + encode(place);
    }
    private static String encode(String text) {
        try { return URLEncoder.encode(text, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException e) { throw new IllegalStateException(e); }
    }
    public static Place place(JSONObject geocode) throws Exception {
        JSONArray results = geocode.optJSONArray("results");
        if (results == null || results.length() == 0) throw new IllegalArgumentException("No encontré ese lugar. Decime la ciudad y el país.");
        JSONObject r = results.getJSONObject(0);
        double lat = r.getDouble("latitude"), lon = r.getDouble("longitude");
        if (Math.abs(lat) > 90 || Math.abs(lon) > 180) throw new IllegalArgumentException("El servicio del clima devolvió un lugar inválido.");
        String name = Memory.clip(r.optString("name", "ese lugar"), 60);
        String country = Memory.clip(r.optString("country", ""), 40);
        return new Place(country.isEmpty() ? name : name + ", " + country, lat, lon);
    }
    public static String forecastUrl(Place place, int days) {
        return String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f", place.latitude, place.longitude)
                + "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                + "&timezone=auto&forecast_days=" + days;
    }
    public static String render(Place place, JSONObject forecast, int days) throws Exception {
        StringBuilder out = new StringBuilder();
        JSONObject now = forecast.optJSONObject("current");
        if (now != null) {
            out.append("Ahora en ").append(place.name).append(": ").append(degrees(now.optDouble("temperature_2m")))
                    .append(" (sensación ").append(degrees(now.optDouble("apparent_temperature"))).append("), ")
                    .append(sky(now.optInt("weather_code", -1))).append(", viento ")
                    .append(Math.round(now.optDouble("wind_speed_10m"))).append(" km/h.\n");
        }
        JSONObject daily = forecast.getJSONObject("daily");
        JSONArray dates = daily.getJSONArray("time");
        for (int i = 0; i < Math.min(days, dates.length()); i++) {
            LocalDate date = LocalDate.parse(dates.getString(i));
            String day = i == 0 ? "Hoy" : i == 1 ? "Mañana" : weekday(date) + " " + date.getDayOfMonth();
            out.append(day).append(": ").append(sky(daily.getJSONArray("weather_code").optInt(i, -1)))
                    .append(", máxima ").append(degrees(daily.getJSONArray("temperature_2m_max").optDouble(i)))
                    .append(", mínima ").append(degrees(daily.getJSONArray("temperature_2m_min").optDouble(i)));
            JSONArray rain = daily.optJSONArray("precipitation_probability_max");
            if (rain != null && !rain.isNull(i)) out.append(", lluvia ").append(rain.optInt(i)).append(" %");
            out.append(".\n");
        }
        if (out.length() == 0) throw new IllegalArgumentException("El servicio del clima no devolvió datos.");
        return out.toString().trim();
    }
    private static String degrees(double value) { return Double.isNaN(value) ? "?" : Math.round(value) + " °C"; }
    private static String weekday(LocalDate date) {
        String[] names = {"lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo"};
        String name = names[date.getDayOfWeek().getValue() - 1];
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
    /** WMO weather interpretation codes, as Open-Meteo documents them. */
    static String sky(int code) {
        if (code == 0) return "despejado";
        if (code == 1 || code == 2) return "parcialmente nublado";
        if (code == 3) return "nublado";
        if (code == 45 || code == 48) return "con niebla";
        if (code >= 51 && code <= 57) return "con llovizna";
        if (code >= 61 && code <= 67 || code >= 80 && code <= 82) return "con lluvia";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "con nieve";
        if (code >= 95) return "con tormenta";
        return "sin dato del cielo";
    }
}
