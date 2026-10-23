package dev.poco.asistente.core;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.*;

public class DocumentCoreTest {
    private static final class DocumentStore extends TestStore {
        int sent;
        @Override public String enqueueDocument(String operation, Documents.Prepared document, long time) {
            sent++; String result = "Documento listo para enviar"; finishAction(operation, result); return result;
        }
    }
    private final DocumentStore store = new DocumentStore();
    private AssistantCore core(String tool, org.json.JSONObject args) {
        AssistantCore.Settings settings = new AssistantCore.Settings() {
            public String apiKey() { return "test-key"; }
            public String model() { return "test-model"; }
            public String currency() { return "PYG"; }
        };
        return new AssistantCore(store, settings, (key, model, body) -> new GeminiProtocol.Reply(null, tool, args, ""), new TestStore.MutableClock());
    }
    private Documents.Prepared prepared() { return new Documents.Prepared("a.pdf", "application/pdf", new byte[]{1}); }
    @Test public void multilinePdfIsQueuedOnceWithAuditWithoutConfirmation() throws Exception {
        AssistantCore core = core("armar_pdf", Json.object("titulo", "Nota", "contenido", "Primero\nSegundo"));
        core.setDocuments((tool, args, cancelled) -> { assertEquals("Primero\nSegundo", Documents.content(args)); return prepared(); });
        String result = core.receive("Armá un PDF", AssistantCore.Source.WHATSAPP_OWNER, "pdf1");
        assertEquals("Documento listo para enviar", result);
        assertEquals(result, core.receive("Armá un PDF", AssistantCore.Source.WHATSAPP_OWNER, "pdf1"));
        assertEquals(1, store.sent); assertEquals(result, store.receipt("whatsapp:pdf1"));
    }
    @Test public void inventedUrlNeverReachesDownloaderOrAudit() throws Exception {
        AssistantCore core = core("descargar", Json.object("url", "https://example.com/a.pdf"));
        core.setDocuments((tool, args, cancelled) -> { throw new AssertionError("Invented URL reached network"); });
        assertTrue(core.receive("Descargá lo de antes").contains("mensaje actual"));
        assertEquals(0, store.sent); assertTrue(store.receipts.isEmpty());
    }
    @Test public void stopInterruptsDownloadAndCannotQueueLateResult() throws Exception {
        AssistantCore core = core("descargar", Json.object("url", "https://example.com/a.pdf"));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        core.setDocuments((tool, args, cancelled) -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { /* Deliberately return late to exercise the enqueue guard. */ }
            return prepared();
        });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<String> request = worker.submit(() -> core.receive("Descargá https://example.com/a.pdf", AssistantCore.Source.WHATSAPP_OWNER, "download1"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            core.stop();
            request.get(2, TimeUnit.SECONDS);
            release.countDown();
            assertEquals(0, store.sent);
            assertTrue(store.receipt("whatsapp:download1").contains("cancelado"));
        } finally { release.countDown(); worker.shutdownNow(); }
    }
    @Test public void downloadWorksWhileChatModeIsActive() throws Exception {
        AssistantCore core = core("descargar", Json.object("url", "https://example.com/a.pdf"));
        core.setDocuments((tool, args, cancelled) -> prepared());
        core.receive("charlemos");
        assertEquals("Documento listo para enviar", core.receive("Descargá https://example.com/a.pdf"));
        assertEquals(1, store.sent);
    }
}
