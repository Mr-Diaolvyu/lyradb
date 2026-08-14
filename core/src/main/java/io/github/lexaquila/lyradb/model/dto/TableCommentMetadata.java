package io.github.lexaquila.lyradb.model.dto;

/**
 * 表注释读取结果及其证据状态。
 *
 * <p>{@code remarksStatus} 区分：AVAILABLE（已取得）、EMPTY（权威元数据确认
 * 未设置）与 UNAVAILABLE（权限、版本或解析失败，无法判断是否为空）。</p>
 */
public class TableCommentMetadata {

    private String remarks;
    private String metadataSource = "UNKNOWN";
    private String metadataStatus = "PARTIAL";
    private String metadataReason = "";
    private String remarksStatus = "UNAVAILABLE";

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks == null || remarks.isBlank()
                ? null : remarks.trim();
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
}
