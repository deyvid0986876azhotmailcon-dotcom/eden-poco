package dev.poco.asistente.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import dev.poco.asistente.core.Store;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Validated typed records share one table; payload is JSON, never contains credentials. */
public final class AssistantDb extends SQLiteOpenHelper implements dev.poco.asistente.core.Proactive.Port {
    private final java.io.File documentDir;
    public AssistantDb(Context context) {
        super(context, "asistente.db", null, 4);
        documentDir = new java.io.File(context.getFilesDir(), "documentos");
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE registros (id TEXT PRIMARY KEY, kind TEXT NOT NULL CHECK(kind IN ('movimiento','peso','comida','entreno','presupuesto','meta')), version INTEGER NOT NULL, payload TEXT NOT NULL, budget_key TEXT UNIQUE)");
        db.execSQL("CREATE INDEX registros_kind ON registros(kind)");
        db.execSQL("CREATE TABLE operaciones (id TEXT PRIMARY KEY, fecha TEXT NOT NULL, instante INTEGER NOT NULL, accion TEXT NOT NULL, registro_id TEXT NOT NULL, resultado TEXT NOT NULL)");
        db.execSQL("CREATE INDEX operaciones_fecha ON operaciones(fecha)");
        createMessaging(db);
        createMemory(db);
        db.execSQL("ALTER TABLE salientes ADD COLUMN documento TEXT");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) createMessaging(db);
        if (oldVersion < 3) createMemory(db);
        if (oldVersion < 4) db.execSQL("ALTER TABLE salientes ADD COLUMN documento TEXT");
    }
    private void createMessaging(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE wa_messages (id TEXT PRIMARY KEY NOT NULL, sender TEXT NOT NULL, state TEXT NOT NULL, response TEXT NOT NULL DEFAULT '', updated INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE wa_log (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, sender TEXT NOT NULL, event TEXT NOT NULL)");
    }
    private void createMemory(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE conversacion (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL, usuario TEXT NOT NULL, respuesta TEXT NOT NULL)");
        db.execSQL("CREATE TABLE memoria (id TEXT PRIMARY KEY, texto TEXT NOT NULL CHECK(length(texto)<=200), categoria TEXT NOT NULL CHECK(categoria IN ('gustos','salud','finanzas','rutina','estilo','otro')), fecha TEXT NOT NULL, origen TEXT NOT NULL CHECK(origen IN ('whatsapp','app')), version INTEGER NOT NULL, payload TEXT NOT NULL)");
        db.execSQL("CREATE TABLE recordatorios (id TEXT PRIMARY KEY, texto TEXT NOT NULL, cuando TEXT, repeticion TEXT, hora TEXT, activo INTEGER NOT NULL, ultimo_envio INTEGER NOT NULL, proximo INTEGER NOT NULL, version INTEGER NOT NULL, payload TEXT NOT NULL)");
        db.execSQL("CREATE TABLE salientes (id TEXT PRIMARY KEY, texto TEXT NOT NULL, tipo TEXT NOT NULL, nocturno INTEGER NOT NULL, creado INTEGER NOT NULL, estado TEXT NOT NULL DEFAULT 'pendiente', ofrecido INTEGER NOT NULL DEFAULT 0, entregado INTEGER NOT NULL DEFAULT 0, origen_id TEXT)");
        db.execSQL("CREATE INDEX salientes_estado ON salientes(estado,ofrecido)");
        db.execSQL("CREATE TABLE proactivo_estado (clave TEXT PRIMARY KEY, proximo INTEGER NOT NULL)");
        // The ten-day countdown starts at installation/migration, not at the first bridge poll.
        db.execSQL("INSERT INTO proactivo_estado VALUES ('whatsapp',?)", new Object[]{java.time.ZonedDateTime.now(dev.poco.asistente.core.Reminders.ZONE).plusDays(10).toInstant().toEpochMilli()});
    }
    private ContentValues extraValues(Row row) throws Exception {
        ContentValues v = new ContentValues();
        v.put("id", row.id); v.put("version", row.version); v.put("payload", row.data.toString());
        v.put("texto", row.data.getString("texto"));
        if (row.kind.equals("memoria")) {
            for (String key : new String[]{"categoria", "fecha", "origen"}) v.put(key, row.data.getString(key));
        } else {
            for (String key : new String[]{"cuando", "repeticion", "hora"}) {
                if (row.data.has(key)) v.put(key, row.data.getString(key)); else v.putNull(key);
            }
            v.put("activo", row.data.getBoolean("activo") ? 1 : 0);
            v.put("ultimo_envio", row.data.getLong("ultimo_envio")); v.put("proximo", row.data.getLong("proximo"));
        }
        return v;
    }
    private List<Row> extraRows(String table, String kind) throws Exception {
        List<Row> rows = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,version,payload FROM " + table + (table.equals("recordatorios") ? " WHERE activo=1" : "") + " ORDER BY rowid", null)) {
            while (c.moveToNext()) rows.add(new Row(c.getString(0), kind, c.getLong(1), new JSONObject(c.getString(2))));
        }
        return rows;
    }
    @Override public synchronized List<Row> memories() throws Exception { return extraRows("memoria", "memoria"); }
    @Override public synchronized List<Row> reminders() throws Exception { return extraRows("recordatorios", "recordatorio"); }
    @Override public synchronized List<dev.poco.asistente.core.Memory.Exchange> conversation() {
        List<dev.poco.asistente.core.Memory.Exchange> history = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT usuario,respuesta FROM conversacion ORDER BY seq", null)) {
            while (c.moveToNext()) history.add(new dev.poco.asistente.core.Memory.Exchange(c.getString(0), c.getString(1)));
        }
        return history;
    }
    @Override public synchronized void exchange(String id, String user, String model) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            ContentValues v = new ContentValues(); v.put("id", id); v.put("usuario", user);
            v.put("respuesta", dev.poco.asistente.core.Memory.clip(model, 4000));
            db.insertWithOnConflict("conversacion", null, v, SQLiteDatabase.CONFLICT_IGNORE);
            db.execSQL("DELETE FROM conversacion WHERE seq NOT IN (SELECT seq FROM conversacion ORDER BY seq DESC LIMIT 10)");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    @Override public synchronized void clearConversation() { getWritableDatabase().delete("conversacion", null, null); }
    @Override public synchronized long scheduleState(String key, long initial) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("INSERT OR IGNORE INTO proactivo_estado(clave,proximo) VALUES (?,?)", new Object[]{key, initial});
        try (Cursor c = db.rawQuery("SELECT proximo FROM proactivo_estado WHERE clave=?", new String[]{key})) { c.moveToFirst(); return c.getLong(0); }
    }
    @Override public synchronized void bankTransaction(java.util.concurrent.Callable<Void> action) throws Exception {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try { action.call(); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }
    @Override public synchronized boolean queued(String id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM salientes WHERE id=?", new String[]{id})) { return c.moveToFirst(); }
    }
    @Override public synchronized void enqueue(String id, String text, String kind, boolean night, long now, Row reminder, long next, String state) throws Exception {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            if (queued(id)) { db.setTransactionSuccessful(); return; }
            if (reminder != null) {
                // Scheduling doesn't change the version: a pending cancellation remains bound to the same user-defined schedule.
                Row current = find(reminder.id);
                if (current == null || current.version != reminder.version || current.data.getLong("proximo") != reminder.data.getLong("proximo")) return;
                JSONObject data = dev.poco.asistente.core.Json.copy(current.data);
                data.put("proximo", next); data.put("activo", true);
                db.update("recordatorios", extraValues(new Row(current.id, current.kind, current.version, data)), "id=?", new String[]{current.id});
            }
            ContentValues v = new ContentValues(); v.put("id", id); v.put("texto", text); v.put("tipo", kind); v.put("nocturno", night ? 1 : 0); v.put("creado", now);
            if (reminder != null) v.put("origen_id", reminder.id);
            db.insertOrThrow("salientes", null, v);
            if (state != null) db.execSQL("UPDATE proactivo_estado SET proximo=? WHERE clave=?", new Object[]{next, state});
            messagingLog("Salientes", "Preparado " + kind + " · " + id);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    @Override public synchronized String enqueueDocument(String operation, dev.poco.asistente.core.Documents.Prepared document, long now) throws Exception {
        dev.poco.asistente.core.Documents.validate(document.name, document.mime, document.bytes.length);
        if (!documentDir.isDirectory() && !documentDir.mkdirs()) throw new java.io.IOException("No pude crear documentos");
        String key = java.util.UUID.randomUUID().toString();
        java.io.File file = new java.io.File(documentDir, key);
        boolean committed = false;
        String result = "Dejé «" + document.name + "» listo para enviártelo por WhatsApp.";
        try {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) { out.write(document.bytes); out.getFD().sync(); }
            SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
            try {
                String id = "doc_" + key;
                enqueue(id, "", "documento", true, now, null, 0, null);
                JSONObject metadata = dev.poco.asistente.core.Json.object("archivo", key, "nombre", document.name, "mime", document.mime, "operacion", operation);
                db.execSQL("UPDATE salientes SET documento=? WHERE id=?", new Object[]{metadata.toString(), id});
                finishAction(operation, result);
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            committed = true;
            return result;
        } finally { if (!committed) file.delete(); }
    }
    @Override public synchronized org.json.JSONArray lease(long now, boolean quiet) throws Exception {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            org.json.JSONArray items = new org.json.JSONArray();
            try (Cursor c = db.rawQuery("SELECT id,texto,documento FROM salientes WHERE estado='pendiente' AND (ofrecido=0 OR ofrecido<=?)" + (quiet ? " AND nocturno=1" : "") + " ORDER BY creado,id LIMIT 20", new String[]{Long.toString(now - 600000)})) {
                int bytes = 2;
                while (c.moveToNext()) {
                    JSONObject item = dev.poco.asistente.core.Json.object("id", c.getString(0), "texto", c.getString(1));
                    boolean document = !c.isNull(2);
                    if (document) {
                        if (items.length() > 0) break; // one document per bounded response
                        JSONObject metadata = new JSONObject(c.getString(2));
                        String key = metadata.getString("archivo");
                        if (!key.matches("[0-9a-f-]{36}")) throw new IllegalStateException("Archivo inválido");
                        java.io.File file = new java.io.File(documentDir, key);
                        // A database-only restore must not block all future reminders on a missing file.
                        if (!file.isFile()) {
                            db.execSQL("UPDATE salientes SET estado='cancelado',documento=NULL WHERE id=?", new Object[]{c.getString(0)});
                            finishAction(metadata.getString("operacion"), "No envié el documento: falta el archivo local. Pedime prepararlo otra vez.");
                            messagingLog("Salientes", "Archivo local ausente · " + c.getString(0));
                            continue;
                        }
                        dev.poco.asistente.core.Documents.validate(metadata.getString("nombre"), metadata.getString("mime"), file.length());
                        byte[] data;
                        try (java.io.InputStream in = new java.io.FileInputStream(file); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                            byte[] buffer = new byte[16384]; int n;
                            while ((n = in.read(buffer)) != -1) {
                                dev.poco.asistente.core.Documents.size((long) out.size() + n); out.write(buffer, 0, n);
                            }
                            data = out.toByteArray();
                        }
                        dev.poco.asistente.core.Documents.validate(metadata.getString("nombre"), metadata.getString("mime"), data.length);
                        item = dev.poco.asistente.core.Json.object("id", c.getString(0), "documento", dev.poco.asistente.core.Json.object(
                                "nombre", metadata.getString("nombre"), "mime", metadata.getString("mime"), "base64", java.util.Base64.getEncoder().encodeToString(data)));
                    }
                    int length = document ? 0 : item.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
                    if (!document && bytes + length > 192 * 1024) break;
                    items.put(item); bytes += length;
                    if (document) break;
                }
            }
            for (int i = 0; i < items.length(); i++) {
                String id = items.getJSONObject(i).getString("id");
                db.execSQL("UPDATE salientes SET ofrecido=? WHERE id=?", new Object[]{now, id});
                messagingLog("Salientes", "Ofrecido · " + id);
            }
            db.setTransactionSuccessful(); return items;
        } finally { db.endTransaction(); }
    }
    @Override public synchronized void acknowledge(List<String> ids, long now) throws Exception {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            for (String id : ids) {
                String origin = null;
                try (Cursor c = db.rawQuery("SELECT origen_id,documento FROM salientes WHERE id=? AND estado='pendiente' AND ofrecido>0", new String[]{id})) {
                    if (!c.moveToFirst()) continue;
                    origin = c.getString(0);
                    if (!c.isNull(1)) {
                        JSONObject document = new JSONObject(c.getString(1));
                        finishAction(document.getString("operacion"), "Te mandé «" + document.getString("nombre") + "» por WhatsApp.");
                    }
                }
                db.execSQL("UPDATE salientes SET estado='entregado',entregado=?,texto='',documento=NULL WHERE id=?", new Object[]{now, id});
                if (origin != null) {
                    try (Cursor c = db.rawQuery("SELECT payload FROM recordatorios WHERE id=?", new String[]{origin})) {
                        if (c.moveToFirst()) {
                            JSONObject d = new JSONObject(c.getString(0)); d.put("ultimo_envio", now);
                            if (d.getLong("proximo") < 0) d.put("activo", false);
                            db.execSQL("UPDATE recordatorios SET ultimo_envio=?,payload=?,activo=? WHERE id=?", new Object[]{now, d.toString(), d.getBoolean("activo") ? 1 : 0, origin});
                        }
                    }
                }
                messagingLog("Salientes", "Confirmado por puente · " + id);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    public synchronized void cancelOutgoing() {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            for (Row row : reminders()) if (row.data.getLong("proximo") < 0) {
                JSONObject data = dev.poco.asistente.core.Json.copy(row.data); data.put("activo", false);
                db.update("recordatorios", extraValues(new Row(row.id, row.kind, row.version, data)), "id=?", new String[]{row.id});
            }
            db.execSQL("UPDATE salientes SET estado='cancelado',texto='',documento=NULL WHERE estado='pendiente'");
            db.setTransactionSuccessful();
        } catch (Exception e) { throw new IllegalStateException("No se pudo cancelar la cola saliente."); }
        finally { db.endTransaction(); }
    }
    /** Atomic persistent claim. Never prune these IDs: old updates must not repeat a financial action. */
    public synchronized boolean claimNotice(String id, String sender) {
        ContentValues values = new ContentValues();
        values.put("id", id); values.put("sender", sender); values.put("state", "en cola"); values.put("updated", System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict("wa_messages", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }
    public synchronized void noticeState(String id, String state, String remaining) {
        ContentValues values = new ContentValues(); values.put("state", state); values.put("updated", System.currentTimeMillis());
        if (remaining != null) values.put("response", remaining);
        getWritableDatabase().update("wa_messages", values, "id=?", new String[]{id});
    }
    public synchronized void messagingLog(String sender, String event) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues(); values.put("time", System.currentTimeMillis()); values.put("sender", sender); values.put("event", event);
        db.insertOrThrow("wa_log", null, values);
        db.execSQL("DELETE FROM wa_log WHERE id NOT IN (SELECT id FROM wa_log ORDER BY id DESC LIMIT 500)");
    }
    public synchronized void cancelNotices() {
        getWritableDatabase().execSQL("UPDATE wa_messages SET state='cancelado' WHERE state IN ('en cola','procesando')");
    }
    public synchronized void recoverNotices() {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues(); values.put("state", "interrumpido; revisar Resumen antes de repetir");
        int count = db.update("wa_messages", values, "state IN ('en cola','procesando','enviando')", null);
        if (count > 0) messagingLog("Sistema", "Proceso reiniciado: " + count + " pedidos interrumpidos. No se reejecutan; revisar Resumen y respuestas pendientes.");
    }
    public synchronized String messagingHistory() {
        StringBuilder out = new StringBuilder("Salientes proactivos (sin texto):\n");
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,tipo,estado,creado FROM salientes ORDER BY creado DESC LIMIT 100", null)) {
            while (c.moveToNext()) out.append(logTime(c.getLong(3))).append(" · ").append(c.getString(1)).append(" · ")
                    .append(c.getString(2)).append(" · ").append(c.getString(0)).append('\n');
        }
        out.append("\nRespuestas pendientes (sin reenvío automático):\n");
        try (Cursor c = getReadableDatabase().rawQuery("SELECT sender,state,response,updated FROM wa_messages WHERE response<>'' ORDER BY updated DESC LIMIT 50", null)) {
            while (c.moveToNext()) out.append(logTime(c.getLong(3))).append(" · ").append(c.getString(0)).append(" · ")
                    .append(c.getString(1)).append('\n').append(c.getString(2)).append("\n\n");
        }
        out.append("\nRegistro (últimos 200):\n");
        try (Cursor c = getReadableDatabase().rawQuery("SELECT time,sender,event FROM wa_log ORDER BY id DESC LIMIT 200", null)) {
            while (c.moveToNext()) out.append(logTime(c.getLong(0))).append(" · ").append(c.getString(1)).append(" · ").append(c.getString(2)).append('\n');
        }
        return out.toString();
    }
    private String logTime(long time) {
        return Instant.ofEpochMilli(time).atZone(dev.poco.asistente.core.Reminders.ZONE).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm:ss"));
    }
    @Override public synchronized List<Row> rows() throws Exception {
        List<Row> rows = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,kind,version,payload FROM registros ORDER BY rowid", null)) {
            while (c.moveToNext()) rows.add(read(c));
        }
        return rows;
    }
    @Override public synchronized Row find(String id) throws Exception {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,kind,version,payload FROM registros WHERE id=?", new String[]{id})) {
            return c.moveToFirst() ? read(c) : findExtra(id);
        }
    }
    private Row findExtra(String id) throws Exception {
        for (String table : new String[]{"memoria", "recordatorios"}) {
            try (Cursor c = getReadableDatabase().rawQuery("SELECT id,version,payload FROM " + table + " WHERE id=?" + (table.equals("recordatorios") ? " AND activo=1" : ""), new String[]{id})) {
                if (c.moveToFirst()) return new Row(c.getString(0), table.equals("memoria") ? "memoria" : "recordatorio", c.getLong(1), new JSONObject(c.getString(2)));
            }
        }
        return null;
    }
    private Row read(Cursor c) throws Exception { return new Row(c.getString(0), c.getString(1), c.getLong(2), new JSONObject(c.getString(3))); }
    @Override public synchronized String receipt(String id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT resultado FROM operaciones WHERE id=?", new String[]{id})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }
    @Override public synchronized String mutate(String id, Row before, Row after, String result, long time) throws Exception {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String previous = receipt(id);
            if (previous != null) { db.setTransactionSuccessful(); return previous; }
            String target = before == null ? after.id : before.id;
            Row targetRow = before == null ? after : before;
            String table = targetRow.kind.equals("memoria") ? "memoria" : targetRow.kind.equals("recordatorio") ? "recordatorios" : "registros";
            if (before == null) {
                if (table.equals("memoria") && memories().size() >= 100) throw new IllegalArgumentException("Máximo 100 entradas de memoria.");
                if (table.equals("recordatorios") && reminders().size() >= 50) throw new IllegalArgumentException("Máximo 50 recordatorios activos.");
                db.insertOrThrow(table, null, values(after));
            }
            else {
                String[] args = {before.id, Long.toString(before.version)};
                int changed = after == null ? db.delete(table, "id=? AND version=?", args)
                        : db.update(table, values(after), "id=? AND version=?", args);
                if (changed != 1) throw new IllegalArgumentException("El registro cambió. Consultá su estado y repetí el pedido.");
            }
            if (after == null && before.kind.equals("recordatorio")) db.execSQL("UPDATE salientes SET estado='cancelado',texto='',documento=NULL WHERE origen_id=? AND estado<>'entregado'", new Object[]{before.id});
            ContentValues log = new ContentValues();
            log.put("id", id); log.put("instante", time);
            log.put("fecha", Instant.ofEpochMilli(time).atZone(dev.poco.asistente.core.Reminders.ZONE).toLocalDate().toString());
            log.put("accion", before == null ? "registrar" : after == null ? "borrar" : "corregir");
            log.put("registro_id", target); log.put("resultado", result);
            db.insertOrThrow("operaciones", null, log);
            db.setTransactionSuccessful();
            return result;
        } finally { db.endTransaction(); }
    }
    private ContentValues values(Row row) throws Exception {
        ContentValues values = new ContentValues();
        if (row.kind.equals("memoria") || row.kind.equals("recordatorio")) return extraValues(row);
        values.put("id", row.id); values.put("kind", row.kind); values.put("version", row.version); values.put("payload", row.data.toString());
        if (row.kind.equals("presupuesto")) values.put("budget_key", row.data.getString("mes") + "|" + row.data.getString("moneda") + "|" + row.data.getString("categoria"));
        else values.putNull("budget_key");
        return values;
    }
    @Override public synchronized String history(String date) {
        StringBuilder text = new StringBuilder("Acciones de ").append(date).append(" (últimas 50):\n");
        try (Cursor c = getReadableDatabase().rawQuery("SELECT resultado FROM operaciones WHERE fecha=? ORDER BY instante DESC LIMIT 50", new String[]{date})) {
            while (c.moveToNext()) text.append(c.getString(0)).append('\n');
        }
        return text.toString();
    }
    @Override public synchronized void beginAction(String id, String tool, long time) {
        ContentValues v = new ContentValues();
        v.put("id", id); v.put("instante", time);
        v.put("fecha", Instant.ofEpochMilli(time).atZone(dev.poco.asistente.core.Reminders.ZONE).toLocalDate().toString());
        v.put("accion", tool); v.put("registro_id", "");
        v.put("resultado", "Pedido de " + tool.replace('_', ' ') + " iniciado; resultado todavía sin confirmar. Revisá antes de repetirlo.");
        getWritableDatabase().insertOrThrow("operaciones", null, v);
    }
    @Override public synchronized void finishAction(String id, String result) {
        ContentValues v = new ContentValues(); v.put("resultado", result);
        if (getWritableDatabase().update("operaciones", v, "id=?", new String[]{id}) != 1) throw new IllegalStateException("Falta el registro de la acción.");
    }
}
