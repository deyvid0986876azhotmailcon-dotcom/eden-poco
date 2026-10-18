package dev.poco.asistente.messaging;

/** The ledger must atomically insert-if-absent and survive process restarts (SQLite in production). */
public final class NoticeDeduplicator {
    public interface Ledger { boolean claim(String id, String sender); }
    private final Ledger ledger;
    public NoticeDeduplicator(Ledger ledger) { this.ledger = ledger; }
    public String claim(String notificationKey, NotificationPolicy.Message message, String sender) {
        String id = NotificationPolicy.messageId(notificationKey, message);
        return ledger.claim(id, sender) ? id : null;
    }
}
