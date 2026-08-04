package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.lexaquila.lyradb.ai.AiDigest;
import io.github.lexaquila.lyradb.ai.tool.AgentPermissionEnvelope;
import io.github.lexaquila.lyradb.ai.tool.AgentRiskLevel;
import io.github.lexaquila.lyradb.ai.tool.AgentToolCall;
import io.github.lexaquila.lyradb.ai.tool.AgentToolDefinition;
import io.github.lexaquila.lyradb.ai.tool.AgentToolEffect;
import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.model.entity.AiAgentRun;
import io.github.lexaquila.lyradb.repository.AiAgentRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReadAgentPlanStoreTest {

    @Mock
    private AiAgentRunRepository repository;
    @Mock
    private CredentialService credentialService;
    private AppProperties properties;
    private ObjectMapper objectMapper;
    private AiAgentRun run;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        properties.getAi().setExecutionNodeId("node-test");
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
        run = run();
        when(credentialService.encryptValue(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.lenient().when(credentialService.decryptValue(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findByIdForUpdate("run-1"))
                .thenAnswer(ignored -> Optional.of(run));
    }

    @Test
    void persistedPlanCanBeClaimedAfterStoreRestartOnlyOnce() {
        ReadAgentPlanStore first = store();
        first.put(run, session(run));
        assertTrue(run.getPlanPayloadCiphertext().contains("SELECT"));

        ReadAgentPlanStore restarted = store();
        ReadAgentPlanStore.PlanSession claimed = restarted.claim(
                "run-1", "workspace-1", "user-1",
                run.getPlanSha256());

        assertEquals("SELECT * FROM sales.orders", claimed.sql());
        assertEquals("RUNNING", run.getStatus());
        assertTrue(run.isPlanConsumed());
        assertEquals("node-test", run.getExecutionNodeId());
        assertThrows(ReadAgentPlanStore.PlanStateException.class,
                () -> restarted.claim(
                        "run-1", "workspace-1", "user-1",
                        run.getPlanSha256()));
    }

    @Test
    void persistedPlanRemainsBoundToWorkspaceAndUser() {
        ReadAgentPlanStore store = store();
        store.put(run, session(run));

        assertThrows(AccessDeniedException.class,
                () -> store.claim(
                        "run-1", "workspace-2", "user-1",
                        run.getPlanSha256()));
    }

    @Test
    void cancellingPlannedRunClearsEncryptedPayload() {
        ReadAgentPlanStore store = store();
        store.put(run, session(run));

        var result = store.requestCancel(
                "run-1", "workspace-1", "user-1");

        assertEquals("CANCELLED", result.status());
        assertEquals(null, run.getPlanPayloadCiphertext());
        assertTrue(run.isPlanConsumed());
    }

    private ReadAgentPlanStore store() {
        return new ReadAgentPlanStore(
                repository, credentialService, objectMapper, properties);
    }

    private static AiAgentRun run() {
        Instant expiresAt = Instant.now().plusSeconds(60);
        AiAgentRun run = new AiAgentRun();
        run.setId("run-1");
        run.setWorkspaceId("workspace-1");
        run.setUserId("user-1");
        run.setGrantId("grant-1");
        run.setGrantedSourceName("sales-source");
        run.setStatus("PLANNED");
        run.setQuestionSha256(AiDigest.sha256("question"));
        run.setSqlSha256(AiDigest.sha256(
                "SELECT * FROM sales.orders"));
        run.setPlanSha256(AiDigest.sha256("plan"));
        run.setRequestedRows(10);
        run.setEstimatedCostMicros(0L);
        run.setExpiresAt(LocalDateTime.ofInstant(
                expiresAt, ZoneOffset.UTC));
        return run;
    }

    private static ReadAgentPlanStore.PlanSession session(AiAgentRun run) {
        Instant expiresAt = run.getExpiresAt().toInstant(ZoneOffset.UTC);
        AgentToolDefinition tool = new AgentToolDefinition(
                "sql.execute.readonly", "1", "只读查询",
                AgentToolEffect.READ_DATA, true, true);
        String sql = "SELECT * FROM sales.orders";
        AgentToolCall call = new AgentToolCall(
                "call-1", "nonce-1", tool.name(),
                Set.of("sales.orders"), 10, 0,
                AiDigest.sha256(sql + "\n"));
        AgentPermissionEnvelope envelope = new AgentPermissionEnvelope(
                "user-1", "workspace-1", "grant-1",
                Set.of(tool.name()), Set.of("sales.orders"),
                10, 0, expiresAt, AgentRiskLevel.R2);
        return new ReadAgentPlanStore.PlanSession(
                run.getId(), run.getWorkspaceId(), run.getUserId(),
                run.getGrantId(), run.getGrantedSourceName(),
                run.getQuestionSha256(), sql, run.getSqlSha256(),
                null, run.getPlanSha256(), Set.of("sales.orders"),
                envelope, tool, call, expiresAt);
    }
}
