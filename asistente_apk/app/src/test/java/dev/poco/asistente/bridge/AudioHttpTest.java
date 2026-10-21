package dev.poco.asistente.bridge;

import dev.poco.asistente.core.AudioInput;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class AudioHttpTest {
    private static final String TOKEN = "a".repeat(43);
    private String header(int length) {
        return "POST /audio HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer " + TOKEN
                + "\r\nContent-Type: audio/ogg; codecs=opus\r\nX-Message-Id: VOICE_1\r\nX-Audio-Seconds: 180"
                + "\r\nContent-Length: " + length + "\r\n\r\n";
    }
    private BridgeHttp.Request read(String headers, byte[] body) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(headers.getBytes(StandardCharsets.US_ASCII)); bytes.write(body);
        return BridgeHttp.read(new ByteArrayInputStream(bytes.toByteArray()), TOKEN);
    }
    private void reject(int status, String headers, byte[] body) {
        BridgeHttp.Failure error = assertThrows(BridgeHttp.Failure.class, () -> read(headers, body));
        assertEquals(status, error.status);
    }
    @Test public void binaryIsNotDecodedAsUtf8AndBoundaryIsAccepted() throws Exception {
        byte[] bytes = new byte[AudioInput.MAX_BYTES]; bytes[0] = (byte) 255;
        BridgeHttp.Request request = read(header(bytes.length), bytes);
        assertEquals("VOICE_1", request.id); assertNull(request.text);
        assertEquals("audio/ogg", request.audio.mime); assertEquals(180, request.audio.seconds, 0);
        assertArrayEquals(bytes, request.audio.bytes);
    }
    @Test public void authenticatesBeforeReadingMediaAndRejectsAmbiguousFraming() {
        byte[] empty = new byte[0];
        reject(401, header(10).replace(TOKEN, "wrong"), empty);
        reject(400, header(10).replace("Host:", "Content-Length: 10\r\nHost:"), empty);
        reject(400, header(10).replace("Host:", "Transfer-Encoding: chunked\r\nHost:"), empty);
        reject(400, header(10).replace("Host:", "X-Message-Id: OTHER\r\nHost:"), empty);
        reject(405, header(10).replace("POST", "GET"), empty);
    }
    @Test public void limitsAndMetadataAreCheckedBeforeAllocatingTheBody() {
        byte[] empty = new byte[0];
        reject(413, header(AudioInput.MAX_BYTES + 1), empty);
        reject(400, header(0), empty);
        reject(413, header(10).replace("Seconds: 180", "Seconds: 181"), empty);
        reject(413, header(10).replace("Seconds: 180", "Seconds: 0"), empty);
        reject(400, header(10).replace("Seconds: 180", "Seconds: NaN"), empty);
        reject(400, header(10).replace("X-Audio-Seconds: 180\r\n", ""), empty);
        reject(400, header(10).replace("VOICE_1", "../escape"), empty);
        reject(415, header(10).replace("audio/ogg; codecs=opus", "text/plain"), empty);
        reject(411, header(10).replace("Content-Length: 10\r\n", ""), empty);
        reject(400, header(10), new byte[9]);
    }
}
