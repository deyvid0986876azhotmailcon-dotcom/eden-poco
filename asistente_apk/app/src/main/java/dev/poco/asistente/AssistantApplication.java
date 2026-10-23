package dev.poco.asistente;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import dev.poco.asistente.core.AssistantCore;
import dev.poco.asistente.data.AppSettings;
import dev.poco.asistente.data.AssistantDb;
import dev.poco.asistente.net.GeminiClient;
import dev.poco.asistente.messaging.MessageQueue;
import dev.poco.asistente.messaging.WhatsAppBridge;
import dev.poco.asistente.bridge.BridgeInbox;
import java.time.Clock;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-scoped core and ordered queue shared by the chat and both WhatsApp adapters. */
public final class AssistantApplication extends Application {
    public AssistantCore core;
    public dev.poco.asistente.core.Proactive proactive;
    public AppSettings settings;
    public AssistantDb db;
    public WhatsAppBridge whatsapp;
    public BridgeInbox bridge;
    public final Object controlGate = new Object();
    public final ExecutorService worker = Executors.newSingleThreadExecutor();
    public final ExecutorService io = Executors.newSingleThreadExecutor();
    public final MessageQueue messages = new MessageQueue(worker);
    public final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private String chat = "Asistente: ¡Hola, che! ¿Cómo andás? Contame un gasto, tu peso o cómo te fue en el gimnasio. Si querés ejemplos, escribí «ayuda».\n";
    private boolean busy;
    @Override public void onCreate() {
        super.onCreate();
        settings = new AppSettings(this);
        db = new AssistantDb(this);
        core = new AssistantCore(db, settings, new GeminiClient(), Clock.system(dev.poco.asistente.core.Reminders.ZONE), SystemClock::elapsedRealtime,
                new dev.poco.asistente.phone.AndroidPhone(this), new dev.poco.asistente.net.ProClient(settings::bridgeToken));
        core.setDocuments(new dev.poco.asistente.phone.AndroidDocuments());
        proactive = new dev.poco.asistente.core.Proactive(db, settings, new GeminiClient(), Clock.system(dev.poco.asistente.core.Reminders.ZONE), core);
        proactive.onReminderCall = text -> main.post(() -> dev.poco.asistente.call.CallLauncher.ring(this, text));
        importBankReader();
        try {
            if (settings.bankReader() != null)
                proactive.bank = new dev.poco.asistente.core.BankFeed(db, core, new dev.poco.asistente.net.BankClient(settings::bankReader));
        } catch (Exception e) { bridgeLog("Banco: no pude abrir la configuración cifrada"); }
        whatsapp = new WhatsAppBridge(this);
        bridge = new BridgeInbox(core, messages, controlGate, settings::bridgeEnabled, id -> {
            if (!db.claimNotice(id, "Puente")) return false;
            db.noticeState(id, "recibido por puente", null); return true;
        }, this::bridgeLog, this::stop);
    }
    public void listen(Runnable listener) { listeners.addIfAbsent(listener); }
    public void unlisten(Runnable listener) { listeners.remove(listener); }
    public synchronized String chat() { return chat; }
    public synchronized boolean busy() { return busy; }
    private synchronized void append(String text) {
        chat += "\n" + text + "\n";
        if (chat.length() > 32000) chat = "[Conversación anterior recortada; los registros siguen en Resumen]\n" + chat.substring(chat.length() - 30000);
    }
    public void changed() { main.post(() -> { for (Runnable listener : listeners) listener.run(); }); }
    public void messagingError() { append("Sistema: falló el almacenamiento de WhatsApp. Revisá Registro y Resumen antes de repetir un pedido."); changed(); }
    /**
     * One-time setup of the bank mail reader: files/banco_importar.txt (deployment id, then token, one per line),
     * placed with adb run-as. It goes into the encrypted vault and the file is deleted whatever happens.
     */
    private void importBankReader() {
        java.io.File file = new java.io.File(getFilesDir(), "banco_importar.txt");
        if (!file.exists()) return;
        try {
            String[] lines = new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim().split("\\s+");
            if (lines.length != 2) throw new IllegalArgumentException();
            settings.saveBankReader(lines[0], lines[1]);
            bridgeLog("Banco: lector configurado");
        } catch (Exception e) {
            bridgeLog("Banco: no pude importar la configuración del lector");
        } finally {
            if (!file.delete()) bridgeLog("Banco: no pude borrar el archivo de importación");
        }
    }
    public void bridgeLog(String event) {
        try { db.messagingLog("Puente", event); }
        catch (RuntimeException e) { messagingError(); }
        changed();
    }
    public void send(String text) {
        if (dev.poco.asistente.core.Rules.normalize(text).equals("parar")) { stop(); return; }
        synchronized (controlGate) {
            synchronized (this) { if (busy) return; busy = true; }
            long token = core.cancellationToken();
            append("David: " + text); changed();
            messages.add(() -> {
                try { append("Asistente: " + dev.poco.asistente.core.ExternalData.display(core.receive(text, token))); }
                finally {
                    synchronized (controlGate) {
                        if (token == core.cancellationToken()) synchronized (this) { busy = false; }
                    }
                    changed();
                }
            });
        }
    }
    public void stop() {
        synchronized (controlGate) {
            append("Asistente: " + core.stop()); messages.clear();
            if (bridge != null) bridge.cancelPending();
            synchronized (this) { busy = false; }
            try { db.cancelOutgoing(); db.cancelNotices(); db.messagingLog("Sistema", "PARAR: cola vaciada; no se deshacen acciones ya terminadas."); }
            catch (RuntimeException e) { messagingError(); }
        }
        changed();
    }
    public void forgetConversation() {
        stop();
        long ticket = core.cancellationToken();
        worker.execute(() -> {
            try { if (core.clearConversation(ticket)) synchronized (this) { chat = "Asistente: Olvidé la conversación.\n"; } }
            catch (Exception e) { append("Sistema: No pude borrar el historial."); }
            changed();
        });
    }
    public void configureWhatsapp(boolean enabled, String name, String phone) {
        synchronized (controlGate) {
            if (enabled && settings.bridgeEnabled()) throw new IllegalStateException("Apagá primero el puente QR.");
            settings.saveWhatsapp(enabled, name, phone); stop();
        }
    }
}
