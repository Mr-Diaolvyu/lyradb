package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.service.QueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 查询执行 REST 控制器。执行失败交由统一异常边界返回非 2xx 状态，
 * 不再把数据库错误伪装成成功结果或向客户端暴露驱动异常。
 */
@RestController
@RequestMapping("/query")
public class QueryController {

    private final QueryService queryService;

    public QueryController(QueryService queryService) {
        this.queryService = queryService;
    }

    @PostMapping("/{connectionId}/execute")
    public ResponseEntity<QueryResult> executeQuery(@PathVariable String connectionId,
            @RequestBody Map<String, String> request) throws Exception {
        String sql = request.get("sql");
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        QueryResult result = queryService.executeQuery(connectionId, sql,
                request.get("defaultDatabase"),
                Boolean.parseBoolean(request.get("force")));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{connectionId}/update")
    public ResponseEntity<Map<String, Object>> executeUpdate(@PathVariable String connectionId,
            @RequestBody Map<String, String> request) throws Exception {
        String sql = request.get("sql");
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        int affected = queryService.executeUpdate(connectionId, sql,
                request.get("defaultDatabase"),
                Boolean.parseBoolean(request.get("force")));
        return ResponseEntity.ok(Map.of("success", true, "affectedRows", affected));
    }

    @PostMapping("/{connectionId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelQuery(@PathVariable String connectionId) {
        return ResponseEntity.ok(Map.of("cancelled", queryService.cancelQuery(connectionId)));
    }
}
