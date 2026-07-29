
package io.github.lexaquila.lyradb.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.model.entity.ApprovalRequest;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.service.ApprovalService;
import io.github.lexaquila.lyradb.service.AuditService;
import io.github.lexaquila.lyradb.service.DataSourceService;
import io.github.lexaquila.lyradb.service.EnterpriseQueryService;
import io.github.lexaquila.lyradb.service.GrantService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 企业导出必须从审批记录派生逻辑数据源，不能信任请求体另行指定资源。
 */
class EnterpriseExportControllerSecurityTest {

    private ApprovalService approvalService;
    private GrantService grantService;
    private DataSourceService dataSourceService;
    private EnterpriseQueryService queryService;
    private AuditService auditService;
    private SecurityUtil securityUtil;
    private EnterpriseExportController controller;
    private User user;

    @BeforeEach
    void setUp() {
        approvalService = mock(ApprovalService.class);
        grantService = mock(GrantService.class);
        dataSourceService = mock(DataSourceService.class);
        queryService = mock(EnterpriseQueryService.class);
        auditService = mock(AuditService.class);
        securityUtil = mock(SecurityUtil.class);
        controller = new EnterpriseExportController(
                approvalService, grantService, dataSourceService, queryService,
                auditService, securityUtil, new ObjectMapper());

        user = new User();
        user.setId("user-1");
        user.setUsername("alice");
        when(securityUtil.requireCurrentUser()).thenReturn(user);
        when(securityUtil.requireCurrentWorkspace()).thenReturn("workspace-1");
        when(securityUtil.effectiveRoles("workspace-1")).thenReturn(Set.of("ANALYST"));
    }

    @Test
    void requestWithoutGrantedSourceUsesApprovalBoundSource() throws Exception {
        ApprovalRequest approval = approval("sales");
        Grant grant = grant();
        DataSource dataSource = dataSource();
        when(approvalService.get("approval-1")).thenReturn(approval);
        when(grantService.resolveForUser("user-1", "workspace-1", "sales")).thenReturn(grant);
        when(dataSourceService.getEntity("source-1")).thenReturn(dataSource);
        when(queryService.streamExport(
                eq(grant), eq("select * from dw.orders"), eq("dw"), any()))
                .thenReturn(new EnterpriseQueryService.ExportSummary(0, false, 1));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.export("approval-1", Map.of(
                "sql", "select * from dw.orders",
                "format", "csv",
                "defaultDatabase", "dw"), response);

        verify(grantService).resolveForUser("user-1", "workspace-1", "sales");
        verify(approvalService).claimForExecution(
                "approval-1", user, grant, "EXPORT",
                "select * from dw.orders", "csv", "dw");
        verify(queryService).streamExport(
                eq(grant), eq("select * from dw.orders"), eq("dw"), any());
    }

    @Test
    void approvalWithoutGrantedSourceFailsBeforeGrantResolution() {
        when(approvalService.get("approval-1")).thenReturn(approval(" "));

        assertThrows(IllegalArgumentException.class, () -> controller.export(
                "approval-1", Map.of("sql", "select 1"),
                new MockHttpServletResponse()));

        verify(grantService, never()).resolveForUser(anyString(), anyString(), anyString());
        verify(approvalService, never()).claimForExecution(
                anyString(), any(), any(), anyString(), anyString(), anyString(), anyString());
    }

    private static ApprovalRequest approval(String grantedSourceName) {
        ApprovalRequest approval = new ApprovalRequest();
        approval.setId("approval-1");
        approval.setGrantedSourceName(grantedSourceName);
        return approval;
    }

    private static Grant grant() {
        Grant grant = new Grant();
        grant.setWorkspaceId("workspace-1");
        grant.setUserId("user-1");
        grant.setDataSourceId("source-1");
        grant.setGrantedSourceName("sales");
        grant.setAllowedSchemas("dw");
        grant.setAllowedTables("dw.orders");
        return grant;
    }

    private static DataSource dataSource() {
        DataSource dataSource = new DataSource();
        dataSource.setId("source-1");
        dataSource.setWorkspaceId("workspace-1");
        dataSource.setDbType("POSTGRESQL");
        return dataSource;
    }
}
