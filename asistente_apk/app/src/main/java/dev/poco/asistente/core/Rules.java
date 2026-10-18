package dev.poco.asistente.core;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** Full matches only: a partly understood message must never silently write a record. */
public final class Rules {
    public static final class Result {
        public final String tool, question;
        public final JSONObject args;
        private Result(String tool, JSONObject args, String question) { this.tool = tool; this.args = args; this.question = question; }
        static Result call(String tool, JSONObject args) { return new Result(tool, args, null); }
        static Result ask(String question) { return new Result(null, null, question); }
    }
    private Rules() {}
    public static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }
    public static long cents(String text) {
        // Rioplatense separators: 3.500,25 or 3500,25. A dot is never guessed as decimal.
        if (!text.matches("(?:[0-9]+|[1-9][0-9]{0,2}(?:\\.[0-9]{3})+)(?:,[0-9]{1,2})?"))
            throw new IllegalArgumentException("¿Qué monto fue? Usá 3500,50 o 3.500,50, sin redondear.");
        try { return new BigDecimal(text.replace(".", "").replace(',', '.')).movePointRight(2).longValueExact(); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("El monto es demasiado grande."); }
    }
    public static Result parse(String raw, LocalDate today, String currency) throws Exception {
        String text = normalize(raw);
        Matcher m = Pattern.compile("(gaste|pague|cobre|ingrese) (\\S+)(?: (?:en|de) ([a-z][a-z /-]{0,49}))?").matcher(text);
        if (m.matches()) {
            // Avoid treating additional clauses, dates or units as part of the category.
            String category = m.group(3);
            if (category != null && Pattern.compile("\\b(y|mas|menos|pero|ayer|anteayer|hoy|manana|el|pesos|dolares|ars|usd)\\b").matcher(category).find()) return null;
            long amount;
            try { amount = cents(m.group(2)); } catch (IllegalArgumentException e) { return Result.ask(e.getMessage()); }
            if (category == null) return Result.ask("¿En qué categoría? Repetí el pedido completo, por ejemplo: gasté 3500 en super o cobré 450000 de sueldo.");
            return Result.call("registrar_movimiento", Json.object("tipo", text.startsWith("cobre ") || text.startsWith("ingrese ") ? "ingreso" : "gasto",
                    "centavos", amount, "moneda", currency, "categoria", category, "fecha", today.toString()));
        }
        m = Pattern.compile("peso ([0-9]+(?:[,.][0-9]{1,3})?)(?: kg)?").matcher(text);
        if (m.matches()) return Result.call("registrar_peso", Json.object("kg", Double.parseDouble(m.group(1).replace(',', '.')), "fecha", today.toString()));
        m = Pattern.compile("hice ([a-z][a-z /-]{0,79}) ([0-9]{1,3})x([0-9]{1,4}) con ([0-9]+(?:[,.][0-9]{1,3})?)(?: kg)?").matcher(text);
        if (m.matches()) return Result.call("registrar_entreno", Json.object("ejercicio", m.group(1), "series", Long.parseLong(m.group(2)),
                "repeticiones", Long.parseLong(m.group(3)), "kg", Double.parseDouble(m.group(4).replace(',', '.')), "fecha", today.toString()));
        if (text.equals("resumen") || text.equals("saldo") || text.equals("como voy") || text.equals("¿como voy?"))
            return Result.call("consultar_resumen", new JSONObject());
        // Exact local commands also make CRUD possible without a Gemini key.
        m = Pattern.compile("borrar ([a-f0-9-]{36})").matcher(text);
        if (m.matches()) return Result.call("borrar_registro", Json.object("id", m.group(1)));
        m = Pattern.compile("corregir ([a-f0-9-]{36}) (\\{.*})", Pattern.DOTALL).matcher(raw.trim());
        if (m.matches()) {
            try { return Result.call("corregir_registro", Json.object("id", m.group(1), "cambios", new JSONObject(m.group(2)))); }
            catch (Exception e) { return Result.ask("Revisá el JSON: corregir ID {\"centavos\":350000}."); }
        }
        m = Pattern.compile("presupuesto ([0-9.,]+) en ([a-z][a-z0-9 /-]{0,49}) (\\d{4}-\\d{2})").matcher(text);
        if (m.matches()) return Result.call("fijar_presupuesto", Json.object("centavos", cents(m.group(1)), "categoria", m.group(2), "mes", m.group(3), "moneda", currency));
        m = Pattern.compile("meta ([0-9.,]+) para ([a-z][a-z /-]{0,79})").matcher(text);
        if (m.matches()) return Result.call("fijar_meta", Json.object("centavos", cents(m.group(1)), "nombre", m.group(2), "moneda", currency));
        if (text.startsWith("comi ") && Pattern.compile("\\b(ayer|anteayer|hoy|manana|lunes|martes|miercoles|jueves|viernes|sabado|domingo|gaste|cobre|peso|hice|borrar|corregir)\\b|[0-9]+[-/][0-9]+|\\bel [0-9]|\\ba las\\b").matcher(text.substring(5)).find()) return null;
        if (text.startsWith("comi ") && raw.trim().length() > 5)
            return Result.call("registrar_comida", Json.object("descripcion", raw.trim().substring(5).trim(), "fecha", today.toString()));
        return null;
    }
}
