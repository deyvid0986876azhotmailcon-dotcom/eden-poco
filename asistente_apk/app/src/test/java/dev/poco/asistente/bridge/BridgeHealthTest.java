package dev.poco.asistente.bridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class BridgeHealthTest {
    @Test public void startupSilenceSleepAndRecoveryUseWallTime() {
        BridgeHealth health = new BridgeHealth(1000);
        assertNull(health.notice(1000 + BridgeHealth.GRACE_MS - 1));
        assertEquals(BridgeHealth.SILENT, health.notice(1000 + BridgeHealth.GRACE_MS));
        assertEquals(BridgeHealth.SILENT, health.notice(9_000_000));
        health.poll("conectado", 9_000_001); assertNull(health.notice(9_000_001));
    }
    @Test public void repeatedPollsDoNotResetDisconnectionAndReconnectResetsIt() {
        BridgeHealth health = new BridgeHealth(0);
        health.poll("desconectado", 0);
        health.poll("desconectado", BridgeHealth.GRACE_MS - 1);
        assertNull(health.notice(BridgeHealth.GRACE_MS - 1));
        assertEquals(BridgeHealth.DISCONNECTED, health.notice(BridgeHealth.GRACE_MS));
        health.poll("conectado", BridgeHealth.GRACE_MS); assertNull(health.notice(BridgeHealth.GRACE_MS));
        health.poll("desconectado", BridgeHealth.GRACE_MS + 1); assertNull(health.notice(BridgeHealth.GRACE_MS + 2));
    }
    @Test public void logoutIsImmediateAndSilenceHasPriorityUntilHeartbeatReturns() {
        BridgeHealth health = new BridgeHealth(0);
        health.poll("desvinculado", 1); assertEquals(BridgeHealth.UNLINKED, health.notice(1));
        assertEquals(BridgeHealth.SILENT, health.notice(BridgeHealth.GRACE_MS + 1));
        health.poll("desvinculado", BridgeHealth.GRACE_MS + 2);
        assertEquals(BridgeHealth.UNLINKED, health.notice(BridgeHealth.GRACE_MS + 2));
        health.poll("conectado", BridgeHealth.GRACE_MS + 3); assertNull(health.notice(BridgeHealth.GRACE_MS + 3));
    }
    @Test public void legacyPollAndBackwardsClockDoNotCreateFalseAlarm() {
        BridgeHealth health = new BridgeHealth(1_000_000);
        health.poll(null, 1_000_001); assertNull(health.notice(0));
        assertNull(health.notice(BridgeHealth.GRACE_MS - 1));
    }
}
