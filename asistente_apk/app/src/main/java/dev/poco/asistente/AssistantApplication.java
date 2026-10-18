package dev.poco.asistente;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import dev.poco.asistente.core.AssistantCore;
import dev.poco.asistente.data.AppSettings;
import dev.poco.asistente.data.AssistantDb;
import dev.poco.asistente.net.GeminiClient;
import java.time.Clock;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-scoped core survives Activity rotation; phase 2 can reuse it from a foreground service. */
public final class AssistantApplication extends Application {
    public AssistantCore core;
    public AppSettings settings;
    public final ExecutorService worker = Executors.newSingleThreadExecutor();
    public final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private String chat = "Asistente listo. Podés usar las reglas sin clave. Escribí «ayuda» para ver ejemplos.\n";
    private boolean busy;
    @Override public void onCreate() {
        super.onCreate();
        settings = new AppSettings(this);
        core = new AssistantCore(new AssistantDb(this), settings, new GeminiClient(), Clock.systemDefaultZone(), SystemClock::elapsedRealtime);
    }
    public void listen(Runnable listener) { listeners.addIfAbsent(listener); }
    public void unlisten(Runnable listener) { listeners.remove(listener); }
    public synchronized String chat() { return chat; }
    public synchronized boolean busy() { return busy; }
    private synchronized void append(String text) {
        chat += "\n" + text + "\n";
        if (chat.length() > 32000) chat = "[Conversación anterior recortada; los registros siguen en Resumen]\n" + chat.substring(chat.length() - 30000);
    }
    private void changed() { main.post(() -> { for (Runnable listener : listeners) listener.run(); }); }
    public void send(String text) {
        if (dev.poco.asistente.core.Rules.normalize(text).equals("parar")) { stop(); return; }
        synchronized (this) { if (busy) return; busy = true; }
        long token = core.cancellationToken();
        append("David: " + text); changed();
        worker.execute(() -> {
            String response = core.receive(text, token);
            append("Asistente: " + response);
            synchronized (this) { busy = false; }
            changed();
        });
    }
    public void stop() { append("Asistente: " + core.stop()); changed(); }
}
