package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.model.entity.ApprovalRequest;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 外部 DML 前的执行意图审计失败时必须关闭，不得触碰目标连接。
 */
class EnterpriseQueryAuditFailClosedTest {

    @Test
    void dmlStartAuditFailurePreventsResolvingOrExecutingTargetConnection() throws Exception {
        GrantService grantService = mock(GrantService.class);
        DataSourceService dataSourceService = mock(DataSourceService.class);
        AuditService auditService = mock(AuditService.class);
        SecurityUtil securityUtil = mock(SecurityUtil.class);
        SqlReviewService sqlReviewService = mock(SqlReviewService.class);
        ApprovalService approvalService = mock(ApprovalService.class);
        MaskingService maskingService = mock(MaskingService.class);
        EnterpriseQueryService service = new EnterpriseQueryService(
                grantService, dataSourceService, auditService, securityUtil,
                sqlReviewService, approvalService, maskingService, new AppProperties());

        User user = new User();
        user.setId("user-1");
        user.setUsername("alice");
        Grant grant = new Grant();
        grant.setWorkspaceId("workspace-1");
        grant.setUserId("user-1");
        grant.setDataSourceId("source-1");
        grant.setGrantedSourceName("sales");
        grant.setSqlCapability("DML_ALLOWED");
        grant.setAllowedSchemas("dw");
        grant.setAllowedTables("dw.orders");
        DataSource dataSource = new DataSource();
        dataSource.setId("source-1");
        dataSource.setWorkspaceId("workspace-1");
        dataSource.setDbType("POSTGRESQL");
        ApprovalRequest approval = new ApprovalRequest();
        approval.setId("approval-1");
        approval.setWorkspaceId("workspace-1");
        approval.setApplicantId("user-1");
        approval.setOperationType("DANGEROUS_SQL");
        approval.setDataSourceId("source-1");
        approval.setGrantedSourceName("sales");
        approval.setStatus("APPROVED");

        when(securityUtil.requireCurrentUser()).thenReturn(user);
        when(securityUtil.requireCurrentWorkspace()).thenReturn("workspace-1");
        when(securityUtil.effectiveRoles("workspace-1")).thenReturn(Set.of("ANALYST"));
        when(grantService.resolveForUser("user-1", "workspace-1", "sales")).thenReturn(grant);
        when(dataSourceService.getEntity("source-1")).thenReturn(dataSource);
        when(sqlReviewService.review(anyString(), eq("POSTGRESQL"))).thenReturn(List.of());
        when(approvalService.findActiveMatching(
                user, grant, "DANGEROUS_SQL",
                "update dw.orders set status = 'DONE'", null, "dw"))
                .thenReturn(java.util.Optional.of(approval));
        when(approvalService.claimForExecution(
                "approval-1", user, grant, "DANGEROUS_SQL",
                "update dw.orders set status = 'DONE'", null, "dw"))
                .thenReturn(approval);
        doThrow(new IllegalStateException("audit unavailable"))
                .when(auditService).record(
                        nullable(String.class), nullable(String.class),
                        nullable(String.class), nullable(String.class),
                        nullable(String.class), nullable(String.class),
                        nullable(String.class), nullable(String.class),
                        nullable(String.class), nullable(String.class),
                        anyLong(), anyLong(), anyLong(), anyBoolean(),
                        nullable(String.class), nullable(String.class));

        assertThrows(IllegalStateException.class, () -> service.executeQuery(
                "sales", "update dw.orders set status = 'DONE'", "dw"));

        verify(dataSourceService, never()).resolveActiveConnection(anyString());
    }
}
