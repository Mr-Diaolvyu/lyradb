package io.github.lexaquila.lyradb.model.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * ER 图数据
 *
 * <p>包含表节点与基于外键的关系边（PRD F7）。NoSQL 无外键，返回空边。</p>
 */
@Data
public class ErDiagram {

    private String sourceName;
    private String dbType;
    private String schema;
    private boolean truncated;

    /** 表节点 */
    private List<Table> tables = new ArrayList<>();

    /** 关系边（基于外键） */
    private List<Edge> edges = new ArrayList<>();

    @Data
    public static class Table {
        private String name;
        private List<String> columns = new ArrayList<>();
        private String schema;
        private String remarks;
        private List<Column> columnDetails = new ArrayList<>();

        public Table() {}

        public Table(String name, String schema) {
            this.name = name;
            this.schema = schema;
        }
    }

    @Data
    public static class Column {
        private String name;
        private String typeName;
        private String remarks;
        private boolean primaryKey;

        public Column() {
        }

        public Column(String name, String typeName,
                      String remarks, boolean primaryKey) {
            this.name = name;
            this.typeName = typeName;
            this.remarks = remarks;
            this.primaryKey = primaryKey;
        }
    }

    @Data
    public static class Edge {
        /** 源表（含外键的表） */
        private String source;
        /** 目标表（被引用的表） */
        private String target;
        private String sourceColumn;
        private String targetColumn;

        public Edge() {}

        public Edge(String source, String target, String sourceColumn, String targetColumn) {
            this.source = source;
            this.target = target;
            this.sourceColumn = sourceColumn;
            this.targetColumn = targetColumn;
        }
    }
}
