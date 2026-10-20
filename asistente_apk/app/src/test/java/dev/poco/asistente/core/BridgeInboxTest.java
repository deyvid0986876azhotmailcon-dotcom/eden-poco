package dev.poco.asistente.core;

import dev.poco.asistente.bridge.BridgeInbox;
import dev.poco.asistente.messaging.MessageQueue;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import static org.junit.Assert.*;

public class BridgeInboxTest {
    private final TestStore store = new TestStore();
    private final ArrayDeque<Runnable> scheduled = new ArrayDeque<>();
    private final MessageQueue queue = new MessageQueue(scheduled::add);
    private final Set<String> ids = new HashSet<>();
    private final Object gate = new Object();
    private final AssistantCore core = new AssistantCore(store, new AssistantCore.Settings() {
        public String apiKey() { return ""; }
        public String model() { return GeminiProtocol.MODELS[0]; }
        public String currency() { return "ARS"; }
    }, (key, model, body) -> { throw new AssertionError(); }, new TestStore.MutableClock());
    private final BridgeInbox inbox = new BridgeInbox(core, queue, gate, () -> true, ids::add, event -> {}, this::stop);
    private void stop() { synchronized (gate) { core.stop(); queue.clear(); inbox.cancelPending(); } }
    private String receive(String id, String text) {
        CompletableFuture<String> response = inbox.accept(id, text);
        if (!scheduled.isEmpty()) scheduled.remove().run();
        return response.join();
    }
    @Test public void bridgeAndChatShareOneQueueAndDuplicateIdDoesNotWriteAgain() {
        queue.add(() -> core.receive("gasté 2 en local"));
        CompletableFuture<String> response = inbox.accept("one", "gasté 3 en puente");
        assertEquals(1, scheduled.size()); assertFalse(response.isDone()); scheduled.remove().run();
        assertTrue(response.join().startsWith("Anoté")); assertEquals(2, store.rows().size());
        assertTrue(inbox.accept("one", "gasté 3 en puente").join().contains("repetido")); assertEquals(2, store.rows().size());
    }
    @Test public void stopBypassesQueueAndCompletesCancelledWaiters() {
        CompletableFuture<String> first = inbox.accept("one", "gasté 3500 en super");
        CompletableFuture<String> stop = inbox.accept("two", "PARAR");
        assertTrue(stop.isDone()); assertTrue(first.join().contains("cancelado"));
        scheduled.remove().run(); assertTrue(store.rows().isEmpty());
    }
    @Test public void timeoutCancelsQueuedMutationsAndConfirmations() {
        CompletableFuture<String> first = inbox.accept("one", "gasté 3500 en super");
        inbox.expire(first); scheduled.remove().run();
        assertTrue(first.join().contains("cancelado")); assertTrue(store.rows().isEmpty());
    }
    @Test public void confirmationCodeBelongsToWhatsappActorAndStopInvalidatesIt() {
        receive("one", "gasté 3500 en super");
        String proposal = receive("two", "borrar " + store.rows().get(0).id);
        String code = proposal.substring(proposal.indexOf("SÍ ") + 3, proposal.indexOf("\n(vale"));
        assertTrue(core.receive("SÍ " + code).contains("no corresponde"));
        assertTrue(receive("three", "SÍ " + code).startsWith("Borré")); assertTrue(store.rows().isEmpty());
        receive("four", "gasté 3500 en super");
        proposal = receive("five", "borrar " + store.rows().get(0).id);
        code = proposal.substring(proposal.indexOf("SÍ ") + 3, proposal.indexOf("\n(vale"));
        receive("six", "PARAR"); assertFalse(receive("seven", "SÍ " + code).startsWith("Borré"));
        assertEquals(1, store.rows().size());
    }
    @Test public void maximumInputIdStillFitsCoreIdAfterNamespacing() {
        assertTrue(receive("x".repeat(120), "gasté 3500 en super").startsWith("Anoté"));
    }
    @Test public void stopAlsoRejectsAnOlderConnectionThatWasStillReadingItsBody() {
        long arrival = core.cancellationToken(); stop();
        assertTrue(inbox.accept("slow", "gasté 3500 en super", arrival).join().contains("cancelado"));
        assertTrue(scheduled.isEmpty()); assertTrue(store.rows().isEmpty());
    }
    @Test public void stopAndTimeoutPreventALateCloudMutation() throws Exception {
        for (boolean timeout : new boolean[]{false, true}) {
            java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
            java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
            TestStore otherStore = new TestStore(); MessageQueue otherQueue = new MessageQueue(worker);
            AssistantCore otherCore = new AssistantCore(otherStore, new AssistantCore.Settings() {
                public String apiKey() { return "test-key"; }
                public String model() { return GeminiProtocol.MODELS[0]; }
                public String currency() { return "ARS"; }
            }, (key, model, body) -> {
                entered.countDown();
                if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException();
                return new GeminiProtocol.Reply(null, "registrar_peso", Json.object("kg", 58.4, "fecha", "2026-10-17"), "");
            }, new TestStore.MutableClock());
            BridgeInbox[] holder = new BridgeInbox[1];
            holder[0] = new BridgeInbox(otherCore, otherQueue, gate, () -> true, new HashSet<String>()::add, event -> {}, () -> {
                synchronized (gate) { otherCore.stop(); otherQueue.clear(); holder[0].cancelPending(); }
            });
            try {
                CompletableFuture<String> response = holder[0].accept("cloud", "anotá que estoy pesando algo");
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                if (timeout) holder[0].expire(response); else holder[0].accept("stop", "PARAR");
                assertTrue(response.join().contains("cancelado"));
            } finally {
                release.countDown(); worker.shutdown();
                assertTrue(worker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertTrue(otherStore.rows().isEmpty());
        }
    }
}
