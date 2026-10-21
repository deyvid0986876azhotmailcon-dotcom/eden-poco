package dev.poco.asistente.core;

import java.util.List;
import org.json.JSONObject;

/** No Android dependency. All mutations and their receipt/audit entry must be atomic. */
public interface Store {
    final class Row {
        public final String id, kind;
        public final long version;
        public final JSONObject data;
        public Row(String id, String kind, long version, JSONObject data) {
            this.id = id; this.kind = kind; this.version = version; this.data = data;
        }
    }
    List<Row> rows() throws Exception;
    Row find(String id) throws Exception;
    String receipt(String operationId) throws Exception;
    // before == null inserts; after == null deletes. Must compare before.version.
    String mutate(String operationId, Row before, Row after, String result, long time) throws Exception;
    String history(String date) throws Exception;
    // Claim BEFORE an external side effect. A crash leaves an uncertain receipt and never silently retries.
    default void beginAction(String id, String tool, long time) throws Exception { throw new IllegalStateException("Registro de acciones no disponible."); }
    default void finishAction(String id, String result) throws Exception { throw new IllegalStateException("Registro de acciones no disponible."); }
    default List<Row> memories() throws Exception { return java.util.Collections.emptyList(); }
    default List<Row> reminders() throws Exception { return java.util.Collections.emptyList(); }
    default List<Memory.Exchange> conversation() throws Exception { return java.util.Collections.emptyList(); }
    default void exchange(String id, String user, String model) throws Exception {}
    default void clearConversation() throws Exception {}

}
