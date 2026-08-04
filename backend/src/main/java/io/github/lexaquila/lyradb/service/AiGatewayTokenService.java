package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.ai.AiDigest;
import io.github.lexaquila.lyradb.ai.AiFeature;
import io.github.lexaquila.lyradb.ai.gateway.AgentGatewayScope;
import io.github.lexaquila.lyradb.model.dto.AiGatewayTokenIssueRequest;
import io.github.lexaquila.lyradb.model.dto.AiGatewayTokenIssuedView;
import io.github.lexaquila.lyradb.model.dto.AiGatewayTokenView;
import io.github.lexaquila.lyradb.model.entity.AiGatewayToken;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.repository.AiGatewayTokenRepository;
import io.github.lexaquila.lyradb.repository.UserRepository;
import io.github.lexaquila.lyradb.repository.WorkspaceMembershipRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Gateway 令牌签发、撤销与每请求失败关闭验证。 */
@Service
public class AiGatewayTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_LIFETIME_DAYS = 90;

    private final AiGatewayTokenRepository repository;
    private final GrantService grantService;
    private final UserRepository userRepository;
    private final WorkspaceMembershipRepository membershipRepository;
    private final SecurityUtil securityUtil;
    private final AiFeatureGate featureGate;
    private final AuditService auditService;

    public AiGatewayTokenService(
            AiGatewayTokenRepository repository,
            GrantService grantService,
            UserRepository userRepository,
            WorkspaceMembershipRepository membershipRepository,
            SecurityUtil securityUtil,
            AiFeatureGate featureGate,
            AuditService auditService) {
        this.repository = repository;
        this.grantService = grantService;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.securityUtil = securityUtil;
        this.featureGate = featureGate;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<AiGatewayTokenView> list(String workspaceId) {
        featureGate.requireEnabled(AiFeature.AGENT_GATEWAY);
        requireIssuerRole();
        String workspace = requireText(workspaceId, "工作空间 ID", 36);
        return repository.findByWorkspaceIdOrderByCreatedAtDesc(workspace)
                .stream().map(AiGatewayTokenService::view).toList();
    }

    @Transactional
    public AiGatewayTokenIssuedView issue(
            String workspaceId, AiGatewayTokenIssueRequest request) {
        featureGate.requireEnabled(AiFeature.AGENT_GATEWAY);
        requireIssuerRole();
        if (request == null) {
            throw new IllegalArgumentException("Gateway 令牌请求不能为空");
        }
        String workspace = requireText(workspaceId, "工作空间 ID", 36);
        Grant grant = grantService.getById(
                requireText(request.getGrantId(), "Grant ID", 36));
        if (!workspace.equals(grant.getWorkspaceId())) {
            throw new AccessDeniedException("Grant 不属于当前工作空间");
        }
        if (!"READ_ONLY".equalsIgnoreCase(grant.getSqlCapability())) {
            throw new IllegalArgumentException(
                    "Gateway 只能绑定 READ_ONLY Grant");
        }
        if (grant.getExpiresAt() != null
                && !grant.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("Grant 已过期");
        }
        User principal = userRepository.findById(grant.getUserId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Grant 绑定用户不存在"));
        validatePrincipal(principal, grant);
        Set<AgentGatewayScope> scopes = requireScopes(request.getScopes());
        LocalDateTime expiresAt = requireExpiry(request.getExpiresAt());
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String plaintext = "lyra_" + Base64.getUrlEncoder()
                .withoutPadding().encodeToString(random);

        User issuer = securityUtil.requireCurrentUser();
        AiGatewayToken token = new AiGatewayToken();
        token.setWorkspaceId(workspace);
        token.setPrincipalUserId(principal.getId());
        token.setGrantId(grant.getId());
        token.setGrantedSourceName(grant.getGrantedSourceName());
        token.setDisplayName(requireText(
                request.getDisplayName(), "身份名称", 100));
        token.setTokenSha256(AiDigest.sha256(plaintext));
        token.setTokenPrefix(plaintext.substring(0,
                Math.min(14, plaintext.length())));
        token.setScopesCsv(joinScopes(scopes));
        token.setCredentialVersion(principal.getCredentialVersion());
        token.setRevoked(false);
        token.setExpiresAt(expiresAt);
        token.setCreatedBy(issuer.getId());
        AiGatewayToken saved = repository.saveAndFlush(token);
        auditService.recordCurrent(workspace,
                "AI_GATEWAY_TOKEN_ISSUE", grant.getDataSourceId(),
                grant.getGrantedSourceName(), true, null);
        return new AiGatewayTokenIssuedView(
                view(saved), plaintext,
                "令牌正文只展示一次，请保存到受控密钥系统；LyraDB 不保存明文");
    }

    @Transactional
    public AiGatewayTokenView revoke(
            String workspaceId, String tokenId) {
        featureGate.requireEnabled(AiFeature.AGENT_GATEWAY);
        requireIssuerRole();
        String workspace = requireText(workspaceId, "工作空间 ID", 36);
        AiGatewayToken token = repository.findByIdAndWorkspaceId(
                        requireText(tokenId, "令牌 ID", 36), workspace)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Gateway 令牌不存在"));
        token.setRevoked(true);
        AiGatewayToken saved = repository.saveAndFlush(token);
        auditService.recordCurrent(workspace,
                "AI_GATEWAY_TOKEN_REVOKE", null,
                token.getGrantedSourceName(), true, null);
        return view(saved);
    }

    @Transactional
    public AiGatewayPrincipal authenticate(String plaintextToken) {
        if (!featureGate.isEnabled(AiFeature.AGENT_GATEWAY)) {
            throw new AccessDeniedException("Agent Gateway 尚未启用");
        }
        String raw = requireText(plaintextToken, "Gateway 令牌", 200);
        if (!raw.startsWith("lyra_")) {
            throw invalidToken();
        }
        AiGatewayToken token = repository.findByTokenSha256(
                        AiDigest.sha256(raw))
                .orElseThrow(AiGatewayTokenService::invalidToken);
        LocalDateTime now = LocalDateTime.now();
        if (token.isRevoked() || token.getExpiresAt() == null
                || !token.getExpiresAt().isAfter(now)) {
            throw invalidToken();
        }
        Grant grant = grantService.getById(token.getGrantId());
        User principal = userRepository.findById(
                        token.getPrincipalUserId())
                .orElseThrow(AiGatewayTokenService::invalidToken);
        if (!principal.isEnabled()
                || principal.getCredentialVersion()
                != token.getCredentialVersion()
                || principal.getRoles().contains("PLATFORM_ADMIN")) {
            throw invalidToken();
        }
        if (!membershipRepository.existsByUserIdAndWorkspaceId(
                principal.getId(), token.getWorkspaceId())) {
            throw invalidToken();
        }
        if (!token.getWorkspaceId().equals(grant.getWorkspaceId())
                || !token.getPrincipalUserId().equals(grant.getUserId())
                || !token.getGrantedSourceName().equals(
                grant.getGrantedSourceName())
                || !"READ_ONLY".equalsIgnoreCase(grant.getSqlCapability())
                || grant.getExpiresAt() != null
                && !grant.getExpiresAt().isAfter(now)) {
            throw invalidToken();
        }
        token.setLastUsedAt(now);
        repository.saveAndFlush(token);
        return new AiGatewayPrincipal(
                token.getId(), principal.getUsername(), principal.getId(),
                token.getWorkspaceId(), token.getGrantId(),
                token.getGrantedSourceName(), parseScopes(token.getScopesCsv()));
    }

    private void validatePrincipal(User principal, Grant grant) {
        if (!principal.isEnabled()) {
            throw new IllegalArgumentException("Gateway 绑定用户已停用");
        }
        if (principal.getRoles().contains("PLATFORM_ADMIN")) {
            throw new IllegalArgumentException(
                    "Gateway 身份不得绑定平台管理员");
        }
        if (!membershipRepository.existsByUserIdAndWorkspaceId(
                principal.getId(), grant.getWorkspaceId())) {
            throw new IllegalArgumentException(
                    "Gateway 绑定用户已不属于工作空间");
        }
    }

    private void requireIssuerRole() {
        if (!securityUtil.hasRole("DS_ADMIN")
                && !securityUtil.hasRole("STEWARD")) {
            throw new AccessDeniedException(
                    "Gateway 身份管理需要 DS_ADMIN 或 STEWARD 角色");
        }
    }

    private static Set<AgentGatewayScope> requireScopes(
            Set<AgentGatewayScope> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new IllegalArgumentException(
                    "Gateway 至少需要一个明确权限");
        }
        LinkedHashSet<AgentGatewayScope> result =
                new LinkedHashSet<>(requested);
        if (result.contains(AgentGatewayScope.READ_EXECUTE)
                && !result.contains(AgentGatewayScope.READ_PLAN)) {
            throw new IllegalArgumentException(
                    "read.execute 必须同时授予 read.plan");
        }
        return Set.copyOf(result);
    }

    private static LocalDateTime requireExpiry(LocalDateTime value) {
        LocalDateTime now = LocalDateTime.now();
        if (value == null || !value.isAfter(now.plusMinutes(5))
                || value.isAfter(now.plusDays(MAX_LIFETIME_DAYS))) {
            throw new IllegalArgumentException(
                    "Gateway 令牌有效期必须在 5 分钟到 90 天之间");
        }
        return value;
    }

    private static String joinScopes(Set<AgentGatewayScope> scopes) {
        return scopes.stream().map(Enum::name)
                .sorted().reduce((left, right) -> left + "," + right)
                .orElseThrow();
    }

    private static Set<AgentGatewayScope> parseScopes(String csv) {
        if (csv == null || csv.isBlank()) {
            throw invalidToken();
        }
        LinkedHashSet<AgentGatewayScope> result = new LinkedHashSet<>();
        try {
            for (String value : csv.split(",")) {
                result.add(AgentGatewayScope.valueOf(value.trim()));
            }
        } catch (RuntimeException exception) {
            throw invalidToken();
        }
        return Set.copyOf(result);
    }

    private static AiGatewayTokenView view(AiGatewayToken token) {
        return new AiGatewayTokenView(
                token.getId(), token.getDisplayName(), token.getTokenPrefix(),
                token.getPrincipalUserId(), token.getGrantId(),
                token.getGrantedSourceName(), parseScopes(token.getScopesCsv()),
                token.isRevoked(), token.getExpiresAt(), token.getLastUsedAt(),
                token.getCreatedAt());
    }

    private static String requireText(
            String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + "必填且长度不得超过 " + maxLength);
        }
        return value.trim();
    }

    private static AccessDeniedException invalidToken() {
        return new AccessDeniedException("Gateway 身份无效或已失效");
    }
}
