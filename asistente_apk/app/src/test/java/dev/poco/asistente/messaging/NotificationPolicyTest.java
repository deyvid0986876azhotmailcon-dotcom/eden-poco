package dev.poco.asistente.messaging;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import static dev.poco.asistente.messaging.NotificationPolicy.*;

public class NotificationPolicyTest {
    private final Sender david = new Sender("Yo David", "opaque-key", "");
    private Notice notice(String pkg, boolean summary, Boolean group, String title, String conversation, List<Message> messages) {
        return new Notice(pkg, "notification-key", summary, group, title, conversation, messages, "texto fallback", 90, false);
    }
    private Notice direct(Sender sender) {
        return notice(PACKAGE, false, false, "Yo David", "", Collections.singletonList(new Message(sender, "resumen", 100)));
    }
    @Test public void extractsLastMessageAndAllPersonFieldsWithoutUsingTitle() {
        Sender person = new Sender("Yo David", "person-key", "tel:+595981123456");
        Notice n = notice(PACKAGE, false, false, "Otro título", "", Arrays.asList(
                new Message(person, "anterior", 100), new Message(person, "último", 101)));
        Message m = latest(n);
        assertEquals("último", m.text); assertEquals(101, m.time);
        assertEquals("Yo David", m.sender.name); assertEquals("person-key", m.sender.key); assertEquals("tel:+595981123456", m.sender.uri);
        assertTrue(evaluate(n, "Yo David", "+595981123456").accepted());
    }
    @Test public void fallbackUsesExtraTextAndStableWhenOnlyWithoutMessages() {
        Notice n = notice(PACKAGE, false, false, "Yo David", "", Collections.emptyList());
        assertEquals("texto fallback", latest(n).text); assertEquals(90, latest(n).time);
        assertTrue(evaluate(n, "Yo David", "").accepted());
    }
    @Test public void fallbackWithUnknownGroupTypeIsRejected() {
        assertFalse(evaluate(notice(PACKAGE, false, null, "Yo David", "", Collections.emptyList()), "Yo David", "").accepted());
    }
    @Test public void restrictsPackageSummaryGroupsAndConversationTitle() {
        List<Message> messages = Collections.singletonList(new Message(david, "resumen", 100));
        // plain WhatsApp is accepted too (the bot number lives in the Second Space); other apps are not
        assertFalse(evaluate(notice("org.telegram.messenger", false, false, "Yo David", "", messages), "Yo David", "").accepted());
        assertFalse(evaluate(notice(PACKAGE, true, false, "Yo David", "", messages), "Yo David", "").accepted());
        assertFalse(evaluate(notice(PACKAGE, false, true, "Yo David", "", messages), "Yo David", "").accepted());
        assertFalse(evaluate(notice(PACKAGE, false, false, "Yo David", "Familia", messages), "Yo David", "").accepted());
    }
    @Test public void mixedSendersAreRejectedEvenIfLastIsOwner() {
        Notice n = notice(PACKAGE, false, false, "Yo David", "", Arrays.asList(
                new Message(new Sender("Desconocido", "", ""), "no registrar", 100), new Message(david, "resumen", 101)));
        assertFalse(evaluate(n, "Yo David", "").accepted());
    }
    @Test public void namesMustMatchExactlyAndCannotBeEmpty() {
        assertTrue(evaluate(direct(david), "Yo David", "").identity.contains("nombre exacto"));
        for (String name : new String[]{"yo david", "Yo David ", "Yó David", "", "Yo\u200b David"})
            assertFalse(name, evaluate(direct(david), name, "").accepted());
        assertFalse(evaluate(direct(new Sender("+595999999999", "", "")), "Yo David", "").accepted());
    }
    @Test public void telUriIsStrongOnlyAgainstConfiguredNumber() {
        Notice n = direct(new Sender("Yo David", "opaque", "tel:+595 (981) 123-456"));
        assertEquals("nombre + tel: E.164", evaluate(n, "Yo David", "+595981123456").identity);
        assertFalse(evaluate(n, "Yo David", "+595981999999").accepted());
        assertTrue(evaluate(n, "Yo David", "").identity.contains("nombre exacto"));
        assertFalse(evaluate(n, "Otro nombre", "+595981123456").accepted());
    }
    @Test public void malformedAndConflictingNumbersNeverFallBackToName() {
        assertFalse(evaluate(direct(new Sender("Yo David", "", "tel:unknown")), "Yo David", "").accepted());
        assertFalse(evaluate(direct(new Sender("Yo David", "+595981999999", "tel:+595981123456")), "Yo David", "+595981123456").accepted());
        assertFalse(evaluate(direct(new Sender("Yo David", "+595981999999", "")), "Yo David", "+595981123456").accepted());
        assertFalse(evaluate(direct(david), "Yo David", "981123456").accepted());
    }
    @Test public void opaquePersonKeyIsNotAPhoneOrAnAuthorization() {
        assertTrue(evaluate(direct(david), "Yo David", "+595981123456").identity.contains("nombre exacto"));
        assertFalse(evaluate(direct(new Sender("Intruso", "Yo David", "")), "Yo David", "").accepted());
        assertTrue(evaluate(direct(new Sender("Yo David", "+595981123456", "")), "Yo David", "+595981123456").identity.contains("clave E.164"));
    }
    @Test public void outgoingOrEmptyLastMessageDoesNotReplayOlderIncoming() {
        List<Message> messages = Arrays.asList(new Message(david, "gasté 3500 en super", 100), new Message(null, "Guardado", 101));
        assertFalse(evaluate(notice(PACKAGE, false, false, "Yo David", "", messages), "Yo David", "").accepted());
        assertFalse(evaluate(notice(PACKAGE, false, false, "Yo David", "", Collections.singletonList(new Message(david, "", 102))), "Yo David", "").accepted());
        assertFalse(evaluate(new Notice(PACKAGE, "k", false, false, "Yo David", "", Collections.emptyList(), "respuesta propia", 100, true), "Yo David", "").accepted());
    }
    @Test public void missingStableMetadataIsRejected() {
        assertFalse(evaluate(new Notice(PACKAGE, "", false, false, "Yo David", "", Collections.emptyList(), "resumen", 100, false), "Yo David", "").accepted());
        assertFalse(evaluate(new Notice(PACKAGE, "k", false, false, "Yo David", "", Collections.emptyList(), "resumen", 0, false), "Yo David", "").accepted());
    }
    @Test public void rejectedLogContainsSenderOnlyAndRemovesControlCharacters() {
        Notice n = direct(new Sender("Intruso\n\u202e", "", ""));
        assertEquals("Intruso  ", logSender(n)); assertFalse(logSender(n).contains("resumen"));
    }
    @Test public void validatesE164() {
        assertTrue(e164("+595981123456"));
        for (String value : new String[]{"+01234567", "595981123456", "+595 981123456", "+123", "+1234567890123456"}) assertFalse(value, e164(value));
    }
    @Test public void splitsLongAnswerLosslesslyWithinLimit() {
        String text = String.join("", Collections.nCopies(1000, "Una línea larga con espacios.\n"));
        List<String> chunks = split(text, 1800);
        assertTrue(chunks.size() > 1); assertEquals(text, String.join("", chunks));
        for (String chunk : chunks) { assertFalse(chunk.isEmpty()); assertTrue(chunk.codePointCount(0, chunk.length()) <= 1800); }
    }
    @Test public void splitsUnbrokenWordsAndSurrogatePairsWithoutCorruption() {
        String text = "😀😀😀😀😀abc";
        assertEquals(Arrays.asList("😀😀", "😀😀", "😀a", "bc"), split(text, 2));
        assertTrue(split("", 2).isEmpty());
        assertEquals(Arrays.asList("abc", "def", "g"), split("abcdefg", 3));
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsZeroChunkLimit() { split("text", 0); }
}
