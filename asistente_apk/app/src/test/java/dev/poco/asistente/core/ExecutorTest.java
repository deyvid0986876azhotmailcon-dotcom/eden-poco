package dev.poco.asistente.core;

import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ExecutorTest {
    private final TestStore store = new TestStore();
    private final TestStore.MutableClock clock = new TestStore.MutableClock();
    private final ToolExecutor executor = new ToolExecutor(store, clock, clock::millis);
    private JSONObject movement() throws Exception {
        return Json.object("tipo", "gasto", "centavos", 350000, "moneda", "ARS", "categoria", "super", "fecha", "2026-10-17");
    }
    private String run(String tool, JSONObject args) throws Exception { return executor.execute("david", UUID.randomUUID().toString(), tool, args); }
    private String create() throws Exception { run("registrar_movimiento", movement()); return store.rows().get(0).id; }
    private String propose(String id) throws Exception {
        String result = run("borrar_registro", Json.object("id", id));
        return result.substring(result.indexOf("escribí SÍ ") + 11, result.indexOf(". PARAR"));
    }
    @Test public void rejectsWrongTypesUnknownFieldsAndAmountsWithoutWrites() throws Exception {
        Object[] bad = {"350000", -1, 0, 1.1, Validation.MONEY_LIMIT, JSONObject.NULL};
        for (Object amount : bad) {
            JSONObject args = movement().put("centavos", amount);
            assertThrows(IllegalArgumentException.class, () -> run("registrar_movimiento", args));
        }
        assertThrows(IllegalArgumentException.class, () -> run("registrar_movimiento", movement().put("confirmado", true)));
        assertThrows(IllegalArgumentException.class, () -> run("registrar_movimiento", movement().put("moneda", "BTC")));
        assertTrue(store.rows().isEmpty()); assertTrue(store.receipts.isEmpty());
    }
    @Test public void rejectsInvalidFutureDatesAndBodyWeight() throws Exception {
        for (String date : new String[]{"2026-02-30", "2026-10-18", "17/10/2026", "1900-01-01"})
            assertThrows(IllegalArgumentException.class, () -> run("registrar_peso", Json.object("kg", 58.4, "fecha", date)));
        for (double kg : new double[]{29.9, 250.1})
            assertThrows(IllegalArgumentException.class, () -> run("registrar_peso", Json.object("kg", kg, "fecha", "2026-10-17")));
        assertTrue(store.rows().isEmpty());
    }
    @Test public void validatesEveryRecordKind() throws Exception {
        create();
        run("registrar_peso", Json.object("fecha", "2026-10-17", "kg", 58.4));
        run("registrar_comida", Json.object("fecha", "2026-10-17", "descripcion", "pollo", "calorias", 500, "proteina", 30));
        run("registrar_entreno", Json.object("fecha", "2026-10-17", "ejercicio", "sentadilla", "series", 3, "repeticiones", 10, "kg", 0));
        run("fijar_presupuesto", Json.object("mes", "2026-10", "categoria", "super", "centavos", 8000000, "moneda", "ARS"));
        run("fijar_meta", Json.object("nombre", "emergencia", "centavos", 50000000, "moneda", "ARS"));
        assertEquals(6, store.rows().size()); assertEquals(6, store.receipts.size());
        assertThrows(IllegalArgumentException.class, () -> run("registrar_entreno", Json.object("fecha", "2026-10-17", "ejercicio", "sentadilla", "series", 3.5, "repeticiones", 10, "kg", 40)));
        assertThrows(IllegalArgumentException.class, () -> run("registrar_comida", Json.object("fecha", "2026-10-17", "descripcion", "pollo", "proteina", -1)));
    }
    @Test public void duplicateDeliveryDoesNotDuplicateMovement() throws Exception {
        String first = executor.execute("david", "message-1", "registrar_movimiento", movement());
        String second = executor.execute("david", "message-1", "registrar_movimiento", movement());
        assertEquals(first, second); assertEquals(1, store.rows().size());
    }
    @Test public void correctionsPreserveIdAndValidateMergedData() throws Exception {
        String id = create();
        run("corregir_registro", Json.object("id", id, "cambios", Json.object("centavos", 123)));
        assertEquals(123, store.find(id).data.getLong("centavos")); assertEquals(2, store.find(id).version);
        assertThrows(IllegalArgumentException.class, () -> run("corregir_registro", Json.object("id", id, "cambios", Json.object("centavos", 0))));
        assertThrows(IllegalArgumentException.class, () -> run("corregir_registro", Json.object("id", id, "cambios", Json.object("id", UUID.randomUUID().toString()))));
        assertEquals(123, store.find(id).data.getLong("centavos"));
    }
    @Test public void deletionNeedsActorBoundSingleUseCode() throws Exception {
        String id = create(), code = propose(id);
        assertEquals(16, code.length()); assertNotNull(store.find(id));
        executor.confirm("other", "c1", code); assertNotNull(store.find(id));
        executor.confirm("david", "c2", "0000"); assertNotNull(store.find(id));
        assertTrue(executor.confirm("david", "c3", code).startsWith("Borrado")); assertNull(store.find(id));
        assertFalse(executor.confirm("david", "c4", code).startsWith("Borrado"));
    }
    @Test public void expirationAndModifiedSnapshotInvalidateDeletion() throws Exception {
        String id = create(), code = propose(id);
        clock.millis += 300000;
        assertTrue(executor.confirm("david", "c1", code).contains("venció")); assertNotNull(store.find(id));
        String newCode = propose(id);
        run("corregir_registro", Json.object("id", id, "cambios", Json.object("centavos", 1)));
        assertTrue(executor.confirm("david", "c2", newCode).contains("cambió")); assertNotNull(store.find(id));
    }
    @Test public void stopAndNewProposalInvalidateOldCode() throws Exception {
        String id = create(), oldCode = propose(id), newCode = propose(id);
        executor.confirm("david", "c1", oldCode); assertNotNull(store.find(id));
        executor.cancel(); executor.confirm("david", "c2", newCode); assertNotNull(store.find(id));
    }
    @Test public void budgetsCannotSilentlyOverwrite() throws Exception {
        JSONObject budget = Json.object("mes", "2026-10", "categoria", "super", "centavos", 100, "moneda", "ARS");
        run("fijar_presupuesto", budget);
        assertThrows(IllegalArgumentException.class, () -> run("fijar_presupuesto", budget));
        assertEquals(1, store.rows().size());
    }
    @Test public void unknownToolAndUnknownRecordCannotMutate() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> run("confirmar_borrado", Json.object("codigo", "123")));
        assertThrows(IllegalArgumentException.class, () -> run("borrar_registro", Json.object("id", UUID.randomUUID().toString())));
        assertTrue(store.rows().isEmpty());
    }
}
