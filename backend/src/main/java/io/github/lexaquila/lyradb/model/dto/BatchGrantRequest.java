package io.github.lexaquila.lyradb.model.dto;

import java.time.LocalDateTime;
import java.util.List;

public record BatchGrantRequest(List<String> userIds, List<Source> sources) {
    public record Source(String dataSourceId, String grantedSourceName,
                         String allowedSchemas, String allowedTables,
                         String blockedTables, String sqlCapability,
                         Integer maxRowsPerQuery, LocalDateTime expiresAt) { }
}
