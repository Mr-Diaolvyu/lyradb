package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.entity.DataSource;
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

import java.time.LocalDateTime;
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
    private EnterpriseMetadataSnapshotService metadataSnapshotService;
    @Mock
    private AuditService auditService;
    private EnterpriseAiService service;
    private User user;
    private AiProviderConfig provider;

    @BeforeEach
    void setUp() {
        service = new EnterpriseAiService(aiProviderService, grantService,
                dataSourceService, securityUtil, metadataSnapshotService,
                auditService);
        user = new User();
        user.setId("user-1");
        provider = new AiProviderConfig();
        provider.setId("provider-1");
    }

    @Test
    void emptyAllowedTablesExposeNoMetadata() throws Exception {
        Grant grant = grant("workspace-1", null, "sales");
        prepare(grant);
        when(aiProviderService.chat(eq(provider), any()))
                .thenReturn("没有足够的授权表结构，无法生成 SQL。");

        Map<String, Object> result = service.chat(
                "workspace-1", "sales-source", "统计订单", List.of(), false, null);

        assertEquals(false, result.get("executed"));
        assertFalse(result.containsKey("error"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> messages =
                ArgumentCaptor.forClass(List.class);
        verify(aiProviderService).chat(eq(provider), messages.capture());
        String prompt = messages.getValue().stream()
                .map(item -> item.getOrDefault("content", ""))
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(prompt.contains("当前用户消息中明确提供的表与列"));
        assertFalse(prompt.contains("只能使用下面给出的表和列"));

        verify(dataSourceService, never()).resolveActiveConnection(any());
        verifyNoInteractions(metadataSnapshotService);
    }

    @Test
    void workspaceMismatchFailsBeforeProviderLookup() {
        Grant grant = grant("workspace-2", "sales.orders", "sales");
        when(securityUtil.requireCurrentUser()).thenReturn(user);
        when(grantService.resolveForUser("user-1", "workspace-1", "sales-source"))
                .thenReturn(grant);

        assertThrows(AccessDeniedException.class, () -> service.chat(
                "workspace-1", "sales-source", "统计订单", List.of(), false, null));

        verifyNoInteractions(aiProviderService, dataSourceService);
    }

    @Test
    void schemaPromptContainsOnlyExplicitlyAuthorizedTables() throws Exception {
        Grant grant = grant("workspace-1", "sales.orders", "sales");
        prepare(grant);
        MetadataSnapshotSessionStore.SnapshotSession snapshot =
                new MetadataSnapshotSessionStore.SnapshotSession(
                        "snapshot-1", "user-1", "workspace-1",
                        "grant-1", "source-1", "sales-source",
                        "fingerprint",
                        new MetadataSnapshotSessionStore.MapScope(
                                null, List.of("sales"),
                                List.of("sales.orders")),
                        null, 1, 1, 50,
                        LocalDateTime.now().plusMinutes(5), false);
        when(metadataSnapshotService.consumeForAi(
                "workspace-1", user, "snapshot-1")).thenReturn(snapshot);
        when(metadataSnapshotService.renderForAi(snapshot))
                .thenReturn("{\"tables\":[{\"table\":\"sales.orders\"}]}");
        when(metadataSnapshotService.auditOf(snapshot)).thenReturn(
                new EnterpriseMetadataSnapshotService.MetadataAudit(
                        "snapshot-1", snapshot.scope(), "a".repeat(64)));
        when(aiProviderService.chat(eq(provider), any()))
                .thenReturn("已识别授权结构，但未生成查询。");

        service.chat("workspace-1", "sales-source", "统计订单", List.of(), true, "snapshot-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, String>>> messages =
                ArgumentCaptor.forClass(List.class);
        verify(aiProviderService).chat(eq(provider), messages.capture());
        String prompt = messages.getValue().stream()
                .map(item -> item.getOrDefault("content", ""))
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(prompt.contains("sales.orders"));
        assertFalse(prompt.contains("sales.secrets"));
        verify(dataSourceService, never()).resolveActiveConnection(any());
        verify(auditService).recordCurrentMetadata(
                "workspace-1", "AI_METADATA_ATTACH", "source-1", "sales-source",
                "snapshot-1", snapshot.scope(), "a".repeat(64));
    }

    @Test
    void ddlGeneratedByModelIsRejectedByAstGuard() throws Exception {
        Grant grant = grant("workspace-1", null, null);
        prepare(grant);
        String fence = String.valueOf((char) 96).repeat(3);
        when(aiProviderService.chat(eq(provider), any()))
                .thenReturn("建议如下\n" + fence + "sql\nDROP TABLE users\n" + fence);

        Map<String, Object> result = service.chat(
                "workspace-1", "sales-source", "删除用户表", List.of(), false, null);

        assertEquals(false, result.get("executed"));
        assertEquals("AI 生成的 SQL 未通过安全校验", result.get("error"));
        verify(enterpriseQueryService, never())
                .executeQuery(any(), any(), any());
    }

    @Test
    void readOnlySqlIsSuggestedButNeverAutomaticallyExecuted()
            throws Exception {
        Grant grant = grant(
                "workspace-1", "sales.orders", "sales");
        prepare(grant);
        String fence = String.valueOf((char) 96).repeat(3);
        when(aiProviderService.chat(eq(provider), any()))
                .thenReturn("建议查询\n" + fence + "sql\n"
                        + "SELECT * FROM sales.orders\n" + fence);

        Map<String, Object> result = service.chat(
                "workspace-1", "sales-source", "查询订单",
                List.of(), false, null);

        assertEquals(false, result.get("executed"));
        assertEquals("SELECT * FROM sales.orders", result.get("sql"));
        assertFalse(result.containsKey("result"));
        verify(enterpriseQueryService, never())
                .executeQuery(any(), any(), any());
    }

    @Test
    void dmlIsReturnedForApprovalAndNeverAutoExecuted() throws Exception {
        Grant grant = grant(
                "workspace-1", "sales.orders", "sales");
        grant.setSqlCapability("DML_ALLOWED");
        prepare(grant);
        String fence = String.valueOf((char) 96).repeat(3);
        when(aiProviderService.chat(eq(provider), any()))
                .thenReturn("可执行更新\n" + fence + "sql\n"
                        + "UPDATE sales.orders SET status = 'done' WHERE status = 'new'\n"
                        + fence);

        Map<String, Object> result = service.chat(
                "workspace-1", "sales-source", "完成新订单", List.of(), false, null);

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
        DataSource source = new DataSource();
        source.setWorkspaceId(grant.getWorkspaceId());
        source.setDbType("H2");
        when(dataSourceService.getEntity("source-1"))
                .thenReturn(source);
    }

    private static Grant grant(
            String workspaceId, String allowedTables, String allowedSchemas) {
        Grant grant = new Grant();
        grant.setWorkspaceId(workspaceId);
        grant.setDataSourceId("source-1");
        grant.setId("grant-1");
        grant.setGrantedSourceName("sales-source");
        grant.setAllowedTables(allowedTables);
        grant.setAllowedSchemas(allowedSchemas);
        grant.setSqlCapability("READ_ONLY");
        return grant;
    }
}
