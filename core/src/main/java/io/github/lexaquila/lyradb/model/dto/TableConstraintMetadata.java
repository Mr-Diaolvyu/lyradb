package io.github.lexaquila.lyradb.model.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 表索引与约束元数据。
 *
 * <p>类型使用 PRIMARY_KEY、FOREIGN_KEY、UNIQUE_INDEX、INDEX，
 * 便于桌面端和 B/S 端使用同一套结构展示。</p>
 */
public class TableConstraintMetadata {

    private String name;
    private String type;
    private List<String> columns = new ArrayList<>();
    private String referencedTable;
    private List<String> referencedColumns = new ArrayList<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public List<String> getColumns() {
        return columns;
    }

    public void setColumns(List<String> columns) {
        this.columns = columns == null
                ? new ArrayList<>() : new ArrayList<>(columns);
    }

    public String getReferencedTable() {
        return referencedTable;
    }

    public void setReferencedTable(String referencedTable) {
        this.referencedTable = referencedTable;
    }

    public List<String> getReferencedColumns() {
        return referencedColumns;
    }

    public void setReferencedColumns(List<String> referencedColumns) {
        this.referencedColumns = referencedColumns == null
                ? new ArrayList<>() : new ArrayList<>(referencedColumns);
    }
}
