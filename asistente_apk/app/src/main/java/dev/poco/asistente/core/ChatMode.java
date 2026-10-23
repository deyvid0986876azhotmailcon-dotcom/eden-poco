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
        if (isClosing(normalized)) {
            clear(); return "Dale, dejamos la charla por acá.";
        }
        return null;
    }

    private static boolean isClosing(String normalized) {
        // Whole messages only, up to seven words (including David's reported closing).
        // Fixed phrases and courtesy suffixes avoid swallowing "listo para hablar" or requests.
        String words = normalized.replaceAll("[\\p{P}]+", " ").trim().replaceAll("\\s+", " ");
        if (words.isEmpty() || words.split(" ").length > 7) return false;
        return words.matches("(?:bueno )?(?:listo(?: ya esta| ya tengo una opinion)?|ya esta|"
                + "fin de la charla|chau|nos vemos|hablamos despues|gracias por la charla|"
                + "terminamos|salir del modo charla)(?: gracias)?(?: nos vemos| hablamos despues)?");
    }
}
