package io.github.lexaquila.lyradb.model.dto;

/**
 * 跨库数据迁移请求
 *
 * <p>PRD F8：源→目标表映射，批量读写。</p>
 */
public class MigrationRequest {

    /** 源连接 ID */
    private String sourceConnectionId;
    /** 目标连接 ID */
    private String targetConnectionId;

    private String sourceSchema;
    private String sourceTable;
    private String targetSchema;
    private String targetTable;

    /** create=先建表再写入；append=追加到已存在表 */
    private String mode = "append";

    /** 单批写入行数 */
    private int batchSize = 1000;

    /** 最大迁移行数（安全上限） */
    private int maxRows = 100000;

    public String getSourceConnectionId() { return sourceConnectionId; }
    public void setSourceConnectionId(String v) { this.sourceConnectionId = v; }

    public String getTargetConnectionId() { return targetConnectionId; }
    public void setTargetConnectionId(String v) { this.targetConnectionId = v; }

    public String getSourceSchema() { return sourceSchema; }
    public void setSourceSchema(String v) { this.sourceSchema = v; }

    public String getSourceTable() { return sourceTable; }
    public void setSourceTable(String v) { this.sourceTable = v; }

    public String getTargetSchema() { return targetSchema; }
    public void setTargetSchema(String v) { this.targetSchema = v; }

    public String getTargetTable() { return targetTable; }
    public void setTargetTable(String v) { this.targetTable = v; }

    public String getMode() { return mode; }
    public void setMode(String v) { this.mode = v; }

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int v) { this.batchSize = v; }

    public int getMaxRows() { return maxRows; }
    public void setMaxRows(int v) { this.maxRows = v; }
}
