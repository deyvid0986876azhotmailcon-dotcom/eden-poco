package dev.poco.asistente.core;

import java.time.*;
import java.util.*;
import org.json.JSONObject;

public final class Reminders {
    public static final ZoneId ZONE = ZoneId.of("America/Asuncion");
    private Reminders() {}
    public static LocalTime time(String text) {
        if (text == null || !text.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")) throw new IllegalArgumentException("Hora inválida: usá HH:mm.");
        return LocalTime.parse(text);
    }
    /** «hoy a las 20:19», «mañana a las 8:00», «el 25/10 a las 9:30», «todos los días a las 7:00», «los lunes y jueves a las 18:00». */
    public static String when(org.json.JSONObject d, java.time.LocalDate today) {
        if (d.has("cuando")) {
            LocalDateTime at = LocalDateTime.parse(d.optString("cuando"));
            String hour = at.toLocalTime().toString();
            if (at.toLocalDate().equals(today)) return "hoy a las " + hour;
            if (at.toLocalDate().equals(today.plusDays(1))) return "mañana a las " + hour;
            return "el " + at.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM")) + " a las " + hour;
        }
        String hour = " a las " + d.optString("hora");
        if ("diaria".equals(d.optString("repeticion"))) return "todos los días" + hour;
        String[] names = {"", "lunes", "martes", "miércoles", "jueves", "viernes", "sábados", "domingos"};
        java.util.List<String> list = new java.util.ArrayList<>();
        for (int day : days(d.optString("repeticion"))) list.add(names[day]);
        String joined = list.size() == 1 ? list.get(0)
                : String.join(", ", list.subList(0, list.size() - 1)) + " y " + list.get(list.size() - 1);
        return "los " + joined + hour;
    }
    public static Set<Integer> days(String repetition) {
        Set<Integer> result = new TreeSet<>();
        if ("diaria".equals(repetition)) { for (int i = 1; i <= 7; i++) result.add(i); return result; }
        if (repetition == null || !repetition.matches("[1-7](?:,[1-7]){0,6}")) throw new IllegalArgumentException("Repetición: diaria o días 1=lunes a 7=domingo separados por comas.");
        for (String day : repetition.split(",")) if (!result.add(Integer.parseInt(day))) throw new IllegalArgumentException("Días repetidos.");
        return result;
    }
    public static JSONObject validate(JSONObject args, Clock clock) throws Exception {
        Validation.keys(args, "texto,cuando,repeticion,hora");
        JSONObject d = Json.object("texto", Validation.string(args, "texto", 500));
        if (args.has("cuando")) {
            if (args.has("repeticion") || args.has("hora")) throw new IllegalArgumentException("Elegí fecha única o repetición.");
            String when = Validation.string(args, "cuando", 16);
            if (!when.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}")) throw new IllegalArgumentException("Usá AAAA-MM-DDTHH:mm local.");
            try { Validation.date(when.substring(0, 10)); LocalDateTime.parse(when); }
            catch (RuntimeException e) { throw new IllegalArgumentException("Fecha y hora inválidas."); }
            d.put("cuando", when);
        } else {
            String repetition = Validation.string(args, "repeticion", 20);
            days(repetition); d.put("repeticion", repetition);
            d.put("hora", time(Validation.string(args, "hora", 5)).toString());
        }
        long next = next(d, clock.instant(), clock.getZone());
        if (next < 0) throw new IllegalArgumentException("El recordatorio necesita una fecha futura.");
        d.put("proximo", next); d.put("activo", true); d.put("ultimo_envio", 0L);
        return d;
    }
    /** Local schedules resolve against zone rules at each occurrence, not a fixed UTC offset. */
    public static long next(JSONObject d, Instant after, ZoneId zone) throws Exception {
        if (d.has("cuando")) {
            Instant instant = LocalDateTime.parse(d.getString("cuando")).atZone(zone).toInstant();
            return instant.isAfter(after) ? instant.toEpochMilli() : -1;
        }
        Set<Integer> days = days(d.getString("repeticion"));
        LocalTime hour = time(d.getString("hora"));
        LocalDate date = after.atZone(zone).toLocalDate();
        for (int i = 0; i <= 7; i++) {
            LocalDate candidate = date.plusDays(i);
            Instant instant = candidate.atTime(hour).atZone(zone).toInstant();
            if (days.contains(candidate.getDayOfWeek().getValue()) && instant.isAfter(after)) return instant.toEpochMilli();
        }
        throw new IllegalArgumentException("No se pudo calcular la próxima fecha.");
    }
    public static boolean quiet(ZonedDateTime now) { return now.getHour() >= 23 || now.getHour() < 7; }
    public static boolean explicitNight(JSONObject d) throws Exception {
        LocalTime hour = d.has("cuando") ? LocalDateTime.parse(d.getString("cuando")).toLocalTime() : time(d.getString("hora"));
        return hour.getHour() >= 23 || hour.getHour() < 7;
    }
    public static String list(List<Store.Row> rows) {
        StringBuilder out = new StringBuilder("Recordatorios activos:\n");
        for (Store.Row row : rows) out.append(row.id).append(" · ").append(row.data.optString("texto")).append(" · ")
                .append(row.data.has("cuando") ? row.data.optString("cuando") : row.data.optString("repeticion") + " " + row.data.optString("hora")).append('\n');
        return out.toString();
    }
}
