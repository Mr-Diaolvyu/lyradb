package io.github.lexaquila.lyradb.repository;

import io.github.lexaquila.lyradb.model.entity.MaskingRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MaskingRuleRepository extends JpaRepository<MaskingRule, String> {
    List<MaskingRule> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);
    Optional<MaskingRule> findByIdAndWorkspaceId(String id, String workspaceId);
    List<MaskingRule> findByWorkspaceIdAndDataSourceId(
            String workspaceId, String dataSourceId);
    List<MaskingRule> findByWorkspaceIdAndDataSourceIdIsNull(String workspaceId);
    List<MaskingRule> findByWorkspaceIdAndDataSourceIdAndEnabledTrue(
            String workspaceId, String dataSourceId);
    List<MaskingRule> findByWorkspaceIdAndDataSourceIdIsNullAndEnabledTrue(String workspaceId);
}
