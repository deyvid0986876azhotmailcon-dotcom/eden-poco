package dev.poco.asistente.core;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class RequestBudgetTest {
    @Test public void sharedClockBoundsAllStagesAndRejectsLateResults() throws Exception {
        AtomicLong now = new AtomicLong(); RequestBudget budget = new RequestBudget(now::get);
        assertEquals("router", budget.read(() -> { now.addAndGet(29_000); return "router"; }, () -> false, RequestBudget.ROUTER_MS));
        assertEquals(41_000, budget.remaining()); assertTrue(budget.canUsePro());
        assertThrows(TimeoutException.class, () -> budget.read(() -> { now.addAndGet(41_000); return "late"; }, () -> false));
        assertThrows(TimeoutException.class, () -> budget.read(() -> { fail("No new call after deadline"); return ""; }, () -> false));
    }
    @Test public void proRequiresFifteenSecondsAndQueueTimeConsumesBudget() {
        AtomicLong now = new AtomicLong(); RequestBudget budget = new RequestBudget(now::get);
        now.set(55_000); assertTrue(budget.canUsePro());
        now.incrementAndGet(); assertFalse(budget.canUsePro());
        now.set(70_000); assertTrue(budget.expired());
    }
    @Test public void stageTimeoutInterruptsStalledReadWithoutWaitingForProvider() throws Exception {
        AtomicLong now = new AtomicLong(); RequestBudget budget = new RequestBudget(now::get);
        CountDownLatch entered = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> answer = caller.submit(() -> {
                assertThrows(TimeoutException.class, () -> budget.read(() -> {
                    entered.countDown();
                    try { new CountDownLatch(1).await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { interrupted.countDown(); throw e; }
                    return "late";
                }, () -> false, RequestBudget.ROUTER_MS));
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS)); now.set(RequestBudget.ROUTER_MS);
            answer.get(2, TimeUnit.SECONDS); assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertEquals(40_000, budget.remaining());
        } finally { caller.shutdownNow(); }
    }
}
