package dev.poco.asistente.core;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class BankFeedTest {
    private final ProactiveTest.Port store = new ProactiveTest.Port();
    private final ProactiveTest.ClockAt clock = new ProactiveTest.ClockAt("2026-10-21T21:00");
    private final AssistantCore core = new AssistantCore(store, new ProactiveTest.Settings(), (k, m, b) -> { throw new AssertionError(); }, clock);
    private static final String BATCH = "{\"version\":1,\"movimientos\":["
            + "{\"id\":\"19a0b1c2d3e4f5a6\",\"tipo\":\"gasto\",\"monto\":85500,\"moneda\":\"PYG\",\"fecha\":\"2026-10-20T12:15\",\"detalle\":\"SUPERMERCADO EJEMPLO\"},"
            + "{\"id\":\"19a0b1c2d3e4f5a7\",\"tipo\":\"ingreso\",\"monto\":875000,\"moneda\":\"PYG\",\"fecha\":\"2026-10-18T01:03\",\"detalle\":\"Transferencia de BANCO BASA S.A.\"}]}";

    private BankFeed feed(AtomicReference<String> body, AtomicInteger calls) {
        return new BankFeed(store, core, () -> { calls.incrementAndGet(); return body.get(); });
    }

    @Test public void recordsEachMailOnceAndSendsOneMessagePerBatch() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        BankFeed feed = feed(new AtomicReference<>(BATCH), calls);
        long now = clock.millis();
        feed.run(now, core.cancellationToken(), () -> false);
        assertEquals(2, store.rows().size());
        JSONObject expense = store.rows().stream().filter(r -> "gasto".equals(r.data.optString("tipo"))).findFirst().get().data;
        assertEquals(8550000L, expense.getLong("centavos"));
        assertEquals("compras", expense.getString("categoria"));
        assertEquals("2026-10-20", expense.getString("fecha"));
        assertEquals(1, store.out.size());
        String message = store.out.values().iterator().next().getString("texto");
        assertTrue(message, message.contains("SUPERMERCADO EJEMPLO") && message.contains("BANCO BASA"));
        assertFalse(store.out.values().iterator().next().getBoolean("night")); // waits for the morning in quiet hours
        // within 30 minutes: no fetch at all
        feed.run(now + 60_000, core.cancellationToken(), () -> false);
        assertEquals(1, calls.get());
        // later, the same mails come back: nothing new is noted or said
        feed.run(now + BankFeed.INTERVAL, core.cancellationToken(), () -> false);
        assertEquals(2, calls.get()); assertEquals(2, store.rows().size()); assertEquals(1, store.out.size());
    }

    @Test public void unknownMailAsksOnceAndRecordsNothing() throws Exception {
        String body = "{\"version\":1,\"movimientos\":[{\"id\":\"19a0b1c2d3e4f5a8\",\"tipo\":\"desconocido\",\"asunto\":\"Débito automático 44556677889\"}]}";
        BankFeed feed = feed(new AtomicReference<>(body), new AtomicInteger());
        feed.run(clock.millis(), core.cancellationToken(), () -> false);
        feed.run(clock.millis() + BankFeed.INTERVAL, core.cancellationToken(), () -> false);
        assertTrue(store.rows().isEmpty());
        assertEquals(1, store.out.size());
        String message = store.out.values().iterator().next().getString("texto");
        assertTrue(message, message.contains("Débito automático") && !message.contains("44556677889"));
    }

    @Test public void untrustedBatchesAreRejectedWhole() throws Exception {
        for (String bad : new String[]{"{\"error\":\"no autorizado\"}", "no es json",
                BATCH.replace("\"detalle\":\"SUPERMERCADO EJEMPLO\"", "\"detalle\":\"X\",\"cuenta\":\"1234\""),
                BATCH.replace("19a0b1c2d3e4f5a6", "../../etc"), BATCH.replace("\"monto\":85500", "\"monto\":-5"),
                BATCH.replace("\"monto\":85500", "\"monto\":85.5"), BATCH.replace("\"PYG\"", "\"USD\"")}) {
            BankFeed feed = feed(new AtomicReference<>(bad), new AtomicInteger());
            feed.run(clock.millis(), core.cancellationToken(), () -> false);
            assertTrue(bad, store.rows().isEmpty()); assertTrue(bad, store.out.isEmpty());
        }
    }

    @Test public void oneRejectedMovementDoesNotLoseTheOthers() throws Exception {
        String future = BATCH.replace("2026-10-20T12:15", "2026-12-24T20:00"); // the executor rejects future dates
        feed(new AtomicReference<>(future), new AtomicInteger()).run(clock.millis(), core.cancellationToken(), () -> false);
        assertEquals(1, store.rows().size()); assertEquals("ingreso", store.rows().get(0).data.getString("tipo"));
    }

    @Test public void pausedByPararFetchesNothing() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        core.stop();
        BankFeed feed = feed(new AtomicReference<>(BATCH), calls);
        feed.run(clock.millis(), core.cancellationToken(), core::proactivePaused);
        assertEquals(0, calls.get()); assertTrue(store.rows().isEmpty());
    }

    @Test public void categories() {
        assertEquals("telefono", BankFeed.category(new BankFeed.Item("x", "gasto", 1, "", "TIGO Compra de saldo")));
        assertEquals("servicios", BankFeed.category(new BankFeed.Item("x", "gasto", 1, "", "ANDE")));
        assertEquals("transferencias", BankFeed.category(new BankFeed.Item("x", "gasto", 1, "", "Transferencia a BANCO ITAU")));
        assertEquals("ingresos", BankFeed.category(new BankFeed.Item("x", "ingreso", 1, "", "Transferencia de BANCO BASA")));
    }

    @Test public void failedNoticeRollsBackRecordsAndReceiptsAndRetryIsComplete() throws Exception {
        ProactiveTest.Port failing = new ProactiveTest.Port() {
            boolean fail = true;
            @Override public void enqueue(String id, String text, String kind, boolean night, long now,
                    Store.Row row, long next, String state) throws Exception {
                if (fail) { fail = false; throw new java.io.IOException("disk full"); }
                super.enqueue(id, text, kind, night, now, row, next, state);
            }
        };
        AssistantCore c = new AssistantCore(failing, new ProactiveTest.Settings(), (k,m,b) -> { throw new AssertionError(); }, clock);
        BankFeed feed = new BankFeed(failing, c, () -> BATCH);
        assertThrows(java.io.IOException.class, () -> feed.run(clock.millis(), c.cancellationToken(), () -> false));
        assertTrue(failing.rows().isEmpty()); assertTrue(failing.receipts.isEmpty()); assertTrue(failing.out.isEmpty());
        feed.run(clock.millis() + BankFeed.INTERVAL, c.cancellationToken(), () -> false);
        assertEquals(2, failing.rows().size()); assertEquals(1, failing.out.size());
    }

    @Test public void stalledFetchDoesNotDelayPollOrPararAndLateBatchIsDiscarded() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
        ProactiveTest.Settings settings = new ProactiveTest.Settings();
        Proactive proactive = new Proactive(store, settings, (k,m,b) -> { throw new AssertionError(); }, clock, core);
        proactive.bank = new BankFeed(store, core, () -> {
            entered.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS); return BATCH;
        }) ;
        // The bank worker holds the feed monitor throughout run; acquiring it after release waits for completion.
        java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            store.enqueue("due", "Tomá agua", "recordatorio", true, clock.millis(), null, 0, null);
            assertEquals(1, worker.submit(proactive::poll).get(1, java.util.concurrent.TimeUnit.SECONDS).length());
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            worker.submit(core::stop).get(1, java.util.concurrent.TimeUnit.SECONDS);
            release.countDown();
            worker.submit(() -> { synchronized (proactive.bank) { finished.countDown(); } }).get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(0, finished.getCount()); assertTrue(store.rows().isEmpty()); assertEquals(1, store.out.size());
        } finally { release.countDown(); worker.shutdownNow(); }
    }
}
