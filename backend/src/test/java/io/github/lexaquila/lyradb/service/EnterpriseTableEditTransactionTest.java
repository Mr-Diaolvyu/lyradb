package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.driver.DatabaseDriver;
import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.dto.EnterpriseMetadataCatalog;
import io.github.lexaquila.lyradb.model.dto.TableEditRequest;
import io.github.lexaquila.lyradb.model.entity.ApprovalRequest;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.model.entity.DriverCapability;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 使用真实 JDBC 事务核验审批后整批提交与冲突回滚。 */
class EnterpriseTableEditTransactionTest {
    private Connection jdbc;
    private EnterpriseTableEditService service;
    private ApprovalService approvals;
    private SecurityUtil security;
    private User user;
    private Grant grant;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = DriverManager.getConnection("jdbc:h2:mem:table_edit_"
                + System.nanoTime() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        jdbc.createStatement().execute("CREATE SCHEMA IF NOT EXISTS public");
        jdbc.createStatement().execute("CREATE TABLE public.orders "
                + "(id INT PRIMARY KEY, name VARCHAR(50) UNIQUE, qty INT)");
        jdbc.createStatement().execute("INSERT INTO public.orders VALUES "
                + "(1, 'first', 1), (2, 'second', 2)");

        GrantService grants = mock(GrantService.class);
        DataSourceService sources = mock(DataSourceService.class);
        EnterpriseMetadataCatalogService metadata = mock(EnterpriseMetadataCatalogService.class);
        MaskingService masking = mock(MaskingService.class);
        approvals = mock(ApprovalService.class);
        CredentialService credentials = mock(CredentialService.class);
        AuditService audit = mock(AuditService.class);
        security = mock(SecurityUtil.class);
        user = new User();
        user.setId("user-1");
        user.setUsername("alice");
        grant = new Grant();
        grant.setId("grant-1");
        grant.setUserId("user-1");
        grant.setWorkspaceId("ws-1");
        grant.setDataSourceId("source-1");
        grant.setGrantedSourceName("sales");
        grant.setSqlCapability("DML_ALLOWED");
        DataSource source = new DataSource();
        source.setWorkspaceId("ws-1");
        source.setDbType("POSTGRESQL");
        EnterpriseMetadataCatalog catalog = new EnterpriseMetadataCatalog();
        catalog.setTables(List.of(new EnterpriseMetadataCatalog.Table(
                "public", "public", "orders", "public.orders", "TABLE", null)));
        ColumnMetadata id = column("id", true);
        ColumnMetadata name = column("name", false);
        ColumnMetadata qty = column("qty", false);
        DatabaseDriver driver = mock(DatabaseDriver.class);
        DriverCapability capability = new DriverCapability();
        capability.setSupportsTransaction(true);
        when(driver.getCapabilities()).thenReturn(capability);

        when(security.requireCurrentUser()).thenReturn(user);
        when(security.requireCurrentWorkspace()).thenReturn("ws-1");
        when(grants.resolveForUser("user-1", "ws-1", "sales")).thenReturn(grant);
        when(sources.getEntity("source-1")).thenReturn(source);
        when(sources.resolveActiveConnection("source-1"))
                .thenReturn(new ConnectionService.ActiveConnection(driver, jdbc));
        when(metadata.catalog("sales", false)).thenReturn(catalog);
        when(metadata.columns("sales", "public", "orders"))
                .thenReturn(List.of(id, name, qty));
        when(credentials.blindIndex(anyString(), anyString())).thenAnswer(invocation ->
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(
                                invocation.getArgument(1, String.class)
                                        .getBytes(StandardCharsets.UTF_8))));
        ApprovalRequest approval = new ApprovalRequest();
        approval.setId("approval-1");
        approval.setWorkspaceId("ws-1");
        approval.setApplicantId("user-1");
        approval.setGrantId("grant-1");
        approval.setGrantedSourceName("sales");
        approval.setOperationType("TABLE_EDIT");
        approval.setStatus("EXECUTING");
        when(approvals.get("approval-1")).thenReturn(approval);
        when(approvals.claimTableEdit(anyString(), any(), any())).thenReturn("");
        service = new EnterpriseTableEditService(grants, sources, metadata,
                masking, approvals, credentials, audit, security, new ObjectMapper());
    }

    @AfterEach
    void close() throws Exception {
        jdbc.close();
    }

    @Test
    void successfulInsertUpdateAndDeleteCommitTogether() throws Exception {
        List<Map<String, Object>> rows = rows();
        TableEditRequest request = new TableEditRequest("sales", "public", "orders", List.of(
                new TableEditRequest.Change("UPDATE", key(rows.get(0)),
                        token(rows.get(0)), Map.of("qty", 11)),
                new TableEditRequest.Change("DELETE", key(rows.get(1)),
                        token(rows.get(1)), null),
                new TableEditRequest.Change("INSERT", null, null,
                        Map.of("id", 3, "name", "third", "qty", 3))), null);
        executeApproved(request);
        assertThat(namesAndQuantities()).containsExactly("first:11", "third:3");
        verify(approvals).markExecutionResult("approval-1", true,
                "已提交 3 项表格变更");
    }

    @Test
    void laterConstraintFailureRollsBackEarlierUpdate() throws Exception {
        List<Map<String, Object>> rows = rows();
        TableEditRequest request = new TableEditRequest("sales", "public", "orders", List.of(
                new TableEditRequest.Change("UPDATE", key(rows.get(0)),
                        token(rows.get(0)), Map.of("qty", 11)),
                new TableEditRequest.Change("INSERT", null, null,
                        Map.of("id", 3, "name", "second", "qty", 3))), null);
        when(approvals.claimTableEdit(anyString(), any(), any()))
                .thenReturn(new ObjectMapper().writeValueAsString(Map.of(
                        "schema", "public", "table", "orders", "changes", request.changes())));
        assertThatThrownBy(() -> service.execute("approval-1"));
        assertThat(namesAndQuantities()).containsExactly("first:1", "second:2");
        verify(approvals).markExecutionResult("approval-1", false,
                "表格编辑失败，已回滚或未发送写入");
    }

    @Test
    void changedOriginalRowRejectsWholeBatch() throws Exception {
        List<Map<String, Object>> rows = rows();
        TableEditRequest request = new TableEditRequest("sales", "public", "orders", List.of(
                new TableEditRequest.Change("UPDATE", key(rows.get(0)),
                        token(rows.get(0)), Map.of("qty", 11)),
                new TableEditRequest.Change("INSERT", null, null,
                        Map.of("id", 3, "name", "third", "qty", 3))), null);
        jdbc.createStatement().executeUpdate("UPDATE public.orders SET qty = 7 WHERE id = 1");
        when(approvals.claimTableEdit(anyString(), any(), any()))
                .thenReturn(new ObjectMapper().writeValueAsString(Map.of(
                        "schema", "public", "table", "orders", "changes", request.changes())));

        assertThatThrownBy(() -> service.execute("approval-1"))
                .hasMessageContaining("表数据已变化");
        assertThat(namesAndQuantities()).containsExactly("first:7", "second:2");
    }

    @Test
    void crossWorkspaceApprovalCannotReachTargetDatabase() throws Exception {
        when(security.requireCurrentWorkspace()).thenReturn("ws-2");
        assertThatThrownBy(() -> service.execute("approval-1"))
                .hasMessageContaining("无权执行");
        assertThat(namesAndQuantities()).containsExactly("first:1", "second:2");
        verify(approvals, never()).claimTableEdit(anyString(), any(), any());
    }

    private void executeApproved(TableEditRequest request) throws Exception {
        when(approvals.claimTableEdit(anyString(), any(), any()))
                .thenReturn(new ObjectMapper().writeValueAsString(Map.of(
                        "schema", "public", "table", "orders", "changes", request.changes())));
        service.execute("approval-1");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows() throws Exception {
        return (List<Map<String, Object>>) service.snapshot("sales", "public", "orders").get("rows");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> key(Map<String, Object> row) {
        return (Map<String, Object>) row.get("key");
    }

    private static String token(Map<String, Object> row) {
        return (String) row.get("token");
    }

    private List<String> namesAndQuantities() throws Exception {
        List<String> result = new java.util.ArrayList<>();
        try (ResultSet rows = jdbc.createStatement().executeQuery(
                "SELECT name, qty FROM public.orders ORDER BY id")) {
            while (rows.next()) result.add(rows.getString(1) + ":" + rows.getInt(2));
        }
        return result;
    }

    private static ColumnMetadata column(String name, boolean primaryKey) {
        ColumnMetadata column = new ColumnMetadata();
        column.setName(name);
        column.setTypeName(primaryKey || "qty".equals(name) ? "INTEGER" : "VARCHAR");
        column.setPrimaryKey(primaryKey);
        return column;
    }
}
