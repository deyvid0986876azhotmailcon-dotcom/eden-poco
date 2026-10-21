package dev.poco.asistente.core;

import dev.poco.asistente.net.GeminiClient;
import dev.poco.asistente.net.GeminiSpeech;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AudioProtocolTest {
    private JSONObject voice(String mime, byte[] bytes, String finish) throws Exception {
        return Json.object("candidates", new JSONArray().put(Json.object("finishReason", finish,
                "content", Json.object("parts", new JSONArray().put(Json.object("inlineData",
                        Json.object("mimeType", mime, "data", Base64.getEncoder().encodeToString(bytes))))))));
    }
    @Test public void inlineDataContainsExactBytesAndNoToolsOrHistory() throws Exception {
        byte[] bytes = {0, (byte)255, 42};
        JSONObject request = AudioProtocol.transcription(new AudioInput(bytes, "audio/ogg; codecs=opus", 3));
        JSONObject data = request.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getJSONObject("inlineData");
        assertEquals("audio/ogg", data.getString("mimeType"));
        assertArrayEquals(bytes, Base64.getDecoder().decode(data.getString("data")));
        assertFalse(request.has("tools")); assertEquals(1, request.getJSONArray("contents").length());
    }
    @Test public void modeSelectionCoversBothInputTypes() {
        for (boolean incoming : new boolean[]{false, true}) {
            assertFalse(AudioProtocol.shouldSpeak(AudioProtocol.NEVER, incoming));
            assertTrue(AudioProtocol.shouldSpeak(AudioProtocol.ALWAYS, incoming));
            assertEquals(incoming, AudioProtocol.shouldSpeak(AudioProtocol.ON_AUDIO, incoming));
            assertFalse(AudioProtocol.shouldSpeak("unknown", incoming));
        }
    }
    @Test public void transcriptRejectsToolCallsEmptyAndUnintelligibleReplies() {
        for (String text : new String[]{"", " ", "[inaudible]", "x".repeat(4001)})
            assertThrows(IllegalArgumentException.class, () -> AudioProtocol.transcript(new GeminiProtocol.Reply(text, null, null, "")));
        assertThrows(IllegalArgumentException.class, () -> AudioProtocol.transcript(new GeminiProtocol.Reply(null, "borrar_registro", new JSONObject(), "")));
        assertEquals("PARAR", AudioProtocol.transcript(new GeminiProtocol.Reply("Parar.", null, null, "")));
        assertEquals("no parar", AudioProtocol.transcript(new GeminiProtocol.Reply("no parar", null, null, "")));
    }
    @Test public void ttsIsOutputOnlyAndRejectsTruncatedOrOversizedPcm() throws Exception {
        JSONObject request = AudioProtocol.speech("Monto 25000 PYG. SÍ abcd");
        assertFalse(request.has("tools"));
        assertEquals("AUDIO", request.getJSONObject("generationConfig").getJSONArray("responseModalities").getString(0));
        assertEquals("pcm_s16le", AudioProtocol.pcm(voice("audio/L16;codec=pcm;rate=24000", new byte[4], "STOP")).getString("format"));
        assertThrows(IllegalArgumentException.class, () -> AudioProtocol.pcm(voice("audio/L16;rate=24000", new byte[4], "MAX_TOKENS")));
        assertThrows(IllegalArgumentException.class, () -> AudioProtocol.pcm(voice("audio/ogg", new byte[4], "STOP")));
        for (int size : new int[]{0, 3, AudioProtocol.MAX_PCM + 2})
            assertThrows(IllegalArgumentException.class, () -> AudioProtocol.pcm(voice("audio/L16;rate=24000", new byte[size], "STOP")));
        assertThrows(IllegalArgumentException.class, () -> AudioProtocol.speech("x".repeat(2001)));
    }
    @Test public void ttsFallsBackOnUnavailableModelsButHonorsCancellationAndBadKey() throws Exception {
        List<String> attempts = new ArrayList<>();
        String body = voice("audio/L16;rate=24000", new byte[4], "STOP").toString();
        GeminiSpeech speech = new GeminiSpeech((model, key, request) -> {
            attempts.add(model);
            return new GeminiClient.Response(attempts.size() == 1 ? 404 : attempts.size() == 2 ? 429 : 200, body);
        });
        assertNotNull(speech.generate("a".repeat(25), "Hola", () -> false));
        assertEquals(java.util.Arrays.asList(AudioProtocol.TTS_MODELS), attempts);
        attempts.clear();
        assertNull(speech.generate("a".repeat(25), "Hola", () -> true)); assertTrue(attempts.isEmpty());
        assertNull(speech.generate("", "Hola", () -> false)); assertTrue(attempts.isEmpty());
    }
}
