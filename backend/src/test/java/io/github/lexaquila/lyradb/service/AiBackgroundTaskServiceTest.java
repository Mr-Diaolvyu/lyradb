package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiBackgroundTaskServiceTest {
    SecurityUtil security = mock(SecurityUtil.class);
    GrantService grants = mock(GrantService.class);
    ApprovalSecurityContextService fingerprints = mock(ApprovalSecurityContextService.class);
    EnterpriseAiService ai = mock(EnterpriseAiService.class);
    EnterpriseMetadataSnapshotService metadata = mock(EnterpriseMetadataSnapshotService.class);
    AiBackgroundTaskService service;
    User user = new User();
    Grant grant = new Grant();

    @BeforeEach void setup() throws Exception {
        user.setId("u1"); grant.setId("g1");
        when(security.requireCurrentUser()).thenReturn(user);
        when(grants.resolveForUser("u1", "w1", "source")).thenReturn(grant);
        when(grants.getByIdForUser("g1", "u1", "w1")).thenReturn(grant);
        when(fingerprints.fingerprint(grant)).thenReturn("v1");
        when(security.inBackgroundWorkspace(eq("w1"), any())).thenAnswer(invocation ->
                ((Callable<?>) invocation.getArgument(1)).call());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "", List.of()));
        service = new AiBackgroundTaskService(security, grants, fingerprints, ai,
                mock(EnterpriseTableSearchService.class), metadata, mock(AuditService.class));
    }
    @AfterEach void cleanup() { service.shutdown(); SecurityContextHolder.clearContext(); }

    AiBackgroundTaskService.Request request(String id) {
        return new AiBackgroundTaskService.Request(id, "CHAT", "source", "解释表", List.of(), false, null, null, null, null);
    }
    AiBackgroundTaskService.View awaitDone() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var rows = service.list("w1");
            if (!rows.isEmpty() && Set.of("SUCCEEDED", "FAILED").contains(rows.get(0).state())) return rows.get(0);
            Thread.sleep(10);
        }
        throw new AssertionError("任务未在测试期限内完成");
    }

    @Test void returnsBeforeCompletionAndRecoversResultWithoutRequestContext() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(ai.chat(anyString(), anyString(), anyString(), anyList(), anyBoolean(), any())).thenAnswer(invocation -> {
            assertEquals("alice", SecurityContextHolder.getContext().getAuthentication().getName());
            assertNull(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes());
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return Map.of("explanation", "回答内容");
        });
        try {
            var request = request("task-request-000001");
            var first = service.submit("w1", request);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(first.id(), service.submit("w1", request).id());
            assertEquals(1, service.list("w1").size());
        } finally { release.countDown(); }
        assertEquals("SUCCEEDED", awaitDone().state());
        assertEquals(Map.of("explanation", "回答内容"), service.list("w1").get(0).result());
        verify(ai, times(1)).chat(anyString(), anyString(), anyString(), anyList(), anyBoolean(), any());
    }

    @Test void isolatesOwnerWorkspaceAndRevokedGrant() throws Exception {
        when(ai.chat(anyString(), anyString(), anyString(), anyList(), anyBoolean(), any())).thenReturn(Map.of("explanation", "ok"));
        service.submit("w1", request("task-request-000002"));
        awaitDone();
        assertTrue(service.list("w2").isEmpty());
        User other = new User(); other.setId("u2");
        when(security.requireCurrentUser()).thenReturn(other);
        assertTrue(service.list("w1").isEmpty());
        when(security.requireCurrentUser()).thenReturn(user);
        when(fingerprints.fingerprint(grant)).thenReturn("changed");
        assertTrue(service.list("w1").isEmpty());
    }

    @Test void providerErrorIsFailureRatherThanSuccessfulEmptyAnswer() throws Exception {
        when(ai.chat(anyString(), anyString(), anyString(), anyList(), anyBoolean(), any())).thenReturn(Map.of("error", "AI 服务调用失败"));
        service.submit("w1", request("task-request-000003"));
        assertEquals("FAILED", awaitDone().state());
    }

    @Test void driverExceptionDoesNotLeakCredentials() throws Exception {
        when(ai.chat(anyString(), anyString(), anyString(), anyList(), anyBoolean(), any()))
                .thenThrow(new IllegalArgumentException("jdbc:password=private-secret"));
        service.submit("w1", request("task-request-000004"));
        var result = awaitDone();
        assertEquals("FAILED", result.state());
        assertFalse(result.error().contains("private-secret"));
    }

    @Test void emptyMetadataKeepsActionableError() throws Exception {
        when(metadata.capture(eq("w1"), eq(user), any())).thenThrow(new IllegalArgumentException("未采集到任何表。请检查数据库名称"));
        service.submit("w1", new AiBackgroundTaskService.Request("task-request-000005", "METADATA", "source", null,
                null, false, null, "erp", null, null));
        var result = awaitDone();
        assertEquals("FAILED", result.state());
        assertTrue(result.error().contains("未采集到任何表"));
    }
}
