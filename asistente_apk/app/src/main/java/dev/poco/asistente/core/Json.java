package dev.poco.asistente.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class Json {
    private Json() {}
    public static JSONObject object(Object... pairs) throws JSONException {
        JSONObject result = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    public static JSONArray array(String... values) {
        JSONArray result = new JSONArray();
        for (String value : values) result.put(value);
        return result;
    }
    public static JSONObject copy(JSONObject value) throws JSONException {
        return new JSONObject(value.toString());
    }
}
