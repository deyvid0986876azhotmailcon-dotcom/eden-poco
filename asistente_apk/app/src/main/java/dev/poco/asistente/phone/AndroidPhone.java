package dev.poco.asistente.phone;

import android.Manifest;
import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.media.session.*;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.view.KeyEvent;
import dev.poco.asistente.AssistantApplication;
import dev.poco.asistente.core.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.json.JSONObject;

public final class AndroidPhone implements PhoneTools.Port {
    private final AssistantApplication app;
    public AndroidPhone(AssistantApplication app) { this.app = app; }
    @Override public JSONObject prepare(String tool, JSONObject args) throws Exception { return new AndroidActions(app, this).prepare(tool, args); }
    private List<PhonePolicy.App> apps() {
        List<PhonePolicy.App> apps = new ArrayList<>();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo r : app.getPackageManager().queryIntentActivities(query, 0))
            // no activityInfo.enabled check: an activity-alias turned on at runtime (YT Music Morphe's icon)
            // keeps enabled=false from its manifest, and the query already returns only enabled components
            if (r.activityInfo != null && r.activityInfo.exported)
                apps.add(new PhonePolicy.App(r.loadLabel(app.getPackageManager()).toString(), r.activityInfo.packageName));
        return apps;
    }
    @Override public String execute(String tool, JSONObject a) throws Exception {
        if (!app.settings.phoneEnabled()) throw new IllegalArgumentException("El control del teléfono está apagado.");
        switch (tool) {
            case "llamar": case "mensaje_whatsapp": case "agendar": case "no_molestar": case "brillo": case "wifi": case "bluetooth":
                return new AndroidActions(app, this).execute(tool, a);
            case "abrir_app":
                PhonePolicy.App found = PhonePolicy.resolve(a.getString("nombre"), apps());
                launchApp(found.pkg); return "Pedí abrir " + found.label + ". Si no apareció, revisá los permisos de Asistente en HyperOS.";
            case "alarma":
                String[] hour = a.getString("hora").split(":");
                launchResolved(new Intent(AlarmClock.ACTION_SET_ALARM).setPackage("com.google.android.deskclock")
                        .putExtra(AlarmClock.EXTRA_HOUR, Integer.parseInt(hour[0])).putExtra(AlarmClock.EXTRA_MINUTES, Integer.parseInt(hour[1]))
                        .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("etiqueta", "Alarma")).putExtra(AlarmClock.EXTRA_SKIP_UI, true));
                return "Le pedí al Reloj una alarma a las " + a.getString("hora") + ". Revisá que haya quedado activada.";
            case "temporizador":
                launchResolved(new Intent(AlarmClock.ACTION_SET_TIMER).setPackage("com.google.android.deskclock")
                        .putExtra(AlarmClock.EXTRA_LENGTH, a.getInt("segundos")).putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("etiqueta", "Temporizador"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true));
                return "Le pedí al Reloj un temporizador de " + a.getInt("segundos") + " segundos. Revisá que esté corriendo.";
            case "musica": return music(a);
            case "leer_notificaciones": return PhoneNotifications.read(a.has("app") ? PhonePolicy.resolve(a.getString("app"), apps()).pkg : null);
            case "consultar_agenda": return calendar(a);
            case "buscar_en_internet": return new dev.poco.asistente.net.TavilyClient().search(a.getString("consulta"));
            case "clima": {
                WeatherProtocol.Place place = WeatherProtocol.place(getJson(WeatherProtocol.geocodeUrl(a.getString("lugar"))));
                int days = a.getInt("dias");
                return ExternalData.wrap(WeatherProtocol.render(place, getJson(WeatherProtocol.forecastUrl(place, days)), days));
            }
            default: throw new IllegalArgumentException("Esa herramienta no está permitida.");
        }
    }
    /** Open-Meteo only (the URLs come from WeatherProtocol, never from the model); small bounded answers. */
    private static JSONObject getJson(String url) throws Exception {
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) throw new IllegalArgumentException("El servicio del clima no respondió. Probá en un rato.");
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                for (int n; (n = in.read(buffer)) != -1; ) {
                    out.write(buffer, 0, n);
                    if (out.size() > 256 * 1024) throw new IllegalArgumentException("El servicio del clima devolvió demasiados datos.");
                }
            }
            return new JSONObject(out.toString("UTF-8"));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("No pude consultar el clima. Revisá la conexión a internet.");
        } finally { connection.disconnect(); }
    }
    private void launchApp(String pkg) {
        if (PhonePolicy.blocked(pkg)) throw new IllegalArgumentException("Esa no la puedo abrir.");
        Intent intent = app.getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent == null) throw new IllegalArgumentException("No encontré esa app instalada.");
        launchResolved(intent);
    }
    void launchResolved(Intent intent) {
        String pkg = intent.getComponent() == null ? intent.getPackage() : intent.getComponent().getPackageName();
        if (PhonePolicy.blocked(pkg)) throw new IllegalArgumentException("Esa no la puedo abrir.");
        ResolveInfo target = app.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (target == null || target.activityInfo == null || !target.activityInfo.exported || !pkg.equals(target.activityInfo.packageName))
            throw new IllegalArgumentException("No encontré una app compatible para ese pedido.");
        intent.setComponent(new ComponentName(pkg, target.activityInfo.name));
        PhoneAccessibility.launch(intent);
    }
    private String music(JSONObject a) throws Exception {
        String action = a.getString("accion");
        if (action.equals("abrir") || action.equals("buscar")) {
            String pkg = a.getString("app").equals("spotify") ? "com.spotify.music" : "app.morphe.android.apps.youtube.music";
            // No URI from the model: a plain-text query through Android's standard play-from-search, pinned to the chosen player.
            if (action.equals("abrir")) { launchApp(pkg); return "Pedí abrir " + a.getString("app") + "."; }
            launchResolved(new Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(pkg)
                    .putExtra(android.app.SearchManager.QUERY, a.getString("consulta"))
                    .putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*"));
            return "Puse «" + a.getString("consulta") + "» en " + (pkg.equals("com.spotify.music") ? "Spotify" : "YouTube Music") + ".";
        }
        if (!PhoneNotifications.connected()) throw new IllegalArgumentException("Habilitá Notificaciones en Control del teléfono para identificar qué app está reproduciendo.");
        MediaSessionManager manager = app.getSystemService(MediaSessionManager.class);
        List<MediaController> sessions;
        try { sessions = manager.getActiveSessions(new ComponentName(app, PhoneNotifications.class)); }
        catch (RuntimeException e) { throw new IllegalArgumentException("No pude acceder al reproductor. Revisá el acceso a notificaciones."); }
        List<MediaController> permitted = new ArrayList<>();
        for (MediaController session : sessions) if (!PhonePolicy.blocked(session.getPackageName())) permitted.add(session);
        int[] states = new int[permitted.size()];
        for (int i = 0; i < states.length; i++) {
            PlaybackState state = permitted.get(i).getPlaybackState();
            states[i] = state == null ? PlaybackState.STATE_NONE : state.getState();
        }
        int key = action.equals("reproducir") ? KeyEvent.KEYCODE_MEDIA_PLAY : action.equals("pausar") ? KeyEvent.KEYCODE_MEDIA_PAUSE
                : action.equals("siguiente") ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PREVIOUS;
        // AudioManager's global routing can hit a blocked package; target the verified session instead.
        MediaController target = permitted.get(PhonePolicy.player(states, action));
        boolean down = target.dispatchMediaButtonEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
        boolean up = target.dispatchMediaButtonEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
        return down && up ? "Mandé al reproductor la orden de " + action + "." : "El reproductor no aceptó la orden. Probá desde su app.";
    }
    private String calendar(JSONObject a) {
        if (app.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
            throw new IllegalArgumentException("En Asistente → Ajustes → Control del teléfono, tocá Permitir lectura de agenda.");
        PhoneTools.CalendarWindow window = new PhoneTools.CalendarWindow(a.optString("desde"), a.optString("hasta"));
        android.net.Uri.Builder uri = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(uri, window.queryStart()); ContentUris.appendId(uri, window.queryEnd());
        String[] columns = {CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY};
        StringBuilder text = new StringBuilder(); int count = 0;
        try (Cursor c = app.getContentResolver().query(uri.build(), columns, CalendarContract.Instances.VISIBLE + "=1", null, CalendarContract.Instances.BEGIN + " ASC")) {
            if (c == null) throw new IllegalArgumentException("No pude leer la agenda.");
            while (c.moveToNext()) {
                boolean allDay = c.getInt(4) != 0;
                if (!window.includes(c.getLong(1), c.getLong(2), allDay)) continue;
                if (count++ == 50 || text.length() > 10000) { text.append("Hay más eventos; pedime un período más corto.\n"); break; }
                String start = Instant.ofEpochMilli(c.getLong(1)).atZone(allDay ? ZoneOffset.UTC : Reminders.ZONE)
                        .format(DateTimeFormatter.ofPattern(allDay ? "dd/MM 'todo el día'" : "dd/MM HH:mm"));
                String end = allDay ? "" : " a " + Instant.ofEpochMilli(c.getLong(2)).atZone(Reminders.ZONE).format(DateTimeFormatter.ofPattern("dd/MM HH:mm"));
                text.append(start).append(end).append(" · ").append(Memory.clip(PhonePolicy.redact(c.getString(0)), 200));
                if (c.getString(3) != null && !c.getString(3).isEmpty()) text.append(" · ").append(Memory.clip(PhonePolicy.redact(c.getString(3)), 200));
                text.append('\n');
            }
        }
        return ExternalData.wrap(count == 0 ? "No encontré eventos en esas fechas." : text.toString());
    }
}
