package dev.poco.asistente.call;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.poco.asistente.AssistantApplication;
import dev.poco.asistente.ui.Icons;
import dev.poco.asistente.ui.Views;

/**
 * Looks like an incoming phone call from "Asistente". Rings for a reminder (or opens already answered when
 * David starts a call from the app); answering opens a Gemini Live voice conversation (LiveSession).
 */
public final class CallActivity extends Activity implements LiveSession.Events {
    public static final String EXTRA_REASON = "motivo", EXTRA_ANSWERED = "atendida";
    private static final int MIC_REQUEST = 7;
    private static final long RING_MS = 45_000;

    private AssistantApplication app;
    private Views ui;
    private TextView status, muteButton, speakerButton;
    private boolean speaker;
    private android.os.PowerManager.WakeLock ear; // screen off with the phone at the ear, like a call
    private LinearLayout buttons;
    private Ringtone ringtone;
    private Vibrator vibrator;
    private LiveSession session;
    private String reason;
    private TextView aboutView;
    private boolean muted, finished;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable missed = () -> { status.setText("Llamada perdida. Te lo dejé por WhatsApp."); stopRinging(); main.postDelayed(this::finish, 2500); };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setShowWhenLocked(true); setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        app = (AssistantApplication) getApplication();
        ui = new Views(this);
        reason = getIntent().getStringExtra(EXTRA_REASON);
        getSystemService(NotificationManager.class).cancel(CallLauncher.NOTIFICATION_ID);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(ui.palette.background);
        root.setPadding(ui.dp(24), ui.dp(96), ui.dp(24), ui.dp(64));
        TextView avatar = ui.icon(Icons.PHONE, 40, ui.palette.onBrand, false);
        GradientDrawable circle = new GradientDrawable(); circle.setShape(GradientDrawable.OVAL); circle.setColor(ui.palette.brand);
        avatar.setBackground(circle);
        root.addView(avatar, new LinearLayout.LayoutParams(ui.dp(112), ui.dp(112)));
        TextView name = ui.text("Asistente", 30, ui.palette.text, true); name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-2, -2); nameParams.topMargin = ui.dp(24);
        root.addView(name, nameParams);
        status = ui.text(reason == null ? "Llamada" : "Te está llamando…", 16, ui.palette.muted, false); status.setGravity(Gravity.CENTER);
        root.addView(status);
        if (reason != null) {
            TextView about = ui.text("«" + reason + "»", 15, ui.palette.text, false); about.setGravity(Gravity.CENTER); aboutView = about;
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.topMargin = ui.dp(12); root.addView(about, p);
        }
        root.addView(new android.view.View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        buttons = new LinearLayout(this); buttons.setGravity(Gravity.CENTER);
        root.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);

        if (getIntent().getBooleanExtra(EXTRA_ANSWERED, false)) answer();
        else ring();
    }

    private TextView roundAction(String glyph, int color, String label, Runnable action) {
        TextView b = ui.icon(glyph, 26, 0xFFFFFFFF, false);
        GradientDrawable circle = new GradientDrawable(); circle.setShape(GradientDrawable.OVAL); circle.setColor(color);
        b.setBackground(circle); b.setContentDescription(label); b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ui.dp(72), ui.dp(72));
        p.leftMargin = ui.dp(16); p.rightMargin = ui.dp(16);
        buttons.addView(b, p);
        return b;
    }

    private void ring() {
        buttons.removeAllViews();
        roundAction(Icons.PHONE_SLASH, ui.palette.negative, "Rechazar", this::decline);
        roundAction(Icons.PHONE, ui.palette.positive, "Atender", this::answer);
        try {
            ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE));
            if (ringtone != null) {
                ringtone.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build());
                ringtone.play();
            }
        } catch (RuntimeException ignored) { }
        vibrator = getSystemService(Vibrator.class);
        if (vibrator != null) vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 800, 800}, 0));
        main.postDelayed(missed, RING_MS);
    }

    private void stopRinging() {
        main.removeCallbacks(missed);
        if (ringtone != null) { ringtone.stop(); ringtone = null; }
        if (vibrator != null) { vibrator.cancel(); vibrator = null; }
    }

    private void decline() { stopRinging(); finish(); }

    private void answer() {
        if (finished || session != null) return;
        stopRinging();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST);
            return;
        }
        String key;
        try { key = app.settings.apiKey(); } catch (Exception e) { key = null; }
        if (key == null || key.isEmpty()) { status.setText("Falta la clave de Gemini en Ajustes."); return; }
        buttons.removeAllViews();
        muteButton = roundAction(Icons.MICROPHONE, ui.palette.surface, "Silenciar", this::toggleMute);
        speakerButton = roundAction(Icons.VOLUME, ui.palette.surface, "Altavoz", this::toggleSpeaker);
        roundAction(Icons.PHONE_SLASH, ui.palette.negative, "Colgar", () -> { if (session != null) session.hangUp(); else finish(); });
        session = new LiveSession(app.core, getSystemService(AudioManager.class), this);
        session.start(key, reason);
        earMode(true);
    }

    private void toggleSpeaker() {
        speaker = !speaker;
        if (session != null) session.setSpeaker(speaker);
        GradientDrawable circle = new GradientDrawable(); circle.setShape(GradientDrawable.OVAL);
        circle.setColor(speaker ? ui.palette.brand : ui.palette.surface);
        speakerButton.setBackground(circle);
        speakerButton.setContentDescription(speaker ? "Altavoz activado" : "Altavoz");
        earMode(!speaker);
    }

    /** With the earpiece, the proximity sensor turns the screen off at the ear; with the loudspeaker, not. */
    private void earMode(boolean on) {
        android.os.PowerManager power = getSystemService(android.os.PowerManager.class);
        if (on && ear == null && power.isWakeLockLevelSupported(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            ear = power.newWakeLock(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "asistente:llamada");
            ear.acquire(60 * 60 * 1000L);
        } else if (!on && ear != null) {
            if (ear.isHeld()) ear.release();
            ear = null;
        }
    }

    private void toggleMute() {
        muted = !muted;
        if (session != null) session.setMuted(muted);
        muteButton.setText(muted ? Icons.MICROPHONE_SLASH : Icons.MICROPHONE);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code != MIC_REQUEST) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) answer();
        else status.setText("Sin permiso de micrófono no puedo hablar con vos.");
    }

    @Override public void onState(String text) { main.post(() -> { if (!finished) status.setText(text); }); }

    @Override public void onEnded(String why) {
        main.post(() -> {
            if (finished) return;
            earMode(false);
            status.setText(why);
            main.postDelayed(this::finish, 1500);
        });
    }

    @Override protected void onDestroy() {
        finished = true;
        main.removeCallbacksAndMessages(null);
        stopRinging();
        earMode(false);
        if (session != null) session.hangUp();
        super.onDestroy();
    }

    /** A second reminder while this one rings or talks: join it instead of losing it (singleTop). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String more = intent.getStringExtra(EXTRA_REASON);
        if (more == null || more.equals(reason)) return;
        if (session != null) { session.addReminder(more); return; }
        reason = reason == null ? more : reason + " · " + more;
        if (aboutView != null) aboutView.setText("«" + reason + "»");
    }

    /** Opens the screen already answered: David calling her from the app. */
    public static void callNow(Context context) {
        context.startActivity(new Intent(context, CallActivity.class).putExtra(EXTRA_ANSWERED, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
