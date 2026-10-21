package dev.poco.asistente.core;

import dev.poco.asistente.bridge.BridgeInbox;
import dev.poco.asistente.messaging.MessageQueue;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import static org.junit.Assert.*;

public class AudioInboxTest {
    private final TestStore store = new TestStore();
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private final MessageQueue queue = new MessageQueue(tasks::add);
    private final Object gate = new Object();
    private AssistantCore core;
    private BridgeInbox inbox;
    private static final AudioInput AUDIO = new AudioInput(new byte[]{1, 2}, "audio/ogg", 1);
    private void setup(String keyValue, Cloud cloud) {
        core = new AssistantCore(store, new AssistantCore.Settings() {
            public String apiKey() { return keyValue; }
            public String model() { return GeminiProtocol.MODELS[0]; }
            public String currency() { return "PYG"; }
        }, cloud, new TestStore.MutableClock());
        inbox = new BridgeInbox(core, queue, gate, () -> true, new HashSet<String>()::add, event -> {}, () -> {
            synchronized (gate) { core.stop(); queue.clear(); inbox.cancelPending(); }
        });
    }
    private GeminiProtocol.Reply transcript(String value) { return new GeminiProtocol.Reply(value, null, null, ""); }
    @Test public void audioRunsExistingRulesSavesTextHistoryAndSharesTextDedup() {
        int[] calls = {0};
        setup("key", (key, model, body) -> { calls[0]++; assertFalse(body.has("tools")); return transcript("gasté 25000 en almuerzo"); });
        CompletableFuture<String> result = inbox.acceptAudio("one", AUDIO, core.cancellationToken());
        tasks.remove().run(); assertTrue(result.join().startsWith("Anoté"));
        assertEquals(1, store.rows().size()); assertEquals(1, calls[0]);
        assertEquals("gasté 25000 en almuerzo", store.conversation().get(0).user);
        assertTrue(inbox.accept("one", "gasté 25000 en almuerzo").join().contains("repetido"));
        assertEquals(1, store.rows().size());
    }
    @Test public void missingKeyQuotaAndUnintelligibleAudioNeverMutate() {
        for (int mode = 0; mode < 3; mode++) {
            final int failure = mode;
            setup(mode == 0 ? "" : "key", (key, model, body) -> {
                if (failure == 0) throw new AssertionError("No request without a key");
                if (failure == 1) throw new IllegalArgumentException("429");
                return transcript("[inaudible]");
            });
            CompletableFuture<String> result = inbox.acceptAudio("fail", AUDIO, core.cancellationToken());
            tasks.remove().run(); assertEquals(AudioInput.FALLBACK, result.join());
            assertTrue(store.rows().isEmpty()); assertTrue(store.conversation().isEmpty());
        }
    }
    @Test public void spokenStopCancelsFollowingWorkBeforeAcknowledging() {
        setup("key", (key, model, body) -> transcript("Parar."));
        long ticket = core.cancellationToken();
        CompletableFuture<String> stop = inbox.acceptAudio("stop", AUDIO, ticket);
        CompletableFuture<String> later = inbox.accept("later", "gasté 25000 en almuerzo");
        tasks.remove().run();
        assertTrue(BridgeInbox.wasStopped(stop)); assertTrue(stop.join().contains("Cola vaciada"));
        assertTrue(later.join().contains("cancelado")); assertNotEquals(ticket, core.cancellationToken());
        assertTrue(store.rows().isEmpty());
    }
    @Test public void typedStopDuringTranscriptionPreventsLateMutation() {
        setup("key", (key, model, body) -> {
            inbox.accept("stop", "PARAR"); return transcript("gasté 25000 en almuerzo");
        });
        CompletableFuture<String> result = inbox.acceptAudio("one", AUDIO, core.cancellationToken());
        tasks.remove().run(); assertTrue(result.join().contains("cancelado"));
        assertTrue(store.rows().isEmpty()); assertTrue(store.conversation().isEmpty());
    }
    @Test public void transcriptReachesContextAndToolExecutorWithoutAudioInHistory() throws Exception {
        store.exchange("previous", "HISTORIAL_PREVIO", "Respuesta anterior");
        int[] calls = {0};
        setup("key", (key, model, body) -> {
            if (++calls[0] == 1) {
                assertFalse(body.toString().contains("HISTORIAL_PREVIO"));
                return transcript("anotá que estoy pesando algo");
            }
            assertTrue(body.has("tools")); assertTrue(body.toString().contains("HISTORIAL_PREVIO"));
            assertFalse(body.toString().contains("inlineData"));
            return new GeminiProtocol.Reply(null, "registrar_peso", Json.object("kg", 58.4, "fecha", "2026-10-17"), "");
        });
        CompletableFuture<String> result = inbox.acceptAudio("weight", AUDIO, core.cancellationToken());
        tasks.remove().run(); result.join();
        assertEquals(2, calls[0]); assertEquals(1, store.rows().size());
        assertEquals("anotá que estoy pesando algo", store.conversation().get(1).user);
    }
    @Test public void spokenDeletionStillRequiresExactCodeFromWhatsappActor() {
        String[] spoken = {"gasté 25000 en almuerzo"};
        setup("key", (key, model, body) -> transcript(spoken[0]));
        CompletableFuture<String> first = inbox.acceptAudio("first", AUDIO, core.cancellationToken());
        tasks.remove().run(); first.join();
        spoken[0] = "borrar " + store.rows().get(0).id;
        CompletableFuture<String> proposal = inbox.acceptAudio("delete", AUDIO, core.cancellationToken());
        tasks.remove().run();
        java.util.regex.Matcher code = java.util.regex.Pattern.compile("SÍ ([a-f0-9]{16})").matcher(proposal.join());
        assertTrue(code.find()); assertEquals(1, store.rows().size());
        assertTrue(core.receive("SÍ " + code.group(1)).contains("no corresponde"));
        CompletableFuture<String> confirm = inbox.accept("confirm", "SÍ " + code.group(1));
        tasks.remove().run(); confirm.join(); assertTrue(store.rows().isEmpty());
    }
}
