package dev.poco.asistente.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.json.JSONObject;

/** What David reads after a record is saved, changed or deleted: one plain sentence, no JSON. */
public final class Receipt {
    private Receipt() {}

    /** ₲ 25.000, $ 3.500, US$ 10,50: the guaraní has no cents in use, the others show them only when there are. */
    public static String money(long cents, String currency) {
        String symbol;
        switch (currency) {
            case "PYG": symbol = "₲"; break;
            case "ARS": symbol = "$"; break;
            case "USD": symbol = "US$"; break;
            case "EUR": symbol = "€"; break;
            case "BRL": symbol = "R$"; break;
            default: symbol = currency;
        }
        BigDecimal value = BigDecimal.valueOf(Math.abs(cents), 2);
        boolean decimals = !currency.equals("PYG") && cents % 100 != 0;
        value = value.setScale(decimals ? 2 : 0, RoundingMode.HALF_UP);
        String plain = value.toPlainString();
        String whole = decimals ? plain.substring(0, plain.indexOf('.')) : plain;
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < whole.length(); i++) {
            if (i > 0 && (whole.length() - i) % 3 == 0) grouped.append('.');
            grouped.append(whole.charAt(i));
        }
        if (decimals) grouped.append(',').append(plain.substring(plain.indexOf('.') + 1));
        return (cents < 0 ? "-" : "") + symbol + " " + grouped;
    }

    static String day(String date, LocalDate today) {
        if (date == null || date.isEmpty()) return "";
        LocalDate d = LocalDate.parse(date);
        if (d.equals(today)) return "hoy";
        if (d.equals(today.minusDays(1))) return "ayer";
        return d.format(DateTimeFormatter.ofPattern(d.getYear() == today.getYear() ? "dd/MM" : "dd/MM/yyyy"));
    }

    private static String number(JSONObject d, String key) {
        return BigDecimal.valueOf(d.optDouble(key)).stripTrailingZeros().toPlainString().replace('.', ',');
    }

    /** The record in words, without the verb: "un gasto de ₲ 25.000 en almuerzo (hoy)". */
    public static String describe(String kind, JSONObject d, LocalDate today) {
        if (kind.equals("memoria")) return "la entrada de la libreta";
        if (kind.equals("recordatorio")) return "el recordatorio";
        String when = day(d.optString("fecha", ""), today);
        String at = when.isEmpty() ? "" : " (" + when + ")";
        switch (kind) {
            case "movimiento":
                return (d.optString("tipo").equals("ingreso") ? "un ingreso de " : "un gasto de ")
                        + money(d.optLong("centavos"), d.optString("moneda")) + " en " + d.optString("categoria") + at;
            case "peso": return "tu peso: " + number(d, "kg") + " kg" + at;
            case "comida": {
                String text = "la comida «" + d.optString("descripcion") + "»" + at;
                if (d.has("calorias")) text += ", unas " + number(d, "calorias") + " kcal";
                if (d.has("proteina")) text += ", " + number(d, "proteina") + " g de proteína";
                return text;
            }
            case "entreno": {
                String text = d.optString("ejercicio");
                if (d.has("series") && d.has("repeticiones")) text += " " + d.optLong("series") + "×" + d.optLong("repeticiones");
                if (d.has("kg")) text += " con " + number(d, "kg") + " kg";
                return "el entreno: " + text + at;
            }
            case "presupuesto":
                return "un presupuesto de " + money(d.optLong("centavos"), d.optString("moneda")) + " para "
                        + d.optString("categoria") + " en " + d.optString("mes");
            case "meta": {
                String text = "la meta «" + d.optString("nombre") + "»: " + money(d.optLong("centavos"), d.optString("moneda"));
                return d.has("fecha") ? text + " para el " + day(d.optString("fecha"), today) : text;
            }
            default: return kind;
        }
    }

    /**
     * A short friendly closing in Paraguayan Spanish after a saved record, picked by the record's id so the
     * same receipt always reads the same. Never carries numbers: those are in the sentence before.
     */
    public static String closer(String kind, JSONObject d, String id) {
        String[] options;
        switch (kind) {
            case "movimiento":
                options = d.optString("tipo").equals("ingreso")
                        ? new String[]{"¡Bien ahí! A cuidar esa plata.", "Re bien, che.", "Dale, que siga entrando."}
                        : new String[]{"Listo, che.", "Ya quedó.", "Anotado, cuidá la billetera luego."};
                break;
            case "peso": options = new String[]{"Seguimos sumando.", "Re bien, a seguir comiendo.", "Paso a paso, che."}; break;
            case "entreno": options = new String[]{"¡Eso! Re buen entreno.", "Bien ahí, posta.", "Dale que se nota."}; break;
            case "comida": options = new String[]{"Buen provecho, che.", "Re bien, a comer rico.", "Eso suma."}; break;
            default: options = new String[]{"Listo, lo vamos siguiendo de cerca.", "Ya quedó anotado."};
        }
        return options[Math.floorMod(id.hashCode(), options.length)];
    }

    /** Keep the complete ID: local correction/deletion commands require a full UUID. */
    public static String ref(String id) { return id; }
}
