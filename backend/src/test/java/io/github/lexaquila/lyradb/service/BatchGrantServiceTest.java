package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.model.dto.BatchGrantRequest;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.repository.DataSourceRepository;
import io.github.lexaquila.lyradb.repository.GrantRepository;
import io.github.lexaquila.lyradb.repository.UserRepository;
import io.github.lexaquila.lyradb.repository.WorkspaceMembershipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchGrantServiceTest {
    private GrantRepository grants;
    private DataSourceRepository sources;
    private UserRepository users;
    private WorkspaceMembershipRepository memberships;
    private GrantService service;

    @BeforeEach
    void setUp() {
        grants = mock(GrantRepository.class);
        sources = mock(DataSourceRepository.class);
        users = mock(UserRepository.class);
        memberships = mock(WorkspaceMembershipRepository.class);
        service = new GrantService(grants, sources, users, memberships,
                mock(ApprovalSecurityContextService.class), new AppProperties());
        DataSource source = new DataSource();
        source.setWorkspaceId("ws-1");
        when(sources.findById("source-1")).thenReturn(Optional.of(source));
        for (String id : List.of("alice", "bob")) {
            User user = new User();
            user.setId(id);
            when(users.findById(id)).thenReturn(Optional.of(user));
            when(memberships.existsByUserIdAndWorkspaceId(id, "ws-1"))
                    .thenReturn(true);
        }
    }

    @Test
    void previewBuildsEveryCombinationAndDoesNotWrite() {
        Map<String, Object> result = service.previewBatch("ws-1", request());
        assertThat(result.get("valid")).isEqualTo(true);
        assertThat(result.get("count")).isEqualTo(2);
        verify(grants, never()).saveAllAndFlush(any());
    }

    @Test
    void previewAcceptsExplicitAllNamespaceAndTableWildcards() {
        BatchGrantRequest request = new BatchGrantRequest(List.of("alice"), List.of(
                new BatchGrantRequest.Source("source-1", "all_tables", "*",
                        "*.*", null, "READ_ONLY", 100, null)));

        assertThat(service.previewBatch("ws-1", request).get("valid"))
                .isEqualTo(true);
    }

    @Test
    void invalidCombinationAbortsEntireBatchBeforeWrite() {
        DataSource outside = new DataSource();
        outside.setWorkspaceId("ws-2");
        when(sources.findById("source-2")).thenReturn(Optional.of(outside));
        BatchGrantRequest mixed = new BatchGrantRequest(List.of("alice", "bob"),
                List.of(request().sources().get(0),
                        new BatchGrantRequest.Source("source-2", "outside",
                                "public", "public.orders", null, "READ_ONLY", 100, null)));

        assertThat(service.previewBatch("ws-1", mixed).get("valid")).isEqualTo(false);
        assertThatThrownBy(() -> service.createBatch("ws-1", mixed))
                .hasMessageContaining("数据源不属于当前工作空间");
        verify(grants, never()).saveAllAndFlush(any());
    }

    @Test
    void duplicateNamesAndMoreThanTwoHundredAreRejected() {
        BatchGrantRequest.Source source = request().sources().get(0);
        BatchGrantRequest duplicate = new BatchGrantRequest(List.of("alice"),
                List.of(source, source));
        assertThatThrownBy(() -> service.createBatch("ws-1", duplicate))
                .hasMessageContaining("同一用户的逻辑名重复");
        BatchGrantRequest oversized = new BatchGrantRequest(
                java.util.stream.IntStream.range(0, 201)
                        .mapToObj(index -> "u" + index).toList(), List.of(source));
        assertThatThrownBy(() -> service.createBatch("ws-1", oversized))
                .hasMessageContaining("最多 200 条");
        verify(grants, never()).saveAllAndFlush(any());
    }

    private static BatchGrantRequest request() {
        return new BatchGrantRequest(List.of("alice", "bob"), List.of(
                new BatchGrantRequest.Source("source-1", "sales", "public",
                        "public.orders", null, "READ_ONLY", 100, null)));
    }
}
