package dev.poco.asistente.data;

import android.content.Context;
import android.content.SharedPreferences;
import dev.poco.asistente.core.AssistantCore;
import dev.poco.asistente.core.GeminiProtocol;
import dev.poco.asistente.core.Validation;
import java.util.Arrays;

public final class AppSettings implements AssistantCore.Settings {
    private final SharedPreferences prefs;
    public final KeyVault vault;
    public AppSettings(Context context) { prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE); vault = new KeyVault(context); }
    @Override public String apiKey() throws Exception { return vault.load(); }
    @Override public String model() { return prefs.getString("model", GeminiProtocol.MODELS[0]); }
    @Override public String currency() { return prefs.getString("currency", "ARS"); }
    public void save(String model, String currency) {
        if (!Arrays.asList(GeminiProtocol.MODELS).contains(model)) throw new IllegalArgumentException("Modelo inválido");
        Validation.currency(currency);
        if (!prefs.edit().putString("model", model).putString("currency", currency).commit()) throw new IllegalStateException("No se guardaron los ajustes.");
    }
}
