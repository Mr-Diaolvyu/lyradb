package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.service.EnterpriseQueryService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 企业查询控制器（用户不见连接信息）
 *
 * <p>POST /api/ent/query {grantedSourceName, sql, defaultDatabase?}</p>
 */
@RestController
@RequestMapping("/ent")
public class EnterpriseQueryController {

    private final EnterpriseQueryService queryService;

    public EnterpriseQueryController(EnterpriseQueryService queryService) {
        this.queryService = queryService;
    }

    @PostMapping("/query")
    public QueryResult execute(@RequestBody Map<String, String> body) throws Exception {
        String grantedSourceName = body.get("grantedSourceName");
        String sql = body.get("sql");
        String defaultDatabase = body.get("defaultDatabase");
        if (grantedSourceName == null || sql == null || sql.isBlank()) {
            throw new RuntimeException("grantedSourceName 和 sql 必填");
        }
        return queryService.executeQuery(grantedSourceName, sql, defaultDatabase);
    }
}
