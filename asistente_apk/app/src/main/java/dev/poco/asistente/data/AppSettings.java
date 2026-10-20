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
    }
    public boolean bridgeEnabled() { return prefs.getBoolean("bridge_enabled", false); }
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
