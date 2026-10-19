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
        public final String id, text;
        Request(String id, String text) { this.id = id; this.text = text; }
    }
    public static Request read(InputStream input, String token) throws IOException {
        int[] budget = {MAX_HEADERS};
        String[] first = line(input, budget).split(" ", -1);
        if (first.length != 3 || !first[2].equals("HTTP/1.1")) throw new Failure(400);
        if (!first[0].equals("POST")) throw new Failure(405);
        if (!first[1].equals("/mensaje")) throw new Failure(404);
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
        String type = headers.getOrDefault("content-type", "");
        if (!type.matches("(?i)application/json(?:\\s*;\\s*charset=utf-8)?")) throw new Failure(415);
        String length = headers.get("content-length");
        if (length == null) throw new Failure(411);
        if (!length.matches("[0-9]{1,10}")) throw new Failure(400);
        long count = Long.parseLong(length);
        if (count > MAX_BODY) throw new Failure(413);
        if (count == 0) throw new Failure(400);
        byte[] body = new byte[(int) count];
        int used = 0;
        while (used < body.length) {
            int read = input.read(body, used, body.length - used);
            if (read < 0) throw new Failure(400);
            if (read == 0) continue;
            used += read;
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
            if (object.length() != 2 || !(object.get("id") instanceof String) || !(object.get("texto") instanceof String)) throw new Failure(400);
            String id = object.getString("id"), text = object.getString("texto");
            if (!id.matches("[A-Za-z0-9_-]{1,120}") || text.trim().isEmpty() || text.length() > 4000) throw new Failure(400);
            return new Request(id, text);
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
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        byte[] head = ("HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "Error") + "\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "
                + body.length + "\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] response = new byte[head.length + body.length];
        System.arraycopy(head, 0, response, 0, head.length); System.arraycopy(body, 0, response, head.length, body.length);
        return response;
    }
}
