package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.dto.TreeNode;
import io.github.lexaquila.lyradb.model.entity.DriverCapability;
import io.github.lexaquila.lyradb.service.MetadataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Collections;

/**
 * 元数据REST控制器
 *
 * <p>
 * 提供数据库导航树、表结构元数据、DDL查询等API。
 * </p>
 *
 * <p>
 * API路径：
 * </p>
 * <ul>
 * <li>GET /api/metadata/{connectionId}/tree?path=xxx - 获取导航树节点</li>
 * <li>GET /api/metadata/{connectionId}/columns?schema=xxx&table=xxx -
 * 获取表列信息</li>
 * <li>GET /api/metadata/{connectionId}/ddl?schema=xxx&table=xxx - 获取表DDL</li>
 * <li>GET /api/metadata/{connectionId}/capabilities - 获取驱动能力声明</li>
 * </ul>
 */
@RestController
@RequestMapping("/metadata")
public class MetadataController {

    private static final Logger log = LoggerFactory.getLogger(MetadataController.class);

    private final MetadataService metadataService;

    public MetadataController(MetadataService metadataService) {
        this.metadataService = metadataService;
    }

    /**
     * 获取导航树节点
     *
     * @param connectionId 连接ID
     * @param path         父节点路径（null或空=顶层）
     */
    @GetMapping("/{connectionId}/tree")
    public ResponseEntity<List<TreeNode>> getTreeNodes(
            @PathVariable String connectionId,
            @RequestParam(value = "path", required = false) String path) {
        try {
            List<TreeNode> nodes = metadataService.getTreeNodes(connectionId, path);
            return ResponseEntity.ok(nodes);
        } catch (Exception e) {
            log.error("获取树节点失败: {} - {}", connectionId, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 获取表列信息
     */
    @GetMapping("/{connectionId}/columns")
    public ResponseEntity<List<ColumnMetadata>> getTableColumns(
            @PathVariable String connectionId,
            @RequestParam(value = "schema", required = false) String schema,
            @RequestParam("table") String table) {
        try {
            List<ColumnMetadata> columns = metadataService.getTableColumns(connectionId, schema, table);
            return ResponseEntity.ok(columns);
        } catch (Exception e) {
            log.error("获取表列信息失败: {} - {}", table, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 获取表DDL
     */
    @GetMapping("/{connectionId}/ddl")
    public ResponseEntity<String> getTableDDL(
            @PathVariable String connectionId,
            @RequestParam(value = "schema", required = false) String schema,
            @RequestParam("table") String table) {
        try {
            String ddl = metadataService.getTableDDL(connectionId, schema, table);
            return ResponseEntity.ok(ddl);
        } catch (Exception e) {
            log.error("获取DDL失败: {} - {}", table, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 获取数据库列表
     *
     * <p>
     * 返回当前连接下可用的数据库（Catalog）名称列表，
     * 用于前端数据库切换下拉菜单。
     * </p>
     *
     * @param connectionId 连接ID
     * @return 数据库名称列表
     */
    @GetMapping("/{connectionId}/databases")
    public ResponseEntity<List<String>> getDatabases(@PathVariable String connectionId) {
        try {
            List<String> databases = metadataService.getDatabases(connectionId);
            return ResponseEntity.ok(databases);
        } catch (Exception e) {
            log.error("获取数据库列表失败: {} - {}", connectionId, e.getMessage(), e);
            return ResponseEntity.ok(Collections.emptyList());
        }
    }

    /**
     * 搜索导航树节点
     *
     * <p>
     * 按关键字搜索表名/视图名/集合名等，支持按节点类型过滤。
     * 返回匹配的节点列表（最多100条）。
     * </p>
     *
     * @param connectionId 连接ID
     * @param keyword      搜索关键字
     * @param type         可选节点类型过滤 (TABLE/VIEW/COLLECTION/KEY)
     * @return 匹配的节点列表
     */
    @GetMapping("/{connectionId}/search")
    public ResponseEntity<List<TreeNode>> searchNodes(
            @PathVariable String connectionId,
            @RequestParam("keyword") String keyword,
            @RequestParam(value = "type", required = false) String type) {
        try {
            List<TreeNode> results = metadataService.searchNodes(connectionId, keyword, type);
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            log.error("搜索节点失败: {} - {}", connectionId, e.getMessage(), e);
            return ResponseEntity.ok(Collections.emptyList());
        }
    }

    /**
     * 获取驱动能力声明
     */
    @GetMapping("/{connectionId}/capabilities")
    public ResponseEntity<DriverCapability> getCapabilities(@PathVariable String connectionId) {
        try {
            return ResponseEntity.ok(metadataService.getCapabilities(connectionId));
        } catch (Exception e) {
            log.error("获取能力声明失败: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 获取 ER 图数据（表 + 外键关系），用于 ER 图视图（PRD F7）
     *
     * <p>请求参数: schema（可选）</p>
     */
    @GetMapping("/{connectionId}/er")
    public ResponseEntity<io.github.lexaquila.lyradb.model.dto.ErDiagram> getErDiagram(
            @PathVariable String connectionId,
            @RequestParam(value = "schema", required = false) String schema) {
        try {
            return ResponseEntity.ok(metadataService.getErDiagram(connectionId, schema));
        } catch (Exception e) {
            log.error("获取 ER 图失败: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }
}
