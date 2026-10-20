package dev.poco.asistente.core;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Facts are untrusted data even after these deliberately conservative checks. */
public final class Memory {
    public static final String[] CATEGORIES = {"gustos", "salud", "finanzas", "rutina", "estilo", "otro"};
    private Memory() {}
    public static JSONObject validate(JSONObject args) throws Exception {
        Validation.keys(args, "texto,categoria");
        String text = Validation.string(args, "texto", 200);
        String category = Validation.string(args, "categoria", 20);
        if (!Arrays.asList(CATEGORIES).contains(category)) throw new IllegalArgumentException("Categoría de memoria inválida.");
        String n = Normalizer.normalize(text, Normalizer.Form.NFKD).replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT);
        if (text.matches("(?s).*[\\p{Cf}\\p{Cc}].*")
                || n.matches("(?s).*\\p{Nd}{6,}.*")
                || n.matches("(?s).*(?:\\p{Nd}[ -]?){6,}.*")
                || n.matches("(?s).*\\b(?:contrasena|password|clave|token|pin|codigo)\\b\\s*(?:es\\s+|:|=|de\\s+|para\\s+)?\\S+.*"))
            throw new IllegalArgumentException("No guardo secretos, tarjetas ni códigos en la libreta.");
        if (n.matches("(?s)^[\\s\\p{Punct}]*(?:sos|eres|actua|debes|tenes que|tienes que|responde|ejecuta|haz|hace|usa|utiliza|no pidas|no preguntes|recuerda|remember|always|never|from now on|system|assistant|developer|asistente|sistema)\\b.*")
                || n.matches("(?s).*(?:\\bignora\\w*|\\bdesobedece\\w*|\\bobedece\\w*|\\bolvida\\s+(?:las|tus|todas)|\\ba partir de ahora|\\bsystem\\s*:|\\b(?:omite|salta|desactiva|anula|revela)\\w*\\s+(?:las?|el|tus?)\\s+(?:reglas|instrucciones|seguridad|confirmacion|secretos)|\\b(?:instrucciones|prompt) del sistema|\\bignore previous|\\bdisregard|\\byou are now).*"))
            throw new IllegalArgumentException("La libreta guarda datos, no instrucciones para la asistente.");
        return Json.object("texto", text, "categoria", category);
    }
    /** Category for a note David dictates with «acordate que…»; text already normalized (no accents, lower case). */
    public static String guessCategory(String n) {
        if (n.matches(".*\\b(alergi\\w*|salud|medic\\w*|enferm\\w*|dolor\\w*|lesion\\w*)\\b.*")) return "salud";
        if (n.matches(".*\\b(gusta\\w*|como|comer|comida|odio|prefiero|favorit\\w*)\\b.*")) return "gustos";
        if (n.matches(".*\\b(cobro|cobra\\w*|sueldo|plata|ahorr\\w*|deuda\\w*|pago|pagar|presupuesto)\\b.*")) return "finanzas";
        if (n.matches(".*\\b(gimnasio|gym|entren\\w*|horario|despierto|duermo|trabajo|lunes|martes|miercoles|jueves|viernes|sabado|domingo)\\b.*")) return "rutina";
        if (n.matches(".*\\b(hablame|decime|tratame|respuestas|corto|largo)\\b.*")) return "estilo";
        return "otro";
    }
    /** What David reads for «qué sabés de mí»: grouped by topic, in plain words, without IDs. */
    public static String list(List<Store.Row> rows) {
        if (rows.isEmpty()) return "Tu libreta está vacía todavía, che. Contame cosas como «acordate que no me gusta el mondongo» y me las guardo.";
        String[][] topics = {{"gustos", "Lo que te gusta y lo que no"}, {"salud", "Salud"}, {"finanzas", "Plata"},
                {"rutina", "Tu rutina"}, {"estilo", "Cómo te gusta que te hable"}, {"otro", "Otras cosas"}};
        StringBuilder out = new StringBuilder("Esto es lo que tengo anotado de vos:\n");
        for (String[] topic : topics) {
            StringBuilder items = new StringBuilder();
            for (Store.Row row : rows) if (topic[0].equals(row.data.optString("categoria", "otro")))
                items.append("• ").append(row.data.optString("texto")).append('\n');
            if (items.length() > 0) out.append('\n').append(topic[1]).append('\n').append(items);
        }
        return out.append("\nSi querés que me olvide de algo, decime por ejemplo «olvidá lo del mondongo».").toString();
    }

    /** The notes whose text contains what David named in «olvidá lo del …» (both normalized). */
    public static List<Store.Row> find(List<Store.Row> rows, String phrase) {
        List<Store.Row> found = new java.util.ArrayList<>();
        String needle = Rules.normalize(phrase).replaceAll("[¿?¡!.,]", "").trim();
        if (needle.isEmpty()) return found;
        for (Store.Row row : rows) if (Rules.normalize(row.data.optString("texto")).replaceAll("[¿?¡!.,]", "").contains(needle)) found.add(row);
        return found;
    }
    public static JSONArray quoted(List<Store.Row> rows) throws Exception {
        JSONArray data = new JSONArray();
        for (Store.Row row : rows) data.put(Json.object("id", row.id, "categoria", row.data.getString("categoria"), "texto", row.data.getString("texto")));
        return data;
    }
    public static final class Exchange {
        public final String user, model;
        public Exchange(String user, String model) { this.user = user; this.model = model; }
    }
    /** Most recent ten pairs, chronological order, with a hard 4000 UTF-16 unit budget. */
    public static JSONArray contents(List<Exchange> history, String message) throws Exception {
        java.util.LinkedList<Exchange> selected = new java.util.LinkedList<>();
        int remaining = 4000;
        for (int i = history.size() - 1; i >= Math.max(0, history.size() - 10) && remaining >= 2; i--) {
            Exchange pair = history.get(i);
            int userLimit = Math.min(pair.user.length(), remaining / 2);
            String user = clip(pair.user, userLimit);
            String model = clip(pair.model, remaining - user.length());
            if (!user.isEmpty() && !model.isEmpty()) selected.addFirst(new Exchange(user, model));
            remaining -= user.length() + model.length();
        }
        JSONArray contents = new JSONArray();
        for (Exchange pair : selected) { contents.put(part("user", pair.user)); contents.put(part("model", pair.model)); }
        contents.put(part("user", message));
        return contents;
    }
    public static String clip(String s, int max) {
        int end = Math.min(s.length(), max);
        if (end > 0 && end < s.length() && Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }
    private static JSONObject part(String role, String text) throws Exception {
        return Json.object("role", role, "parts", new JSONArray().put(Json.object("text", text)));
    }
}
