package dev.poco.asistente.net;

import dev.poco.asistente.core.Cloud;
import dev.poco.asistente.core.GeminiProtocol;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Only HTTPS to Google's fixed host, key in a header, no logs, redirects disabled. */
public final class GeminiClient implements Cloud {
    public static final class Response {
        public final int status;
        public final String body;
        public Response(int status, String body) { this.status = status; this.body = body; }
    }
    public interface Transport { Response post(String model, String key, String body) throws IOException; }
    private final Transport transport;
    public GeminiClient() { this(new HttpTransport()); }
    public GeminiClient(Transport transport) { this.transport = transport; }
    @Override public GeminiProtocol.Reply generate(String apiKey, String selected, JSONObject body) throws Exception {
        return generate(apiKey, selected, body, () -> false);
    }
    @Override public GeminiProtocol.Reply generate(String apiKey, String selected, JSONObject body, BooleanSupplier cancelled) throws Exception {
        if (!Arrays.asList(GeminiProtocol.MODELS).contains(selected)) throw new IllegalArgumentException("Elegí un modelo de la lista de Ajustes.");
        if (apiKey == null || !apiKey.matches("[A-Za-z0-9._-]{20,200}")) throw new IllegalArgumentException("Revisá la clave de Gemini en Ajustes.");
        LinkedHashSet<String> models = new LinkedHashSet<>(); models.add(selected); models.addAll(Arrays.asList(GeminiProtocol.MODELS));
        StringBuilder notices = new StringBuilder();
        String request = body.toString();
        for (String model : models) {
            if (cancelled.getAsBoolean()) throw new IllegalArgumentException("Pedido cancelado. No ejecuté ninguna respuesta de Gemini.");
            Response response;
            try { response = transport.post(model, apiKey, request); }
            catch (IOException e) {
                notices.append("Error de red con ").append(model).append(". ");
                continue; // no executor runs until one complete successful response is accepted
            }
            if (response.status == 429 || response.status == 503) {
                notices.append(model).append(response.status == 429 ? ": cuota agotada (429). " : ": ocupado (503). ");
                continue;
            }
            if (response.status != 200) {
                String reason = response.status == 400 ? "Revisá la clave o la compatibilidad del pedido."
                        : response.status == 401 || response.status == 403 ? "Revisá la clave y sus permisos."
                        : response.status == 404 ? "Modelo no disponible para esta clave." : "Probá más tarde.";
                throw new IllegalArgumentException(notices + "Gemini devolvió HTTP " + response.status + ". " + reason + " No guardé nada.");
            }
            try {
                return GeminiProtocol.parse(new JSONObject(response.body), notices.length() == 0 ? "" : notices + "Respondió " + model + ".\n");
            } catch (org.json.JSONException e) {
                throw new IllegalArgumentException("Respuesta inválida de Gemini. No guardé nada.");
            }
        }
        throw new IllegalArgumentException(notices + "No pude conectar con ningún modelo. No guardé nada; las reglas locales siguen funcionando.");
    }
    private static final class HttpTransport implements Transport {
        @Override public Response post(String model, String key, String body) throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent").openConnection();
            try {
                connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(15000); connection.setReadTimeout(30000); connection.setUseCaches(false);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setRequestProperty("x-goog-api-key", key);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
                int status = connection.getResponseCode();
                if (status != 200) return new Response(status, ""); // never surface raw errors containing credentials/data
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096]; int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (out.size() + read > 262144) throw new IOException("Respuesta demasiado grande");
                        out.write(buffer, 0, read);
                    }
                    return new Response(status, new String(out.toByteArray(), StandardCharsets.UTF_8));
                }
            } finally { connection.disconnect(); }
        }
    }
}
