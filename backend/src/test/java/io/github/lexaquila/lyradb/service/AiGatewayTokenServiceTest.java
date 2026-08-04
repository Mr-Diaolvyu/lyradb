package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.ai.gateway.AgentGatewayScope;
import io.github.lexaquila.lyradb.model.dto.AiGatewayTokenIssueRequest;
import io.github.lexaquila.lyradb.model.entity.AiGatewayToken;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.repository.AiGatewayTokenRepository;
import io.github.lexaquila.lyradb.repository.UserRepository;
import io.github.lexaquila.lyradb.repository.WorkspaceMembershipRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiGatewayTokenServiceTest {

    @Mock
    private AiGatewayTokenRepository repository;
    @Mock
    private GrantService grantService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private WorkspaceMembershipRepository membershipRepository;
    @Mock
    private SecurityUtil securityUtil;
    @Mock
    private AiFeatureGate featureGate;
    @Mock
    private AuditService auditService;

    @Test
    void issueReturnsPlaintextOnceButPersistsOnlyDigest() {
        AiGatewayTokenService service = service();
        when(securityUtil.hasRole("DS_ADMIN")).thenReturn(true);
        Grant grant = grant("user-1");
        when(grantService.getById("grant-1")).thenReturn(grant);
        User principal = user("user-1", "agent-user", 3L);
        when(userRepository.findById("user-1"))
                .thenReturn(Optional.of(principal));
        when(membershipRepository.existsByUserIdAndWorkspaceId(
                "user-1", "workspace-1")).thenReturn(true);
        User issuer = user("issuer-1", "steward", 1L);
        when(securityUtil.requireCurrentUser()).thenReturn(issuer);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            AiGatewayToken token = invocation.getArgument(0);
            token.setId("token-1");
            token.setCreatedAt(LocalDateTime.now());
            return token;
        });

        var issued = service.issue("workspace-1", issueRequest());

        assertTrue(issued.plaintextToken().startsWith("lyra_"));
        ArgumentCaptor<AiGatewayToken> captor =
                ArgumentCaptor.forClass(AiGatewayToken.class);
        verify(repository).saveAndFlush(captor.capture());
        assertNotEquals(issued.plaintextToken(),
                captor.getValue().getTokenSha256());
        assertFalse(captor.getValue().getTokenSha256()
                .contains(issued.plaintextToken()));
    }

    @Test
    void platformAdminCannotBecomeGatewayPrincipal() {
        AiGatewayTokenService service = service();
        when(securityUtil.hasRole("DS_ADMIN")).thenReturn(true);
        when(grantService.getById("grant-1"))
                .thenReturn(grant("user-1"));
        User platformAdmin = user("user-1", "root", 0L);
        platformAdmin.setRoles(java.util.List.of("PLATFORM_ADMIN"));
        when(userRepository.findById("user-1"))
                .thenReturn(Optional.of(platformAdmin));

        assertThrows(IllegalArgumentException.class,
                () -> service.issue("workspace-1", issueRequest()));
    }

    @Test
    void credentialVersionChangeInvalidatesToken() {
        AiGatewayTokenService service = service();
        String plaintext = "lyra_test-token";
        AiGatewayToken token = new AiGatewayToken();
        token.setId("token-1");
        token.setWorkspaceId("workspace-1");
        token.setPrincipalUserId("user-1");
        token.setGrantId("grant-1");
        token.setGrantedSourceName("sales-source");
        token.setScopesCsv("READ_PLAN");
        token.setCredentialVersion(2L);
        token.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(featureGate.isEnabled(
                io.github.lexaquila.lyradb.ai.AiFeature.AGENT_GATEWAY))
                .thenReturn(true);
        when(repository.findByTokenSha256(
                io.github.lexaquila.lyradb.ai.AiDigest.sha256(plaintext)))
                .thenReturn(Optional.of(token));
        when(grantService.getById("grant-1"))
                .thenReturn(grant("user-1"));
        User changed = user("user-1", "agent-user", 3L);
        when(userRepository.findById("user-1"))
                .thenReturn(Optional.of(changed));

        assertThrows(AccessDeniedException.class,
                () -> service.authenticate(plaintext));
    }

    private AiGatewayTokenService service() {
        return new AiGatewayTokenService(
                repository, grantService, userRepository,
                membershipRepository, securityUtil,
                featureGate, auditService);
    }

    private static AiGatewayTokenIssueRequest issueRequest() {
        AiGatewayTokenIssueRequest request =
                new AiGatewayTokenIssueRequest();
        request.setDisplayName("BI Agent");
        request.setGrantId("grant-1");
        request.setScopes(Set.of(
                AgentGatewayScope.READ_PLAN,
                AgentGatewayScope.READ_EXECUTE));
        request.setExpiresAt(LocalDateTime.now().plusDays(1));
        return request;
    }

    private static Grant grant(String userId) {
        Grant grant = new Grant();
        grant.setId("grant-1");
        grant.setWorkspaceId("workspace-1");
        grant.setUserId(userId);
        grant.setDataSourceId("source-1");
        grant.setGrantedSourceName("sales-source");
        grant.setSqlCapability("READ_ONLY");
        return grant;
    }

    private static User user(
            String id, String username, long credentialVersion) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEnabled(true);
        user.setCredentialVersion(credentialVersion);
        return user;
    }
}
