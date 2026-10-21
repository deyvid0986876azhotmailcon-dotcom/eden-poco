package dev.poco.asistente.net;

import dev.poco.asistente.core.AudioProtocol;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** TTS is output-only: no tool declarations and no executor. Failure leaves the written answer. */
public final class GeminiSpeech {
    private final GeminiClient.Transport transport;
    public GeminiSpeech() { this(new GeminiClient.HttpTransport(4 * 1024 * 1024, 10000)); }
    public GeminiSpeech(GeminiClient.Transport transport) { this.transport = transport; }
    public JSONObject generate(String key, String text, BooleanSupplier cancelled) throws Exception {
        if (key == null || !key.matches("[A-Za-z0-9._-]{20,200}")) return null;
        String body = AudioProtocol.speech(text).toString();
        for (String model : AudioProtocol.TTS_MODELS) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) return null;
            GeminiClient.Response response;
            try { response = transport.post(model, key, body); }
            catch (java.io.IOException e) { continue; }
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) return null;
            if (response.status == 404 || response.status == 429 || response.status == 503) continue;
            if (response.status != 200) return null;
            return AudioProtocol.pcm(new JSONObject(response.body));
        }
        return null;
    }
}
