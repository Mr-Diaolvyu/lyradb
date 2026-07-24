package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.model.entity.AuditLog;
import io.github.lexaquila.lyradb.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 操作审计服务（企业版，append-only）
 *
 * <p>记录查询/导出/迁移/管理员操作；失败不影响主流程。
 * 支持 {@code app.audit.maskSql} 脱敏：仅存 SQL 哈希，不存明文。</p>
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repository;
    private final SecurityUtil securityUtil;
    private final AppProperties appProperties;

    public AuditService(AuditLogRepository repository, SecurityUtil securityUtil, AppProperties appProperties) {
        this.repository = repository;
        this.securityUtil = securityUtil;
        this.appProperties = appProperties;
    }

    /** 记录一条审计（不抛异常） */
    public void record(String workspaceId, String userId, String username, String role,
                       String dataSourceId, String grantedSourceName, String dbType,
                       String operationType, String sql, long affectedRows, long resultRows,
                       long elapsedMs, boolean success, String error) {
        try {
            AuditLog a = new AuditLog();
            a.setWorkspaceId(workspaceId);
            a.setUserId(userId);
            a.setUsername(username);
            a.setRole(role);
            a.setDataSourceId(dataSourceId);
            a.setGrantedSourceName(grantedSourceName);
            a.setDbType(dbType);
            a.setOperationType(operationType);
            // 脱敏：仅存哈希
            boolean mask = appProperties.getAudit() != null && appProperties.getAudit().isMaskSql();
            a.setSqlText(mask ? null : truncate(sql, 10000));
            a.setSqlHash(sha256(sql));
            a.setAffectedRows(affectedRows);
            a.setResultRows(resultRows);
            a.setElapsedMs(elapsedMs);
            a.setSuccess(success);
            if (!success && error != null) a.setErrorMessage(truncate(error, 2000));
            repository.save(a);
        } catch (Exception e) {
            log.warn("审计落库失败: {}", e.getMessage());
        }
    }

    public Page<AuditLog> listMine(String userId, Pageable pageable) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    public Page<AuditLog> listByWorkspace(String workspaceId, Pageable pageable) {
        return repository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId, pageable);
    }

    public Page<AuditLog> listAll(Pageable pageable) {
        return repository.findAllByOrderByCreatedAtDesc(pageable);
    }

    private static String sha256(String s) {
        if (s == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
