package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.ai.model.AiEvidenceType;
import io.github.lexaquila.lyradb.ai.tool.AgentRiskLevel;
import io.github.lexaquila.lyradb.model.dto.AiQualityAutoEvaluationRequest;
import io.github.lexaquila.lyradb.model.dto.AiQualityEvaluationRequest;
import io.github.lexaquila.lyradb.model.dto.AiQualityObservationRequest;
import io.github.lexaquila.lyradb.model.entity.AiEvaluationRun;
import io.github.lexaquila.lyradb.model.entity.AiProviderConfig;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.repository.AiEvaluationRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiQualityServiceTest {

    @Mock
    private AiEvaluationRunRepository repository;
    @Mock
    private SecurityUtil securityUtil;
    @Mock
    private AiFeatureGate featureGate;
    @Mock
    private AuditService auditService;
    @Mock
    private AiProviderService providerService;
    private AiQualityService service;

    @BeforeEach
    void setUp() {
        service = new AiQualityService(repository, securityUtil,
                featureGate, auditService, new ObjectMapper(),
                providerService);
        service.validateGoldenSet();
    }

    @Test
    void goldenSetIsVersionedAndCannotBePartiallySubmitted() {
        assertEquals("1.1.0", service.goldenSet().version());
        assertEquals(8, service.goldenSet().cases().size());
        when(securityUtil.hasRole("STEWARD")).thenReturn(true);
        AiQualityEvaluationRequest request = new AiQualityEvaluationRequest();
        request.setObservations(List.of(observation(
                "ask-verified-query-001", "安全回答", "READ",
                Set.of(AiEvidenceType.VERIFIED_QUERY), AgentRiskLevel.R2)));

        assertThrows(IllegalArgumentException.class,
                () -> service.evaluate("workspace-1", request));
    }

    @Test
    void completePassingRunOpensReleaseGate() {
        when(securityUtil.hasRole("STEWARD")).thenReturn(true);
        User user = new User();
        user.setId("user-1");
        when(securityUtil.requireCurrentUser()).thenReturn(user);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            AiEvaluationRun run = invocation.getArgument(0);
            run.setId("eval-1");
            run.setCreatedAt(LocalDateTime.of(2026, 8, 3, 13, 0));
            return run;
        });
        AiQualityEvaluationRequest request = new AiQualityEvaluationRequest();
        request.setObservations(service.goldenSet().cases().stream()
                .map(testCase -> observation(
                        testCase.id(), "安全回答",
                        testCase.expectedSqlType(),
                        testCase.requiredEvidence(), testCase.maxRisk()))
                .toList());

        var result = service.evaluate("workspace-1", request);

        assertTrue(result.releaseGatePassed());
        assertEquals(8, result.passedCount());
        assertEquals(1D, result.passRate());
    }

    @Test
    void automaticRunCallsProviderAndDerivesObservations() {
        when(securityUtil.hasRole("STEWARD")).thenReturn(true);
        User user = new User();
        user.setId("user-1");
        when(securityUtil.requireCurrentUser()).thenReturn(user);
        AiProviderConfig provider = new AiProviderConfig();
        provider.setWorkspaceId("workspace-1");
        provider.setProviderKey("custom");
        provider.setModel("quality-model");
        when(providerService.resolveDefault("workspace-1"))
                .thenReturn(provider);
        when(providerService.chatWithUsage(any(), any()))
                .thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<java.util.Map<String, String>> messages =
                    invocation.getArgument(1);
            String prompt = messages.toString();
            if (prompt.contains("ask-verified-query-001")) {
                return chatResult(
                        "依据审核模板。[证据: VQ-ORDER-COUNT]\n"
                        + "```sql\nSELECT COUNT(*) FROM sales.orders\n```");
            }
            if (prompt.contains("no-execution-claim-001")) {
                return chatResult(
                        "仅生成计划，等待确认。[证据: VQ-ORDER-COUNT] "
                        + "[证据: POLICY-READ-ONLY]\n"
                        + "```sql\nSELECT COUNT(*) FROM sales.orders\n```");
            }
            if (prompt.contains("no-business-inference-001")
                    || prompt.contains("missing-context-001")) {
                return chatResult(
                        "缺少已审核口径或实际数据，无法确定。"
                        + "[证据: KNOWLEDGE-GAP]");
            }
            return chatResult(
                    "拒绝写入、越权或无预检请求。"
                    + "[证据: POLICY-READ-ONLY]");
        });
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            AiEvaluationRun run = invocation.getArgument(0);
            run.setId("eval-auto-1");
            run.setCreatedAt(LocalDateTime.of(2026, 8, 3, 14, 0));
            return run;
        });
        AiQualityAutoEvaluationRequest request =
                new AiQualityAutoEvaluationRequest();
        request.setAcknowledgeProviderUsage(true);

        var result = service.evaluateAutomatically(
                "workspace-1", request);

        assertTrue(result.releaseGatePassed());
        assertEquals("AUTO", result.evaluationMode());
        assertEquals("quality-model", result.model());
        assertEquals(160, result.totalTokens());
        assertEquals(8, result.passedCount());
    }

    private static AiQualityObservationRequest observation(
            String caseId, String response, String sqlType,
            Set<AiEvidenceType> evidence, AgentRiskLevel risk) {
        AiQualityObservationRequest result =
                new AiQualityObservationRequest();
        result.setCaseId(caseId);
        result.setResponseText(response);
        result.setSqlType(sqlType);
        result.setEvidenceTypes(evidence);
        result.setRiskLevel(risk);
        return result;
    }

    private static AiProviderChatResult chatResult(String content) {
        return new AiProviderChatResult(content,
                new AiProviderToolTurn.Usage(10, 10, 20));
    }
}
