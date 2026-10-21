package dev.poco.asistente.core;

import dev.poco.asistente.net.GeminiClient;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SearchProtocolTest {
    private JSONObject response(JSONObject part) throws Exception {
        return Json.object("candidates", new JSONArray().put(Json.object("finishReason", "STOP", "content", Json.object("parts", new JSONArray().put(part)),
                "groundingMetadata", Json.object("groundingChunks", new JSONArray().put(Json.object("web", Json.object("title", "Fuente", "uri", "https://example.org/info")))))));
    }
    @Test public void requestHasOnlySearchAndPublicQueryAndResponseIsDataWithSources() throws Exception {
        JSONObject body = SearchProtocol.request("lunas de Saturno");
        assertEquals(1, body.getJSONArray("tools").length());
        assertTrue(body.getJSONArray("tools").getJSONObject(0).has("google_search"));
        assertFalse(body.toString().contains("functionDeclarations")); assertFalse(body.toString().contains("David"));
        GeminiProtocol.Reply reply = SearchProtocol.parse(response(Json.object("text", "Saturno tiene lunas.")), "");
        assertNull(reply.tool); assertTrue(ExternalData.isExternal(reply.text)); assertTrue(reply.text.contains("Fuente — example.org"));
        assertFalse(ExternalData.history(reply.text).contains("Saturno"));
    }
    @Test public void serverReturningAFunctionIsRejectedEvenThoughRequestHasNoFunctions() throws Exception {
        JSONObject r = response(Json.object("functionCall", Json.object("name", "abrir_app", "args", Json.object("nombre", "Música"))));
        assertThrows(IllegalArgumentException.class, () -> SearchProtocol.parse(r, ""));
    }
    @Test public void searchUsesSameFallbackAndReportsExhaustedQuota() throws Exception {
        AtomicInteger n = new AtomicInteger();
        String reply = response(Json.object("text", "Respuesta pública")).toString();
        GeminiClient client = new GeminiClient((model, key, body) -> {
            int attempt = n.incrementAndGet();
            return new GeminiClient.Response(attempt == 1 ? 404 : attempt == 2 ? 429 : attempt == 3 ? 503 : 200, reply);
        });
        assertTrue(ExternalData.isExternal(client.generate("abcdefghijklmnopqrstuv", GeminiProtocol.MODELS[0], SearchProtocol.request("clima")).text));
        assertEquals(GeminiProtocol.MODELS.length, n.get());
        GeminiClient exhausted = new GeminiClient((model, key, body) -> new GeminiClient.Response(429, "secret"));
        Exception error = assertThrows(IllegalArgumentException.class, () -> exhausted.generate("abcdefghijklmnopqrstuv", GeminiProtocol.MODELS[0], SearchProtocol.request("clima")));
        assertTrue(error.getMessage().contains("429")); assertFalse(error.getMessage().contains("secret"));
    }
}
