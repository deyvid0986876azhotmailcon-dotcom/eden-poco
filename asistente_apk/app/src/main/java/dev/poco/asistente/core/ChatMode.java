package dev.poco.asistente.core;

import java.util.function.LongSupplier;

/** Session-only, monotonic inactivity clock. PARAR may call clear concurrently. */
public final class ChatMode {
    private final LongSupplier elapsed;
    private boolean on;
    private long last;
    public ChatMode(LongSupplier elapsed) { this.elapsed = elapsed; }
    public synchronized void clear() { on = false; }
    public synchronized boolean active() {
        if (on && elapsed.getAsLong() - last >= 30 * 60 * 1000L) on = false;
        return on;
    }
    public synchronized void touch() { if (active()) last = elapsed.getAsLong(); }
    public synchronized String command(String normalized, boolean enabled) {
        if (normalized.equals("charlemos") || normalized.equals("modo charla") || normalized.equals("quiero charlar")) {
            if (!enabled) return "Encendé «Usar mi Google AI Pro» en Ajustes para charlar así.";
            on = true; last = elapsed.getAsLong(); return "Dale, charlemos. ¿Qué tenés en la cabeza?";
        }
        if (normalized.equals("listo") || normalized.equals("fin de la charla") || normalized.equals("chau")) {
            clear(); return "Dale, dejamos la charla por acá.";
        }
        return null;
    }
}
