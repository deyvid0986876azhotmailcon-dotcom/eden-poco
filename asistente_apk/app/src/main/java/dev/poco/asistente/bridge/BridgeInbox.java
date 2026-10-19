package dev.poco.asistente.bridge;

import dev.poco.asistente.core.AssistantCore;
import dev.poco.asistente.core.Rules;
import dev.poco.asistente.messaging.MessageQueue;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Authenticated ingress; shares the chat queue/generation, with confirmations owned by the WhatsApp actor. */
public final class BridgeInbox {
    private final AssistantCore core;
    private final MessageQueue queue;
    private final Object gate;
    private final BooleanSupplier enabled;
    private final Predicate<String> claim;
    private final Consumer<String> log;
    private final Runnable stop;
    private final Map<String, CompletableFuture<String>> pending = new HashMap<>();
    public BridgeInbox(AssistantCore core, MessageQueue queue, Object gate, BooleanSupplier enabled,
                       Predicate<String> claim, Consumer<String> log, Runnable stop) {
        this.core = core; this.queue = queue; this.gate = gate; this.enabled = enabled;
        this.claim = claim; this.log = log; this.stop = stop;
    }
    public CompletableFuture<String> accept(String id, String text) {
        return accept(id, text, core.cancellationToken());
    }
    public CompletableFuture<String> accept(String id, String text, long arrivalToken) {
        synchronized (gate) {
            if (!enabled.getAsBoolean()) throw new IllegalStateException("Puente apagado");
            // Reserve room for PARAR even when the ordinary queue is full.
            boolean isStop = Rules.normalize(text).equals("parar");
            if (!isStop && arrivalToken != core.cancellationToken()) return CompletableFuture.completedFuture("Pedido cancelado antes de empezar.");
            if (!isStop && pending.size() >= 6) throw new IllegalStateException("Cola llena");
            String requestId = requestId(id);
            if (!claim.test(requestId)) return CompletableFuture.completedFuture("Mensaje repetido; no se ejecutó otra vez. Consultá Resumen.");
            log.accept("Aceptado");
            if (isStop) {
                stop.run();
                return CompletableFuture.completedFuture("Cola vaciada y confirmaciones canceladas. No se deshacen acciones ya terminadas.");
            }
            long ticket = arrivalToken;
            CompletableFuture<String> result = new CompletableFuture<>();
            pending.put(requestId, result);
            queue.add(() -> {
                try {
                    synchronized (gate) {
                        if (result.isDone() || !enabled.getAsBoolean() || ticket != core.cancellationToken()) {
                            result.complete("Pedido cancelado antes de empezar."); return;
                        }
                    }
                    String response = core.receive(text, AssistantCore.Source.WHATSAPP_OWNER, requestId, ticket);
                    synchronized (gate) {
                        result.complete(ticket == core.cancellationToken() ? response : "Pedido cancelado; consultá Resumen.");
                    }
                } catch (RuntimeException e) { result.completeExceptionally(e); }
                finally { synchronized (gate) { pending.remove(requestId); } }
            });
            return result;
        }
    }
    /** Called by app.stop while holding the same gate, including the chat's PARAR button. */
    public void cancelPending() {
        synchronized (gate) {
            for (CompletableFuture<String> result : pending.values()) result.complete("Pedido cancelado; consultá Resumen por acciones ya terminadas.");
            pending.clear();
        }
    }
    /** A timed-out cloud result must not mutate the database later. Cancel the shared generation. */
    public void expire(CompletableFuture<String> result) {
        synchronized (gate) { if (!result.isDone()) stop.run(); }
    }
    private static String requestId(String id) {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256").digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder("bridge_");
            for (byte b : bytes) key.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return key.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
