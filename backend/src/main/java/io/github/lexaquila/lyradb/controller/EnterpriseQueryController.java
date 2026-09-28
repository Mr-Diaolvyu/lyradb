package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.dto.EnterpriseMetadataCatalog;
import io.github.lexaquila.lyradb.model.dto.EnterprisePartitionPageView;
import io.github.lexaquila.lyradb.model.dto.ErDiagram;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.model.dto.TableInspection;
import io.github.lexaquila.lyradb.service.EnterpriseMetadataCatalogService;
import io.github.lexaquila.lyradb.service.EnterpriseQueryService;
import io.github.lexaquila.lyradb.service.EnterpriseQueryExecutionService;
import io.github.lexaquila.lyradb.service.EnterpriseSqlWorkspaceService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
    private final EnterpriseMetadataCatalogService metadataService;
    private final EnterpriseSqlWorkspaceService sqlWorkspaceService;
    private final SecurityUtil securityUtil;
    private final EnterpriseQueryExecutionService executions;

    public EnterpriseQueryController(
            EnterpriseQueryService queryService,
            EnterpriseMetadataCatalogService metadataService,
            EnterpriseSqlWorkspaceService sqlWorkspaceService,
            SecurityUtil securityUtil,
            EnterpriseQueryExecutionService executions) {
        this.queryService = queryService;
        this.metadataService = metadataService;
        this.sqlWorkspaceService = sqlWorkspaceService;
        this.securityUtil = securityUtil;
        this.executions = executions;
    }

    @PostMapping("/query/executions")
    public Map<String, String> prepare(@RequestBody Map<String, String> body) {
        return Map.of("executionId", executions.prepare(body.get("grantedSourceName")));
    }

    @PostMapping("/query/executions/{id}/cancel")
    public Map<String, Boolean> cancel(@PathVariable String id) {
        return Map.of("cancelRequested", executions.cancel(id));
    }

    @PostMapping("/query")
    public QueryResult execute(@RequestBody Map<String, String> body,
                               HttpSession session) throws Exception {
        String grantedSourceName = body.get("grantedSourceName");
        String sql = body.get("sql");
        String defaultDatabase = body.get("defaultDatabase");
        if (grantedSourceName == null || sql == null || sql.isBlank()) {
            throw new RuntimeException("grantedSourceName 和 sql 必填");
        }
        long started = System.currentTimeMillis();
        boolean succeeded = false;
        try {
            String executionId = body.get("executionId");
            QueryResult result = executionId == null
                    ? queryService.executeQuery(grantedSourceName, sql, defaultDatabase)
                    : executions.execute(executionId, grantedSourceName, sql, defaultDatabase);
            succeeded = true;
            return result;
        } finally {
            try {
                sqlWorkspaceService.recordHistory(
                        securityUtil.requireCurrentWorkspace(session),
                        securityUtil.requireCurrentUser().getId(), grantedSourceName,
                        sql, succeeded, System.currentTimeMillis() - started);
            } catch (RuntimeException ignoredHistoryFailure) {
                // 查询服务已有独立审计；历史写入失败不能遮盖 SQL 或审批的原始结果。
            }
        }
    }

    @PostMapping("/table-inspection")
    public TableInspection inspectTable(
            @RequestBody Map<String, Object> body) throws Exception {
        String grantedSourceName = text(body.get("grantedSourceName"));
        String schema = text(body.get("schema"));
        String table = text(body.get("table"));
        String objectType = text(body.get("objectType"));
        int limit = body.get("limit") instanceof Number number
                ? number.intValue() : 200;
        boolean includePreview = body.get("includePreview") instanceof Boolean value
                && value;
        String partitionSpec = text(body.get("partitionSpec"));
        if (grantedSourceName.isBlank() || schema.isBlank()
                || table.isBlank()) {
            throw new IllegalArgumentException(
                    "grantedSourceName、schema 和 table 必填");
        }
        return queryService.inspectTable(
                grantedSourceName, schema, table, objectType, limit,
                includePreview,
                partitionSpec.isBlank() ? null : partitionSpec);
    }

    @PostMapping("/table-partitions")
    public EnterprisePartitionPageView tablePartitions(
            @RequestBody Map<String, Object> body) throws Exception {
        String grantedSourceName = text(body.get("grantedSourceName"));
        String schema = text(body.get("schema"));
        String table = text(body.get("table"));
        int offset = body.get("offset") instanceof Number number
                ? number.intValue() : 0;
        int limit = body.get("limit") instanceof Number number
                ? number.intValue() : 50;
        String filter = text(body.get("filter"));
        if (grantedSourceName.isBlank() || schema.isBlank()
                || table.isBlank()) {
            throw new IllegalArgumentException(
                    "grantedSourceName、schema 和 table 必填");
        }
        return queryService.listTablePartitions(
                grantedSourceName, schema, table,
                offset, limit, filter);
    }

    @GetMapping("/metadata/catalog")
    public EnterpriseMetadataCatalog metadataCatalog(
            @RequestParam String grantedSourceName,
            @RequestParam(defaultValue = "false") boolean refresh)
            throws Exception {
        return metadataService.catalog(
                grantedSourceName, refresh);
    }

    @GetMapping("/metadata/navigation")
    public Map<String, Object> navigation(@RequestParam String grantedSourceName,
                                          @RequestParam(required = false) String parentPath,
                                          @RequestParam(required = false) String query,
                                          @RequestParam(defaultValue = "0") int offset,
                                          @RequestParam(defaultValue = "100") int limit)
            throws Exception {
        return metadataService.navigation(grantedSourceName, parentPath, query, offset, limit);
    }

    @GetMapping("/metadata/search")
    public Map<String, Object> searchTables(@RequestParam String grantedSourceName,
                                            @RequestParam String query) throws Exception {
        return metadataService.searchTables(grantedSourceName, query);
    }

    @GetMapping("/metadata/columns")
    public List<ColumnMetadata> columns(
            @RequestParam String grantedSourceName,
            @RequestParam String namespace,
            @RequestParam String table) throws Exception {
        return metadataService.columns(
                grantedSourceName, namespace, table);
    }

    @GetMapping("/er")
    public ErDiagram erDiagram(
            @RequestParam String grantedSourceName,
            @RequestParam String schema,
            @RequestParam(defaultValue = "") String tables) throws Exception {
        List<String> selectedTables = java.util.Arrays.stream(
                        (tables == null ? "" : tables).split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
        return metadataService.erDiagram(
                grantedSourceName, schema, selectedTables);
    }

    private String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
