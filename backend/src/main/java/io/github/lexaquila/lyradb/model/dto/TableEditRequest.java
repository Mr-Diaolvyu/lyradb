package io.github.lexaquila.lyradb.model.dto;

import java.util.List;
import java.util.Map;

public record TableEditRequest(String grantedSourceName, String schema, String table,
                               List<Change> changes, String reason) {
    public record Change(String action, Map<String, Object> key,
                         String token, Map<String, Object> values) { }
}
