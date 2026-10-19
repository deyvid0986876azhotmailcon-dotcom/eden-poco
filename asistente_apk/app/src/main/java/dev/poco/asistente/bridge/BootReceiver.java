package dev.poco.asistente.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import dev.poco.asistente.AssistantApplication;

/**
 * Starts the bridge service after a reboot if it was on. Android lets a specialUse foreground service
 * start from BOOT_COMPLETED; Termux starts its half from ~/.termux/boot (see asistente_puente/README.md).
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        AssistantApplication app = (AssistantApplication) context.getApplicationContext();
        if (!app.settings.bridgeEnabled()) return;
        try { context.startForegroundService(new Intent(context, BridgeService.class)); }
        catch (RuntimeException e) { app.bridgeLog("Fallido: no se pudo iniciar el servicio al prender el teléfono"); }
    }
}
