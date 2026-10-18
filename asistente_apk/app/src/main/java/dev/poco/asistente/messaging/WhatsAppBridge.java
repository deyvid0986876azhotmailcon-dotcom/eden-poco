package dev.poco.asistente.messaging;

import android.content.Context;
import dev.poco.asistente.AssistantApplication;
import dev.poco.asistente.core.AssistantCore;
import dev.poco.asistente.core.Rules;
import java.util.List;

/** Ingress/SQLite never wait behind Gemini. All model work shares the app's one message queue. */
public final class WhatsAppBridge {
    public interface Reply { void send(Context context, String text) throws Exception; }
    private final AssistantApplication app;
    private final NoticeDeduplicator deduplicator;
    public WhatsAppBridge(AssistantApplication app) {
        this.app = app;
        deduplicator = new NoticeDeduplicator(app.db::claimNotice);
        app.io.execute(() -> {
            try { app.db.recoverNotices(); app.changed(); }
            catch (RuntimeException e) { app.messagingError(); }
        });
    }
    public void systemLog(String event) {
        app.io.execute(() -> {
            try { log("Sistema", event); } catch (RuntimeException e) { app.messagingError(); }
        });
    }
    public void accept(NotificationPolicy.Notice notice, Reply reply) {
        long arrivalToken = app.core.cancellationToken();
        app.io.execute(() -> {
            try {
                synchronized (app.controlGate) {
                    if (!app.settings.whatsappEnabled() || arrivalToken != app.core.cancellationToken()) return;
                    NotificationPolicy.Decision decision = NotificationPolicy.evaluate(notice, app.settings.whatsappName(), app.settings.whatsappPhone());
                    String sender = NotificationPolicy.logSender(notice);
                    if (!decision.accepted()) { log(sender, "Rechazado: " + decision.reason); return; }
                    String id = deduplicator.claim(notice.key, decision.message, sender);
                    if (id == null) return;
                    log(sender, "Aceptado: " + decision.identity);
                    if (Rules.normalize(decision.message.text).equals("parar")) {
                        app.stop(); // bypass the model queue; already authenticated and deduplicated
                        app.db.noticeState(id, "pendiente", "Cola vaciada y respuesta en curso cancelada.");
                        deliver(id, sender, "Cola vaciada y respuesta en curso cancelada.", reply, app.core.cancellationToken());
                    } else {
                        app.messages.add(() -> process(id, sender, decision.message.text, reply, arrivalToken));
                    }
                }
            } catch (RuntimeException e) { app.messagingError(); }
        });
    }
    private void process(String id, String sender, String text, Reply reply, long token) {
        try {
            synchronized (app.controlGate) {
                if (!active(token)) return;
                app.db.noticeState(id, "procesando", null);
            }
            String response = app.core.receive(text, AssistantCore.Source.WHATSAPP_OWNER, id, token);
            synchronized (app.controlGate) {
                if (!active(token)) {
                    app.db.noticeState(id, "cancelado", null);
                    log(sender, "Respuesta cancelada; revisar Resumen por operaciones ya terminadas."); return;
                }
                app.db.noticeState(id, "pendiente", response); // durable BEFORE the first PendingIntent
            }
            deliver(id, sender, response, reply, token);
        } catch (RuntimeException e) {
            try { app.db.noticeState(id, "fallido; revisar Resumen", null); log(sender, "Respuesta fallida; revisar Resumen antes de repetir."); }
            catch (RuntimeException storageFailure) { app.messagingError(); }
        } finally { app.changed(); }
    }
    private boolean active(long token) { return app.settings.whatsappEnabled() && token == app.core.cancellationToken(); }
    private void deliver(String id, String sender, String response, Reply reply, long token) {
        if (reply == null) { log(sender, "Respuesta pendiente: el aviso no trae la acción Responder."); return; }
        List<String> chunks = NotificationPolicy.split(response, 1800);
        int offset = 0;
        for (int i = 0; i < chunks.size(); i++) {
            synchronized (app.controlGate) {
                if (!active(token)) {
                    app.db.noticeState(id, "cancelado; respuesta pendiente", response.substring(offset));
                    log(sender, "Envío cancelado; resto disponible en Registro."); return;
                }
                app.db.noticeState(id, "enviando", response.substring(offset));
                try { reply.send(app, chunks.get(i)); }
                catch (Exception e) {
                    app.db.noticeState(id, "pendiente; envío fallido", response.substring(offset));
                    log(sender, "Respuesta fallida en parte " + (i + 1) + "/" + chunks.size() + "; resto en Registro."); return;
                }
                offset += chunks.get(i).length();
                app.db.noticeState(id, offset == response.length() ? "enviado a Responder" : "pendiente", response.substring(offset));
                log(sender, "Respuesta enviada a Responder " + (i + 1) + "/" + chunks.size() + " (entrega no confirmada).");
            }
        }
    }
    private void log(String sender, String event) { app.db.messagingLog(sender, event); app.changed(); }
}
