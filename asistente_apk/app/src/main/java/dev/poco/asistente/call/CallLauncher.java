package dev.poco.asistente.call;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;

/**
 * Rings David like an incoming call: a full-screen notification (category CALL) that opens CallActivity,
 * even with the screen locked. Android 14+ lets an app that is not a phone app do this only if David allows
 * "notificaciones de pantalla completa" for it (Ajustes has a button); without it, it is a heads-up notification.
 */
public final class CallLauncher {
    static final int NOTIFICATION_ID = 77;
    private static final String CHANNEL = "llamadas";
    private CallLauncher() { }

    public static void ring(Context context, String reason) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Llamadas de la asistente", NotificationManager.IMPORTANCE_HIGH);
        channel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build());
        manager.createNotificationChannel(channel);
        Intent screen = new Intent(context, CallActivity.class).putExtra(CallActivity.EXTRA_REASON, reason)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(context, 1, screen, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle("Asistente te está llamando")
                .setContentText(reason)
                .setCategory(Notification.CATEGORY_CALL)
                .setFullScreenIntent(open, true)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setTimeoutAfter(50_000)
                .build();
        manager.notify(NOTIFICATION_ID, notification);
    }

    /** Whether Android will show the call over the lock screen (always true before Android 14). */
    public static boolean canRingFullScreen(Context context) {
        return Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager.class).canUseFullScreenIntent();
    }
}
