package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.service.ImportService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 数据导入控制器（个人版连接）
 *
 * <p>
 * POST /api/query/{connectionId}/import {schema?, table, rows:[{...}]}
 * </p>
 * <p>
 * POST /api/query/{connectionId}/import/file multipart(file, table, schema?,
 * format?)
 * </p>
 */
@RestController
@RequestMapping("/query")
public class ImportController {

    private final ImportService importService;

    public ImportController(ImportService importService) {
        this.importService = importService;
    }

    @PostMapping("/{connectionId}/import")
    public Map<String, Object> importRows(@PathVariable String connectionId,
            @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("rows");
        String schema = body.get("schema") != null ? body.get("schema").toString() : null;
        String table = body.get("table") != null ? body.get("table").toString() : null;
        if (table == null || rows == null) {
            throw new RuntimeException("table 和 rows 必填");
        }
        return importService.importRows(connectionId, schema, table, rows);
    }

    /**
     * 文件上传导入（CSV 首行表头 / JSON 数组）
     *
     * <p>
     * format 缺省时按文件后缀自动识别（.json → json，其余 → csv）。
     * </p>
     */
    @PostMapping("/{connectionId}/import/file")
    public Map<String, Object> importFile(@PathVariable String connectionId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("table") String table,
            @RequestParam(value = "schema", required = false) String schema,
            @RequestParam(value = "format", required = false) String format) {
        return importService.importFile(connectionId, schema, table, file, format);
    }
}
