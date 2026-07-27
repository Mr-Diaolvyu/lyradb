package io.github.lexaquila.lyradb.repository;

import io.github.lexaquila.lyradb.model.entity.MaskingRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MaskingRuleRepository extends JpaRepository<MaskingRule, String> {
    List<MaskingRule> findAllByOrderByCreatedAtDesc();

    List<MaskingRule> findByDataSourceIdAndEnabledTrue(String dataSourceId);

    List<MaskingRule> findByDataSourceIdIsNullAndEnabledTrue();
}
