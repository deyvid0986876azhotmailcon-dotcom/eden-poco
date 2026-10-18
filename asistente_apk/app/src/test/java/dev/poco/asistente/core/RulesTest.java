package dev.poco.asistente.core;

import org.junit.Test;
import java.time.LocalDate;
import static org.junit.Assert.*;

public class RulesTest {
    private final LocalDate today = LocalDate.of(2026, 10, 17);
    private Rules.Result parse(String text) throws Exception { return Rules.parse(text, today, "ARS"); }
    @Test public void parsesRioplatenseExpenseExactlyInCents() throws Exception {
        Rules.Result r = parse("Gasté 3.500,25 en súper");
        assertEquals("registrar_movimiento", r.tool);
        assertEquals(350025, r.args.getLong("centavos")); assertEquals("super", r.args.getString("categoria"));
        assertEquals("gasto", r.args.getString("tipo")); assertEquals("2026-10-17", r.args.getString("fecha"));
    }
    @Test public void incomeAndDefaultCurrency() throws Exception {
        Rules.Result r = Rules.parse("cobré 450000 de sueldo", today, "PYG");
        assertEquals(45000000, r.args.getLong("centavos")); assertEquals("ingreso", r.args.getString("tipo"));
        assertEquals("PYG", r.args.getString("moneda"));
    }
    @Test public void bodyWeightAndTrainingLoadAreDistinct() throws Exception {
        assertEquals(58.4, parse("peso 58,4").args.getDouble("kg"), 0.00001);
        Rules.Result r = parse("hice sentadilla 3x10 con 40");
        assertEquals("registrar_entreno", r.tool); assertEquals(3, r.args.getInt("series"));
        assertEquals(10, r.args.getInt("repeticiones")); assertEquals(40, r.args.getDouble("kg"), 0);
    }
    @Test public void missingAndAmbiguousAmountsAskInsteadOfGuessing() throws Exception {
        assertNotNull(parse("cobré 450000").question);
        assertNotNull(parse("gaste 3.50 en super").question);
        assertNotNull(parse("gaste 3,555 en super").question);
        assertNotNull(parse("gaste -3500 en super").question);
        assertNotNull(parse("gaste 9999999999999999999999999999 en super").question);
    }
    @Test public void multipleActionsOrDatesAreNotPartiallyParsed() throws Exception {
        assertNull(parse("gasté 3500 en super y 500 en taxi"));
        assertNull(parse("gasté 3500 en super ayer"));
        assertNull(parse("peso 58,4 ayer"));
        assertNull(parse("hice sentadilla 3x10"));
        assertNull(parse("no gasté 3500 en super"));
        assertNull(parse("comí arroz ayer"));
        assertNull(parse("comí arroz y gasté 3500"));
    }
    @Test public void localCrudAndNoNutritionGuess() throws Exception {
        assertEquals("fijar_presupuesto", parse("presupuesto 80000 en super 2026-10").tool);
        assertEquals("fijar_meta", parse("meta 500000 para emergencia").tool);
        assertFalse(parse("Comí arroz con pollo").args.has("calorias"));
        String id = "b954710d-d5eb-4280-ae4b-ded75fab4bdf";
        assertEquals(id, parse("borrar " + id).args.getString("id"));
        assertEquals(123, parse("corregir " + id + " {\"centavos\":123}").args.getJSONObject("cambios").getLong("centavos"));
    }
    @Test public void centsDoNotRound() {
        assertEquals(101, Rules.cents("1,01")); assertEquals(123456789, Rules.cents("1.234.567,89"));
        assertThrows(IllegalArgumentException.class, () -> Rules.cents("1,001"));
        assertThrows(IllegalArgumentException.class, () -> Rules.cents("1e6"));
    }
}
