package io.github.lexaquila.lyradb.model.dto;

import lombok.Data;
import java.util.HashMap;
import java.util.Map;

/**
 * 导航树节点DTO
 *
 * <p>
 * 统一的树节点结构，适配不同数据库的导航层级：
 * <ul>
 * <li>RDBMS: DATABASE → SCHEMA → TABLE/VIEW</li>
 * <li>MaxCompute: PROJECT → TABLE → PARTITION</li>
 * <li>MongoDB: DATABASE → COLLECTION</li>
 * <li>Redis: DB_INDEX → KEY_PREFIX → KEY</li>
 * </ul>
 * </p>
 */
@Data
public class TreeNode {

    /** 节点唯一标识 */
    private String id;

    /** 节点显示名称 */
    private String name;

    /**
     * 节点类型：CONNECTION/DATABASE/SCHEMA/TABLE/VIEW/COLLECTION/PARTITION/KEY_GROUP/KEY
     */
    private String type;

    /** 节点图标类型（前端根据此选择图标） */
    private String iconType;

    /** 是否有子节点（用于懒加载判断） */
    private boolean hasChildren = true;

    /** 节点完整路径（如 "db1/users"） */
    private String path;

    /** 附加属性（如表行数/分区值等） */
    private Map<String, Object> properties = new HashMap<>();

    /**
     * 快速创建树节点的工厂方法
     */
    public static TreeNode of(String id, String name, String type, String path) {
        TreeNode node = new TreeNode();
        node.setId(id);
        node.setName(name);
        node.setType(type);
        node.setPath(path);
        return node;
    }
}
