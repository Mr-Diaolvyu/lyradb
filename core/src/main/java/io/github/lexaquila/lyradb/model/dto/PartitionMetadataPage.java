package io.github.lexaquila.lyradb.model.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 分区元数据分页结果。
 *
 * <p>故意不提供 total：MaxCompute 单表最多可有大量分区，为计算总数而全量
 * 扫描会破坏分页的意义。调用方依据 {@code hasMore} 继续加载。</p>
 */
public class PartitionMetadataPage {

    private String schema = "";
    private String table = "";
    private boolean partitioned;
    private List<String> partitionKeys = new ArrayList<>();
    private List<PartitionMetadata> items = new ArrayList<>();
    private int offset;
    private int limit;
    private boolean hasMore;
    private String ordering = "SERVICE_DEFINED";
    private String metadataSource = "UNSUPPORTED";
    private String metadataStatus = "PARTIAL";
    private String metadataReason = "当前驱动未提供分区元数据能力";

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema == null ? "" : schema;
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table == null ? "" : table;
    }

    public boolean isPartitioned() {
        return partitioned;
    }

    public void setPartitioned(boolean partitioned) {
        this.partitioned = partitioned;
    }

    public List<String> getPartitionKeys() {
        return partitionKeys;
    }

    public void setPartitionKeys(List<String> partitionKeys) {
        this.partitionKeys = partitionKeys == null
                ? new ArrayList<>() : new ArrayList<>(partitionKeys);
    }

    public List<PartitionMetadata> getItems() {
        return items;
    }

    public void setItems(List<PartitionMetadata> items) {
        this.items = items == null
                ? new ArrayList<>() : new ArrayList<>(items);
    }

    public int getOffset() {
        return offset;
    }

    public void setOffset(int offset) {
        this.offset = offset;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public boolean isHasMore() {
        return hasMore;
    }

    public void setHasMore(boolean hasMore) {
        this.hasMore = hasMore;
    }

    /**
     * PARTITION_SPEC_DESC 表示按分区规范逆序；SERVICE_DEFINED 表示服务端
     * 未承诺顺序。两者都不等价于“最后修改时间最新”。
     */
    public String getOrdering() {
        return ordering;
    }

    public void setOrdering(String ordering) {
        this.ordering = ordering == null ? "SERVICE_DEFINED" : ordering;
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
}
