package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.driver.DatabaseDriver;
import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.dto.TreeNode;
import io.github.lexaquila.lyradb.model.entity.AiProviderConfig;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnterpriseAiServiceTest {

    @Mock
    private AiProviderService aiProviderService;
    @Mock
    private GrantService grantService;
    @Mock
    private DataSourceService dataSourceService;
    @Mock
    private EnterpriseQueryService enterpriseQueryService;
    @Mock
    private SecurityUtil securityUtil;
    @Mock
    private DatabaseDriver driver;

    private EnterpriseAiService service;
    private User user;
    private AiProviderConfig provider;
    private ConnectionService.ActiveConnection active;

    @BeforeEach
    void setUp() {
        service = new EnterpriseAiService(aiProviderService, grantService,
                dataSourceService, enterpriseQueryService, securityUtil);
        user = new User();
        user.setId("user-1");
        provider = new AiProviderConfig();
        provider.setId("provider-1");
        active = new ConnectionService.ActiveConnection(driver, new Object());
    }

    @Test
    void emptyAllowedTablesExposeNoMetadata() throws Exception {
        Grant grant = grant("workspace-1", null, "sales");
        prepare(grant);
        when(aiProviderService.chat(eq(provider), any()))
                .thenReturn("没有足够的授权表结构，无法生成 SQL。");

        Map<String, Object> result = service.chat(
                "workspace-1", "sales-source", "统计订单", List.of());

        assertEquals(false, result.get("executed"));
        assertFalse(result.containsKey("error"));
        verify(driver, never()).getTreeNodes(any(), any());
    }

    @Test
    void workspaceMismatchFailsBeforeProviderLookup() {
        Grant grant = grant("workspace-2", "sales.orders", "sales");
        when(securityUtil.requireCurrentUser()).thenReturn(user);
        when(grantService.resolveForUser("user-1", "workspace-1", "sales-source"))
                .thenReturn(grant);

        assertThrows(AccessDeniedException.class, () -> service.chat(
                "workspace-1", "sales-source", "统计订单", List.of()));

        verifyNoInteractions(aiProviderService, dataSourceService);
    }

    @Test
    void schemaPromptContainsOnlyExplicitlyAuthorizedTables() throws Exception {
        Grant grant = grant("workspace-1", "sales.orders", "sales");
        prepare(grant);
        Object connection = active.connection;
        TreeNode schema = node("sales", "SCHEMA", "sales");
        TreeNode orders = node("orders", "TABLE", "sales/orders");
        TreeNode secrets = node("secrets", "TABLE", "sales/secrets");
        when(driver.getTreeNodes(connection, null)).thenReturn(List.of(schema));
        when(driver.getTreeNodes(connection, "sales"))
                .thenReturn(List.of(orders, secrets));
        when(driver.getTableColumns(connection, "sales", "orders"))
                .thenReturn(List.of(column("id", "BIGINT")));
        when(aiProviderService.chat(eq(provider), any()))
                .thenReturn("已识别授权结构，但未生成查询。");

        service.chat("workspace-1", "sales-source", "统计订单", List.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> messages =
                ArgumentCaptor.forClass(List.class);
        verify(aiProviderService).chat(eq(provider), messages.capture());
        String prompt = messages.getValue().stream()
                .map(item -> item.getOrDefault("content", ""))
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(prompt.contains("sales.orders"));
        assertFalse(prompt.contains("sales.secrets"));
        verify(driver, never()).getTableColumns(
                connection, "sales", "secrets");
    }

    @Test
    void ddlGeneratedByModelIsRejectedByAstGuard() throws Exception {
        Grant grant = grant("workspace-1", null, null);
        prepare(grant);
        String fence = String.valueOf((char) 96).repeat(3);
        when(aiProviderService.chat(eq(provider), any()))
                .thenReturn("建议如下\n" + fence + "sql\nDROP TABLE users\n" + fence);

        Map<String, Object> result = service.chat(
                "workspace-1", "sales-source", "删除用户表", List.of());

        assertEquals(false, result.get("executed"));
        assertEquals("AI 生成的 SQL 未通过安全校验", result.get("error"));
        verify(enterpriseQueryService, never())
                .executeQuery(any(), any(), any());
    }

    @Test
    void dmlIsReturnedForApprovalAndNeverAutoExecuted() throws Exception {
        Grant grant = grant(
                "workspace-1", "sales.orders", "sales");
        grant.setSqlCapability("DML_ALLOWED");
        prepare(grant);
        Object connection = active.connection;
        TreeNode schema = node("sales", "SCHEMA", "sales");
        TreeNode orders = node("orders", "TABLE", "sales/orders");
        when(driver.getTreeNodes(connection, null)).thenReturn(List.of(schema));
        when(driver.getTreeNodes(connection, "sales")).thenReturn(List.of(orders));
        when(driver.getTableColumns(connection, "sales", "orders"))
                .thenReturn(List.of(column("status", "VARCHAR")));
        String fence = String.valueOf((char) 96).repeat(3);
        when(aiProviderService.chat(eq(provider), any()))
                .thenReturn("可执行更新\n" + fence + "sql\n"
                        + "UPDATE sales.orders SET status = 'done' WHERE status = 'new'\n"
                        + fence);

        Map<String, Object> result = service.chat(
                "workspace-1", "sales-source", "完成新订单", List.of());

        assertEquals(true, result.get("needsApproval"));
        assertEquals(false, result.get("executed"));
        verify(enterpriseQueryService, never())
                .executeQuery(any(), any(), any());
    }

    private void prepare(Grant grant) {
        when(securityUtil.requireCurrentUser()).thenReturn(user);
        when(grantService.resolveForUser("user-1", "workspace-1", "sales-source"))
                .thenReturn(grant);
        when(aiProviderService.resolveDefault(grant.getWorkspaceId()))
                .thenReturn(provider);
        when(dataSourceService.resolveActiveConnection("source-1"))
                .thenReturn(active);
    }

    private static Grant grant(
            String workspaceId, String allowedTables, String allowedSchemas) {
        Grant grant = new Grant();
        grant.setWorkspaceId(workspaceId);
        grant.setDataSourceId("source-1");
        grant.setAllowedTables(allowedTables);
        grant.setAllowedSchemas(allowedSchemas);
        grant.setSqlCapability("READ_ONLY");
        return grant;
    }

    private static TreeNode node(String name, String type, String path) {
        return TreeNode.of(path, name, type, path);
    }

    private static ColumnMetadata column(String name, String type) {
        ColumnMetadata column = new ColumnMetadata();
        column.setName(name);
        column.setTypeName(type);
        return column;
    }
}
