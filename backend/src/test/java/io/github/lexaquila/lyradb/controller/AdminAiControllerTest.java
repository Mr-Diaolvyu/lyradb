package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.service.AiProviderAuthenticationException;
import io.github.lexaquila.lyradb.service.AiProviderService;
import io.github.lexaquila.lyradb.service.AuditService;
import io.github.lexaquila.lyradb.service.SecurityUtil;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAiControllerTest {
    @Test
    void availabilityTestCallsProviderInCurrentWorkspaceAndReturnsLatency() {
        AiProviderService providers = mock(AiProviderService.class);
        SecurityUtil security = mock(SecurityUtil.class);
        AuditService audit = mock(AuditService.class);
        HttpSession session = mock(HttpSession.class);
        when(security.requireCurrentWorkspace(session)).thenReturn("ws");
        when(providers.testConnection("provider", "ws")).thenReturn(42L);

        Map<String, Object> result = new AdminAiController(providers, security, audit)
                .test("provider", session);

        assertThat(result).containsEntry("success", true)
                .containsEntry("elapsedMs", 42L);
        verify(security).requireRole("DS_ADMIN");
        verify(audit).recordCurrent("ws", "AI_PROVIDER_TEST",
                null, "provider", true, null);
    }

    @Test
    void availabilityTestReportsAuthenticationFailureWithoutExposingKey() {
        AiProviderService providers = mock(AiProviderService.class);
        SecurityUtil security = mock(SecurityUtil.class);
        AuditService audit = mock(AuditService.class);
        HttpSession session = mock(HttpSession.class);
        when(security.requireCurrentWorkspace(session)).thenReturn("ws");
        when(providers.testConnection("provider", "ws"))
                .thenThrow(new AiProviderAuthenticationException());

        Map<String, Object> result = new AdminAiController(providers, security, audit)
                .test("provider", session);

        assertThat(result).containsEntry("success", false)
                .containsEntry("message", "Provider 鉴权失败");
        verify(audit).recordCurrent("ws", "AI_PROVIDER_TEST",
                null, "provider", false, "Provider 鉴权失败");
    }
}
