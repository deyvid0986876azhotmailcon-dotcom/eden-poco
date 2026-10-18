package dev.poco.asistente.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** The executor is authoritative; model schemas are only hints. No coercion of JSON types. */
public final class Validation {
    public static final long MONEY_LIMIT = 100_000_000_000L; // exclusive, cents (1 billion units)
    public static final String[] CURRENCIES = {"ARS", "PYG", "USD", "EUR", "BRL"};
    public static final String[] KINDS = {"movimiento", "peso", "comida", "entreno", "presupuesto", "meta"};
    private Validation() {}

    public static void keys(JSONObject data, String allowed) {
        Set<String> fields = new HashSet<>(Arrays.asList(allowed.split(",")));
        Iterator<String> it = data.keys();
        while (it.hasNext()) if (!fields.contains(it.next())) fail("Hay campos desconocidos. Reformulá el pedido.");
    }
    public static String string(JSONObject data, String key, int max) throws Exception {
        Object value = data.opt(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) fail("Falta " + key + ". ¿Cuál es?");
        String result = ((String) value).trim();
        if (result.length() > max || result.chars().anyMatch(c -> c < 32)) fail("Revisá " + key + ": texto demasiado largo o inválido.");
        return result;
    }
    public static BigDecimal number(JSONObject data, String key, BigDecimal min, BigDecimal max, boolean integer) {
        Object value = data.opt(key);
        if (!(value instanceof Number)) fail("Falta un número válido para " + key + ".");
        BigDecimal n;
        try { n = new BigDecimal(value.toString()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Número inválido: " + key + "."); }
        if (n.compareTo(min) < 0 || n.compareTo(max) > 0 || (integer && n.stripTrailingZeros().scale() > 0))
            fail("Revisá " + key + ": fuera de rango o con decimales no permitidos.");
        return n;
    }
    public static String currency(String value) {
        if (!Arrays.asList(CURRENCIES).contains(value)) fail("Moneda admitida: ARS, PYG, USD, EUR o BRL.");
        return value;
    }
    public static String date(String value) {
        try {
            if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException();
            LocalDate parsed = LocalDate.parse(value);
            if (parsed.getYear() < 2000 || parsed.getYear() > 2100) throw new IllegalArgumentException();
        } catch (RuntimeException e) { fail("Fecha inválida: usá AAAA-MM-DD (2000 a 2100)."); }
        return value;
    }
    public static String month(String value) {
        try {
            if (!value.matches("\\d{4}-\\d{2}")) throw new IllegalArgumentException();
            YearMonth parsed = YearMonth.parse(value);
            if (parsed.getYear() < 2000 || parsed.getYear() > 2100) throw new IllegalArgumentException();
        } catch (RuntimeException e) { fail("Mes inválido: usá AAAA-MM."); }
        return value;
    }
    public static String id(JSONObject args) throws Exception {
        String id = string(args, "id", 36);
        try { if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException e) { fail("Necesito el ID completo del registro (copialo del resumen)."); }
        return id;
    }
    public static JSONObject record(String kind, JSONObject input, LocalDate today) throws Exception {
        JSONObject d = Json.copy(input);
        switch (kind) {
            case "movimiento":
                keys(d, "tipo,centavos,moneda,categoria,fecha,nota");
                String type = string(d, "tipo", 7);
                if (!type.equals("ingreso") && !type.equals("gasto")) fail("¿Fue ingreso o gasto?");
                money(d); category(d); dated(d, today);
                if (d.has("nota")) d.put("nota", string(d, "nota", 500));
                break;
            case "peso":
                keys(d, "fecha,kg"); dated(d, today); numeric(d, "kg", 30, 250, false); break;
            case "comida":
                keys(d, "fecha,descripcion,calorias,proteina"); dated(d, today);
                d.put("descripcion", string(d, "descripcion", 500));
                if (d.has("calorias")) numeric(d, "calorias", 0, 20000, false);
                if (d.has("proteina")) numeric(d, "proteina", 0, 1000, false);
                break;
            case "entreno":
                keys(d, "fecha,ejercicio,series,repeticiones,kg"); dated(d, today);
                d.put("ejercicio", string(d, "ejercicio", 80));
                numeric(d, "series", 1, 100, true); numeric(d, "repeticiones", 1, 1000, true);
                numeric(d, "kg", 0, 1000, false); break;
            case "presupuesto":
                keys(d, "mes,categoria,centavos,moneda"); money(d); category(d);
                d.put("mes", month(string(d, "mes", 7))); break;
            case "meta":
                keys(d, "nombre,centavos,moneda,fecha"); money(d);
                d.put("nombre", string(d, "nombre", 80));
                if (d.has("fecha")) d.put("fecha", date(string(d, "fecha", 10)));
                break;
            default: fail("Tipo de registro desconocido.");
        }
        return d;
    }
    private static void dated(JSONObject d, LocalDate today) throws Exception {
        String value = date(string(d, "fecha", 10));
        if (LocalDate.parse(value).isAfter(today)) fail("La fecha está en el futuro. ¿Qué fecha fue?");
        d.put("fecha", value);
    }
    private static void money(JSONObject d) throws Exception {
        d.put("centavos", number(d, "centavos", BigDecimal.ONE, BigDecimal.valueOf(MONEY_LIMIT - 1), true).longValueExact());
        d.put("moneda", currency(string(d, "moneda", 3)));
    }
    private static void category(JSONObject d) throws Exception {
        String category = Rules.normalize(string(d, "categoria", 50));
        if (!category.matches("[a-z][a-z0-9 /-]{0,49}")) fail("Revisá la categoría (solo letras, números, espacios, / y -).");
        d.put("categoria", category);
    }
    private static void numeric(JSONObject d, String k, int min, int max, boolean integer) throws Exception {
        BigDecimal n = number(d, k, BigDecimal.valueOf(min), BigDecimal.valueOf(max), integer);
        if (integer) d.put(k, n.longValueExact()); else d.put(k, n.doubleValue());
    }
    private static void fail(String message) { throw new IllegalArgumentException(message); }

    /** Used to build Gemini's declarations, with exactly the same field names as validation. */
    public static JSONObject schema(String kind) throws Exception {
        JSONObject properties = new JSONObject();
        JSONArray required = new JSONArray();
        switch (kind) {
            case "movimiento":
                field(properties, required, "tipo", "STRING", true, "ingreso o gasto", "ingreso", "gasto");
                moneySchema(properties, required); categorySchema(properties, required); dateSchema(properties, required, true);
                field(properties, required, "nota", "STRING", false, "Nota opcional"); break;
            case "peso":
                dateSchema(properties, required, true); field(properties, required, "kg", "NUMBER", true, "Peso corporal entre 30 y 250 kg"); break;
            case "comida":
                dateSchema(properties, required, true);
                field(properties, required, "descripcion", "STRING", true, "Comida indicada por David");
                field(properties, required, "calorias", "NUMBER", false, "Estimación opcional, solo si David pidió estimarla, 0 a 20000");
                field(properties, required, "proteina", "NUMBER", false, "Gramos estimados opcionales, 0 a 1000"); break;
            case "entreno":
                dateSchema(properties, required, true);
                field(properties, required, "ejercicio", "STRING", true, "Ejercicio indicado");
                field(properties, required, "series", "INTEGER", true, "1 a 100");
                field(properties, required, "repeticiones", "INTEGER", true, "1 a 1000");
                field(properties, required, "kg", "NUMBER", true, "Carga de 0 a 1000 kg; preguntar si falta"); break;
            case "presupuesto":
                moneySchema(properties, required); categorySchema(properties, required);
                field(properties, required, "mes", "STRING", true, "Mes AAAA-MM"); break;
            case "meta":
                moneySchema(properties, required); field(properties, required, "nombre", "STRING", true, "Nombre de la meta de ahorro");
                dateSchema(properties, required, false); break;
            default: throw new IllegalArgumentException("Esquema desconocido");
        }
        return Json.object("type", "OBJECT", "properties", properties, "required", required);
    }
    private static void moneySchema(JSONObject p, JSONArray r) throws Exception {
        field(p, r, "centavos", "INTEGER", true, "Importe positivo en centavos, menor a " + MONEY_LIMIT + "; sin redondear");
        field(p, r, "moneda", "STRING", true, "Moneda explícita o predeterminada", CURRENCIES);
    }
    private static void categorySchema(JSONObject p, JSONArray r) throws Exception {
        field(p, r, "categoria", "STRING", true, "Categoría indicada por el usuario; preguntar si es ambigua");
    }
    private static void dateSchema(JSONObject p, JSONArray r, boolean mandatory) throws Exception {
        field(p, r, "fecha", "STRING", mandatory, "Fecha AAAA-MM-DD; hoy si no se especificó fecha");
    }
    public static void field(JSONObject p, JSONArray r, String name, String type, boolean mandatory, String description, String... choices) throws Exception {
        JSONObject value = Json.object("type", type, "description", description);
        if (choices.length > 0) value.put("enum", Json.array(choices));
        p.put(name, value);
        if (mandatory) r.put(name);
    }
}
