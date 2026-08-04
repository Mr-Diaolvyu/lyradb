package io.github.lexaquila.lyradb.repository;

import io.github.lexaquila.lyradb.model.entity.AiGatewayToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiGatewayTokenRepository
        extends JpaRepository<AiGatewayToken, String> {

    Optional<AiGatewayToken> findByTokenSha256(String tokenSha256);

    Optional<AiGatewayToken> findByIdAndWorkspaceId(
            String id, String workspaceId);

    List<AiGatewayToken> findByWorkspaceIdOrderByCreatedAtDesc(
            String workspaceId);
}
