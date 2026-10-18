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
}
