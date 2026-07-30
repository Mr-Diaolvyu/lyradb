package io.github.lexaquila.lyradb.desktop.metadata;

import io.github.lexaquila.lyradb.metadata.snapshot.MetadataSnapshot;

import java.time.Instant;

/**
 * 一次手动元数据采集的范围、统计和共享快照。
 */
public record MetadataCapture(MetadataSelection selection,
                              MetadataSnapshot snapshot,
                              Instant capturedAt,
                              int tableCount,
                              int columnCount,
                              long estimatedTokens) {

    public MetadataCapture {
        selection = java.util.Objects.requireNonNull(selection, "selection");
        snapshot = java.util.Objects.requireNonNull(snapshot, "snapshot");
        capturedAt = capturedAt == null ? Instant.now() : capturedAt;
        if (tableCount < 0 || columnCount < 0 || estimatedTokens < 0) {
            throw new IllegalArgumentException("元数据统计不能为负数");
        }
    }

    public String scopeLabel() {
        return selection.displayScope();
    }
}
