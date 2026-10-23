package dev.poco.asistente.bridge;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class BridgeWatchdogTest {
    private static class Fake implements BridgeWatchdog.Actions {
        long now;
        int restarts, notices;
        boolean failNotice, failRestart, failLog, blocked;
        boolean interactive, connected = true;
        public boolean screenInteractive() { return interactive; }
        public boolean accessibilityConnected() { return connected; }
        final List<String> logs = new ArrayList<>();
        public boolean notice(String text) {
            notices++;
            if (failNotice) throw new IllegalStateException();
            return !blocked;
        }
        public void restart() { restarts++; if (failRestart) throw new SecurityException(); }
        public void log(String event) { if (failLog) throw new IllegalStateException(); logs.add(event); }
        BridgeWatchdog watchdog() { return new BridgeWatchdog(() -> now, this); }
    }
    @Test public void onlyScreenOffWithAccessibilityCanRestart() {
        for (boolean interactive : new boolean[]{false, true}) {
            for (boolean connected : new boolean[]{false, true}) {
                Fake fake = new Fake(); BridgeWatchdog watchdog = fake.watchdog();
                fake.interactive = interactive; fake.connected = connected;
                fake.now = BridgeHealth.GRACE_MS;
                watchdog.run();
                assertEquals(!interactive && connected ? 1 : 0, fake.restarts);
                assertEquals(1, fake.notices);
                assertTrue(watchdog.isSilent());
                watchdog.poll(null);
                assertFalse(watchdog.isSilent());
            }
        }
    }
    @Test public void skippedAttemptsDoNotConsumeLimitAndEligibilityDoesNotResetIt() {
        Fake fake = new Fake(); BridgeWatchdog watchdog = fake.watchdog();
        fake.now = BridgeHealth.GRACE_MS; fake.interactive = true;
        watchdog.run(); assertEquals(0, fake.restarts);
        fake.interactive = false; fake.connected = false;
        watchdog.run(); assertEquals(0, fake.restarts);
        fake.connected = true;
        watchdog.run(); assertEquals(1, fake.restarts);
        fake.now += BridgeWatchdog.RETRY_MS - 1;
        fake.interactive = true; watchdog.run();
        fake.interactive = false; watchdog.run(); assertEquals(1, fake.restarts);
        fake.now++; watchdog.run(); assertEquals(2, fake.restarts);
    }
    @Test public void failuresDoNotCancelPeriodicScheduling() throws Exception {
        Fake fake = new Fake();
        BridgeWatchdog watchdog = fake.watchdog();
        fake.now = BridgeHealth.GRACE_MS;
        fake.failNotice = fake.failRestart = fake.failLog = true;
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch repeated = new CountDownLatch(3);
        try {
            ScheduledFuture<?> future = executor.scheduleWithFixedDelay(() -> {
                watchdog.run(); repeated.countDown();
            }, 0, 1, TimeUnit.MILLISECONDS);
            assertTrue(repeated.await(3, TimeUnit.SECONDS));
            assertFalse(future.isDone());
        } finally {
            executor.shutdownNow(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
        assertEquals(1, fake.restarts);
        fake.failNotice = fake.failLog = false;
        watchdog.run();
        assertTrue(fake.logs.contains("Salud: puente sin respuesta (aviso mostrado)"));
    }
    @Test public void rateLimitIncludesFailuresAndRollsOverAtOneHour() {
        Fake fake = new Fake(); BridgeWatchdog watchdog = fake.watchdog();
        fake.failRestart = true;
        fake.now = BridgeHealth.GRACE_MS;
        for (int i = 0; i < 60; i++) {
            watchdog.run(); fake.now += 60_000L;
        }
        assertEquals(4, fake.restarts);
        watchdog.run(); assertEquals(5, fake.restarts);
        fake.now += BridgeWatchdog.RETRY_MS - 1;
        watchdog.run(); assertEquals(5, fake.restarts);
        fake.now++; watchdog.run(); assertEquals(6, fake.restarts);
    }
    @Test public void pollRecoveryResetsAttemptsEvenWhenWhatsappIsUnlinked() {
        Fake fake = new Fake(); BridgeWatchdog watchdog = fake.watchdog();
        fake.now = BridgeHealth.GRACE_MS; watchdog.run(); assertEquals(1, fake.restarts);
        fake.now++; watchdog.poll("desvinculado"); watchdog.run();
        assertTrue(fake.logs.contains("Salud: puente de vuelta"));
        fake.now += BridgeHealth.GRACE_MS; watchdog.run();
        assertEquals(2, fake.restarts); // Less than 15 minutes since the previous attempt.
    }
    @Test public void blockedNotificationsAreRetriedWithoutBlockingRecovery() {
        Fake fake = new Fake(); BridgeWatchdog watchdog = fake.watchdog();
        fake.blocked = true; fake.now = BridgeHealth.GRACE_MS;
        watchdog.run(); watchdog.run();
        assertEquals(2, fake.notices); assertEquals(1, fake.restarts);
        fake.blocked = false; watchdog.run(); watchdog.run();
        assertEquals(3, fake.notices);
        watchdog.poll(null); watchdog.run(); assertEquals(4, fake.notices);
    }
}
