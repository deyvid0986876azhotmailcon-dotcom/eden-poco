package dev.poco.asistente.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import dev.poco.asistente.core.Store;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Validated typed records share one table; payload is JSON, never contains credentials. */
public final class AssistantDb extends SQLiteOpenHelper implements Store {
    public AssistantDb(Context context) { super(context, "asistente.db", null, 1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE registros (id TEXT PRIMARY KEY, kind TEXT NOT NULL CHECK(kind IN ('movimiento','peso','comida','entreno','presupuesto','meta')), version INTEGER NOT NULL, payload TEXT NOT NULL, budget_key TEXT UNIQUE)");
        db.execSQL("CREATE INDEX registros_kind ON registros(kind)");
        db.execSQL("CREATE TABLE operaciones (id TEXT PRIMARY KEY, fecha TEXT NOT NULL, instante INTEGER NOT NULL, accion TEXT NOT NULL, registro_id TEXT NOT NULL, resultado TEXT NOT NULL)");
        db.execSQL("CREATE INDEX operaciones_fecha ON operaciones(fecha)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Never silently destroy David's data on an upgrade.
        throw new IllegalStateException("Falta una migración de la base de datos.");
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
            return c.moveToFirst() ? read(c) : null;
        }
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
            if (before == null) db.insertOrThrow("registros", null, values(after));
            else {
                String[] args = {before.id, Long.toString(before.version)};
                int changed = after == null ? db.delete("registros", "id=? AND version=?", args)
                        : db.update("registros", values(after), "id=? AND version=?", args);
                if (changed != 1) throw new IllegalArgumentException("El registro cambió. Consultá su estado y repetí el pedido.");
            }
            ContentValues log = new ContentValues();
            log.put("id", id); log.put("instante", time);
            log.put("fecha", Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate().toString());
            log.put("accion", before == null ? "registrar" : after == null ? "borrar" : "corregir");
            log.put("registro_id", target); log.put("resultado", result);
            db.insertOrThrow("operaciones", null, log);
            db.setTransactionSuccessful();
            return result;
        } finally { db.endTransaction(); }
    }
    private ContentValues values(Row row) throws Exception {
        ContentValues values = new ContentValues();
        values.put("id", row.id); values.put("kind", row.kind); values.put("version", row.version); values.put("payload", row.data.toString());
        if (row.kind.equals("presupuesto")) values.put("budget_key", row.data.getString("mes") + "|" + row.data.getString("moneda") + "|" + row.data.getString("categoria"));
        else values.putNull("budget_key");
        return values;
    }
    @Override public synchronized String history(String date) {
        StringBuilder text = new StringBuilder("Acciones de ").append(date).append(" (últimas 50):\n");
        try (Cursor c = getReadableDatabase().rawQuery("SELECT accion,registro_id FROM operaciones WHERE fecha=? ORDER BY instante DESC LIMIT 50", new String[]{date})) {
            while (c.moveToNext()) text.append(c.getString(0)).append(' ').append(c.getString(1)).append('\n');
        }
        return text.toString();
    }
}
