package dev.poco.asistente.bridge;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class BridgeHttpTest {
    private static final String TOKEN = "a".repeat(43);
    private static final String BODY = "{\"id\":\"ONE\",\"texto\":\"Gasté 3500 en super\"}";
    private String request(String body) {
        return "POST /mensaje HTTP/1.1\r\nHost: 127.0.0.1:8765\r\nAuthorization: Bearer " + TOKEN
                + "\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
    }
    private BridgeHttp.Request read(String raw) throws Exception {
        return BridgeHttp.read(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)), TOKEN);
    }
    private void rejected(int status, String raw) throws Exception {
        try { read(raw); fail("Request should be rejected"); }
        catch (BridgeHttp.Failure e) { assertEquals(status, e.status); }
    }
    @Test public void acceptsUtf8TextAndCaseInsensitiveHeaderNames() throws Exception {
        BridgeHttp.Request result = read(request(BODY).replace("Authorization:", "authorization:"));
        assertEquals("ONE", result.id); assertEquals("Gasté 3500 en super", result.text);
    }
    @Test public void onlyPostAndExactPathAreAllowed() throws Exception {
        rejected(405, request(BODY).replace("POST ", "GET "));
        rejected(404, request(BODY).replace("/mensaje ", "/mensaje?x=1 "));
        rejected(404, request(BODY).replace("/mensaje ", "http://127.0.0.1/mensaje "));
        rejected(400, request(BODY).replace("HTTP/1.1", "HTTP/1.0"));
    }
    @Test public void tokenIsRequiredAndMustMatchCompletely() throws Exception {
        rejected(401, request(BODY).replace("Authorization: Bearer " + TOKEN + "\r\n", ""));
        rejected(401, request(BODY).replace(TOKEN, "b".repeat(43)));
        rejected(401, request(BODY).replace(TOKEN, TOKEN + "extra"));
        assertTrue(BridgeHttp.constantEquals(TOKEN, TOKEN)); assertFalse(BridgeHttp.constantEquals(TOKEN, ""));
    }
    @Test public void bodyLimitIsBytesAndIncludesExactBoundary() throws Exception {
        String padded = BODY + " ".repeat(BridgeHttp.MAX_BODY - BODY.getBytes(StandardCharsets.UTF_8).length);
        assertEquals("ONE", read(request(padded)).id);
        rejected(413, request(padded + " "));
        rejected(413, request(BODY).replace("Content-Length: " + BODY.getBytes(StandardCharsets.UTF_8).length, "Content-Length: 9999999999"));
    }
    @Test public void rejectsAmbiguousFramingBeforeReadingBody() throws Exception {
        rejected(400, request(BODY).replace("Host:", "Content-Length: 1\r\nHost:"));
        rejected(400, request(BODY).replace("Host:", "Transfer-Encoding: chunked\r\nHost:"));
        rejected(400, request(BODY).replace("Host:", "Expect: 100-continue\r\nHost:"));
        rejected(400, request(BODY).replace("Host:", "Authorization: Bearer anything\r\nHost:"));
        rejected(400, request(BODY).replace("Content-Length: ", "Content-Length: -"));
        rejected(411, request(BODY).replaceAll("Content-Length: [0-9]+\r\n", ""));
    }
    @Test public void rejectsMalformedLinesAndOversizedHeaders() throws Exception {
        rejected(400, request(BODY).replace("\r\n", "\n"));
        rejected(400, request(BODY).replace("Authorization:", " Authorization:"));
        rejected(431, request(BODY).replace("Host:", "X-Padding: " + "x".repeat(BridgeHttp.MAX_HEADERS) + "\r\nHost:"));
    }
    @Test public void requiresCompleteJsonStringsAndValidIds() throws Exception {
        for (String body : new String[]{"{}", "[]", "{\"id\":1,\"texto\":\"hola\"}", "{\"id\":\"x\",\"texto\":true}",
                "{\"id\":\"../x\",\"texto\":\"hola\"}", "{\"id\":\"x\",\"texto\":\" \"}", BODY + "{}",
                "{\"id\":\"x\",\"texto\":\"hola\",\"extra\":1}", "{\"id\":\"x\",\"texto\":\"" + "x".repeat(4001) + "\"}"}) rejected(400, request(body));
        rejected(400, request(BODY).substring(0, request(BODY).length() - 1));
        rejected(415, request(BODY).replace("application/json; charset=utf-8", "text/plain"));
    }
    @Test public void malformedUtf8IsNotSilentlyReplaced() throws Exception {
        byte[] raw = request(BODY).getBytes(StandardCharsets.UTF_8); raw[raw.length - 3] = (byte) 0xff;
        try { BridgeHttp.read(new ByteArrayInputStream(raw), TOKEN); fail(); }
        catch (BridgeHttp.Failure e) { assertEquals(400, e.status); }
    }
    @Test public void responseUsesByteLengthAndClosesConnection() {
        String response = new String(BridgeHttp.response(200, "Sí 😀"), StandardCharsets.UTF_8);
        String[] parts = response.split("\r\n\r\n", 2);
        assertTrue(parts[0].contains("Content-Length: " + parts[1].getBytes(StandardCharsets.UTF_8).length));
        assertTrue(parts[0].contains("Connection: close")); assertTrue(parts[1].contains("respuesta"));
    }
    private String backupRequest(String headers) {
        return "GET /respaldo HTTP/1.1\r\nHost: 127.0.0.1:8765\r\nAuthorization: Bearer " + TOKEN + "\r\n" + headers + "\r\n";
    }
    @Test public void backupIsAuthenticatedAndHasNoBody() throws Exception {
        assertTrue(read(backupRequest("")).backup);
        assertTrue(read(backupRequest("Content-Length: 0\r\n")).backup);
        assertFalse(read(request(BODY)).backup);
        rejected(401, backupRequest("").replace(TOKEN, "wrong"));
        rejected(401, backupRequest("").replace("Authorization: Bearer " + TOKEN + "\r\n", ""));
        rejected(400, backupRequest("Content-Length: 1\r\n") + "x");
        rejected(400, backupRequest("Content-Length: -1\r\n"));
        rejected(400, backupRequest("Content-Length: 0\r\nContent-Length: 0\r\n"));
        rejected(400, backupRequest("Transfer-Encoding: chunked\r\n"));
        rejected(400, backupRequest("Expect: 100-continue\r\n"));
        rejected(405, backupRequest("").replace("GET ", "POST "));
        rejected(404, backupRequest("").replace("/respaldo ", "/respaldo?x=1 "));
        rejected(404, backupRequest("").replace("/respaldo ", "http://127.0.0.1/respaldo "));
    }
}
