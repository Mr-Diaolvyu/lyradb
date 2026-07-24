package io.github.lexaquila.lyradb.repository;

import io.github.lexaquila.lyradb.model.entity.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceRepository extends JpaRepository<Workspace, String> {
}
