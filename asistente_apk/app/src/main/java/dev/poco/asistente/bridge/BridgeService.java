package dev.poco.asistente.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import dev.poco.asistente.phone.PhoneAccessibility;
import dev.poco.asistente.AssistantApplication;
import dev.poco.asistente.MainActivity;
import dev.poco.asistente.data.AppSettings;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Explicitly enabled foreground service. One request/connection; no LAN bind, keepalive or CORS. */
public final class BridgeService extends Service {
    public static final String STOP = "dev.poco.asistente.bridge.STOP";
    private static final String CHANNEL = "whatsapp_bridge";
    private static final String HEALTH_CHANNEL = "whatsapp_health";
    private static final int HEALTH_ID = 411;
    private static final long HEALTH_CHECK_SECONDS = 60;
    private BridgeWatchdog health;
    private AssistantApplication app;
    private volatile boolean closed;
    private volatile ServerSocket server;
    private boolean started;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final ExecutorService listener = Executors.newSingleThreadExecutor();
    private final ThreadPoolExecutor handlers = new ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService healthChecks = Executors.newSingleThreadScheduledExecutor();
    private final BridgeVoice voice = new BridgeVoice();
    @Override public void onCreate() { super.onCreate(); app = (AssistantApplication) getApplication(); }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            app.settings.setBridgeEnabled(false); app.stop(); stopSelf(); return START_NOT_STICKY;
        }
        // Promote even on a delayed start that was disabled in the meantime, then stop immediately.
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Puente de WhatsApp", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, BridgeService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("Puente de WhatsApp").setContentText("Escucha local activa para Termux")
                .setContentIntent(open).setOngoing(true).addAction(android.R.drawable.ic_media_pause, "Apagar y PARAR", stop).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(410, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(410, notification);
        if (!app.settings.bridgeEnabled()) { stopSelf(); return START_NOT_STICKY; }
        if (!started) {
            health = new BridgeWatchdog(android.os.SystemClock::elapsedRealtime, new BridgeWatchdog.Actions() {
                public boolean notice(String text) { return showHealthNotice(text); }
                public boolean screenInteractive() {
                    PowerManager power = getSystemService(PowerManager.class);
                    return power == null || power.isInteractive();
                }
                public boolean accessibilityConnected() { return PhoneAccessibility.connected(); }
                public void restart() { restartTermux(); }
                public void log(String event) { app.bridgeLog(event); }
            });
            manager.createNotificationChannel(new NotificationChannel(HEALTH_CHANNEL, "Problemas de WhatsApp", NotificationManager.IMPORTANCE_HIGH));
            manager.cancel(HEALTH_ID);
            started = true; listener.execute(this::serve);
            app.bridgeLog("Salud: vigilancia iniciada (gracia de 10 minutos)");
            healthChecks.scheduleWithFixedDelay(this::checkHealth, HEALTH_CHECK_SECONDS, HEALTH_CHECK_SECONDS, TimeUnit.SECONDS);
        }
        return START_STICKY;
    }
    private void checkHealth() {
        if (closed || health == null) return;
        health.run();
    }
    private boolean showHealthNotice(String notice) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (notice == null) manager.cancel(HEALTH_ID);
        else {
            NotificationChannel channel = manager.getNotificationChannel(HEALTH_CHANNEL);
            if (!manager.areNotificationsEnabled() || channel == null
                    || channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                app.bridgeLog("Salud: aviso bloqueado por permisos o canal");
                return false;
            }
            PendingIntent open = PendingIntent.getActivity(this, HEALTH_ID, new Intent(this, MainActivity.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder builder = new Notification.Builder(this, HEALTH_CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_error).setContentTitle("Asistente: WhatsApp")
                    .setContentText(notice).setStyle(new Notification.BigTextStyle().bigText(notice))
                    .setContentIntent(open).setOnlyAlertOnce(true);
            if (BridgeHealth.SILENT.equals(notice)) {
                Intent termux = getPackageManager().getLaunchIntentForPackage("com.termux");
                if (termux != null) {
                    termux.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    PendingIntent start = PendingIntent.getActivity(this, HEALTH_ID + 1, termux,
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                    builder.setContentIntent(start)
                            .addAction(android.R.drawable.ic_media_play, "Arrancar el puente", start);
                }
            }
            manager.notify(HEALTH_ID, builder.build());
        }
        return true;
    }
    private void restartTermux() {
        app.main.post(() -> {
            if (closed || !app.settings.bridgeEnabled()) return;
            // A heartbeat or screen change may have arrived while this was queued.
            if (health == null || !health.isSilent()) return;
            boolean opened = PhoneAccessibility.openTermuxForMaintenance();
            app.bridgeLog(opened ? "Salud: apertura de Termux solicitada"
                    : "Salud: Termux no abierto; tocá Arrancar el puente en el aviso");
        });
    }
    private void serve() {
        try (ServerSocket socket = new ServerSocket()) {
            server = socket;
            if (closed) return;
            InetAddress address = InetAddress.getLoopbackAddress();
            // getLoopbackAddress can prefer ::1 on some runtimes; Termux always uses 127.0.0.1.
            if (!address.getHostAddress().equals("127.0.0.1")) address = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
            socket.bind(new InetSocketAddress(address, AppSettings.BRIDGE_PORT), 8);
            String token = app.settings.bridgeToken();
            app.bridgeLog("Aceptado: servicio iniciado en 127.0.0.1");
            while (!closed) {
                Socket client = socket.accept();
                long arrivalToken = app.core.cancellationToken();
                clients.add(client);
                try { handlers.execute(() -> handle(client, token, arrivalToken)); }
                catch (java.util.concurrent.RejectedExecutionException e) {
                    close(client); clients.remove(client); app.bridgeLog("Fallido: demasiadas conexiones");
                }
            }
        } catch (Exception e) {
            if (!closed) {
                app.bridgeLog("Fallido: no se pudo abrir o mantener el servidor local");
                app.main.post(() -> { app.settings.setBridgeEnabled(false); stopSelf(); app.changed(); });
            }
        }
    }
    private void handle(Socket socket, String token, long arrivalToken) {
        dev.poco.asistente.core.RequestBudget budget = app.core.newRequestBudget();
        long startedAt = android.os.SystemClock.elapsedRealtime();
        ScheduledFuture<?> deadline = null;
        CompletableFuture<String> result = null;
        try (Socket client = socket) {
            deadline = deadlines.schedule(() -> close(client), 100, TimeUnit.SECONDS);
            client.setSoTimeout(10000);
            int status = 200;
            String response;
            org.json.JSONObject audio = null;
            boolean stopped = false, incomingAudio = false;
            try {
                BridgeHttp.Request request = BridgeHttp.read(client.getInputStream(), token);
                incomingAudio = request.audio != null;
                if (request.backup) { deliverBackup(client); return; }
                if (request.pending || request.confirm) {
                    if (request.pending) { health.poll(request.whatsapp); checkHealth(); }
                    if (!app.settings.bridgeEnabled() || arrivalToken != app.core.cancellationToken()) throw new BridgeHttp.Failure(503);
                    if (request.confirm) {
                        app.db.acknowledge(request.ids, System.currentTimeMillis());
                        client.getOutputStream().write(BridgeHttp.jsonResponse(200, "{}"));
                    } else {
                        CompletableFuture<String> outgoing = new CompletableFuture<>();
                        app.worker.execute(() -> {
                            try {
                                if (!app.settings.bridgeEnabled() || arrivalToken != app.core.cancellationToken()) outgoing.complete("[]");
                                else outgoing.complete(app.proactive.poll().toString());
                            } catch (Exception e) { outgoing.completeExceptionally(e); }
                        });
                        client.getOutputStream().write(BridgeHttp.jsonResponse(200, outgoing.get(85, TimeUnit.SECONDS)));
                    }
                    client.getOutputStream().flush(); app.changed(); return;
                }
                if (request.audio != null) {
                    try { AudioDuration.validate(request.audio, getCacheDir()); }
                    catch (Exception e) {
                        client.getOutputStream().write(BridgeHttp.response(200, dev.poco.asistente.core.AudioInput.FALLBACK));
                        client.getOutputStream().flush(); app.bridgeLog("Audio rechazado: formato o duración no verificables"); return;
                    }
                    if (android.os.SystemClock.elapsedRealtime() - startedAt >= 80000) throw new TimeoutException();
                    result = app.bridge.acceptAudio(request.id, request.audio, arrivalToken, budget);
                } else result = app.bridge.accept(request.id, request.text, arrivalToken, budget);
                response = result.get(Math.max(1, 80000 - (android.os.SystemClock.elapsedRealtime() - startedAt)), TimeUnit.MILLISECONDS);
                stopped = BridgeInbox.wasStopped(result);
                audio = voice.generate(app, response, request.audio != null, arrivalToken,
                        80000 - (android.os.SystemClock.elapsedRealtime() - startedAt));
                if (!stopped && arrivalToken != app.core.cancellationToken()) {
                    audio = null;
                    // Preserve the explicit text PARAR acknowledgement too.
                    if (!dev.poco.asistente.core.Rules.normalize(request.text == null ? "" : request.text).equals("parar"))
                        response = "Pedido cancelado; consultá Resumen por acciones ya terminadas.";
                }
            } catch (BridgeHttp.Failure failure) {
                status = failure.status; response = "Pedido rechazado";
            } catch (TimeoutException e) {
                if (result != null) app.bridge.expire(result);
                status = 504; response = "Tiempo agotado";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); status = 503; response = "Servicio detenido";
            } catch (Exception e) {
                status = 503; response = "No disponible";
            }
            if (status == 200) {
                org.json.JSONObject payload = new org.json.JSONObject().put("respuesta", dev.poco.asistente.core.ExternalData.display(response));
                if (audio != null) {
                    payload.put("audio", audio);
                    // David sent a voice note: he gets only hers, unless he asked for it in writing or there is a code to copy.
                    // What he asked comes from this request only; without it, the text goes too.
                    String asked = result == null ? null : BridgeInbox.heard(result);
                    if (incomingAudio && asked != null && !dev.poco.asistente.core.AudioProtocol.needsText(response, asked)) payload.put("solo_audio", true);
                }
                if (stopped) payload.put("parar", true);
                client.getOutputStream().write(BridgeHttp.jsonResponse(status, payload.toString()));
            } else client.getOutputStream().write(BridgeHttp.response(status, response));
            client.getOutputStream().flush();
            app.bridgeLog(status == 200 ? "Aceptado: respuesta HTTP entregada" : "Fallido: HTTP " + status);
        } catch (Exception e) {
            app.bridgeLog("Fallido: conexión local interrumpida; revisar Resumen");
        } finally {
            if (result != null && !result.isDone()) app.bridge.expire(result);
            if (deadline != null) deadline.cancel(false);
            clients.remove(socket);
        }
    }
    private static void close(java.io.Closeable closeable) {
        if (closeable == null) return;
        try { closeable.close(); } catch (IOException ignored) { }
    }
    private synchronized void deliverBackup(Socket client) {
        java.io.File snapshot = null;
        boolean headersSent = false;
        try {
            snapshot = java.io.File.createTempFile("respaldo-", ".db", getCacheDir());
            // VACUUM INTO accepts an existing empty file; no preferences or vault are read.
            app.db.getWritableDatabase().execSQL("VACUUM INTO ?", new Object[]{snapshot.getAbsolutePath()});
            long size = snapshot.length();
            java.io.OutputStream output = client.getOutputStream();
            headersSent = true;
            output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.sqlite3\r\nContent-Length: "
                    + size + "\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            try (java.io.InputStream input = new java.io.FileInputStream(snapshot)) {
                byte[] buffer = new byte[32768]; int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            output.flush();
            app.bridgeLog("Respaldo entregado (" + ((size + 1023) / 1024) + " KB)");
        } catch (Exception e) {
            app.bridgeLog("Respaldo fallido: no se pudo crear o entregar la base");
            if (!headersSent) try { client.getOutputStream().write(BridgeHttp.response(503, "Respaldo no disponible")); }
            catch (IOException ignored) { }
        } finally {
            if (snapshot != null && !snapshot.delete()) app.bridgeLog("Respaldo fallido: no se pudo borrar el temporal");
        }
    }
    @Override public void onDestroy() {
        closed = true; close(server);
        app.stop();
        for (Socket client : clients) close(client);
        listener.shutdownNow(); handlers.shutdownNow(); deadlines.shutdownNow(); healthChecks.shutdownNow(); voice.close();
        getSystemService(NotificationManager.class).cancel(HEALTH_ID);
        app.bridgeLog("Aceptado: servicio detenido");
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
