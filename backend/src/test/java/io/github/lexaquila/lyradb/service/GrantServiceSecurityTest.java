package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.repository.DataSourceRepository;
import io.github.lexaquila.lyradb.repository.GrantRepository;
import io.github.lexaquila.lyradb.repository.UserRepository;
import io.github.lexaquila.lyradb.repository.WorkspaceMembershipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 企业授权在持久化边界即拒绝模糊或不可执行的物理资源范围。
 */
class GrantServiceSecurityTest {

    private GrantRepository repository;
    private GrantService service;

    @BeforeEach
    void setUp() {
        repository = mock(GrantRepository.class);
        DataSourceRepository dataSources = mock(DataSourceRepository.class);
        UserRepository users = mock(UserRepository.class);
        WorkspaceMembershipRepository memberships =
                mock(WorkspaceMembershipRepository.class);

        DataSource source = new DataSource();
        source.setId("source-1");
        source.setWorkspaceId("workspace-1");
        User user = new User();
        user.setId("user-1");

        when(dataSources.findById("source-1"))
                .thenReturn(Optional.of(source));
        when(users.findById("user-1")).thenReturn(Optional.of(user));
        when(memberships.existsByUserIdAndWorkspaceId(
                "user-1", "workspace-1")).thenReturn(true);
        when(repository.findByUserIdAndWorkspaceIdAndGrantedSourceName(
                "user-1", "workspace-1", "sales"))
                .thenReturn(Optional.empty());

        service = new GrantService(
                repository, dataSources, users, memberships,
                mock(ApprovalSecurityContextService.class),
                new AppProperties());
    }

    @Test
    void rejectsMissingOrInvalidSchemaWhitelist() {
        for (String invalid : new String[]{
                null, "", " ", "dw.", "dw,", "\"Dw\"",
                "dw;drop", "dw.*"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> create(invalid, "dw.orders"),
                    "应拒绝 allowedSchemas=" + invalid);
        }
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsBareQuotedOrMalformedTableWhitelist() {
        for (String invalid : new String[]{
                null, "", "orders", "dw.\"Orders\"",
                "dw.`orders`", "dw.[orders]", "dw..orders",
                "dw.orders;drop", "dw.", "*.orders", "dw.orders,"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> create("dw", invalid),
                    "应拒绝 allowedTables=" + invalid);
        }
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsBareQuotedOrMalformedBlockedTablePatterns() {
        for (String invalid : new String[]{
                "secret", "dw.\"Secret\"", "dw.`secret`",
                "dw.[secret]", "dw..secret", "dw.secret,"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> create("dw", "dw.*", invalid),
                    "应拒绝 blockedTables=" + invalid);
        }
        verify(repository, never()).save(any());
    }

    @Test
    void persistsOnlyNormalizedQualifiedResourcePatterns() {
        when(repository.save(any(Grant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Grant grant = create(
                " DW , Audit ", " DW.Orders , Audit.Event_* ",
                " DW.Secret ");

        assertEquals("dw,audit", grant.getAllowedSchemas());
        assertEquals("dw.orders,audit.event_*", grant.getAllowedTables());
        assertEquals("dw.secret", grant.getBlockedTables());
    }

    private Grant create(String allowedSchemas, String allowedTables) {
        return create(allowedSchemas, allowedTables, null);
    }

    private Grant create(
            String allowedSchemas, String allowedTables,
            String blockedTables) {
        return service.create(
                "workspace-1", "source-1", "user-1", "sales",
                allowedSchemas, allowedTables, blockedTables,
                "READ_ONLY", 100, null);
    }
}
