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
            // the spoken summary goes to the text models first; unavailable, it falls back to the plain text
            if (!java.util.Arrays.asList(AudioProtocol.TTS_MODELS).contains(model)) return new GeminiClient.Response(503, "");
            attempts.add(model);
            return new GeminiClient.Response(attempts.size() == 1 ? 404 : attempts.size() == 2 ? 429 : 200, body);
        });
        assertNotNull(speech.generate("a".repeat(25), "Hola", () -> false));
        assertEquals(java.util.Arrays.asList(AudioProtocol.TTS_MODELS), attempts);
        attempts.clear();
        assertNull(speech.generate("a".repeat(25), "Hola", () -> true)); assertTrue(attempts.isEmpty());
        assertNull(speech.generate("", "Hola", () -> false)); assertTrue(attempts.isEmpty());
    }
    @Test public void acceptsWavAndPcmWithChannelsFromTtsModels() throws Exception {
        java.nio.ByteBuffer wav = java.nio.ByteBuffer.allocate(48).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes()).putInt(40).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(24000).putInt(48000).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes()).putInt(4).putShort((short) 1).putShort((short) -1);
        org.junit.Assert.assertArrayEquals(new byte[]{1, 0, -1, -1}, AudioProtocol.wavPcm(wav.array()));
        for (String mime : new String[]{"audio/wav", "audio/l16; rate=24000; channels=1", "audio/L16;codec=pcm;rate=24000"}) {
            String data = java.util.Base64.getEncoder().encodeToString(mime.equals("audio/wav") ? wav.array() : new byte[]{1, 0});
            org.json.JSONObject response = new org.json.JSONObject().put("candidates", new org.json.JSONArray().put(new org.json.JSONObject()
                    .put("finishReason", "STOP").put("content", new org.json.JSONObject().put("parts", new org.json.JSONArray()
                            .put(new org.json.JSONObject().put("inlineData", new org.json.JSONObject().put("mimeType", mime).put("data", data)))))));
            org.junit.Assert.assertEquals("pcm_s16le", AudioProtocol.pcm(response).getString("format"));
        }
    }
    @Test public void voiceRepliesAreCleanAndOnlyVoiceUnlessTextIsNeeded() throws Exception {
        org.junit.Assert.assertEquals("Anoté un gasto de 25.000 guaraníes en almuerzo (hoy). Listo, che.",
                AudioProtocol.plainSpoken("Anoté un gasto de ₲ 25.000 en almuerzo (hoy). Listo, che. (ref. 442bb857-795e-488b-bd16-d6f558b03c6c)"));
        org.junit.Assert.assertFalse(AudioProtocol.needsText("Anoté un gasto.", "gasté 25000 en almuerzo"));
        org.junit.Assert.assertTrue(AudioProtocol.needsText("¿Seguro? Si es así, respondé:\nSÍ 0123456789ABCDEF\n(vale 5 minutos)", "borrá el gasto"));
        org.junit.Assert.assertTrue(AudioProtocol.needsText("Gastaste 85.000 hoy.", "cuánto gasté hoy, mandámelo por escrito"));
        org.junit.Assert.assertFalse(AudioProtocol.speech("Hola").toString().contains("Leé"));
    }
    @Test public void spokenSummaryMustBeCompleteAndNeverCarriesCodes() throws Exception {
        java.util.function.Function<String, org.json.JSONObject> reply = finish -> {
            try {
                return new org.json.JSONObject().put("candidates", new org.json.JSONArray().put(new org.json.JSONObject().put("finishReason", finish)
                        .put("content", new org.json.JSONObject().put("parts", new org.json.JSONArray().put(new org.json.JSONObject().put("text", "Listo, che."))))));
            } catch (Exception e) { throw new RuntimeException(e); }
        };
        assertEquals("Listo, che.", AudioProtocol.spokenText(reply.apply("STOP")));
        assertThrows(IllegalArgumentException.class, () -> AudioProtocol.spokenText(reply.apply("MAX_TOKENS")));
        assertThrows(IllegalArgumentException.class, () -> AudioProtocol.spokenText(reply.apply("SAFETY")));
        String spoken = AudioProtocol.plainSpoken("Confirmá con SÍ 0123456789ABCDEF ya. El código es 0123456789abcdef.");
        assertFalse(spoken.toLowerCase().contains("0123456789abcdef"));
    }
    @Test public void truncatedWavIsRejectedButStreamedWavIsRead() {
        java.util.function.IntFunction<byte[]> wav = declared -> {
            java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(47).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            b.put("RIFF".getBytes()).putInt(39).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16)
                    .putShort((short) 1).putShort((short) 1).putInt(24000).putInt(48000).putShort((short) 2).putShort((short) 16)
                    .put("data".getBytes()).putInt(declared).put((byte) 1).put((byte) 2).put((byte) 3);
            return b.array();
        };
        assertThrows(IllegalArgumentException.class, () -> AudioProtocol.wavPcm(wav.apply(4)));
        assertThrows(IllegalArgumentException.class, () -> AudioProtocol.wavPcm(wav.apply(3)));
        org.junit.Assert.assertArrayEquals(new byte[]{1, 2}, AudioProtocol.wavPcm(wav.apply(-1)));
    }
}
