package dev.poco.asistente.phone;

import android.app.*;
import android.content.*;
import android.os.SystemClock;

/** Explicit, immutable alarm survives process death; a newer action invalidates an older expiry. */
public final class DndExpiry extends BroadcastReceiver {
    private static final String PREFS = "dnd_expiry";
    private static PendingIntent alarm(Context c, String token) {
        return PendingIntent.getBroadcast(c, 73, new Intent(c, DndExpiry.class)
                .setData(android.net.Uri.parse("asistente-dnd:" + token)).putExtra("token", token), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    static synchronized String apply(Context c, boolean on, int minutes) {
        NotificationManager manager = c.getSystemService(NotificationManager.class);
        AlarmManager alarms = c.getSystemService(AlarmManager.class);
        String old = c.getSharedPreferences(PREFS, 0).getString("token", "");
        long oldDue = c.getSharedPreferences(PREFS, 0).getLong("due", 0);
        String token = java.util.UUID.randomUUID().toString();
        long due = on && minutes > 0 ? System.currentTimeMillis() + minutes * 60000L : 0;
        if (!c.getSharedPreferences(PREFS, 0).edit().putString("token", token).putLong("due", due).commit()) throw new IllegalStateException("No pude guardar el plazo.");
        PendingIntent pending = alarm(c, token);
        try {
            // Schedule before enabling: failure cannot leave a requested timed mode without an expiry.
            if (on && minutes > 0) alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + minutes * 60000L, pending);
            manager.setInterruptionFilter(on ? NotificationManager.INTERRUPTION_FILTER_PRIORITY : NotificationManager.INTERRUPTION_FILTER_ALL);
        } catch (RuntimeException e) {
            alarms.cancel(pending); pending.cancel();
            c.getSharedPreferences(PREFS, 0).edit().putString("token", old).putLong("due", oldDue).commit();
            throw e;
        }
        if (!old.isEmpty()) { PendingIntent prior = alarm(c, old); alarms.cancel(prior); prior.cancel(); }
        return (on ? "Activé" : "Desactivé") + " No molestar de Asistente" + (on && minutes > 0 ? " por unos " + minutes + " minutos (Android puede demorar el final)" : "") + ". Otros modos del teléfono pueden seguir activos.";
    }
    @Override public void onReceive(Context c, Intent intent) {
        synchronized (DndExpiry.class) {
            if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
                long due = c.getSharedPreferences(PREFS, 0).getLong("due", 0);
                String saved = c.getSharedPreferences(PREFS, 0).getString("token", "");
                if (due <= 0 || saved.isEmpty()) return;
                c.getSystemService(AlarmManager.class).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + Math.max(1, due - System.currentTimeMillis()), alarm(c, saved));
                return;
            }
            String token = intent.getStringExtra("token");
            if (token == null || !token.equals(c.getSharedPreferences(PREFS, 0).getString("token", ""))) return;
            try {
                NotificationManager manager = c.getSystemService(NotificationManager.class);
                if (manager.isNotificationPolicyAccessGranted()) manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
            } catch (RuntimeException ignored) { /* Permission can be revoked after the action. */ }
            c.getSharedPreferences(PREFS, 0).edit().remove("token").remove("due").apply();
        }
    }
}
