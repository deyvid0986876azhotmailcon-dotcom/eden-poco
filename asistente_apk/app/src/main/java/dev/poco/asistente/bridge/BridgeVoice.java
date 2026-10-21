package dev.poco.asistente.bridge;

import dev.poco.asistente.AssistantApplication;
import dev.poco.asistente.core.AudioProtocol;
import dev.poco.asistente.net.GeminiSpeech;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Voice has a separate, bounded budget: it must never hide an already executed written answer. */
final class BridgeVoice {
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), new ThreadPoolExecutor.AbortPolicy());
    JSONObject generate(AssistantApplication app, String text, boolean incomingAudio, long ticket, long budgetMillis) {
        if (!AudioProtocol.shouldSpeak(app.settings.audioReplyMode(), incomingAudio) || budgetMillis <= 0
                || ticket != app.core.cancellationToken() || !app.settings.bridgeEnabled()) return null;
        Future<JSONObject> voice = null;
        try {
            voice = pool.submit(() -> new GeminiSpeech().generate(app.settings.apiKey(), text,
                    () -> ticket != app.core.cancellationToken() || !app.settings.bridgeEnabled()));
            JSONObject audio = voice.get(Math.min(15000, budgetMillis), TimeUnit.MILLISECONDS);
            if (ticket != app.core.cancellationToken() || !app.settings.bridgeEnabled()) return null;
            if (audio == null) app.bridgeLog("Voz no disponible; se entrega texto");
            return audio;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            app.bridgeLog("Voz no disponible o tiempo agotado; se entrega texto"); return null;
        } finally { if (voice != null) voice.cancel(true); }
    }
    void close() { pool.shutdownNow(); }
}
