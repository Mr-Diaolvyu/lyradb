package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 企业查询服务（核心：用户不见连接信息）
 *
 * <p>
 * 流程：用户提交 {@code grantedSourceName + sql} → 服务端按其授权解析真实数据源 →
 * 连接（驱动隔离）→ SQL 授权校验（能力 + 表白/黑名单 + 限行）→ 执行 → 审计 → 返回结果。
 * </p>
 *
 * <p>
 * 整个链路对用户只暴露逻辑数据源名，host/port/密码/AK-SK 永不出后端。
 * </p>
 */
@Service
public class EnterpriseQueryService {

    private static final Logger log = LoggerFactory.getLogger(EnterpriseQueryService.class);

    private static final Pattern TABLE_PATTERN = Pattern.compile(
            "(?i)\\b(?:FROM|JOIN|INTO|UPDATE)\\s+([A-Za-z_][\\w.]*)");

    private final GrantService grantService;
    private final DataSourceService dataSourceService;
    private final AuditService auditService;
    private final SecurityUtil securityUtil;

    public EnterpriseQueryService(GrantService grantService, DataSourceService dataSourceService,
            AuditService auditService, SecurityUtil securityUtil) {
        this.grantService = grantService;
        this.dataSourceService = dataSourceService;
        this.auditService = auditService;
        this.securityUtil = securityUtil;
    }

    /**
     * 执行查询
     *
     * @param grantedSourceName 逻辑数据源名（用户可见）
     * @param sql               SQL
     * @param defaultDatabase   可选切换库
     */
    public QueryResult executeQuery(String grantedSourceName, String sql, String defaultDatabase) throws Exception {
        User user = securityUtil.currentUser();
        if (user == null)
            throw new RuntimeException("未登录");
        String userId = user.getId();
        String username = user.getUsername();
        String role = user.getRoles().isEmpty() ? "ANALYST" : user.getRoles().get(0);

        Grant grant = grantService.resolveForUser(userId, grantedSourceName);
        String dataSourceId = grant.getDataSourceId();
        String dbType = dataSourceService.getEntity(dataSourceId).getDbType();

        long start = System.currentTimeMillis();
        QueryResult result = new QueryResult();
        result.setSql(sql);

        try {
            // 1. 授权时效
            if (grant.getExpiresAt() != null && grant.getExpiresAt().isBefore(LocalDateTime.now())) {
                throw new RuntimeException("授权已过期: " + grantedSourceName);
            }

            // 2. SQL 授权校验
            authorize(grant, sql);

            // 3. 解析连接（用户不可见）
            ConnectionService.ActiveConnection active = dataSourceService.resolveActiveConnection(dataSourceId);

            // 4. 限行
            int limit = grant.getMaxRowsPerQuery() > 0 ? grant.getMaxRowsPerQuery() : 10000;

            // 可选切换库（仅 JDBC）
            if (defaultDatabase != null && !defaultDatabase.isBlank()
                    && active.connection instanceof java.sql.Connection jdbc) {
                try {
                    jdbc.setCatalog(defaultDatabase);
                } catch (Exception ignored) {
                }
            }

            // 5. 执行
            result = active.driver.executeQuery(active.connection, sql, limit);
        } catch (Exception e) {
            log.warn("企业查询失败: {} - {}", grantedSourceName, e.getMessage());
            result.addColumn("error");
            java.util.Map<String, Object> row = result.newRow();
            row.put("error", "查询失败: " + e.getMessage());
            result.addRow(row);
            result.setElapsedMs(System.currentTimeMillis() - start);
            result.setTotalRows(1);
            audit(grant, user, dbType, "QUERY", sql, 0, 1, result.getElapsedMs(), false, e.getMessage());
            return result;
        }

        long elapsed = System.currentTimeMillis() - start;
        result.setElapsedMs(elapsed);
        audit(grant, user, dbType, "QUERY", sql, 0, result.getTotalRows(), elapsed, true, null);
        return result;
    }

    /** SQL 授权校验：能力 + 表白/黑名单 */
    private void authorize(Grant grant, String sql) {
        String trimmed = sql == null ? "" : sql.trim();
        String upper = trimmed.toUpperCase();
        String first = SqlParseUtil.firstWord(upper);

        if (SqlParseUtil.DDL_PREFIX.contains(first)) {
            throw new RuntimeException("不允许执行 DDL（" + first + "）");
        }
        boolean isDml = SqlParseUtil.DML_PREFIX.contains(first);
        if (isDml && !"DML_ALLOWED".equalsIgnoreCase(grant.getSqlCapability())) {
            throw new RuntimeException("当前数据源为只读，不允许 DML");
        }
        if (!SqlParseUtil.READ_PREFIX.contains(first) && !isDml) {
            throw new RuntimeException("不支持的语句: " + first);
        }

        Set<String> tables = extractTables(trimmed);
        // 黑名单
        Set<String> blocked = SqlParseUtil.splitCsv(grant.getBlockedTables());
        for (String t : tables) {
            if (SqlParseUtil.matchAny(t, blocked)) {
                throw new RuntimeException("表在黑名单中，禁止访问: " + t);
            }
        }
        // 白名单（若配置）
        Set<String> allowed = SqlParseUtil.splitCsv(grant.getAllowedTables());
        if (!allowed.isEmpty() && !tables.isEmpty()) {
            for (String t : tables) {
                if (!SqlParseUtil.matchAny(t, allowed)) {
                    throw new RuntimeException("表不在授权白名单内: " + t);
                }
            }
        }
    }

    private Set<String> extractTables(String sql) {
        Set<String> tables = new HashSet<>();
        Matcher m = TABLE_PATTERN.matcher(sql);
        while (m.find()) {
            tables.add(m.group(1));
        }
        return tables;
    }

    private void audit(Grant grant, User user, String dbType, String op,
            String sql, long affected, long resultRows, long elapsed, boolean success, String error) {
        auditService.record(
                grant.getWorkspaceId(), user.getId(), user.getUsername(),
                user.getRoles().isEmpty() ? "ANALYST" : user.getRoles().get(0),
                grant.getDataSourceId(), grant.getGrantedSourceName(), dbType, op,
                sql, affected, resultRows, elapsed, success, error);
    }
}
