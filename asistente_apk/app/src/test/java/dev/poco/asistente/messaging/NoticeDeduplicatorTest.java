package dev.poco.asistente.messaging;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class NoticeDeduplicatorTest {
    private NotificationPolicy.Message message(String text, long time) {
        return new NotificationPolicy.Message(new NotificationPolicy.Sender("Yo David", "", ""), text, time);
    }
    @Test public void repeatedUpdatesAndNewAdapterShareDurableLedger() {
        Set<String> storedIds = new HashSet<>(); // stand-in for persisted SQLite, not an Android DB test
        NoticeDeduplicator.Ledger ledger = (id, sender) -> storedIds.add(id);
        NoticeDeduplicator firstProcess = new NoticeDeduplicator(ledger);
        String id = firstProcess.claim("notification", message("gasté 3500 en super", 100), "Yo David");
        assertNotNull(id); assertEquals(64, id.length());
        assertNull(firstProcess.claim("notification", message("gasté 3500 en super", 100), "Yo David"));
        assertNull(new NoticeDeduplicator(ledger).claim("notification", message("gasté 3500 en super", 100), "Yo David"));
        assertEquals(1, storedIds.size());
    }
    @Test public void newTimeNewTextAndDifferentNotificationAreDifferentMessages() {
        Set<String> storedIds = new HashSet<>(); NoticeDeduplicator d = new NoticeDeduplicator((id, sender) -> storedIds.add(id));
        assertNotNull(d.claim("a", message("hola", 1), "David"));
        assertNotNull(d.claim("a", message("hola", 2), "David"));
        assertNotNull(d.claim("b", message("hola", 1), "David"));
        assertNotNull(d.claim("a", message("adiós", 1), "David"));
        assertEquals(4, storedIds.size());
    }
    @Test public void textHashIsNotJavaHashCodeAndIdentifierFitsCore() {
        assertEquals("Aa".hashCode(), "BB".hashCode());
        String a = NotificationPolicy.messageId("key", message("Aa", 1));
        String b = NotificationPolicy.messageId("key", message("BB", 1));
        assertNotEquals(a, b); assertTrue(a.matches("[a-f0-9]{64}"));
    }
}
