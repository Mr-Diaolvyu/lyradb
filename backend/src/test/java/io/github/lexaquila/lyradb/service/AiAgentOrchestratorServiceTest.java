package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.model.dto.AiAgentOrchestrationRequest;
import io.github.lexaquila.lyradb.model.dto.AiReadAgentPlanRequest;
import io.github.lexaquila.lyradb.model.dto.AiReadAgentPlanView;
import io.github.lexaquila.lyradb.model.entity.AiProviderConfig;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiAgentOrchestratorServiceTest {

    @Mock private AiProviderService providerService;
    @Mock private GrantService grantService;
    @Mock private DataSourceService dataSourceService;
    @Mock private SecurityUtil securityUtil;
    @Mock private EnterpriseMetadataSnapshotService metadataSnapshotService;
    @Mock private AiKnowledgeService knowledgeService;
    @Mock private GovernedReadAgentService readAgentService;
    @Mock private AuditService auditService;
    @Mock private AiFeatureGate featureGate;

    private AiAgentOrchestratorService service;
    private AiProviderConfig provider;

    @BeforeEach
    void setUp() {
        service = new AiAgentOrchestratorService(
                providerService, grantService, dataSourceService,
                securityUtil, metadataSnapshotService, knowledgeService,
                readAgentService, auditService, featureGate,
                new ObjectMapper());
        User user = new User();
        user.setId("user-1");
        Grant grant = new Grant();
        grant.setId("grant-1");
        grant.setWorkspaceId("workspace-1");
        grant.setDataSourceId("source-1");
        grant.setGrantedSourceName("sales-source");
        DataSource source = new DataSource();
        source.setId("source-1");
        source.setWorkspaceId("workspace-1");
        source.setDbType("POSTGRESQL");
        provider = new AiProviderConfig();
        provider.setId("provider-1");
        provider.setWorkspaceId("workspace-1");
        provider.setProviderKey("custom");
        provider.setModel("test-model");
        when(securityUtil.requireCurrentUser()).thenReturn(user);
        when(grantService.resolveForUser(
                "user-1", "workspace-1", "sales-source"))
                .thenReturn(grant);
        when(dataSourceService.getEntity("source-1")).thenReturn(source);
        when(providerService.resolveDefault("workspace-1"))
                .thenReturn(provider);
    }

    @Test
    void modelCanOnlyCreatePlanAndNeverReceivesExecuteTool() {
        AiProviderToolTurn turn = toolTurn(
                "call-1", AiAgentOrchestratorService.SQL_READ_PLAN,
                "{\"sql\":\"SELECT * FROM sales.orders\"}");
        when(providerService.chatWithTools(eq(provider), any(), any()))
                .thenReturn(turn);
        when(readAgentService.plan(eq("workspace-1"), any()))
                .thenReturn(plan());

        var view = service.orchestrate("workspace-1", request());

        assertEquals("WAITING_FOR_CONFIRMATION", view.status());
        assertTrue(view.plan().confirmationRequired());
        ArgumentCaptor<List<Map<String, Object>>> tools =
                ArgumentCaptor.forClass(List.class);
        verify(providerService).chatWithTools(
                eq(provider), any(), tools.capture());
        String serialized = tools.getValue().toString();
        assertTrue(serialized.contains("knowledge.search"));
        assertTrue(serialized.contains("sql.read.plan"));
        assertFalse(serialized.contains("execute"));
        ArgumentCaptor<AiReadAgentPlanRequest> planRequest =
                ArgumentCaptor.forClass(AiReadAgentPlanRequest.class);
        verify(readAgentService).plan(
                eq("workspace-1"), planRequest.capture());
        assertEquals("sales-source",
                planRequest.getValue().getGrantedSourceName());
        assertEquals("查询订单", planRequest.getValue().getQuestion());
    }

    @Test
    void verifiedKnowledgeCanBeRetrievedBeforeAnswerOnly() {
        when(providerService.chatWithTools(eq(provider), any(), any()))
                .thenReturn(
                        toolTurn("call-knowledge",
                                AiAgentOrchestratorService.KNOWLEDGE_SEARCH,
                                "{\"query\":\"订单口径\"}"),
                        new AiProviderToolTurn(
                                "订单口径尚未经过审核。", List.of(),
                                new AiProviderToolTurn.Usage(2, 3, 5),
                                Map.of("role", "assistant",
                                        "content", "订单口径尚未经过审核。")));
        when(knowledgeService.retrieveVerified(
                "workspace-1", "sales-source", "订单口径"))
                .thenReturn(new AiKnowledgeService.KnowledgeContext(
                        "[]", List.of(), List.of()));

        var view = service.orchestrate("workspace-1", request());

        assertEquals("ANSWER_ONLY", view.status());
        assertEquals(2, view.steps());
        assertEquals(7, view.usage().totalTokens());
        verify(knowledgeService).retrieveVerified(
                "workspace-1", "sales-source", "订单口径");
        verify(readAgentService, never()).plan(any(), any());
    }

    @Test
    void unknownModelToolFailsClosed() {
        when(providerService.chatWithTools(eq(provider), any(), any()))
                .thenReturn(toolTurn(
                        "call-evil", "sql.execute.readonly",
                        "{\"sql\":\"SELECT 1\"}"));

        assertThrows(AccessDeniedException.class,
                () -> service.orchestrate("workspace-1", request()));

        verify(readAgentService, never()).plan(any(), any());
    }

    private static AiProviderToolTurn toolTurn(
            String id, String name, String arguments) {
        return new AiProviderToolTurn(
                "", List.of(new AiProviderToolTurn.ToolCall(
                        id, name, arguments)),
                new AiProviderToolTurn.Usage(1, 1, 2),
                Map.of("role", "assistant", "tool_calls", List.of(
                        Map.of("id", id, "type", "function",
                                "function", Map.of(
                                        "name", name,
                                        "arguments", arguments)))));
    }

    private static AiAgentOrchestrationRequest request() {
        AiAgentOrchestrationRequest request =
                new AiAgentOrchestrationRequest();
        request.setGrantedSourceName("sales-source");
        request.setQuestion("查询订单");
        request.setRequestedRows(100);
        return request;
    }

    private static AiReadAgentPlanView plan() {
        return new AiReadAgentPlanView(
                "run-1", "a".repeat(64), "sales-source",
                "SELECT * FROM sales.orders", null,
                Set.of("sales.orders"), 100, 0,
                "R2", Instant.now().plusSeconds(300),
                List.of("只读预检", "等待人工确认"), true);
    }
}
