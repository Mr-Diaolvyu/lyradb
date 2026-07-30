package io.github.lexaquila.lyradb.desktop.metadata;

import java.util.Locale;

/**
 * 数据库导航树中可采集元数据的当前选择。
 */
public record MetadataSelection(String connectionId,
                                String dbType,
                                Scope scope,
                                String name,
                                String path,
                                String nodeType) {

    public enum Scope {
        DATABASE("数据库"),
        SCHEMA("Schema"),
        TABLE("表");

        private final String label;

        Scope(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public MetadataSelection {
        connectionId = required(connectionId, "连接 ID");
        dbType = required(dbType, "数据库类型").toUpperCase(Locale.ROOT);
        scope = java.util.Objects.requireNonNull(scope, "元数据范围不能为空");
        name = required(name, "范围名称");
        path = required(path, "节点路径");
        nodeType = required(nodeType, "节点类型").toUpperCase(Locale.ROOT);
    }

    public String displayScope() {
        return scope.label() + " · " + name;
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return value.trim();
    }
}
