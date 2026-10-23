package dev.poco.asistente.phone;

import android.Manifest;
import android.app.NotificationManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.UserManager;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.telecom.TelecomManager;
import dev.poco.asistente.AssistantApplication;
import dev.poco.asistente.core.*;
import java.util.*;
import org.json.JSONObject;

/** Android effects only receive executor-owned snapshots, after confirmation. */
final class AndroidActions {
    private final AssistantApplication app;
    private final AndroidPhone phone;
    AndroidActions(AssistantApplication app, AndroidPhone phone) { this.app = app; this.phone = phone; }
    private void permission(String name) {
        if (app.checkSelfPermission(name) != PackageManager.PERMISSION_GRANTED)
            throw new IllegalArgumentException("Falta un permiso. Abrí Asistente → Ajustes → Control del teléfono y habilitalo.");
    }
    private void primaryUser() {
        if (!app.getSystemService(UserManager.class).isSystemUser())
            throw new IllegalArgumentException("Estas acciones solo funcionan en el espacio principal del teléfono.");
    }
    private ContactPolicy.Contact contact(String query) {
        permission(Manifest.permission.READ_CONTACTS);
        List<ContactPolicy.Contact> rows = new ArrayList<>();
        try (Cursor c = app.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null, null)) {
            if (c == null) throw new IllegalArgumentException("No pude consultar los contactos.");
            while (c.moveToNext()) {
                if (rows.size() >= 10000) throw new IllegalArgumentException("Hay demasiados contactos para resolver el pedido con seguridad.");
                rows.add(new ContactPolicy.Contact(c.getString(0), c.getString(1)));
            }
        }
        return ContactPolicy.resolve(query, rows);
    }
    private long calendar() {
        permission(Manifest.permission.READ_CALENDAR); permission(Manifest.permission.WRITE_CALENDAR);
        long id = -1;
        String selection = CalendarContract.Calendars.IS_PRIMARY + "=1 AND " + CalendarContract.Calendars.VISIBLE + "=1 AND "
                + CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + ">=?";
        try (Cursor c = app.getContentResolver().query(CalendarContract.Calendars.CONTENT_URI,
                new String[]{CalendarContract.Calendars._ID}, selection,
                new String[]{Integer.toString(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)}, null)) {
            if (c == null) throw new IllegalArgumentException("No pude consultar los calendarios.");
            while (c.moveToNext()) {
                if (id != -1) throw new IllegalArgumentException("Hay varios calendarios principales. Dejá visible uno solo o creá el evento desde Calendario.");
                id = c.getLong(0);
            }
        }
        if (id < 0) throw new IllegalArgumentException("No encontré un calendario principal visible con permiso de escritura.");
        return id;
    }
    JSONObject prepare(String tool, JSONObject input) throws Exception {
        primaryUser(); JSONObject a = Json.copy(input);
        switch (tool) {
            case "llamar": case "mensaje_whatsapp":
                ContactPolicy.Contact c = contact(a.getString("contacto")); a.put("numero", c.number);
                if (tool.equals("llamar")) permission(Manifest.permission.CALL_PHONE);
                else ContactPolicy.whatsapp(c.number, app.settings.phoneBotNumber());
                break;
            case "agendar": a.put("calendario", calendar()); break;
            case "no_molestar":
                if (!app.getSystemService(NotificationManager.class).isNotificationPolicyAccessGranted())
                    throw new IllegalArgumentException("Habilitá acceso a No molestar en Ajustes de Asistente.");
                break;
            case "brillo":
                if (!Settings.System.canWrite(app)) throw new IllegalArgumentException("Habilitá Modificar ajustes del sistema en Ajustes de Asistente.");
                break;
            default: throw new IllegalArgumentException("Acción no permitida.");
        }
        return a;
    }
    String execute(String tool, JSONObject a) throws Exception {
        primaryUser();
        if (!app.settings.phoneEnabled()) throw new IllegalArgumentException("El control del teléfono está apagado.");
        if (tool.equals("wifi") || tool.equals("bluetooth")) {
            phone.launchResolved(new Intent(tool.equals("wifi") ? Settings.ACTION_WIFI_SETTINGS : Settings.ACTION_BLUETOOTH_SETTINGS).setPackage("com.android.settings"));
            return "Abrí los ajustes de " + tool + "; cambiá el estado ahí a mano.";
        }
        // Recheck permission, target and policy at the point of effect. Never retarget a confirmed snapshot.
        JSONObject fresh = prepare(tool, a);
        if ((a.has("numero") && !a.getString("numero").equals(fresh.getString("numero")))
                || (a.has("calendario") && a.getLong("calendario") != fresh.getLong("calendario")))
            throw new IllegalArgumentException("El destinatario o calendario cambió. Pedí la acción otra vez.");
        switch (tool) {
            case "llamar":
                String number = ContactPolicy.mobile(a.getString("numero"));
                TelecomManager telecom = app.getSystemService(TelecomManager.class);
                if (android.os.Build.VERSION.SDK_INT >= 29 && app.getSystemService(android.telephony.TelephonyManager.class).isEmergencyNumber(number))
                    throw new IllegalArgumentException("No llamo a números de emergencia.");
                String dialer = telecom.getDefaultDialerPackage();
                if (PhonePolicy.blocked(dialer)) throw new IllegalArgumentException("No encontré un marcador permitido.");
                phone.launchResolved(new Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)).setPackage(dialer));
                return "Pedí llamar a " + a.getString("contacto") + " (" + number + ").";
            case "mensaje_whatsapp":
                Uri uri = new Uri.Builder().scheme("https").authority("wa.me")
                        .appendPath(a.getString("numero").substring(1)).appendQueryParameter("text", a.getString("texto")).build();
                phone.launchResolved(new Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp"));
                return "Te lo dejé listo en WhatsApp; tocá enviar. Si no se abrió, revisá WhatsApp y Accesibilidad.";
            case "agendar":
                ContentValues values = new ContentValues();
                values.put(CalendarContract.Events.CALENDAR_ID, a.getLong("calendario"));
                values.put(CalendarContract.Events.TITLE, a.getString("titulo"));
                values.put(CalendarContract.Events.DTSTART, PhoneTools.eventTime(a.getString("inicio")).atZone(Reminders.ZONE).toInstant().toEpochMilli());
                values.put(CalendarContract.Events.DTEND, PhoneTools.eventTime(a.getString("fin")).atZone(Reminders.ZONE).toInstant().toEpochMilli());
                values.put(CalendarContract.Events.EVENT_TIMEZONE, Reminders.ZONE.getId());
                values.put(CalendarContract.Events.EVENT_LOCATION, a.optString("lugar", ""));
                if (app.getContentResolver().insert(CalendarContract.Events.CONTENT_URI, values) == null)
                    throw new IllegalArgumentException("El calendario no guardó el evento.");
                return "Creé «" + a.getString("titulo") + "» en el calendario principal, " + a.getString("inicio") + ".";
            case "no_molestar": return DndExpiry.apply(app, a.getString("accion").equals("activar"), a.optInt("minutos", 0));
            case "brillo":
                if (!Settings.System.putInt(app.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                        || !Settings.System.putInt(app.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, Math.round(a.getInt("porcentaje") * 255f / 100)))
                    throw new IllegalArgumentException("No pude aplicar todo el ajuste de brillo. Revisalo antes de repetir.");
                return "Puse el brillo manual al " + a.getInt("porcentaje") + "%.";
            default: throw new IllegalArgumentException("Acción no permitida.");
        }
    }
}
