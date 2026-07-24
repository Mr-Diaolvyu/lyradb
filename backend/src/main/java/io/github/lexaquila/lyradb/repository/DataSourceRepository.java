package io.github.lexaquila.lyradb.repository;

import io.github.lexaquila.lyradb.model.entity.DataSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DataSourceRepository extends JpaRepository<DataSource, String> {
    List<DataSource> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);
}
