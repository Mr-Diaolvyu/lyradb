package io.github.lexaquila.lyradb.model.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表工作台的一次加载快照。
 *
 * <p>各区域独立记录错误，避免 DDL 或索引权限不足时阻断数据预览与字段信息。</p>
 */
public class TableInspection {

    private String schema;
    private String table;
    private String objectType;
    private String dbType = "";
    private String tableComment;
    private String metadataSource = "UNKNOWN";
    private String metadataStatus = "PARTIAL";
    private String metadataReason = "";
    private String remarksStatus = "UNAVAILABLE";
    private boolean partitioned;
    private List<String> partitionColumns = new ArrayList<>();
    private boolean previewRequiresPartition;
    private String selectedPartition = "";
    private List<ColumnMetadata> columns = new ArrayList<>();
    private List<TableConstraintMetadata> constraints = new ArrayList<>();
    private QueryResult preview;
    private String previewSql = "";
    private String ddl = "";
    private Map<String, String> errors = new LinkedHashMap<>();

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public String getObjectType() {
        return objectType;
    }

    public void setObjectType(String objectType) {
        this.objectType = objectType;
    }

    public String getDbType() {
        return dbType;
    }

    public void setDbType(String dbType) {
        this.dbType = dbType == null ? "" : dbType;
    }

    public String getTableComment() {
        return tableComment;
    }

    public void setTableComment(String tableComment) {
        this.tableComment = tableComment == null || tableComment.isBlank()
                ? null : tableComment.trim();
    }

    public String getMetadataSource() {
        return metadataSource;
    }

    public void setMetadataSource(String metadataSource) {
        this.metadataSource = metadataSource == null ? "" : metadataSource;
    }

    public String getMetadataStatus() {
        return metadataStatus;
    }

    public void setMetadataStatus(String metadataStatus) {
        this.metadataStatus = metadataStatus == null ? "" : metadataStatus;
    }

    public String getMetadataReason() {
        return metadataReason;
    }

    public void setMetadataReason(String metadataReason) {
        this.metadataReason = metadataReason == null ? "" : metadataReason;
    }

    public String getRemarksStatus() {
        return remarksStatus;
    }

    public void setRemarksStatus(String remarksStatus) {
        this.remarksStatus = remarksStatus == null ? "" : remarksStatus;
    }

    public boolean isPartitioned() {
        return partitioned;
    }

    public void setPartitioned(boolean partitioned) {
        this.partitioned = partitioned;
    }

    public List<String> getPartitionColumns() {
        return partitionColumns;
    }

    public void setPartitionColumns(List<String> partitionColumns) {
        this.partitionColumns = partitionColumns == null
                ? new ArrayList<>() : new ArrayList<>(partitionColumns);
    }

    public boolean isPreviewRequiresPartition() {
        return previewRequiresPartition;
    }

    public void setPreviewRequiresPartition(boolean previewRequiresPartition) {
        this.previewRequiresPartition = previewRequiresPartition;
    }

    public String getSelectedPartition() {
        return selectedPartition;
    }

    public void setSelectedPartition(String selectedPartition) {
        this.selectedPartition = selectedPartition == null
                ? "" : selectedPartition;
    }

    public List<ColumnMetadata> getColumns() {
        return columns;
    }

    public void setColumns(List<ColumnMetadata> columns) {
        this.columns = columns == null
                ? new ArrayList<>() : new ArrayList<>(columns);
    }

    public List<TableConstraintMetadata> getConstraints() {
        return constraints;
    }

    public void setConstraints(List<TableConstraintMetadata> constraints) {
        this.constraints = constraints == null
                ? new ArrayList<>() : new ArrayList<>(constraints);
    }

    public QueryResult getPreview() {
        return preview;
    }

    public void setPreview(QueryResult preview) {
        this.preview = preview;
    }

    public String getPreviewSql() {
        return previewSql;
    }

    public void setPreviewSql(String previewSql) {
        this.previewSql = previewSql == null ? "" : previewSql;
    }

    public String getDdl() {
        return ddl;
    }

    public void setDdl(String ddl) {
        this.ddl = ddl == null ? "" : ddl;
    }

    public Map<String, String> getErrors() {
        return errors;
    }

    public void setErrors(Map<String, String> errors) {
        this.errors = errors == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(errors);
    }

    public void addError(String section, String message) {
        errors.put(section, message);
    }
}
