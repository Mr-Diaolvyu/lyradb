package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.driver.DatabaseDriver;
import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.dto.TreeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 元数据服务
 *
 * <p>
 * 提供数据库导航树、表结构元数据、DDL查询等功能。
 * 所有操作基于活跃的数据库连接，通过DatabaseDriver接口统一适配9种数据库。
 * </p>
 *
 * <p>
 * 导航树按数据库类型返回不同层级：
 * RDBMS→数据库→Schema→表/视图，MaxCompute→表→分区，
 * MongoDB→Database→Collection，Redis→DB索引→Key前缀→Key
 * </p>
 */
@Service
public class MetadataService {

    private static final Logger log = LoggerFactory.getLogger(MetadataService.class);

    private final ConnectionService connectionService;

    public MetadataService(ConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    /**
     * 获取导航树节点
     *
     * @param connectionId 连接ID
     * @param parentPath   父节点路径（null=顶层）
     * @return 子节点列表
     */
    public List<TreeNode> getTreeNodes(String connectionId, String parentPath) throws Exception {
        ConnectionService.ActiveConnection active = connectionService.getActiveConnection(connectionId);
        log.debug("获取树节点: connectionId={}, parentPath={}", connectionId, parentPath);
        return active.driver.getTreeNodes(active.connection, parentPath);
    }

    /**
     * 获取表列元数据
     *
     * @param connectionId 连接ID
     * @param schemaName   Schema名
     * @param tableName    表名
     * @return 列信息列表
     */
    public List<ColumnMetadata> getTableColumns(String connectionId, String schemaName, String tableName)
            throws Exception {
        ConnectionService.ActiveConnection active = connectionService.getActiveConnection(connectionId);
        log.debug("获取表列元数据: connectionId={}, schema={}, table={}", connectionId, schemaName, tableName);
        return active.driver.getTableColumns(active.connection, schemaName, tableName);
    }

    /**
     * 获取表DDL
     *
     * @param connectionId 连接ID
     * @param schemaName   Schema名
     * @param tableName    表名
     * @return DDL语句
     */
    public String getTableDDL(String connectionId, String schemaName, String tableName) throws Exception {
        ConnectionService.ActiveConnection active = connectionService.getActiveConnection(connectionId);
        log.debug("获取表DDL: connectionId={}, schema={}, table={}", connectionId, schemaName, tableName);
        return active.driver.getTableDDL(active.connection, schemaName, tableName);
    }

    /**
     * 获取数据库列表
     *
     * <p>
     * 返回当前连接下可用的数据库（Catalog）名称列表。
     * 通过导航树根节点的 DATABASE 类型节点提取。
     * </p>
     *
     * @param connectionId 连接ID
     * @return 数据库名称列表
     */
    public List<String> getDatabases(String connectionId) throws Exception {
        ConnectionService.ActiveConnection active = connectionService.getActiveConnection(connectionId);
        log.debug("获取数据库列表: connectionId={}", connectionId);
        List<TreeNode> rootNodes = active.driver.getTreeNodes(active.connection, null);
        return rootNodes.stream()
                .filter(n -> "DATABASE".equals(n.getType()))
                .map(TreeNode::getName)
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * 搜索导航树节点
     *
     * <p>
     * 递归遍历已加载的树节点，按关键字过滤匹配的节点（表名/视图名/集合名等）。
     * 搜索深度限制为3层以避免性能问题，返回前100条匹配结果。
     * </p>
     *
     * @param connectionId 连接ID
     * @param keyword      搜索关键字
     * @param type         可选节点类型过滤 (TABLE/VIEW/COLLECTION/KEY)
     * @return 匹配的节点列表
     */
    public List<TreeNode> searchNodes(String connectionId, String keyword, String type) throws Exception {
        ConnectionService.ActiveConnection active = connectionService.getActiveConnection(connectionId);
        log.debug("搜索节点: connectionId={}, keyword={}, type={}", connectionId, keyword, type);

        List<TreeNode> results = new ArrayList<>();
        String lowerKeyword = keyword.toLowerCase();

        // 获取根节点（通常是 DATABASE / Project / DB_INDEX 层级）
        List<TreeNode> rootNodes = active.driver.getTreeNodes(active.connection, null);

        for (TreeNode root : rootNodes) {
            if (results.size() >= 100)
                break;

            // 根节点本身匹配
            if (matchesNode(root, lowerKeyword, type)) {
                results.add(root);
                if (results.size() >= 100)
                    break;
            }

            // 遍历第二层（DATABASE 下的 Schema/Table/Collection 等）
            try {
                List<TreeNode> level2 = active.driver.getTreeNodes(active.connection, root.getPath());
                for (TreeNode l2 : level2) {
                    if (results.size() >= 100)
                        break;

                    if (matchesNode(l2, lowerKeyword, type)) {
                        results.add(l2);
                    }

                    // 第三层（Schema 下的 Table/View 等）
                    if (l2.isHasChildren() && ("SCHEMA".equals(l2.getType()) || "DATABASE".equals(l2.getType()))) {
                        try {
                            List<TreeNode> level3 = active.driver.getTreeNodes(active.connection, l2.getPath());
                            for (TreeNode l3 : level3) {
                                if (results.size() >= 100)
                                    break;
                                if (matchesNode(l3, lowerKeyword, type)) {
                                    results.add(l3);
                                }
                            }
                        } catch (Exception e) {
                            log.debug("搜索第三层失败: {} - {}", l2.getPath(), e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("搜索第二层失败: {} - {}", root.getPath(), e.getMessage());
            }
        }

        return results;
    }

    /**
     * 判断节点是否匹配搜索条件
     */
    private boolean matchesNode(TreeNode node, String lowerKeyword, String type) {
        if (type != null && !type.isEmpty() && !type.equals(node.getType())) {
            return false;
        }
        return node.getName() != null && node.getName().toLowerCase().contains(lowerKeyword);
    }

    /**
     * 获取驱动能力声明
     */
    public io.github.lexaquila.lyradb.model.entity.DriverCapability getCapabilities(String connectionId) {
        ConnectionService.ActiveConnection active = connectionService.getActiveConnection(connectionId);
        return active.driver.getCapabilities();
    }

    /**
     * 获取 ER 图数据（表节点 + 基于外键的关系边）
     *
     * <p>仅 JDBC 类型数据库有效（NoSQL 无外键概念，返回空边）。</p>
     *
     * @param connectionId 连接ID
     * @param schema       可选，限定 schema/database
     */
    public io.github.lexaquila.lyradb.model.dto.ErDiagram getErDiagram(String connectionId, String schema) {
        io.github.lexaquila.lyradb.model.dto.ErDiagram er = new io.github.lexaquila.lyradb.model.dto.ErDiagram();
        ConnectionService.ActiveConnection active = connectionService.getActiveConnection(connectionId);
        Object conn = active.connection;

        if (!(conn instanceof java.sql.Connection jdbcConn)) {
            // NoSQL：ER 不适用
            return er;
        }

        try {
            java.sql.DatabaseMetaData md = jdbcConn.getMetaData();
            // 表节点
            String catalog = null;
            String schemaPattern = schema;
            try (java.sql.ResultSet rs = md.getTables(catalog, schemaPattern, "%",
                    new String[]{"TABLE", "VIEW"})) {
                while (rs.next()) {
                    String tName = rs.getString("TABLE_NAME");
                    String tSchema = rs.getString("TABLE_SCHEM");
                    io.github.lexaquila.lyradb.model.dto.ErDiagram.Table t =
                            new io.github.lexaquila.lyradb.model.dto.ErDiagram.Table(tName, tSchema);
                    // 列
                    try (java.sql.ResultSet cols = md.getColumns(catalog, schemaPattern, tName, "%")) {
                        while (cols.next()) {
                            t.getColumns().add(cols.getString("COLUMN_NAME"));
                        }
                    }
                    er.getTables().add(t);
                }
            }

            // 外键边
            for (io.github.lexaquila.lyradb.model.dto.ErDiagram.Table t : er.getTables()) {
                try (java.sql.ResultSet fks = md.getImportedKeys(catalog, schemaPattern, t.getName())) {
                    while (fks.next()) {
                        String source = t.getName();
                        String sourceCol = fks.getString("FKCOLUMN_NAME");
                        String target = fks.getString("PKTABLE_NAME");
                        String targetCol = fks.getString("PKCOLUMN_NAME");
                        if (source != null && target != null) {
                            er.getEdges().add(new io.github.lexaquila.lyradb.model.dto.ErDiagram.Edge(
                                    source, target, sourceCol, targetCol));
                        }
                    }
                } catch (Exception e) {
                    // 部分库不支持 getImportedKeys，忽略单表失败
                }
            }
        } catch (java.sql.SQLException e) {
            log.warn("获取 ER 图失败: {}", e.getMessage());
        }

        return er;
    }
}
