package dev.poco.asistente.messaging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Android-free input boundary. Unknown conversation types fail closed. */
public final class NotificationPolicy {
    public static final String PACKAGE = "com.whatsapp.w4b";
    /** WhatsApp Business, or plain WhatsApp: David keeps the bot number in the Second Space, where only the bot uses it. */
    public static boolean isWhatsApp(String packageName) { return PACKAGE.equals(packageName) || "com.whatsapp".equals(packageName); }
    public static final class Sender {
        public final String name, key, uri;
        public Sender(String name, String key, String uri) {
            this.name = value(name); this.key = value(key); this.uri = value(uri);
        }
    }
    public static final class Message {
        public final Sender sender;
        public final String text;
        public final long time;
        public Message(Sender sender, String text, long time) { this.sender = sender; this.text = text; this.time = time; }
    }
    public static final class Notice {
        public final String packageName, key, title, conversationTitle, fallbackText;
        public final boolean summary, remoteHistory;
        public final Boolean group;
        public final long fallbackTime;
        public final List<Message> messages;
        public Notice(String packageName, String key, boolean summary, Boolean group, String title,
                      String conversationTitle, List<Message> messages, String fallbackText, long fallbackTime, boolean remoteHistory) {
            this.packageName = packageName; this.key = value(key); this.summary = summary; this.group = group;
            this.title = value(title); this.conversationTitle = value(conversationTitle);
            this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
            this.fallbackText = fallbackText; this.fallbackTime = fallbackTime; this.remoteHistory = remoteHistory;
        }
    }
    public static final class Decision {
        public final Message message;
        public final String reason, identity;
        private Decision(Message message, String reason, String identity) { this.message = message; this.reason = reason; this.identity = identity; }
        public boolean accepted() { return message != null; }
    }
    private static String value(String s) { return s == null ? "" : s; }
    public static boolean e164(String phone) { return phone != null && phone.matches("\\+[1-9][0-9]{6,14}"); }
    public static Message latest(Notice notice) {
        if (!notice.messages.isEmpty()) return notice.messages.get(notice.messages.size() - 1);
        if (notice.remoteHistory) return null; // fallback cannot distinguish our own inline reply
        return new Message(new Sender(notice.title, "", ""), notice.fallbackText, notice.fallbackTime);
    }
    public static Decision evaluate(Notice n, String allowedName, String allowedPhone) {
        if (!isWhatsApp(n.packageName)) return reject("otro paquete");
        if (n.summary) return reject("resumen de grupo");
        if (!Boolean.FALSE.equals(n.group) || !n.conversationTitle.isEmpty()) return reject("grupo o conversación no identificable");
        Message m = latest(n);
        if (m == null || m.sender == null) return reject("mensaje propio o remitente ausente");
        // A falsely unmarked group must not become a direct conversation through its last sender.
        for (Message previous : n.messages) {
            if (previous.sender != null && !sameSender(previous.sender, m.sender)) return reject("varios remitentes");
        }
        if (allowedName == null || allowedName.isEmpty() || !allowedName.equals(m.sender.name)) return reject("nombre no autorizado");
        String phone = "";
        if (m.sender.uri.startsWith("tel:")) {
            phone = m.sender.uri.substring(4).replaceAll("[ ()-]", "");
            if (!e164(phone)) return reject("tel: inválido");
        }
        // Person.key is an opaque app identifier; only an explicit E.164 value is comparable.
        String keyPhone = e164(m.sender.key) ? m.sender.key : "";
        if (!phone.isEmpty() && !keyPhone.isEmpty() && !phone.equals(keyPhone)) return reject("identificadores contradictorios");
        if (phone.isEmpty()) phone = keyPhone;
        String identity = "nombre exacto (sin número verificable)";
        if (allowedPhone != null && !allowedPhone.isEmpty()) {
            if (!e164(allowedPhone)) return reject("número autorizado inválido");
            if (!phone.isEmpty() && !allowedPhone.equals(phone)) return reject("número no autorizado");
            if (!phone.isEmpty()) identity = m.sender.uri.startsWith("tel:") ? "nombre + tel: E.164" : "nombre + clave E.164";
        }
        if (m.text == null || m.text.trim().isEmpty()) return reject("sin texto");
        if (m.time <= 0 || n.key.isEmpty()) return reject("sin clave u hora estable");
        return new Decision(m, "aceptado", identity);
    }
    private static boolean sameSender(Sender a, Sender b) {
        return Objects.equals(a.name, b.name) && Objects.equals(a.key, b.key) && Objects.equals(a.uri, b.uri);
    }
    private static Decision reject(String reason) { return new Decision(null, reason, ""); }
    public static String messageId(String key, Message message) {
        // Length prefixes prevent separators in a key/text from creating ambiguous tuples.
        String textHash = sha256(message.text);
        return sha256(key.length() + ":" + key + ":" + message.time + ":" + textHash);
    }
    private static String sha256(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String logSender(Notice notice) {
        Message message = latest(notice);
        String name = message == null || message.sender == null ? "(sin remitente)" : message.sender.name;
        String clean = name.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ");
        return clean.substring(0, Math.min(100, clean.length()));
    }
    /** Lossless chunks measured in Unicode code points; prefer a whitespace boundary. */
    public static List<String> split(String text, int limit) {
        if (limit < 1) throw new IllegalArgumentException("Límite inválido");
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int count = text.codePointCount(start, text.length());
            int end = text.offsetByCodePoints(start, Math.min(count, limit));
            if (end < text.length()) {
                int candidate = end;
                while (candidate > start && !Character.isWhitespace(text.codePointBefore(candidate)))
                    candidate = text.offsetByCodePoints(candidate, -1);
                if (candidate > start && text.codePointCount(start, candidate) >= limit / 2) end = candidate;
            }
            chunks.add(text.substring(start, end)); start = end;
        }
        return chunks;
    }
    private NotificationPolicy() {}
}
