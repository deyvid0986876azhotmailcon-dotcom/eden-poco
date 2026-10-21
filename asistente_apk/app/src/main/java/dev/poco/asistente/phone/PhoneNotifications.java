package dev.poco.asistente.phone;

import android.app.KeyguardManager;
import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import dev.poco.asistente.AssistantApplication;
import dev.poco.asistente.core.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Separate from the legacy WhatsApp command listener: nothing here enters an inbox or executor. */
public final class PhoneNotifications extends NotificationListenerService {
    private static volatile PhoneNotifications connected;
    private static final Map<String, Notice> notices = new LinkedHashMap<>();
    private static final class Notice {
        final String pkg, label, text; final long time;
        Notice(String pkg, String label, String text, long time) { this.pkg = pkg; this.label = label; this.text = text; this.time = time; }
    }
    public static boolean connected() { return connected != null; }
    public static void clear() { synchronized (notices) { notices.clear(); } }
    @Override public void onListenerConnected() { connected = this; }
    @Override public void onListenerDisconnected() { if (connected == this) connected = null; clear(); }
    @Override public void onDestroy() { if (connected == this) connected = null; clear(); super.onDestroy(); }
    @Override public void onNotificationRemoved(StatusBarNotification sbn) { synchronized (notices) { notices.remove(sbn.getKey()); } }
    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        Notice notice = collect(sbn);
        synchronized (notices) {
            if (connected != this || !((AssistantApplication) getApplication()).settings.phoneEnabled()) { notices.clear(); return; }
            if (sbn != null) {
                notices.remove(sbn.getKey());
                if (notice != null) put(notices, sbn.getKey(), notice);
            }
        }
    }
    private Notice collect(StatusBarNotification sbn) {
        // Check the package BEFORE reading extras, title, text or a publicVersion.
        if (sbn == null || PhonePolicy.blocked(sbn.getPackageName())
                || !((AssistantApplication) getApplication()).settings.phoneEnabled()) return null;
        Notification n = sbn.getNotification();
        if (n == null || n.visibility == Notification.VISIBILITY_SECRET || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return null;
        KeyguardManager lock = getSystemService(KeyguardManager.class);
        if (lock != null && lock.isDeviceLocked()) {
            n = n.publicVersion;
            if (n == null) return null;
        }
        if (n.extras == null) return null;
        String title = value(n.extras.getCharSequence(Notification.EXTRA_TITLE));
        String text = value(n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (text.isEmpty()) text = value(n.extras.getCharSequence(Notification.EXTRA_TEXT));
        String label;
        try { label = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(sbn.getPackageName(), 0)).toString(); }
        catch (Exception e) { label = "App"; }
        String safe = PhonePolicy.redact(label + ": " + title + "\n" + text);
        return new Notice(sbn.getPackageName(), PhonePolicy.redact(label), Memory.clip(safe, 800), sbn.getPostTime());
    }
    private static void put(Map<String, Notice> target, String key, Notice notice) {
        target.put(key, notice);
        while (target.size() > 50) target.remove(target.keySet().iterator().next());
    }
    private static String value(CharSequence value) { return value == null ? "" : value.toString(); }
    static String read(String pkg) {
        PhoneNotifications service = connected;
        if (service == null) throw new IllegalArgumentException("En Asistente → Ajustes → Control del teléfono, habilitá Notificaciones para Asistente: leer avisos.");
        Map<String, Notice> snapshot = new LinkedHashMap<>();
        // All Binder work (including keyguard and app labels) stays outside the lock needed by PARAR.
        try {
            StatusBarNotification[] active = service.getActiveNotifications();
            if (active != null) {
                Arrays.sort(active, Comparator.comparingLong(StatusBarNotification::getPostTime));
                for (StatusBarNotification sbn : active) {
                    Notice notice = service.collect(sbn);
                    if (notice != null) put(snapshot, sbn.getKey(), notice);
                }
            }
        } catch (RuntimeException e) { throw new IllegalArgumentException("No pude leer los avisos. Revisá el acceso a notificaciones."); }
        synchronized (notices) {
            if (connected != service || !((AssistantApplication) service.getApplication()).settings.phoneEnabled()) { notices.clear(); throw new IllegalArgumentException("El control del teléfono está apagado o se desconectó el acceso a notificaciones."); }
            // Replace the cache from the current snapshot; never serve the previous cache.
            notices.clear();
            notices.putAll(snapshot);
        }
        StringBuilder out = new StringBuilder(); int count = 0;
        List<Notice> latest = new ArrayList<>(snapshot.values()); Collections.reverse(latest);
        for (Notice n : latest) {
            if (PhonePolicy.blocked(n.pkg) || (pkg != null && !pkg.equals(n.pkg))) continue;
            if (out.length() + n.text.length() > 12000) { out.append("\nHay más avisos; pedime filtrar por app."); break; }
            out.append(Instant.ofEpochMilli(n.time).atZone(Reminders.ZONE).format(DateTimeFormatter.ofPattern("dd/MM HH:mm")))
                    .append(" · ").append(PhonePolicy.redact(n.text)).append('\n'); count++;
        }
        return ExternalData.wrap(count == 0 ? "No hay avisos visibles de apps permitidas." : out.toString());
    }
}
