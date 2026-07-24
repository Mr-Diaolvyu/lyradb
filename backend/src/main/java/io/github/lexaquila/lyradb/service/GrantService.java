package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.repository.GrantRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源授权服务（逻辑数据源 → 用户）
 *
 * <p>对用户侧返回的逻辑视图**不含 dataSourceId**（用户不可见真实连接信息）。</p>
 */
@Service
public class GrantService {

    private final GrantRepository repository;

    public GrantService(GrantRepository repository) {
        this.repository = repository;
    }

    public Grant create(String workspaceId, String dataSourceId, String userId, String grantedSourceName,
                        String allowedSchemas, String allowedTables, String blockedTables,
                        String sqlCapability, int maxRows, LocalDateTime expiresAt) {
        Grant g = new Grant();
        g.setWorkspaceId(workspaceId);
        g.setDataSourceId(dataSourceId);
        g.setUserId(userId);
        g.setGrantedSourceName(grantedSourceName);
        g.setAllowedSchemas(allowedSchemas);
        g.setAllowedTables(allowedTables);
        g.setBlockedTables(blockedTables);
        g.setSqlCapability(sqlCapability != null ? sqlCapability : "READ_ONLY");
        g.setMaxRowsPerQuery(maxRows > 0 ? maxRows : 10000);
        g.setExportApprovedOnly(true);
        g.setExpiresAt(expiresAt);
        return repository.save(g);
    }

    /** 用户可见的逻辑数据源列表（不含 dataSourceId） */
    public List<Map<String, Object>> listMine(String userId) {
        List<Grant> grants = repository.findByUserIdOrderByCreatedAtDesc(userId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Grant g : grants) {
            out.add(toLogicalView(g));
        }
        return out;
    }

    /** 管理员视图（含 dataSourceId，用于分配管理）；workspaceId 为空则返回全部 */
    public List<Map<String, Object>> listByWorkspace(String workspaceId) {
        List<Grant> grants = (workspaceId == null || workspaceId.isEmpty())
                ? repository.findAll() : repository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Grant g : grants) {
            Map<String, Object> v = toLogicalView(g);
            v.put("dataSourceId", g.getDataSourceId());
            v.put("userId", g.getUserId());
            out.add(v);
        }
        return out;
    }

    /** 服务端解析：按用户 + 逻辑名 取授权实体（含 dataSourceId） */
    public Grant resolveForUser(String userId, String grantedSourceName) {
        return repository.findByUserIdAndGrantedSourceName(userId, grantedSourceName)
                .orElseThrow(() -> new RuntimeException("无授权的数据源: " + grantedSourceName));
    }

    public Grant getByIdForUser(String grantId, String userId) {
        return repository.findByIdAndUserId(grantId, userId)
                .orElseThrow(() -> new RuntimeException("授权不存在或无权访问: " + grantId));
    }

    public void delete(String id) {
        repository.deleteById(id);
    }

    private Map<String, Object> toLogicalView(Grant g) {
        Map<String, Object> v = new HashMap<>();
        v.put("id", g.getId());
        v.put("grantedSourceName", g.getGrantedSourceName());
        v.put("workspaceId", g.getWorkspaceId());
        v.put("allowedSchemas", g.getAllowedSchemas());
        v.put("allowedTables", g.getAllowedTables());
        v.put("blockedTables", g.getBlockedTables());
        v.put("sqlCapability", g.getSqlCapability());
        v.put("maxRowsPerQuery", g.getMaxRowsPerQuery());
        v.put("exportApprovedOnly", g.isExportApprovedOnly());
        v.put("expiresAt", g.getExpiresAt());
        return v;
    }
}
