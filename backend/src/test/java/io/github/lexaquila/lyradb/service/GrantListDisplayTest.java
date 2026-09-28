package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.repository.DataSourceRepository;
import io.github.lexaquila.lyradb.repository.GrantRepository;
import io.github.lexaquila.lyradb.repository.UserRepository;
import io.github.lexaquila.lyradb.repository.WorkspaceMembershipRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GrantListDisplayTest {
    @Test
    void includesGranteeNamesForReadableAuthorizationList() {
        GrantRepository grants = mock(GrantRepository.class);
        UserRepository users = mock(UserRepository.class);
        GrantService service = new GrantService(grants,
                mock(DataSourceRepository.class), users,
                mock(WorkspaceMembershipRepository.class),
                mock(ApprovalSecurityContextService.class),
                mock(AppProperties.class));
        Grant grant = new Grant();
        grant.setId("grant-1");
        grant.setUserId("user-1");
        grant.setWorkspaceId("ws");
        grant.setDataSourceId("source-1");
        grant.setGrantedSourceName("物业ERP");
        User user = new User();
        user.setId("user-1");
        user.setUsername("analyst");
        user.setDisplayName("分析员");
        when(grants.findByWorkspaceIdOrderByCreatedAtDesc("ws"))
                .thenReturn(List.of(grant));
        when(users.findAllById(List.of("user-1"))).thenReturn(List.of(user));

        List<Map<String, Object>> listed = service.listByWorkspace("ws");

        assertThat(listed).hasSize(1);
        assertThat(listed.get(0)).containsEntry("granteeUsername", "analyst")
                .containsEntry("granteeDisplayName", "分析员");
    }
}
