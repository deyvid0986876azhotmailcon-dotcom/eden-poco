package dev.poco.asistente.data;

import android.content.Context;
import android.content.SharedPreferences;
import dev.poco.asistente.core.AssistantCore;
import dev.poco.asistente.core.GeminiProtocol;
import dev.poco.asistente.core.Validation;
import dev.poco.asistente.messaging.NotificationPolicy;
import java.util.Arrays;

public final class AppSettings implements dev.poco.asistente.core.Proactive.Settings {
    private final SharedPreferences prefs;
    public final KeyVault vault;
    private final KeyVault bridgeVault;
    public static final int BRIDGE_PORT = 8765;
    public AppSettings(Context context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE); vault = new KeyVault(context);
        bridgeVault = new KeyVault(context, "bridge_vault", "dev.poco.asistente.bridge.v1");
        bankVault = new KeyVault(context, "bank_vault", "dev.poco.asistente.bank.v1");
    }
    private final KeyVault bankVault;
    /** Bank mail reader (asistente_finanzas): {deployment id, token}, or null when not set up. */
    public synchronized String[] bankReader() throws Exception {
        String saved = bankVault.load();
        int dot = saved.lastIndexOf('.'); // neither part has dots: the vault only takes [A-Za-z0-9._-]
        return dot < 1 ? null : new String[]{saved.substring(0, dot), saved.substring(dot + 1)};
    }
    public synchronized void saveBankReader(String deployment, String token) throws Exception {
        if (!deployment.matches("[A-Za-z0-9_-]{20,120}") || !token.matches("[A-Za-z0-9_-]{20,64}"))
            throw new IllegalArgumentException("Datos del lector del banco inválidos.");
        bankVault.save(deployment + "." + token);
    }
    public boolean bridgeEnabled() { return prefs.getBoolean("bridge_enabled", false); }
    @Override public boolean proEnabled() { return prefs.getBoolean("pro_enabled", true); }
    public void setProEnabled(boolean enabled) {
        if (!prefs.edit().putBoolean("pro_enabled", enabled).commit()) throw new IllegalStateException("No se guardó el ajuste Pro.");
    }
    private static final Object SEARCH_COUNT_LOCK = new Object();
    private static String searchMonth() { return java.time.YearMonth.now(dev.poco.asistente.core.Reminders.ZONE).toString(); }
    public long searchesThisMonth() {
        synchronized (SEARCH_COUNT_LOCK) { return searchesIn(searchMonth()); }
    }
    private long searchesIn(String month) {
        return month.equals(prefs.getString("search_month", "")) ? Math.max(0, prefs.getLong("search_count", 0)) : 0;
    }
    /** Counts started attempts, not Tavily billing/credits; no local quota is enforced. */
    @Override public void recordSearch() {
        synchronized (SEARCH_COUNT_LOCK) {
            String month = searchMonth(); long count = searchesIn(month);
            prefs.edit().putString("search_month", month).putLong("search_count", count == Long.MAX_VALUE ? count : count + 1).apply();
        }
    }
    @Override public boolean phoneEnabled() { return prefs.getBoolean("phone_enabled", false); }
    @Override public void disablePhone() { setPhoneEnabled(false); }
    public void setPhoneEnabled(boolean enabled) {
        // Commit before returning so a process restart cannot restore control after PARAR.
        boolean saved = prefs.edit().putBoolean("phone_enabled", enabled).commit();
        if (!enabled) dev.poco.asistente.phone.PhoneNotifications.clear();
        if (!saved) throw new IllegalStateException("No pude guardar el estado del control del teléfono.");
    }
    public String audioReplyMode() { return prefs.getString("audio_reply_mode", dev.poco.asistente.core.AudioProtocol.ON_AUDIO); }
    public void saveAudioReplyMode(String mode) {
        if (!Arrays.asList("nunca", "audio", "siempre").contains(mode)) throw new IllegalArgumentException("Modo de audio inválido.");
        if (!prefs.edit().putString("audio_reply_mode", mode).commit()) throw new IllegalStateException("No se guardó el modo de audio.");
    }
    public void setBridgeEnabled(boolean enabled) {
        if (!prefs.edit().putBoolean("bridge_enabled", enabled).commit()) throw new IllegalStateException("No se guardó el estado del puente.");
    }
    public synchronized String bridgeToken() throws Exception {
        if (!bridgeVault.hasKey()) {
            byte[] bytes = new byte[32]; new java.security.SecureRandom().nextBytes(bytes);
            bridgeVault.save(android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING));
            Arrays.fill(bytes, (byte) 0);
        }
        return bridgeVault.load();
    }
    @Override public String apiKey() throws Exception { return vault.load(); }
    @Override public String model() { return prefs.getString("model", GeminiProtocol.MODELS[0]); }
    @Override public String currency() { return prefs.getString("currency", "PYG"); }
    @Override public boolean dailySummary() { return prefs.getBoolean("summary_daily", false); }
    @Override public boolean weeklySummary() { return prefs.getBoolean("summary_weekly", false); }
    @Override public String summaryTime() { return prefs.getString("summary_time", "21:30"); }
    public void saveSummaries(boolean daily, boolean weekly, String hour) {
        dev.poco.asistente.core.Reminders.time(hour);
        if (!prefs.edit().putBoolean("summary_daily", daily).putBoolean("summary_weekly", weekly).putString("summary_time", hour).commit())
            throw new IllegalStateException("No se guardaron los ajustes del resumen.");
    }
    /** Reminders ring the phone as a call (CallLauncher); David asked for it, so it starts on. */
    @Override public boolean callReminders() { return prefs.getBoolean("call_reminders", true); }
    public void setCallReminders(boolean on) { prefs.edit().putBoolean("call_reminders", on).apply(); }
    /** She speaks first (morning brief, big expense, gym): David asked for it, so both start on. */
    @Override public boolean morningBrief() { return prefs.getBoolean("morning_brief", true); }
    @Override public String morningTime() { return prefs.getString("morning_time", "07:30"); }
    @Override public boolean nudges() { return prefs.getBoolean("nudges", true); }
    public void setMorningBrief(boolean on) { prefs.edit().putBoolean("morning_brief", on).apply(); }
    public void setNudges(boolean on) { prefs.edit().putBoolean("nudges", on).apply(); }
    public boolean whatsappEnabled() { return prefs.getBoolean("whatsapp_enabled", false); }
    public String whatsappName() { return prefs.getString("whatsapp_name", ""); }
    public String whatsappPhone() { return prefs.getString("whatsapp_phone", ""); }
    public void saveWhatsapp(boolean enabled, String name, String phone) {
        if (name.length() > 100 || name.matches("(?s).*[\\p{Cntrl}\\p{Cf}].*") || (enabled && name.trim().isEmpty()))
            throw new IllegalArgumentException("Ingresá el nombre exacto del contacto (hasta 100 caracteres, sin controles invisibles).");
        if (!phone.isEmpty() && !NotificationPolicy.e164(phone)) throw new IllegalArgumentException("Número en E.164: + y entre 7 y 15 dígitos, sin espacios.");
        if (!prefs.edit().putBoolean("whatsapp_enabled", enabled).putString("whatsapp_name", name).putString("whatsapp_phone", phone).commit())
            throw new IllegalStateException("No se guardaron los ajustes de WhatsApp.");
    }
    public void save(String model, String currency) {
        if (!Arrays.asList(GeminiProtocol.MODELS).contains(model)) throw new IllegalArgumentException("Modelo inválido");
        Validation.currency(currency);
        if (!prefs.edit().putString("model", model).putString("currency", currency).commit()) throw new IllegalStateException("No se guardaron los ajustes.");
    }
}
