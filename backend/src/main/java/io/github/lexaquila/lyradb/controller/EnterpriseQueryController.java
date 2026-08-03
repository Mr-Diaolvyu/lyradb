package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.dto.EnterpriseMetadataCatalog;
import io.github.lexaquila.lyradb.model.dto.ErDiagram;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.model.dto.TableInspection;
import io.github.lexaquila.lyradb.service.EnterpriseMetadataCatalogService;
import io.github.lexaquila.lyradb.service.EnterpriseQueryService;
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

    public EnterpriseQueryController(
            EnterpriseQueryService queryService,
            EnterpriseMetadataCatalogService metadataService) {
        this.queryService = queryService;
        this.metadataService = metadataService;
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

    @PostMapping("/table-inspection")
    public TableInspection inspectTable(
            @RequestBody Map<String, Object> body) throws Exception {
        String grantedSourceName = text(body.get("grantedSourceName"));
        String schema = text(body.get("schema"));
        String table = text(body.get("table"));
        String objectType = text(body.get("objectType"));
        int limit = body.get("limit") instanceof Number number
                ? number.intValue() : 200;
        if (grantedSourceName.isBlank() || schema.isBlank()
                || table.isBlank()) {
            throw new IllegalArgumentException(
                    "grantedSourceName、schema 和 table 必填");
        }
        return queryService.inspectTable(
                grantedSourceName, schema, table, objectType, limit);
    }

    @GetMapping("/metadata/catalog")
    public EnterpriseMetadataCatalog metadataCatalog(
            @RequestParam String grantedSourceName,
            @RequestParam(defaultValue = "false") boolean refresh)
            throws Exception {
        return metadataService.catalog(
                grantedSourceName, refresh);
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
            @RequestParam String tables) throws Exception {
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
