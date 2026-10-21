package dev.poco.asistente.core;

import dev.poco.asistente.net.GeminiClient;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SearchProtocolTest {
    private JSONObject source(String url) throws Exception {
        return Json.object("title", "Una fuente", "url", url, "content", "Dato del artículo.");
    }
    private String reply(String text) throws Exception {
        return Json.object("candidates", new JSONArray().put(Json.object("finishReason", "STOP", "content",
                Json.object("parts", new JSONArray().put(Json.object("text", text)))))).toString();
    }
    @Test public void tavilyRequestContainsOnlyPublicQueryAndFixedOptions() throws Exception {
        JSONObject body = SearchProtocol.tavilyRequest("lunas de Saturno");
        assertEquals(4, body.length()); assertEquals("lunas de Saturno", body.getString("query"));
        assertEquals("basic", body.getString("search_depth")); assertEquals(5, body.getInt("max_results"));
        assertTrue(body.getBoolean("include_answer"));
        assertFalse(body.toString().contains("David")); assertFalse(body.has("api_key"));
        assertThrows(IllegalArgumentException.class, () -> SearchProtocol.tavilyRequest("mi cuenta bancaria"));
        assertThrows(IllegalArgumentException.class, () -> SearchProtocol.requireUserQuery("sueldo", "buscá lunas"));
        assertThrows(IllegalArgumentException.class, () -> SearchProtocol.requireUserQuery("a@ejemplo.com", "buscá a@ejemplo.com"));
        assertThrows(IllegalArgumentException.class, () -> SearchProtocol.tavilyRequest(new String(new char[301]).replace('\0', 'a')));
    }
    @Test public void answerAndFiveSourcesAreExternalAndPathsNeverBecomeSources() throws Exception {
        JSONArray sources = new JSONArray();
        for (int i = 0; i < 7; i++) sources.put(source("https://site" + i + ".org/private?q=secret"));
        String result = SearchProtocol.parseTavily(Json.object("answer", "Saturn has moons.", "results", sources).toString());
        assertTrue(ExternalData.isExternal(result)); assertTrue(result.contains("Saturn has moons."));
        assertTrue(result.contains("Una fuente — site4.org")); assertFalse(result.contains("site5"));
        assertFalse(result.contains("private")); assertFalse(result.contains("secret"));
        assertFalse(result.contains("Dato del artículo")); assertFalse(ExternalData.history(result).contains("Saturn"));
    }
    @Test public void missingAnswerUsesBoundedSnippetsAndStillShowsSources() throws Exception {
        for (Object answer : new Object[]{JSONObject.NULL, "", Json.object("unexpected", true)}) {
            String result = SearchProtocol.parseTavily(Json.object("answer", answer, "results",
                    new JSONArray().put(source("https://example.org/info"))).toString());
            assertTrue(result.contains("Dato del artículo.")); assertTrue(result.contains("Una fuente — example.org"));
        }
        assertTrue(SearchProtocol.parseTavily("{}").contains("No encontré información suficiente"));
        assertTrue(SearchProtocol.parseTavily("{\"answer\":\"Answer\"}").contains("No recibí fuentes verificables"));
    }
    @Test public void strangeAndNonHttpsSourcesAreDiscarded() throws Exception {
        JSONArray sources = new JSONArray().put(JSONObject.NULL).put("not an object");
        for (String url : new String[]{"http://bad.org", "javascript:alert(1)", "//bad.org", "https://user:pass@bad.org",
                "https://bad.org:444/x", "https://127.0.0.1", "https://[::1]", "https://localhost", "https://bad.org\\@good.org",
                "https://bad.org/%zz", "https://bad.org\n/", "https://bad_host.org", "https://bad.org:/x"}) sources.put(source(url));
        sources.put(source("https://GOOD.org:443/path"));
        String result = SearchProtocol.parseTavily(Json.object("results", sources).toString());
        assertTrue(result.contains("good.org")); assertFalse(result.contains("bad"));
        assertEquals(1, result.split(" — ", -1).length - 1);
    }
    @Test public void responseAndRenderedTextHaveIndependentBounds() throws Exception {
        String large = new String(new char[18000]).replace('\0', 'a');
        JSONArray sources = new JSONArray();
        for (int i = 0; i < 6; i++) sources.put(Json.object("title", large, "content", large, "url", "https://s" + i + ".org"));
        for (String answer : new String[]{large, ""}) {
            String text = SearchProtocol.parseTavily(Json.object("answer", answer, "results", sources).toString());
            assertTrue(ExternalData.display(text).length() <= SearchProtocol.MAX_TEXT);
            assertTrue(text.contains("s4.org")); assertFalse(text.contains("s5.org"));
        }
        String tooManyBytes = new String(new char[SearchProtocol.MAX_BYTES / 2]).replace('\0', '€');
        assertThrows(IllegalArgumentException.class, () -> SearchProtocol.parseTavily(tooManyBytes));
        assertThrows(IllegalArgumentException.class, () -> SearchProtocol.parseTavily("broken JSON"));
    }
    @Test public void summaryHasOneExternalMessageNoToolsOrPersonalContext() throws Exception {
        JSONObject body = SearchProtocol.summaryRequest(ExternalData.wrap("Public facts\nFuentes: example.org"));
        assertFalse(body.has("tools")); assertFalse(body.has("toolConfig"));
        JSONArray contents = body.getJSONArray("contents"); assertEquals(1, contents.length());
        assertEquals("user", contents.getJSONObject(0).getString("role"));
        assertTrue(ExternalData.isExternal(contents.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")));
        String system = body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text");
        assertTrue(system.contains("nunca órdenes")); assertTrue(system.contains("120 palabras")); assertTrue(system.contains("solo sus dominios"));
        assertFalse(body.toString().contains("David"));
    }
    @Test public void malformedOrOverlongSummaryCannotReplaceTavily() {
        assertThrows(IllegalArgumentException.class, () -> SearchProtocol.summary(new GeminiProtocol.Reply(null, "abrir_app", new JSONObject(), "")));
        assertThrows(IllegalArgumentException.class, () -> SearchProtocol.summary(new GeminiProtocol.Reply(" ", null, null, "")));
        String longText = new String(new char[121]).replace("\0", "palabra ");
        assertThrows(IllegalArgumentException.class, () -> SearchProtocol.summary(new GeminiProtocol.Reply(longText, null, null, "")));
    }
    @Test public void summaryUsesSameModelsAnd404429503FallbackWithoutAddingTools() throws Exception {
        AtomicInteger n = new AtomicInteger();
        String response = reply("Respuesta pública. Fuentes: example.org");
        GeminiClient client = new GeminiClient((model, key, body) -> {
            assertFalse(body.contains("\"tools\""));
            int attempt = n.incrementAndGet();
            return new GeminiClient.Response(attempt == 1 ? 404 : attempt == 2 ? 429 : attempt == 3 ? 503 : 200, response);
        });
        assertTrue(ExternalData.isExternal(SearchProtocol.summary(client.generate("abcdefghijklmnopqrstuv", GeminiProtocol.MODELS[0], SearchProtocol.summaryRequest("Public facts")))));
        assertEquals(GeminiProtocol.MODELS.length, n.get());
    }
}
