package dev.poco.asistente.core;

import dev.poco.asistente.net.GeminiClient;
import dev.poco.asistente.net.GeminiSpeech;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class GeminiSpeechTest {
    @Test public void codesAreRemovedBeforeSummaryAndAgainBeforeVoice() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        GeminiSpeech speech = new GeminiSpeech((model, key, body) -> {
            assertFalse(body.toLowerCase().contains("0123456789abcdef"));
            if (calls.incrementAndGet() == 1)
                return new GeminiClient.Response(200, "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"Confirmá SÍ 0123456789ABCDEF\"}]}}]}");
            return new GeminiClient.Response(400, "");
        });
        assertNull(speech.generate("abcdefghijklmnopqrstuv", "Confirmá SÍ 0123456789ABCDEF", () -> false));
        assertEquals(2, calls.get());
    }
    @Test public void externalSummaryIsMarkedButVoiceFallbackDoesNotReadTheInternalLabel() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        GeminiSpeech speech = new GeminiSpeech((model, key, body) -> {
            try {
                JSONObject request = new JSONObject(body);
                assertFalse(request.has("tools"));
                if (calls.incrementAndGet() == 1) {
                    assertTrue(request.getJSONObject("systemInstruction").toString().contains("datos externos"));
                    return new GeminiClient.Response(400, ""); // force the safe plain spoken fallback
                }
                assertFalse(body.contains("Contenido externo"));
                assertEquals("Nos vemos a las 18:30", request.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"));
                return new GeminiClient.Response(400, "");
            } catch (org.json.JSONException e) { throw new AssertionError(e); }
        });
        assertNull(speech.generate("abcdefghijklmnopqrstuv", ExternalData.wrap("Nos vemos a las 18:30"), () -> false));
        assertEquals(2, calls.get());
    }
    @Test public void summaryCannotReturnAFunctionEvenWithCompleteText() throws Exception {
        JSONObject reply = Json.object("candidates", new JSONArray().put(Json.object("finishReason", "STOP", "content",
                Json.object("parts", new JSONArray().put(Json.object("text", "Listo")).put(Json.object("functionCall", Json.object("name", "abrir_app")))))));
        assertThrows(IllegalArgumentException.class, () -> AudioProtocol.spokenText(reply));
    }
}
