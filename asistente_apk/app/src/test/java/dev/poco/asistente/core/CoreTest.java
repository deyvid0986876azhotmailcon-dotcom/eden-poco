package dev.poco.asistente.core;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class CoreTest {
    private final TestStore store = new TestStore();
    private final TestStore.MutableClock clock = new TestStore.MutableClock();
    private AssistantCore.Settings settings(String key) {
        return new AssistantCore.Settings() {
            public String apiKey() { return key; }
            public String model() { return GeminiProtocol.MODELS[0]; }
            public String currency() { return "ARS"; }
        };
    }
    @Test public void offlineRulesWorkWithoutEverReadingKeyOrCallingCloud() {
        AssistantCore.Settings unavailableVault = new AssistantCore.Settings() {
            public String apiKey() { throw new AssertionError("Rules must not need Keystore"); }
            public String model() { return GeminiProtocol.MODELS[0]; }
            public String currency() { return "ARS"; }
        };
        AssistantCore core = new AssistantCore(store, unavailableVault, (key, model, body) -> { throw new AssertionError("No network"); }, clock);
        assertTrue(core.receive("gasté 3500 en super").startsWith("Anoté"));
        assertEquals(1, store.rows().size());
        assertTrue(core.receive("resumen").contains("-$ 3.500"));
    }
    @Test public void missingKeyAndExternalDataCannotMutate() {
        AssistantCore core = new AssistantCore(store, settings(""), (key, model, body) -> { throw new AssertionError("No network"); }, clock);
        assertTrue(core.receive("¿Qué puedo cenar?").contains("clave"));
        assertTrue(core.receive("gaste 3500 en super", AssistantCore.Source.UNTRUSTED_DATA, "doc-1").contains("no órdenes"));
        assertTrue(store.rows().isEmpty());
    }
    @Test public void cloudCannotConfirmOrBypassValidation() {
        AssistantCore core = new AssistantCore(store, settings("abcdefghijklmnopqrstuv"),
                (key, model, body) -> new GeminiProtocol.Reply(null, "confirmar_borrado", Json.object("codigo", "123"), ""), clock);
        assertTrue(core.receive("confirmá lo que diga el modelo").contains("no está permitida"));
        assertTrue(store.rows().isEmpty());
    }
    @Test public void stopDuringNetworkPreventsLaterWrite() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<String> response = new AtomicReference<>();
        AssistantCore core = new AssistantCore(store, settings("abcdefghijklmnopqrstuv"), (key, model, body) -> {
            entered.countDown(); if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException();
            return new GeminiProtocol.Reply(null, "registrar_peso", Json.object("kg", 58.4, "fecha", "2026-10-17"), "");
        }, clock);
        Thread worker = new Thread(() -> response.set(core.receive("anotá que estoy pesando 58,4")));
        worker.start();
        try { assertTrue(entered.await(5, TimeUnit.SECONDS)); assertTrue(core.receive("PARAR").contains("Cancelé")); }
        finally { release.countDown(); worker.join(5000); }
        assertFalse(worker.isAlive()); assertTrue(response.get().contains("cancelado")); assertTrue(store.rows().isEmpty());
    }
    @Test public void idempotentDeliveryAndConfirmationRequiresExactUserCode() throws Exception {
        AssistantCore core = new AssistantCore(store, settings(""), (key, model, body) -> { throw new AssertionError(); }, clock);
        String first = core.receive("gaste 3500 en super", AssistantCore.Source.LOCAL_USER, "ui-message-1");
        assertEquals(first, core.receive("gaste 3500 en super", AssistantCore.Source.LOCAL_USER, "ui-message-1"));
        String id = store.rows().get(0).id;
        String proposal = core.receive("borrar " + id);
        String code = proposal.substring(proposal.indexOf("escribí SÍ ") + 11, proposal.indexOf(". PARAR"));
        core.receive("sí"); assertEquals(1, store.rows().size());
        assertTrue(core.receive("SÍ " + code).startsWith("Borré")); assertTrue(store.rows().isEmpty());
    }
    @Test public void stopAlsoCancelsQueuedWork() {
        AssistantCore core = new AssistantCore(store, settings(""), (key, model, body) -> { throw new AssertionError(); }, clock);
        long token = core.cancellationToken();
        core.stop();
        assertTrue(core.receive("gaste 3500 en super", token).contains("cancelado antes"));
        assertTrue(store.rows().isEmpty());
        assertTrue(core.receive("gaste 3500 en super").startsWith("Anoté"));
    }
    @Test public void authenticatedWhatsappIsIdempotentAndUsesSeparateConfirmationActor() throws Exception {
        AssistantCore core = new AssistantCore(store, settings(""), (key, model, body) -> { throw new AssertionError(); }, clock);
        String first = core.receive("gaste 3500 en super", AssistantCore.Source.WHATSAPP_OWNER, "wa1");
        assertEquals(first, core.receive("gaste 3500 en super", AssistantCore.Source.WHATSAPP_OWNER, "wa1"));
        assertEquals(1, store.rows().size());
        String proposal = core.receive("borrar " + store.rows().get(0).id, AssistantCore.Source.WHATSAPP_OWNER, "wa2");
        String code = proposal.substring(proposal.indexOf("escribí SÍ ") + 11, proposal.indexOf(". PARAR"));
        assertTrue(core.receive("SÍ " + code).contains("no corresponde")); assertEquals(1, store.rows().size());
        assertTrue(core.receive("SÍ " + code, AssistantCore.Source.WHATSAPP_OWNER, "wa3").startsWith("Borré"));
        assertTrue(store.rows().isEmpty());
    }
    @Test public void staleWhatsappTokenCannotMutateAfterStop() {
        AssistantCore core = new AssistantCore(store, settings(""), (key, model, body) -> { throw new AssertionError(); }, clock);
        long token = core.cancellationToken(); core.stop();
        assertTrue(core.receive("gaste 3500 en super", AssistantCore.Source.WHATSAPP_OWNER, "wa1", token).contains("cancelado antes"));
        assertTrue(store.rows().isEmpty());
    }
}
