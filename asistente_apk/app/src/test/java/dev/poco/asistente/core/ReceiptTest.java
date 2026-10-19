package dev.poco.asistente.core;

import static org.junit.Assert.assertEquals;

import java.time.LocalDate;
import org.json.JSONObject;
import org.junit.Test;

public class ReceiptTest {
    private final LocalDate today = LocalDate.of(2026, 10, 19);
    @Test public void moneyUsesSymbolsAndThousands() {
        assertEquals("₲ 25.000", Receipt.money(2500000, "PYG"));
        assertEquals("₲ 1.250.000", Receipt.money(125000000, "PYG"));
        assertEquals("$ 3.500", Receipt.money(350000, "ARS"));
        assertEquals("US$ 10,50", Receipt.money(1050, "USD"));
        assertEquals("-€ 1", Receipt.money(-100, "EUR"));
    }
    @Test public void describesRecordsInWords() throws Exception {
        JSONObject spent = new JSONObject().put("tipo", "gasto").put("centavos", 2500000).put("moneda", "PYG")
                .put("categoria", "almuerzo").put("fecha", "2026-10-19");
        assertEquals("un gasto de ₲ 25.000 en almuerzo (hoy)", Receipt.describe("movimiento", spent, today));
        assertEquals("tu peso: 58,4 kg (ayer)", Receipt.describe("peso", new JSONObject().put("kg", 58.4).put("fecha", "2026-10-18"), today));
        assertEquals("el entreno: sentadilla 3×10 con 40 kg (17/10)", Receipt.describe("entreno", new JSONObject()
                .put("ejercicio", "sentadilla").put("series", 3).put("repeticiones", 10).put("kg", 40).put("fecha", "2026-10-17"), today));
    }
}
