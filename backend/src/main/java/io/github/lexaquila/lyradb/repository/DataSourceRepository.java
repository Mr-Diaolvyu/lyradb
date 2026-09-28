package io.github.lexaquila.lyradb.repository;

import io.github.lexaquila.lyradb.model.entity.DataSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface DataSourceRepository extends JpaRepository<DataSource, String> {
    List<DataSource> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);
    List<DataSource> findByWorkspaceIdAndDisplayNameIgnoreCase(
            String workspaceId, String displayName);

    @Modifying
    @Transactional
    @Query("update DataSource d set d.lastTestStatus = :status, "
            + "d.lastTestedAt = :testedAt, d.lastTestElapsedMs = :elapsedMs, "
            + "d.lastTestErrorCode = :errorCode, d.lastTestConfigHash = :configHash "
            + "where d.id = :id and (d.lastTestedAt is null or d.lastTestedAt <= :testedAt)")
    int recordTest(@Param("id") String id, @Param("status") String status,
                   @Param("testedAt") LocalDateTime testedAt,
                   @Param("elapsedMs") long elapsedMs,
                   @Param("errorCode") String errorCode,
                   @Param("configHash") String configHash);
}
