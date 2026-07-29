
package io.github.lexaquila.lyradb.repository;

import io.github.lexaquila.lyradb.model.entity.Workspace;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WorkspaceRepository extends JpaRepository<Workspace, String> {

    /** 企业治理变更与审批消费共用的租户级悲观锁。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select workspace from Workspace workspace where workspace.id = :id")
    Optional<Workspace> findByIdForGovernanceUpdate(@Param("id") String id);
}
