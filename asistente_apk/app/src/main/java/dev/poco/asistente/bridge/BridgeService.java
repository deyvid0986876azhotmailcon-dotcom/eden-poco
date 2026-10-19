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
    private AssistantApplication app;
    private volatile boolean closed;
    private volatile ServerSocket server;
    private boolean started;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final ExecutorService listener = Executors.newSingleThreadExecutor();
    private final ThreadPoolExecutor handlers = new ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
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
        if (!started) { started = true; listener.execute(this::serve); }
        return START_STICKY;
    }
    private void serve() {
        // TEMPORARY diagnosis (Claude, 2026-10-19): the server answers one connection and then stops
        // accepting. Every 15 s the stack of this thread goes to logcat (tag AsistenteDiag).
        Thread serving = Thread.currentThread();
        deadlines.scheduleWithFixedDelay(() -> {
            StringBuilder trace = new StringBuilder("serve ").append(serving.getState());
            for (StackTraceElement frame : serving.getStackTrace()) trace.append("\n  at ").append(frame);
            for (java.util.Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                String name = entry.getKey().getName();
                if (!name.startsWith("pool-")) continue;
                trace.append("\n").append(name).append(' ').append(entry.getKey().getState());
                StackTraceElement[] frames = entry.getValue();
                for (int i = 0; i < Math.min(6, frames.length); i++) trace.append("\n  at ").append(frames[i]);
            }
            android.util.Log.w("AsistenteDiag", trace.toString());
        }, 15, 15, TimeUnit.SECONDS);
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
        ScheduledFuture<?> deadline = null;
        CompletableFuture<String> result = null;
        try (Socket client = socket) {
            deadline = deadlines.schedule(() -> close(client), 100, TimeUnit.SECONDS);
            client.setSoTimeout(10000);
            int status = 200;
            String response;
            try {
                BridgeHttp.Request request = BridgeHttp.read(client.getInputStream(), token);
                result = app.bridge.accept(request.id, request.text, arrivalToken);
                response = result.get(85, TimeUnit.SECONDS);
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
            client.getOutputStream().write(BridgeHttp.response(status, response));
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
    @Override public void onDestroy() {
        closed = true; close(server);
        app.stop();
        for (Socket client : clients) close(client);
        listener.shutdownNow(); handlers.shutdownNow(); deadlines.shutdownNow();
        app.bridgeLog("Aceptado: servicio detenido");
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
