

package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.model.entity.AuditLog;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 企业版追加式审计服务。
 *
 * <p>SQL 默认只保留 SHA-256，管理动作通过 action 字段保留结构化名称。
 * 审计写入失败会显式抛错，不再静默形成审计空洞。</p>
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repository;
    private final SecurityUtil securityUtil;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository repository, SecurityUtil securityUtil,
                        AppProperties appProperties, ObjectMapper objectMapper) {
        this.repository = repository;
        this.securityUtil = securityUtil;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String workspaceId, String userId, String username, String role,
                       String dataSourceId, String grantedSourceName, String dbType,
                       String operationType, String sql, long affectedRows, long resultRows,
                       long elapsedMs, boolean success, String error) {
        persist(workspaceId, userId, username, role, dataSourceId, grantedSourceName, dbType,
                operationType, operationType, sql, affectedRows, resultRows, elapsedMs,
                success, error, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String workspaceId, String userId, String username, String role,
                       String dataSourceId, String grantedSourceName, String dbType,
                       String operationType, String action, String sql,
                       long affectedRows, long resultRows, long elapsedMs,
                       boolean success, String error, String approvalRequestId) {
        persist(workspaceId, userId, username, role, dataSourceId, grantedSourceName, dbType,
                operationType, action, sql, affectedRows, resultRows, elapsedMs,
                success, error, approvalRequestId, null);
    }

    private void persist(String workspaceId, String userId, String username, String role,
                         String dataSourceId, String grantedSourceName, String dbType,
                         String operationType, String action, String sql,
                         long affectedRows, long resultRows, long elapsedMs,
                         boolean success, String error, String approvalRequestId,
                         String detailsJson) {
        try {
            AuditLog audit = new AuditLog();
            audit.setWorkspaceId(workspaceId);
            audit.setUserId(userId);
            audit.setUsername(username);
            audit.setRole(role);
            audit.setDataSourceId(dataSourceId);
            audit.setGrantedSourceName(grantedSourceName);
            audit.setDbType(dbType);
            audit.setOperationType(truncate(operationType, 32));
            audit.setAction(truncate(action, 64));
            boolean mask = appProperties.getAudit() == null || appProperties.getAudit().isMaskSql();
            audit.setSqlText(mask ? null : truncate(sql, 10000));
            audit.setSqlHash(sha256(sql));
            audit.setAffectedRows(affectedRows);
            audit.setResultRows(resultRows);
            audit.setElapsedMs(elapsedMs);
            audit.setSuccess(success);
            audit.setErrorMessage(success ? null : truncate(error, 2000));
            audit.setApprovalRequestId(approvalRequestId);
            audit.setDetailsJson(detailsJson);
            repository.saveAndFlush(audit);
        } catch (RuntimeException exception) {
            log.error("审计落库失败，动作={}，用户={}：{}", action, username, exception.getMessage(), exception);
            throw new IllegalStateException("审计记录失败，操作结果不可确认", exception);
        }
    }

    /**
     * 需要与业务变更原子提交的完整审计记录。调用方应提供外层事务；
     * 保留 REQUIRED 便于非数据库状态事件独立记录。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void recordJoined(String workspaceId, String userId, String username, String role,
                             String dataSourceId, String grantedSourceName, String dbType,
                             String operationType, String action, String sql,
                             long affectedRows, long resultRows, long elapsedMs,
                             boolean success, String error, String approvalRequestId) {
        persist(workspaceId, userId, username, role, dataSourceId, grantedSourceName, dbType,
                operationType, action, sql, affectedRows, resultRows, elapsedMs,
                success, error, approvalRequestId, null);
    }

    /**
     * 管理与审批写操作审计加入调用方事务；审计失败会标记同一事务回滚。
     * 无外层事务时仍创建独立事务，适用于仅记录事件的管理动作。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void recordCurrent(String workspaceId, String action,
                              String dataSourceId, String grantedSourceName,
                              boolean success, String error) {
        User user = securityUtil.requireCurrentUser();
        String effectiveRole = securityUtil.effectiveRoles(workspaceId).stream()
                .findFirst().orElse("ANALYST");
        persist(workspaceId, user.getId(), user.getUsername(), effectiveRole,
                dataSourceId, grantedSourceName, null, "ADMIN", action, null,
                0, 0, 0, success, error, null, null);
    }

    /** 仅记录可追溯标识、规范化范围与内容摘要，不接收元数据正文。 */
    @Transactional(propagation = Propagation.REQUIRED)
    public void recordCurrentMetadata(
            String workspaceId, String action,
            String dataSourceId, String grantedSourceName,
            String snapshotId,
            MetadataSnapshotSessionStore.MapScope scope,
            String contentSha256) {
        User user = securityUtil.requireCurrentUser();
        String effectiveRole = securityUtil.effectiveRoles(workspaceId)
                .stream().findFirst().orElse("ANALYST");
        persist(workspaceId, user.getId(), user.getUsername(),
                effectiveRole, dataSourceId, grantedSourceName, null,
                "ADMIN", action, null, 0, 0, 0, true, null, null,
                canonicalMetadataDetails(
                        snapshotId, scope, contentSha256));
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void recordCurrentWithApproval(
            String workspaceId, String operationType, String action,
            String dataSourceId, String grantedSourceName,
            boolean success, String error, String approvalRequestId) {
        User user = securityUtil.requireCurrentUser();
        String effectiveRole = securityUtil.effectiveRoles(workspaceId).stream()
                .findFirst().orElse("ANALYST");
        persist(workspaceId, user.getId(), user.getUsername(), effectiveRole,
                dataSourceId, grantedSourceName, null,
                operationType, action, null,
                0, 0, 0, success, error, approvalRequestId, null);
    }

    public Page<AuditLog> listMine(
            String userId, String workspaceId, Pageable pageable) {
        return repository.findByUserIdAndWorkspaceIdOrderByCreatedAtDesc(
                userId, workspaceId, pageable);
    }

    public Page<AuditLog> listByWorkspace(String workspaceId, Pageable pageable) {
        return repository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId, pageable);
    }

    public Page<AuditLog> listAll(Pageable pageable) {
        return repository.findAllByOrderByCreatedAtDesc(pageable);
    }


    private String canonicalMetadataDetails(
            String snapshotId,
            MetadataSnapshotSessionStore.MapScope scope,
            String contentSha256) {
        if (snapshotId == null || snapshotId.isBlank()
                || snapshotId.length() > 64 || scope == null
                || contentSha256 == null
                || !contentSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "元数据审计摘要参数无效");
        }
        List<String> schemas = new ArrayList<>(scope.schemas());
        schemas.sort(String.CASE_INSENSITIVE_ORDER);
        List<String> tables = new ArrayList<>(scope.tables());
        tables.sort(String.CASE_INSENSITIVE_ORDER);
        Map<String, Object> scopeValue = new LinkedHashMap<>();
        scopeValue.put("database", scope.database());
        scopeValue.put("schemas", List.copyOf(schemas));
        scopeValue.put("tables", List.copyOf(tables));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("snapshotId", snapshotId.trim());
        details.put("scope", scopeValue);
        details.put("contentSha256", contentSha256);
        try {
            return objectMapper.writeValueAsString(details);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "无法序列化元数据审计摘要", exception);
        }
    }

    private static String sha256(String value) {
        if (value == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : hash) {
                result.append(String.format("%02x", b));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算审计哈希", exception);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
