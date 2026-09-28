package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.entity.EnterpriseQueryHistory;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.SavedSql;
import io.github.lexaquila.lyradb.repository.EnterpriseQueryHistoryRepository;
import io.github.lexaquila.lyradb.repository.SavedSqlRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 用户与工作空间隔离的 SQL 脚本及历史；仅 SQL 加密入库，不保存结果集。 */
@Service
public class EnterpriseSqlWorkspaceService {
    private final SavedSqlRepository scripts;
    private final EnterpriseQueryHistoryRepository history;
    private final GrantService grantService;
    private final CredentialService credentials;
    private final AuditService audit;

    public EnterpriseSqlWorkspaceService(SavedSqlRepository scripts,
                                         EnterpriseQueryHistoryRepository history,
                                         GrantService grantService,
                                         CredentialService credentials, AuditService audit) {
        this.scripts = scripts;
        this.history = history;
        this.grantService = grantService;
        this.credentials = credentials;
        this.audit = audit;
    }

    public List<Map<String, Object>> scripts(String workspaceId, String userId) {
        return scripts.findByWorkspaceIdAndUserIdOrderByUpdatedAtDesc(workspaceId, userId)
                .stream().map(this::scriptView).toList();
    }

    @Transactional
    public Map<String, Object> save(String workspaceId, String userId,
                                     String id, String grantedSourceName,
                                     String title, String sql) {
        if (title == null || title.isBlank() || title.length() > 100
                || sql == null || sql.isBlank() || sql.length() > 64_000) {
            throw new IllegalArgumentException("脚本标题或 SQL 为空，或超过长度上限");
        }
        Grant grant = grantService.resolveForUser(userId, workspaceId, grantedSourceName);
        SavedSql script;
        if (id == null || id.isBlank()) {
            if (scripts.countByWorkspaceIdAndUserId(workspaceId, userId) >= 100) {
                throw new IllegalStateException("每个用户在工作空间最多保存 100 个脚本");
            }
            script = new SavedSql();
            script.setCreatedAt(LocalDateTime.now());
            script.setWorkspaceId(workspaceId);
            script.setUserId(userId);
        } else {
            script = scripts.findByIdAndWorkspaceIdAndUserId(id, workspaceId, userId)
                    .orElseThrow(() -> new IllegalArgumentException("脚本不存在或无权修改"));
        }
        script.setGrantId(grant.getId());
        script.setGrantedSourceName(grant.getGrantedSourceName());
        script.setTitle(title.trim());
        script.setEncryptedSql(credentials.encryptValue(sql));
        script.setUpdatedAt(LocalDateTime.now());
        Map<String, Object> saved = scriptView(scripts.save(script));
        audit.recordCurrent(workspaceId, "SQL_SCRIPT_SAVE", grant.getDataSourceId(),
                grant.getGrantedSourceName(), true, null);
        return saved;
    }

    @Transactional
    public void delete(String workspaceId, String userId, String id) {
        SavedSql script = scripts.findByIdAndWorkspaceIdAndUserId(id, workspaceId, userId)
                .orElseThrow(() -> new IllegalArgumentException("脚本不存在或无权删除"));
        scripts.delete(script);
        audit.recordCurrent(workspaceId, "SQL_SCRIPT_DELETE", null,
                script.getGrantedSourceName(), true, null);
    }

    public List<Map<String, Object>> history(String workspaceId, String userId) {
        return history.findTop100ByWorkspaceIdAndUserIdOrderByCreatedAtDesc(workspaceId, userId)
                .stream().map(item -> Map.<String, Object>of(
                        "id", item.getId(), "grantedSourceName", item.getGrantedSourceName(),
                        "sql", credentials.decryptValue(item.getEncryptedSql()),
                        "succeeded", item.isSucceeded(), "elapsedMs", item.getElapsedMs(),
                        "createdAt", item.getCreatedAt())).toList();
    }

    @Transactional
    public void recordHistory(String workspaceId, String userId, String grantedSourceName,
                              String sql, boolean succeeded, long elapsedMs) {
        if (sql == null || sql.isBlank() || sql.length() > 64_000) return;
        Grant grant = grantService.resolveForUser(userId, workspaceId, grantedSourceName);
        EnterpriseQueryHistory item = new EnterpriseQueryHistory();
        item.setWorkspaceId(workspaceId);
        item.setUserId(userId);
        item.setGrantId(grant.getId());
        item.setGrantedSourceName(grant.getGrantedSourceName());
        item.setEncryptedSql(credentials.encryptValue(sql));
        item.setSucceeded(succeeded);
        item.setElapsedMs(elapsedMs);
        item.setCreatedAt(LocalDateTime.now());
        history.save(item);
    }

    @Scheduled(cron = "0 15 3 * * *")
    @Transactional
    public void removeExpiredHistory() {
        history.deleteByCreatedAtBefore(LocalDateTime.now().minusDays(90));
    }

    private Map<String, Object> scriptView(SavedSql script) {
        return Map.of("id", script.getId(), "title", script.getTitle(),
                "grantedSourceName", script.getGrantedSourceName(),
                "sql", credentials.decryptValue(script.getEncryptedSql()),
                "createdAt", script.getCreatedAt(), "updatedAt", script.getUpdatedAt());
    }
}
