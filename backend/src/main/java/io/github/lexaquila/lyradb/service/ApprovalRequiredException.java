package io.github.lexaquila.lyradb.service;

/**
 * 查询命中审批规则时返回给 API 边界的结构化领域异常。
 *
 * <p>异常消息固定，不包含 SQL、驱动错误或连接信息；客户端通过审批单 ID
 * 跳转到审批中心，不再依赖解析内部异常文本。</p>
 */
public class ApprovalRequiredException extends RuntimeException {

    private final String approvalRequestId;
    private final String approvalStatus;

    public ApprovalRequiredException(String approvalRequestId, String approvalStatus) {
        super("该操作需要审批，请到审批中心处理");
        this.approvalRequestId = approvalRequestId;
        this.approvalStatus = approvalStatus;
    }

    public String getApprovalRequestId() {
        return approvalRequestId;
    }

    public String getApprovalStatus() {
        return approvalStatus;
    }
}
