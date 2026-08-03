


package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.repository.DataSourceRepository;
import io.github.lexaquila.lyradb.repository.GrantRepository;
import io.github.lexaquila.lyradb.repository.UserRepository;
import io.github.lexaquila.lyradb.repository.WorkspaceMembershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 数据源授权服务（逻辑数据源映射到真实数据源）。
 */
@Service
public class GrantService {

    private static final Set<String> CAPABILITIES = Set.of("READ_ONLY", "DML_ALLOWED");
    private static final Pattern SCHEMA_PATTERN = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_$]*\\*?");
    private static final Pattern QUALIFIED_TABLE_PATTERN = Pattern.compile(
            "(?:[A-Za-z_][A-Za-z0-9_$]*\\.)+"
                    + "(?:[A-Za-z_][A-Za-z0-9_$]*\\*?|\\*)");

    private final GrantRepository repository;
    private final DataSourceRepository dataSourceRepository;
    private final UserRepository userRepository;
    private final WorkspaceMembershipRepository membershipRepository;
    private final ApprovalSecurityContextService approvalSecurityContextService;
    private final AppProperties appProperties;

    public GrantService(GrantRepository repository, DataSourceRepository dataSourceRepository,
                        UserRepository userRepository,
                        WorkspaceMembershipRepository membershipRepository,
                        ApprovalSecurityContextService approvalSecurityContextService,
                        AppProperties appProperties) {
        this.repository = repository;
        this.dataSourceRepository = dataSourceRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.approvalSecurityContextService = approvalSecurityContextService;
        this.appProperties = appProperties;
    }

    @Transactional
    public Grant create(String workspaceId, String dataSourceId, String userId,
                        String grantedSourceName, String allowedSchemas, String allowedTables,
                        String blockedTables, String sqlCapability, int maxRows,
                        LocalDateTime expiresAt) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId 必填");
        }
        DataSource dataSource = dataSourceRepository.findById(dataSourceId)
                .orElseThrow(() -> new RuntimeException("数据源不存在: " + dataSourceId));
        if (!workspaceId.equals(dataSource.getWorkspaceId())) {
            throw new RuntimeException("数据源不属于当前工作空间");
        }
        userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("授权用户不存在: " + userId));
        if (!membershipRepository.existsByUserIdAndWorkspaceId(userId, workspaceId)) {
            throw new RuntimeException("不能向工作空间外的用户授权");
        }
        String logicalName = grantedSourceName == null ? "" : grantedSourceName.trim();
        if (logicalName.isEmpty() || logicalName.length() > 100) {
            throw new IllegalArgumentException("逻辑数据源名称须为 1-100 个字符");
        }
        if (repository.findByUserIdAndWorkspaceIdAndGrantedSourceName(
                userId, workspaceId, logicalName).isPresent()) {
            throw new RuntimeException("该用户在当前工作空间已存在同名逻辑数据源: " + logicalName);
        }
        String capability = sqlCapability == null
                ? "READ_ONLY" : sqlCapability.trim().toUpperCase(Locale.ROOT);
        if (!CAPABILITIES.contains(capability)) {
            throw new IllegalArgumentException("sqlCapability 仅支持 READ_ONLY/DML_ALLOWED");
        }

        String normalizedAllowedSchemas = requireEnterpriseSchemas(allowedSchemas);
        String normalizedAllowedTables = requireEnterpriseTables(allowedTables);
        String normalizedBlockedTables =
                validateOptionalQualifiedTables(blockedTables);

        Grant grant = new Grant();
        grant.setWorkspaceId(workspaceId);
        grant.setDataSourceId(dataSourceId);
        grant.setUserId(userId);
        grant.setGrantedSourceName(logicalName);
        grant.setAllowedSchemas(normalizedAllowedSchemas);
        grant.setAllowedTables(normalizedAllowedTables);
        grant.setBlockedTables(normalizedBlockedTables);
        grant.setSqlCapability(capability);
        int configuredMax = Math.max(1, appProperties.getMaxQueryRows());
        grant.setMaxRowsPerQuery(Math.max(1, Math.min(maxRows, configuredMax)));
        grant.setExportApprovedOnly(true);
        grant.setExpiresAt(expiresAt);
        return repository.save(grant);
    }

    public List<Map<String, Object>> listMine(String userId, String workspaceId) {
        List<Grant> grants = repository
                .findByUserIdAndWorkspaceIdOrderByCreatedAtDesc(userId, workspaceId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Grant grant : grants) {
            if (grant.getExpiresAt() == null || grant.getExpiresAt().isAfter(LocalDateTime.now())) {
                result.add(toLogicalView(grant));
            }
        }
        return result;
    }

    public List<Map<String, Object>> listByWorkspace(String workspaceId) {
        List<Grant> grants = repository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Grant grant : grants) {
            Map<String, Object> item = toLogicalView(grant);
            item.put("dataSourceId", grant.getDataSourceId());
            item.put("userId", grant.getUserId());
            result.add(item);
        }
        return result;
    }

    /**
     * 按当前用户和逻辑名解析授权，并再次验证用户仍属于该工作空间及授权未过期。
     */
    public Grant resolveForUser(String userId, String workspaceId, String grantedSourceName) {
        Grant grant = repository.findByUserIdAndWorkspaceIdAndGrantedSourceName(
                        userId, workspaceId, grantedSourceName)
                .orElseThrow(() -> new RuntimeException("当前工作空间无授权的数据源: "
                        + grantedSourceName));
        validateActiveGrant(grant, userId, workspaceId);
        return grant;
    }

    public Grant getByIdForUser(String grantId, String userId, String workspaceId) {
        Grant grant = repository.findByIdAndUserIdAndWorkspaceId(
                        grantId, userId, workspaceId)
                .orElseThrow(() -> new RuntimeException("授权不存在或无权访问: " + grantId));
        validateActiveGrant(grant, userId, workspaceId);
        return grant;
    }

    public Grant getById(String grantId) {
        return repository.findById(grantId)
                .orElseThrow(() -> new RuntimeException("授权不存在: " + grantId));
    }

    @Transactional
    public void delete(String id) {
        Grant grant = getById(id);
        approvalSecurityContextService.invalidateForGrant(
                grant.getWorkspaceId(), id);
        repository.deleteById(id);
    }

    private void validateActiveGrant(Grant grant, String userId, String workspaceId) {
        if (!userId.equals(grant.getUserId())
                || !workspaceId.equals(grant.getWorkspaceId())) {
            throw new RuntimeException("授权不属于当前用户或工作空间");
        }
        if (grant.getExpiresAt() != null && !grant.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new RuntimeException("授权已过期: " + grant.getGrantedSourceName());
        }
        if (!membershipRepository.existsByUserIdAndWorkspaceId(userId, workspaceId)) {
            throw new RuntimeException("用户已不属于授权工作空间");
        }
    }

    private Map<String, Object> toLogicalView(Grant grant) {
        Map<String, Object> view = new HashMap<>();
        view.put("id", grant.getId());
        view.put("grantedSourceName", grant.getGrantedSourceName());
        view.put("workspaceId", grant.getWorkspaceId());
        view.put("allowedSchemas", grant.getAllowedSchemas());
        view.put("allowedTables", grant.getAllowedTables());
        view.put("blockedTables", grant.getBlockedTables());
        view.put("sqlCapability", grant.getSqlCapability());
        view.put("maxRowsPerQuery", grant.getMaxRowsPerQuery());
        view.put("exportApprovedOnly", grant.isExportApprovedOnly());
        view.put("expiresAt", grant.getExpiresAt());
        dataSourceRepository.findById(grant.getDataSourceId())
                .ifPresent(dataSource ->
                        view.put("dbType",
                                dataSource.getDbType()));
        return view;
    }

    private static String requireEnterpriseSchemas(String value) {
        return validateResourceCsv(value, "allowedSchemas",
                "企业授权必须至少配置一个 Schema", SCHEMA_PATTERN);
    }

    private static String requireEnterpriseTables(String value) {
        return validateResourceCsv(value, "allowedTables",
                "企业授权必须至少配置一个 Schema.Table 完整限定表",
                QUALIFIED_TABLE_PATTERN);
    }

    private static String validateOptionalQualifiedTables(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return validateResourceCsv(
                value, "blockedTables",
                "blockedTables 为空", QUALIFIED_TABLE_PATTERN);
    }

    private static String validateResourceCsv(
            String value, String field, String emptyMessage,
            Pattern pattern) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(emptyMessage);
        }
        if (value.indexOf('"') >= 0 || value.indexOf('`') >= 0
                || value.indexOf('[') >= 0 || value.indexOf(']') >= 0) {
            throw new IllegalArgumentException(
                    field + " 不允许带引号的资源标识符");
        }
        for (String part : value.split(",", -1)) {
            String candidate = part.trim();
            if (candidate.isEmpty() || !pattern.matcher(candidate).matches()) {
                throw new IllegalArgumentException(
                        field + " 包含非法资源模式: " + candidate);
            }
        }
        return normalizeCsv(value);
    }

    private static String normalizeCsv(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return String.join(",", SqlParseUtil.splitCsv(value));
    }
}
