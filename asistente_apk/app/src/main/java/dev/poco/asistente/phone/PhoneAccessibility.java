package dev.poco.asistente.phone;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.accessibility.AccessibilityEvent;
import dev.poco.asistente.AssistantApplication;
import dev.poco.asistente.core.PhonePolicy;

/** Bound by Android after explicit user consent. No window content or gestures. */
public final class PhoneAccessibility extends AccessibilityService {
    private static volatile PhoneAccessibility connected;
    public static boolean connected() { return connected != null; }
    @Override protected void onServiceConnected() { connected = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
    @Override public void onDestroy() { if (connected == this) connected = null; super.onDestroy(); }
    /** Maintenance only: fixed package, independent of the phone-control toggle. Call on main. */
    public static boolean openTermuxForMaintenance() {
        PhoneAccessibility service = connected;
        if (service == null) return false;
        PowerManager power = service.getSystemService(PowerManager.class);
        if (power == null || power.isInteractive()) return false;
        try {
            Intent intent = service.getPackageManager().getLaunchIntentForPackage("com.termux");
            if (intent == null) return false;
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            service.startActivity(intent);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (connected == service && !power.isInteractive()) {
                    try { service.performGlobalAction(GLOBAL_ACTION_HOME); }
                    catch (RuntimeException ignored) { }
                }
            }, 3000);
            return true;
        } catch (RuntimeException e) { return false; }
    }
    static void launch(Intent intent) {
        PhoneAccessibility service = connected;
        if (service == null) throw new IllegalArgumentException("En Asistente → Ajustes → Control del teléfono, tocá Accesibilidad y habilitá Asistente: abrir apps.");
        if (!((AssistantApplication) service.getApplication()).settings.phoneEnabled()) throw new IllegalArgumentException("El control del teléfono está apagado.");
        String pkg = intent.getComponent() == null ? intent.getPackage() : intent.getComponent().getPackageName();
        if (PhonePolicy.blocked(pkg)) throw new IllegalArgumentException("Esa no la puedo abrir.");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { service.startActivity(intent); }
        catch (RuntimeException e) { throw new IllegalArgumentException("No pude abrirla. Revisá los permisos de Asistente en los ajustes del teléfono."); }
    }
}
