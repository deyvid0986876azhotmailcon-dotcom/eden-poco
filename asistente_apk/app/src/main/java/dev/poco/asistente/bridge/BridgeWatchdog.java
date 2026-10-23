package dev.poco.asistente.bridge;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Independent of Android; all times must use the same monotonic clock. */
public final class BridgeWatchdog implements Runnable {
    public static final long RETRY_MS = 15 * 60_000L;
    public static final long HOUR_MS = 60 * 60_000L;
    public interface Actions {
        boolean notice(String text);
        boolean screenInteractive();
        boolean accessibilityConnected();
        void restart();
        void log(String event);
    }
    private final BridgeHealth health;
    private final LongSupplier clock;
    private final Actions actions;
    private final ArrayDeque<Long> attempts = new ArrayDeque<>();
    private String observed, displayed;

    public BridgeWatchdog(LongSupplier clock, Actions actions) {
        this.clock = clock; this.actions = actions;
        health = new BridgeHealth(clock.getAsLong());
    }
    public synchronized void poll(String state) {
        health.poll(state, clock.getAsLong());
        attempts.clear(); // Even legacy/disconnected polls prove that Termux is alive.
    }
    public synchronized boolean isSilent() {
        return BridgeHealth.SILENT.equals(health.notice(clock.getAsLong()));
    }
    private void log(String event) {
        try { actions.log(event); } catch (Throwable ignored) { /* Do not lose the periodic task. */ }
    }
    @Override public synchronized void run() {
        // ScheduledExecutor suppresses every subsequent invocation if a callback throws.
        try { check(); }
        catch (Throwable ignored) { log("Salud: chequeo fallido; se reintentará"); }
    }
    private void check() {
        long now = clock.getAsLong();
        String next = health.notice(now);
        if (!Objects.equals(next, observed)) {
            if (BridgeHealth.SILENT.equals(observed)) log("Salud: puente de vuelta");
            if (next != null) log("Salud: " + next);
            else log("Salud: sin avisos pendientes");
            observed = next;
        }
        // Notification failures must not prevent recovery, and are retried next minute.
        if (!Objects.equals(next, displayed)) {
            try {
                if (actions.notice(next)) {
                    displayed = next;
                    log(next == null ? "Salud: aviso retirado"
                            : BridgeHealth.SILENT.equals(next) ? "Salud: puente sin respuesta (aviso mostrado)"
                            : "Salud: " + next + " (aviso mostrado)");
                }
            } catch (Throwable ignored) { log("Salud: no se pudo actualizar el aviso; se reintentará"); }
        }
        if (!BridgeHealth.SILENT.equals(next)) return;
        if (actions.screenInteractive() || !actions.accessibilityConnected()) return;
        while (!attempts.isEmpty() && now - attempts.peekFirst() >= HOUR_MS) attempts.removeFirst();
        if (attempts.size() >= 4 || (!attempts.isEmpty() && now - attempts.peekLast() < RETRY_MS)) return;
        attempts.addLast(now); // Failed requests consume the limit too.
        try { actions.restart(); }
        catch (Throwable ignored) { log("Salud: intento de reanimación fallido"); }
    }
}
