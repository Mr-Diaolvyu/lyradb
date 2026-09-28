package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.model.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.util.concurrent.CancellationException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EnterpriseQueryExecutionServiceTest {
    SecurityUtil security = mock(SecurityUtil.class);
    GrantService grants = mock(GrantService.class);
    EnterpriseQueryService queries = mock(EnterpriseQueryService.class);
    EnterpriseQueryExecutionService service = new EnterpriseQueryExecutionService(security, grants, queries, mock(AuditService.class));
    User user = new User();

    @BeforeEach void init() {
        user.setId("owner");
        when(security.requireCurrentUser()).thenReturn(user);
        when(security.requireCurrentWorkspace()).thenReturn("space");
    }

    @Test void cancelBeforeExecuteNeverDispatchesSql() {
        String id = service.prepare("sales");
        assertThat(service.cancel(id)).isTrue();
        assertThatThrownBy(() -> service.execute(id, "sales", "select 1", null)).isInstanceOf(CancellationException.class);
        verifyNoInteractions(queries);
    }

    @Test void cannotCancelOtherUserOrWorkspace() throws Exception {
        String id = service.prepare("sales");
        user.setId("other");
        assertThatThrownBy(() -> service.cancel(id)).isInstanceOf(AccessDeniedException.class);
        user.setId("owner");
        when(security.requireCurrentWorkspace()).thenReturn("other-space");
        assertThatThrownBy(() -> service.cancel(id)).isInstanceOf(AccessDeniedException.class);
        when(security.requireCurrentWorkspace()).thenReturn("space");
        service.cancel(id);
        assertThatThrownBy(() -> service.execute(id, "sales", "select 1", null)).isInstanceOf(CancellationException.class);
    }

    @Test void executionIsOneShotAndBoundToSource() throws Exception {
        String id = service.prepare("sales");
        assertThatThrownBy(() -> service.execute(id, "finance", "select 1", null)).isInstanceOf(AccessDeniedException.class);
        when(queries.executeQuery("sales", "select 1", null, id)).thenReturn(new QueryResult());
        service.execute(id, "sales", "select 1", null);
        assertThatThrownBy(() -> service.execute(id, "sales", "select 1", null)).isInstanceOf(AccessDeniedException.class);
        verify(queries, times(1)).executeQuery("sales", "select 1", null, id);
    }
}
