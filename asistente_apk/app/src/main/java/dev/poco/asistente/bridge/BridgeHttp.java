package dev.poco.asistente.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONObject;

/** A single bounded HTTP request. No sockets or Android dependencies; never echoes input in errors. */
public final class BridgeHttp {
    public static final int MAX_BODY = 16 * 1024, MAX_HEADERS = 8192;
    private BridgeHttp() {}
    public static final class Failure extends IOException {
        public final int status;
        public Failure(int status) { super("HTTP " + status); this.status = status; }
    }
    public static final class Request {
        public String whatsapp;
        public dev.poco.asistente.core.AudioInput audio;
        public final boolean backup, pending, confirm;
        public final java.util.List<String> ids;
        public final String id, text;
        Request(String path, String id, String text, java.util.List<String> ids) {
            this.id = id; this.text = text; this.ids = ids;
            backup = path.equals("/respaldo"); pending = path.equals("/pendientes"); confirm = path.equals("/pendientes/confirmar");
        }
    }
    public static Request read(InputStream input, String token) throws IOException {
        int[] budget = {MAX_HEADERS};
        String[] first = line(input, budget).split(" ", -1);
        if (first.length != 3 || !first[2].equals("HTTP/1.1")) throw new Failure(400);
        if (!first[0].equals("POST") && !first[0].equals("GET")) throw new Failure(405);
        if (!java.util.Arrays.asList("/audio", "/mensaje", "/respaldo", "/pendientes", "/pendientes/confirmar").contains(first[1])) throw new Failure(404);
        boolean readOnly = first[1].equals("/respaldo") || first[1].equals("/pendientes");
        if (!first[0].equals(readOnly ? "GET" : "POST")) throw new Failure(405);
        Map<String, String> headers = new HashMap<>();
        while (true) {
            String row = line(input, budget);
            if (row.isEmpty()) break;
            int colon = row.indexOf(':');
            if (colon <= 0 || !row.substring(0, colon).matches("[A-Za-z0-9!#$%&'*+.^_`|~-]+")) throw new Failure(400);
            String name = row.substring(0, colon).toLowerCase(Locale.ROOT);
            if (headers.put(name, row.substring(colon + 1).trim()) != null) throw new Failure(400);
        }
        if (!headers.containsKey("host") || headers.get("host").isEmpty()
                || headers.containsKey("transfer-encoding") || headers.containsKey("expect")) throw new Failure(400);
        String authorization = headers.getOrDefault("authorization", "");
        if (token == null || token.isEmpty() || !constantEquals("Bearer " + token, authorization)) throw new Failure(401);
        if (readOnly) {
            if (headers.containsKey("content-length") && !headers.get("content-length").equals("0")) throw new Failure(400);
            Request request = new Request(first[1], null, null, java.util.Collections.emptyList());
            if (request.pending) {
                request.whatsapp = headers.get("x-whatsapp");
                if (request.whatsapp != null && !BridgeHealth.valid(request.whatsapp)) throw new Failure(400);
            }
            return request;
        }
        String type = headers.getOrDefault("content-type", "");
        boolean audio = first[1].equals("/audio");
        if (audio) {
            try { type = dev.poco.asistente.core.AudioInput.mime(type); }
            catch (IllegalArgumentException e) { throw new Failure(415); }
        } else if (!type.matches("(?i)application/json(?:\\s*;\\s*charset=utf-8)?")) throw new Failure(415);
        String length = headers.get("content-length");
        if (length == null) throw new Failure(411);
        if (!length.matches("[0-9]{1,10}")) throw new Failure(400);
        long count = Long.parseLong(length);
        if (count > (audio ? dev.poco.asistente.core.AudioInput.MAX_BYTES : MAX_BODY)) throw new Failure(413);
        if (count == 0) throw new Failure(400);
        String audioId = headers.getOrDefault("x-message-id", "");
        double seconds = 0;
        if (audio) {
            String duration = headers.getOrDefault("x-audio-seconds", "");
            if (!audioId.matches("[A-Za-z0-9_-]{1,120}") || !duration.matches("[0-9]{1,3}(?:\\.[0-9]{1,3})?")) throw new Failure(400);
            seconds = Double.parseDouble(duration);
            if (seconds <= 0 || seconds > dev.poco.asistente.core.AudioInput.MAX_SECONDS) throw new Failure(413);
        }
        byte[] body = new byte[(int) count];
        int used = 0;
        while (used < body.length) {
            int read = input.read(body, used, body.length - used);
            if (read < 0) throw new Failure(400);
            if (read == 0) continue;
            used += read;
        }
        if (audio) {
            Request request = new Request(first[1], audioId, null, java.util.Collections.emptyList());
            request.audio = new dev.poco.asistente.core.AudioInput(body, type, seconds);
            return request;
        }
        String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException e) { throw new Failure(400); }
        try {
            org.json.JSONTokener reader = new org.json.JSONTokener(json);
            Object parsed = reader.nextValue();
            if (!(parsed instanceof JSONObject) || reader.nextClean() != 0) throw new Failure(400);
            JSONObject object = (JSONObject) parsed;
            if (first[1].equals("/pendientes/confirmar")) {
                if (object.length() != 1 || !(object.opt("ids") instanceof org.json.JSONArray)) throw new Failure(400);
                org.json.JSONArray rawIds = object.getJSONArray("ids");
                if (rawIds.length() < 1 || rawIds.length() > 20) throw new Failure(400);
                java.util.List<String> ids = new java.util.ArrayList<>();
                for (int i = 0; i < rawIds.length(); i++) {
                    Object value = rawIds.get(i);
                    if (!(value instanceof String) || !((String)value).matches("[A-Za-z0-9_-]{1,120}") || ids.contains(value)) throw new Failure(400);
                    ids.add((String)value);
                }
                return new Request(first[1], null, null, ids);
            }
            if (object.length() != 2 || !(object.get("id") instanceof String) || !(object.get("texto") instanceof String)) throw new Failure(400);
            String id = object.getString("id"), text = object.getString("texto");
            if (!id.matches("[A-Za-z0-9_-]{1,120}") || text.trim().isEmpty() || text.length() > 4000) throw new Failure(400);
            return new Request(first[1], id, text, java.util.Collections.emptyList());
        } catch (org.json.JSONException e) { throw new Failure(400); }
    }
    private static String line(InputStream input, int[] budget) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            if (--budget[0] < 0) throw new Failure(431);
            int c = input.read();
            if (c == '\r') {
                if (--budget[0] < 0) throw new Failure(431);
                if (input.read() != '\n') throw new Failure(400);
                return new String(line.toByteArray(), StandardCharsets.US_ASCII);
            }
            if (c < 32 || c > 126) throw new Failure(400);
            line.write(c);
        }
    }
    /** Both inputs are reduced to a fixed size before the constant-time comparison. */
    static boolean constantEquals(String expected, String actual) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return MessageDigest.isEqual(digest.digest(expected.getBytes(StandardCharsets.UTF_8)), digest.digest(actual.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    public static byte[] response(int status, String text) {
        String payload;
        try { payload = new JSONObject().put(status == 200 ? "respuesta" : "error", text).toString(); }
        catch (org.json.JSONException impossible) { throw new AssertionError(impossible); }
        return jsonResponse(status, payload);
    }
    public static byte[] jsonResponse(int status, String payload) {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        byte[] head = ("HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "Error") + "\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "
                + body.length + "\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] response = new byte[head.length + body.length];
        System.arraycopy(head, 0, response, 0, head.length); System.arraycopy(body, 0, response, head.length, body.length);
        return response;
    }
}
