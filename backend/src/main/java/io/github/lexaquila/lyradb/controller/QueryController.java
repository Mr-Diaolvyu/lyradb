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
     * 请求体格式: { "sql": "SELECT * FROM users" }
     * </p>
     */
    @PostMapping("/{connectionId}/execute")
    public ResponseEntity<QueryResult> executeQuery(
            @PathVariable String connectionId,
            @RequestBody Map<String, String> request) {
        String sql = request.get("sql");
        String defaultDatabase = request.get("defaultDatabase");
        if (sql == null || sql.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            QueryResult result = queryService.executeQuery(connectionId, sql, defaultDatabase);
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
     * 请求体格式: { "sql": "INSERT INTO users VALUES(...)" }
     * </p>
     */
    @PostMapping("/{connectionId}/update")
    public ResponseEntity<Map<String, Object>> executeUpdate(
            @PathVariable String connectionId,
            @RequestBody Map<String, String> request) {
        String sql = request.get("sql");
        String defaultDatabase = request.get("defaultDatabase");
        if (sql == null || sql.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            int affected = queryService.executeUpdate(connectionId, sql, defaultDatabase);
            Map<String, Object> result = Map.of(
                    "success", true,
                    "affectedRows", affected);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("更新执行失败: {} - {}", connectionId, e.getMessage(), e);
            Map<String, Object> result = Map.of(
                    "success", false,
                    "message", e.getMessage());
            return ResponseEntity.ok(result);
        }
    }
}
