package io.github.lexaquila.lyradb.repository;

import io.github.lexaquila.lyradb.model.entity.AiMaxComputePreflight;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface AiMaxComputePreflightRepository
        extends JpaRepository<AiMaxComputePreflight, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select item from AiMaxComputePreflight item "
            + "where item.tokenSha256 = :token")
    Optional<AiMaxComputePreflight> findByTokenForUpdate(
            @Param("token") String token);

    @Modifying
    @Query("delete from AiMaxComputePreflight item "
            + "where item.expiresAt < :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
