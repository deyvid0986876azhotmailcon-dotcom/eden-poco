package dev.poco.asistente.core;

import dev.poco.asistente.net.GeminiClient;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class GeminiProtocolTest {
    private JSONObject response(JSONArray parts, String finish) throws Exception {
        return Json.object("candidates", new JSONArray().put(Json.object("finishReason", finish, "content", Json.object("parts", parts))));
    }
    private JSONObject textResponse() throws Exception { return response(new JSONArray().put(Json.object("text", "Hola, David.")), "STOP"); }
    @Test public void requestEscapesUserInputAndDeclaresOnlyAllowedFunctions() throws Exception {
        String message = "comí \"pollo\"\ncon arroz \\ rico";
        JSONObject body = new JSONObject(GeminiProtocol.request(message, "Saldo ARS 1", LocalDate.of(2026, 10, 17), "ARS").toString());
        assertEquals(message, body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"));
        assertFalse(body.has("key")); assertFalse(body.has("apiKey"));
        JSONArray functions = body.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations");
        Set<String> names = new HashSet<>();
        for (int i = 0; i < functions.length(); i++) names.add(functions.getJSONObject(i).getString("name"));
        assertEquals(new HashSet<>(Arrays.asList("registrar_movimiento", "registrar_peso", "registrar_comida", "registrar_entreno",
                "fijar_presupuesto", "fijar_meta", "consultar_resumen", "corregir_registro", "borrar_registro", "recordar", "olvidar", "programar_recordatorio", "listar_recordatorios", "cancelar_recordatorio", "charla_profunda", "armar_pdf", "descargar")), names);
        String system = body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text");
        assertTrue(system.contains("2026-10-17")); assertTrue(system.contains("nutricionista")); assertTrue(system.contains("NO órdenes"));
    }
    @Test public void parsedToolDoesNotTrustAccompanyingSuccessText() throws Exception {
        JSONObject call = Json.object("functionCall", Json.object("name", "registrar_peso", "args", Json.object("kg", 58.4, "fecha", "2026-10-17")));
        GeminiProtocol.Reply reply = GeminiProtocol.parse(response(new JSONArray().put(Json.object("text", "Ya borré todo")).put(call), "STOP"), "");
        assertEquals("registrar_peso", reply.tool); assertNull(reply.text); assertEquals(58.4, reply.args.getDouble("kg"), 0.0001);
    }
    @Test public void multipleCallsTruncationAndSafetyBlockNeverProduceActions() throws Exception {
        JSONObject call = Json.object("functionCall", Json.object("name", "consultar_resumen", "args", new JSONObject()));
        assertThrows(IllegalArgumentException.class, () -> GeminiProtocol.parse(response(new JSONArray().put(call).put(call), "STOP"), ""));
        assertThrows(IllegalArgumentException.class, () -> GeminiProtocol.parse(response(new JSONArray().put(call), "MAX_TOKENS"), ""));
        assertThrows(IllegalArgumentException.class, () -> GeminiProtocol.parse(Json.object("promptFeedback", Json.object("blockReason", "SAFETY")), ""));
    }
    @Test public void fallbackUsesRequestedOrderAndReportsQuotaErrors() throws Exception {
        List<String> attempts = new ArrayList<>(); String response = textResponse().toString();
        GeminiClient client = new GeminiClient((model, key, body) -> {
            attempts.add(model);
            return attempts.size() == 1 ? new GeminiClient.Response(503, "private error")
                    : attempts.size() == 2 ? new GeminiClient.Response(429, "private error")
                    : new GeminiClient.Response(200, response);
        });
        GeminiProtocol.Reply reply = client.generate("abcdefghijklmnopqrstuv", GeminiProtocol.MODELS[0], new JSONObject());
        assertEquals(Arrays.asList(GeminiProtocol.MODELS[0], GeminiProtocol.MODELS[1], GeminiProtocol.MODELS[2]), attempts);
        assertTrue(reply.notice.contains("503")); assertTrue(reply.notice.contains("429")); assertFalse(reply.notice.contains("private error"));
    }
    @Test public void networkFailureFallsBackButAuthenticationFailureDoesNot() throws Exception {
        List<String> attempts = new ArrayList<>(); String response = textResponse().toString();
        GeminiClient client = new GeminiClient((model, key, body) -> {
            attempts.add(model); if (attempts.size() == 1) throw new IOException("secret should never be shown");
            return new GeminiClient.Response(200, response);
        });
        assertTrue(client.generate("abcdefghijklmnopqrstuv", GeminiProtocol.MODELS[0], new JSONObject()).notice.contains("Error de red"));
        assertEquals(2, attempts.size()); attempts.clear();
        GeminiClient invalid = new GeminiClient((model, key, body) -> { attempts.add(model); return new GeminiClient.Response(403, "secret"); });
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> invalid.generate("abcdefghijklmnopqrstuv", GeminiProtocol.MODELS[0], new JSONObject()));
        assertEquals(1, attempts.size()); assertFalse(error.getMessage().contains("secret"));
    }
    @Test public void exhaustedModelsAndInvalidModelAreBounded() throws Exception {
        List<String> attempts = new ArrayList<>();
        GeminiClient client = new GeminiClient((model, key, body) -> { attempts.add(model); return new GeminiClient.Response(429, ""); });
        assertThrows(IllegalArgumentException.class, () -> client.generate("abcdefghijklmnopqrstuv", GeminiProtocol.MODELS[0], new JSONObject()));
        assertEquals(4, attempts.size()); attempts.clear();
        assertThrows(IllegalArgumentException.class, () -> client.generate("abcdefghijklmnopqrstuv", "../../other-host", new JSONObject()));
        assertTrue(attempts.isEmpty());
    }
    @Test public void cancellationPreventsFurtherFallbackRequests() throws Exception {
        List<String> attempts = new ArrayList<>();
        GeminiClient client = new GeminiClient((model, key, body) -> { attempts.add(model); return new GeminiClient.Response(503, ""); });
        assertThrows(IllegalArgumentException.class, () -> client.generate("abcdefghijklmnopqrstuv", GeminiProtocol.MODELS[0], new JSONObject(), () -> !attempts.isEmpty()));
        assertEquals(1, attempts.size());
    }
}
