package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnterpriseTableInspectionSecurityTest {

    private DataSourceService dataSourceService;
    private EnterpriseQueryService service;

    @BeforeEach
    void setUp() {
        GrantService grantService = mock(GrantService.class);
        dataSourceService = mock(DataSourceService.class);
        SecurityUtil securityUtil = mock(SecurityUtil.class);
        service = new EnterpriseQueryService(
                grantService, dataSourceService,
                mock(AuditService.class), securityUtil,
                mock(SqlReviewService.class), mock(ApprovalService.class),
                mock(MaskingService.class), new AppProperties());

        User user = new User();
        user.setId("user-1");
        Grant grant = new Grant();
        grant.setWorkspaceId("workspace-1");
        grant.setUserId("user-1");
        grant.setDataSourceId("source-1");
        grant.setGrantedSourceName("sales");
        grant.setAllowedSchemas("dw");
        grant.setAllowedTables("dw.orders,dw.secret");
        grant.setBlockedTables("dw.secret");
        grant.setMaxRowsPerQuery(500);
        DataSource source = new DataSource();
        source.setId("source-1");
        source.setWorkspaceId("workspace-1");
        source.setDbType("MYSQL");

        when(securityUtil.requireCurrentUser()).thenReturn(user);
        when(securityUtil.requireCurrentWorkspace())
                .thenReturn("workspace-1");
        when(grantService.resolveForUser(
                "user-1", "workspace-1", "sales"))
                .thenReturn(grant);
        when(dataSourceService.getEntity("source-1")).thenReturn(source);
    }

    @Test
    void blockedTableIsRejectedBeforeResolvingPhysicalConnection() {
        assertThatThrownBy(() -> service.inspectTable(
                "sales", "dw", "secret", "TABLE", 200))
                .hasMessageContaining("黑名单");

        verify(dataSourceService, never())
                .resolveActiveConnection("source-1");
    }

    @Test
    void nonWhitelistedTableIsRejectedBeforeResolvingPhysicalConnection() {
        assertThatThrownBy(() -> service.inspectTable(
                "sales", "dw", "payments", "TABLE", 200))
                .hasMessageContaining("白名单");

        verify(dataSourceService, never())
                .resolveActiveConnection("source-1");
    }
}
