package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.service.QueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 查询执行REST控制器
 *
 * <p>
 * 提供SQL查询执行API，支持9种数据库的统一查询。
 * </p>
 *
 * <p>
 * API路径：
 * </p>
 * <ul>
 * <li>POST /api/query/{connectionId}/execute - 执行查询SQL</li>
 * <li>POST /api/query/{connectionId}/update - 执行更新/DDL</li>
 * <li>POST /api/query/{connectionId}/cancel - 取消正在执行的查询</li>
 * </ul>
 */
@RestController
@RequestMapping("/query")
public class QueryController {

    private static final Logger log = LoggerFactory.getLogger(QueryController.class);

    private final QueryService queryService;

    public QueryController(QueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * 执行查询SQL
     *
     * <p>
     * 请求体格式: { "sql": "SELECT * FROM users", "force": "true"? }
     * 命中审核拦截规则时返回 reviewBlocked=true + reviewFindings，
     * 前端确认后携 force=true 重发（"仍要执行"逃生门）。
     * </p>
     */
    @PostMapping("/{connectionId}/execute")
    public ResponseEntity<QueryResult> executeQuery(
            @PathVariable String connectionId,
            @RequestBody Map<String, String> request) {
        String sql = request.get("sql");
        String defaultDatabase = request.get("defaultDatabase");
        boolean force = Boolean.parseBoolean(request.get("force"));
        if (sql == null || sql.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            QueryResult result = queryService.executeQuery(connectionId, sql, defaultDatabase, force);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("查询执行失败: {} - {}", connectionId, e.getMessage(), e);
            QueryResult errorResult = new QueryResult();
            errorResult.setSql(sql);
            errorResult.addColumn("error");
            java.util.Map<String, Object> row = errorResult.newRow();
            row.put("error", "查询失败: " + e.getMessage());
            errorResult.addRow(row);
            errorResult.setElapsedMs(0);
            errorResult.setTotalRows(1);
            return ResponseEntity.ok(errorResult);
        }
    }

    /**
     * 执行更新/DDL语句
     *
     * <p>
     * 请求体格式: { "sql": "INSERT INTO users VALUES(...)", "force": "true"? }
     * 命中审核拦截规则时返回 reviewBlocked=true + findings。
     * </p>
     */
    @PostMapping("/{connectionId}/update")
    public ResponseEntity<Map<String, Object>> executeUpdate(
            @PathVariable String connectionId,
            @RequestBody Map<String, String> request) {
        String sql = request.get("sql");
        String defaultDatabase = request.get("defaultDatabase");
        boolean force = Boolean.parseBoolean(request.get("force"));
        if (sql == null || sql.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            int affected = queryService.executeUpdate(connectionId, sql, defaultDatabase, force);
            Map<String, Object> result = Map.of(
                    "success", true,
                    "affectedRows", affected);
            return ResponseEntity.ok(result);
        } catch (QueryService.SqlReviewBlockedException e) {
            Map<String, Object> result = Map.of(
                    "success", false,
                    "reviewBlocked", true,
                    "reviewFindings", e.getFindings());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("更新执行失败: {} - {}", connectionId, e.getMessage(), e);
            Map<String, Object> result = Map.of(
                    "success", false,
                    "message", e.getMessage());
            return ResponseEntity.ok(result);
        }
    }

    /**
     * 取消正在执行的查询
     *
     * <p>
     * 仅对 JDBC 类驱动有效；返回 cancelled 表示是否找到并取消了执行中语句。
     * </p>
     */
    @PostMapping("/{connectionId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelQuery(@PathVariable String connectionId) {
        try {
            boolean cancelled = queryService.cancelQuery(connectionId);
            return ResponseEntity.ok(Map.of("cancelled", cancelled));
        } catch (Exception e) {
            log.warn("取消查询失败: {} - {}", connectionId, e.getMessage());
            return ResponseEntity.ok(Map.of("cancelled", false, "message", String.valueOf(e.getMessage())));
        }
    }
}
