package dev.poco.asistente.core;

import org.json.JSONObject;
import java.util.function.BooleanSupplier;

public interface Cloud {
    GeminiProtocol.Reply generate(String apiKey, String model, JSONObject body) throws Exception;
    default GeminiProtocol.Reply generate(String apiKey, String model, JSONObject body, BooleanSupplier cancelled) throws Exception {
        return generate(apiKey, model, body);
    }
}
