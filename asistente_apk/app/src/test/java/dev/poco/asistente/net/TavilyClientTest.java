package dev.poco.asistente.net;

import dev.poco.asistente.core.ExternalData;
import dev.poco.asistente.core.SearchProtocol;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class TavilyClientTest {
    private static final class Connection extends HttpURLConnection {
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        byte[] response = "{\"answer\":\"Public fact\"}".getBytes(StandardCharsets.UTF_8);
        int status = 200; long declaredSize = -1;
        boolean disconnected, streamRead;
        Connection() throws Exception { super(new URL("https://api.tavily.com/search")); }
        public void connect() {}
        public boolean usingProxy() { return false; }
        public void disconnect() { disconnected = true; }
        public OutputStream getOutputStream() { return sent; }
        public int getResponseCode() { return status; }
        public long getContentLengthLong() { return declaredSize; }
        public InputStream getInputStream() { streamRead = true; return new ByteArrayInputStream(response); }
    }
    @Test public void fixedHttpsPostHasKeylessHeaderTimeoutsNoRedirectsOrPrivateData() throws Exception {
        Connection connection = new Connection();
        TavilyClient client = new TavilyClient(url -> {
            assertEquals("https://api.tavily.com/search", url.toString()); return connection;
        });
        assertTrue(ExternalData.isExternal(client.search("lunas de Saturno")));
        assertEquals("POST", connection.getRequestMethod()); assertTrue(connection.getDoOutput());
        assertEquals("application/json; charset=UTF-8", connection.getRequestProperty("Content-Type"));
        assertEquals("keyless", connection.getRequestProperty("X-Tavily-Access-Mode"));
        assertEquals(2, connection.getRequestProperties().size());
        assertFalse(connection.getInstanceFollowRedirects()); assertFalse(connection.getUseCaches());
        assertEquals(10000, connection.getConnectTimeout());
        assertTrue(connection.getReadTimeout() > 0 && connection.getReadTimeout() <= 10000);
        JSONObject sent = new JSONObject(connection.sent.toString("UTF-8"));
        assertEquals(4, sent.length()); assertEquals("lunas de Saturno", sent.getString("query"));
        assertEquals("basic", sent.getString("search_depth")); assertEquals(5, sent.getInt("max_results")); assertTrue(sent.getBoolean("include_answer"));
        assertFalse(sent.toString().contains("David")); assertTrue(connection.disconnected);
    }
    @Test public void failedStatusAndRedirectNeverReadOrExposeTheServerBody() throws Exception {
        for (int status : new int[]{301, 302, 307, 308, 401, 429, 500, 503}) {
            Connection connection = new Connection(); connection.status = status;
            Exception error = assertThrows(IllegalArgumentException.class, () -> new TavilyClient(url -> connection).search("Saturno"));
            assertEquals(SearchProtocol.FAILURE, error.getMessage()); assertTrue(connection.disconnected); assertFalse(connection.streamRead);
        }
    }
    @Test public void oversizedKnownAndUnknownLengthsAreRejected() throws Exception {
        Connection declared = new Connection(); declared.declaredSize = SearchProtocol.MAX_BYTES + 1L;
        assertEquals(SearchProtocol.FAILURE, assertThrows(IllegalArgumentException.class,
                () -> new TavilyClient(url -> declared).search("Saturno")).getMessage());
        assertFalse(declared.streamRead); assertTrue(declared.disconnected);
        Connection streamed = new Connection(); streamed.response = new byte[SearchProtocol.MAX_BYTES + 1];
        assertEquals(SearchProtocol.FAILURE, assertThrows(IllegalArgumentException.class,
                () -> new TavilyClient(url -> streamed).search("Saturno")).getMessage());
        assertTrue(streamed.disconnected);
    }
    @Test public void networkAndInvalidJsonUseOnlyNaturalFailureMessage() throws Exception {
        assertEquals(SearchProtocol.FAILURE, assertThrows(IllegalArgumentException.class,
                () -> new TavilyClient(url -> { throw new IOException("technical secret"); }).search("Saturno")).getMessage());
        Connection connection = new Connection(); connection.response = "<html>HTTP 500 secret</html>".getBytes(StandardCharsets.UTF_8);
        assertEquals(SearchProtocol.FAILURE, assertThrows(IllegalArgumentException.class,
                () -> new TavilyClient(url -> connection).search("Saturno")).getMessage());
        assertTrue(connection.disconnected);
    }
}
