package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.lineage.DataWorksLineageClient;
import io.github.lexaquila.lyradb.model.dto.EnterpriseMetadataCatalog;
import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EnterpriseLineageServiceTest {
    SecurityUtil security = mock(SecurityUtil.class);
    GrantService grants = mock(GrantService.class);
    DataSourceService sources = mock(DataSourceService.class);
    CredentialService credentials = mock(CredentialService.class);
    EnterpriseMetadataCatalogService metadata = mock(EnterpriseMetadataCatalogService.class);
    ApprovalSecurityContextService contexts = mock(ApprovalSecurityContextService.class);
    AuditService audit = mock(AuditService.class);
    EnterpriseLineageService service;
    Grant grant = new Grant();
    List<String> queried = new ArrayList<>();

    @BeforeEach void init() throws Exception {
        User user = new User(); user.setId("user");
        when(security.requireCurrentUser()).thenReturn(user);
        when(security.requireCurrentWorkspace()).thenReturn("workspace");
        grant.setId("grant"); grant.setWorkspaceId("workspace"); grant.setDataSourceId("source");
        when(grants.resolveForUser("user", "workspace", "sales")).thenReturn(grant);
        var source = new DataSource(); source.setId("source"); source.setWorkspaceId("workspace");
        source.setDbType("MAXCOMPUTE"); source.setConnectionParamsJson("{}");
        when(sources.getEntity("source")).thenReturn(source);
        when(credentials.decryptSensitiveFields(anyMap())).thenReturn(Map.of());
        when(contexts.fingerprint(grant)).thenReturn("same-policy");
        var catalog = new EnterpriseMetadataCatalog();
        catalog.setTables(List.of(table("root"), table("upstream")));
        when(metadata.catalog("sales", false)).thenReturn(catalog);
        var column = new ColumnMetadata(); column.setName("id");
        when(metadata.columns("sales", "project", "root")).thenReturn(List.of(column));
        service = spy(new EnterpriseLineageService(security, grants, sources, credentials, metadata, contexts, audit, new ObjectMapper()));
        doReturn(new DataWorksLineageClient((id, direction) -> {
            queried.add(id);
            if (!id.contains("::root")) return List.of();
            boolean fields = id.startsWith("maxcompute-column");
            var root = entity("root", fields);
            return List.of(new DataWorksLineageClient.Edge(entity("upstream", fields), root),
                    new DataWorksLineageClient.Edge(entity("secret", fields), root));
        })).when(service).client(anyMap());
    }

    private EnterpriseMetadataCatalog.Table table(String name) {
        return new EnterpriseMetadataCatalog.Table("project", "project", name, "project." + name, "TABLE", "");
    }
    private DataWorksLineageClient.EntityRef entity(String name, boolean fields) {
        return DataWorksLineageClient.parseEntity(fields
                ? DataWorksLineageClient.columnEntityId("project", name, "id")
                : DataWorksLineageClient.tableEntityId("project", name), null);
    }
    private EnterpriseLineageService.Request request(String table, String column) {
        return new EnterpriseLineageService.Request("sales", "project", List.of(table), column, "UPSTREAM", 2, 120);
    }

    @Test void filtersUnauthorizedNodesBeforeTraversal() throws Exception {
        var result = service.explore(request("root", null));
        assertThat(result.getTables()).extracting("name").containsExactlyInAnyOrder("project.root", "project.upstream");
        assertThat(result.getEdges()).hasSize(1);
        assertThat(queried).noneMatch(id -> id.contains("secret"));
        verify(audit).recordCurrent("workspace", "METADATA_LINEAGE", "source", "sales", true, null);
    }

    @Test void preservesPhysicalColumnsAndDirection() throws Exception {
        var result = service.explore(request("root", "id"));
        assertThat(result.getEdges()).singleElement().satisfies(edge -> {
            assertThat(edge.getSource()).isEqualTo("project.upstream");
            assertThat(edge.getSourceColumn()).isEqualTo("id");
            assertThat(edge.getTargetColumn()).isEqualTo("id");
        });
    }

    @Test void rejectsUnauthorizedRootWithoutCallingApi() {
        assertThatThrownBy(() -> service.explore(request("secret", null))).isInstanceOf(AccessDeniedException.class);
        assertThat(queried).isEmpty();
    }

    @Test void rejectsChangedAuthorizationBeforeReturningGraph() {
        when(contexts.fingerprint(grant)).thenReturn("before", "after");
        assertThatThrownBy(() -> service.explore(request("root", null))).isInstanceOf(AccessDeniedException.class);
    }

    @Test void neverExposesSdkExceptionText() throws Exception {
        doReturn(new DataWorksLineageClient((id, direction) -> { throw new Exception("signed-sensitive-request"); }))
                .when(service).client(anyMap());
        assertThatThrownBy(() -> service.explore(request("root", null)))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("signed-sensitive-request");
    }
}
