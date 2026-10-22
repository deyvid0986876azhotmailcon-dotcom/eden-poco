package dev.poco.asistente.bridge;

/** Wall-clock checks also account for time spent asleep. No Android dependencies. */
public final class BridgeHealth {
    public static final long GRACE_MS = 10 * 60_000L;
    public static final String UNLINKED = "El WhatsApp del asistente se desvinculó: abrí el WhatsApp del Segundo espacio y volvé a vincular";
    public static final String DISCONNECTED = "El WhatsApp del asistente está desconectado hace 10 minutos";
    public static final String SILENT = "El puente de WhatsApp no responde: abrí Termux";
    private long lastPoll, disconnectedAt;
    private String state;
    public BridgeHealth(long now) { lastPoll = now; }
    public static boolean valid(String value) {
        return "conectado".equals(value) || "desconectado".equals(value) || "desvinculado".equals(value);
    }
    public synchronized void poll(String value, long now) {
        lastPoll = now;
        // Older bridges still count as alive, but cannot assert WhatsApp connectivity.
        if (value == null) return;
        if (!valid(value)) throw new IllegalArgumentException("Estado inválido");
        if (!value.equals(state)) disconnectedAt = now;
        state = value;
    }
    public synchronized String notice(long now) {
        // A backwards wall-clock adjustment starts a new grace period.
        if (now < lastPoll) lastPoll = now;
        if (now < disconnectedAt) disconnectedAt = now;
        if (now - lastPoll >= GRACE_MS) return SILENT;
        if ("desvinculado".equals(state)) return UNLINKED;
        if ("desconectado".equals(state) && now - disconnectedAt >= GRACE_MS) return DISCONNECTED;
        return null;
    }
}
