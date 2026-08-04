package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.model.dto.AiReadAgentPlanRequest;
import io.github.lexaquila.lyradb.model.dto.AiReadAgentPlanView;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.model.entity.AiAgentRun;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.repository.AiAgentRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GovernedReadAgentServiceTest {

    @Mock
    private GrantService grantService;
    @Mock
    private SecurityUtil securityUtil;
    @Mock
    private EnterpriseQueryService enterpriseQueryService;
    @Mock
    private AiFeatureGate featureGate;
    @Mock
    private AiAgentRunRepository repository;
    @Mock
    private AuditService auditService;
    @Mock
    private DataSourceService dataSourceService;
    @Mock
    private MaxComputePreflightStore maxComputePreflightStore;
    @Mock
    private ReadAgentPlanStore planStore;
    @Mock
    private AiOperationalMetrics metrics;
    private GovernedReadAgentService service;
    private User user;
    private Grant grant;
    private AtomicReference<AiAgentRun> persisted;

    private AtomicReference<ReadAgentPlanStore.PlanSession> planned;
    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.getAi().setReadAgentMaxRows(100);
        properties.getAi().setReadAgentPlanTtlSeconds(300);
        service = new GovernedReadAgentService(
                grantService, securityUtil, enterpriseQueryService,
                featureGate, properties, repository,
                planStore, auditService,
                dataSourceService, maxComputePreflightStore,
                new ObjectMapper().registerModule(new JavaTimeModule()),
                metrics);
        user = new User();
        user.setId("user-1");
        grant = new Grant();
        grant.setId("grant-1");
        grant.setWorkspaceId("workspace-1");
        grant.setGrantedSourceName("sales-source");
        grant.setMaxRowsPerQuery(50);
        persisted = new AtomicReference<>();
        when(securityUtil.requireCurrentUser()).thenReturn(user);
        planned = new AtomicReference<>();
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
            planned.set(invocation.getArgument(1));
            return null;
        }).when(planStore).put(any(), any());
        org.mockito.Mockito.lenient().when(planStore.claim(
                anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    ReadAgentPlanStore.PlanSession session = planned.get();
                    String confirmed = invocation.getArgument(3);
                    if (session == null
                            || !session.planSha256().equals(confirmed)) {
                        throw new IllegalArgumentException("计划摘要不一致");
                    }
                    return session;
                });
        when(grantService.resolveForUser(
                "user-1", "workspace-1", "sales-source"))
                .thenReturn(grant);
    }

    @Test
    void planIsReadOnlyScopedAndRequiresHashConfirmation() throws Exception {
        stubSave();
        when(enterpriseQueryService.authorizeReadOnly(
                grant, "SELECT * FROM sales.orders", null))
                .thenReturn(readAnalysis());

        AiReadAgentPlanView plan = service.plan(
                "workspace-1", request());

        assertEquals(Set.of("sales.orders"), plan.resources());
        assertEquals(50, plan.maxRows());
        assertTrue(plan.confirmationRequired());
        assertEquals("PLANNED", persisted.get().getStatus());
        assertThrows(IllegalArgumentException.class,
                () -> service.execute("workspace-1", "run-1",
                        "0".repeat(64)));
        verify(enterpriseQueryService, never()).executeQuery(
                anyString(), anyString(), any(), anyString());
    }

    @Test
    void confirmedPlanReauthorizesAndReturnsReceipt() throws Exception {
        stubSave();
        when(enterpriseQueryService.authorizeReadOnly(
                grant, "SELECT * FROM sales.orders", null))
                .thenReturn(readAnalysis());
        AiReadAgentPlanView plan = service.plan(
                "workspace-1", request());
        QueryResult result = new QueryResult();
        result.setColumns(List.of("id"));
        result.setRows(List.of(Map.of("id", 1)));
        result.setTotalRows(1);
        result.setElapsedMs(5);
        when(enterpriseQueryService.executeQuery(
                "sales-source", "SELECT * FROM sales.orders",
                null, "ai-read-run-1")).thenReturn(result);

        var execution = service.execute(
                "workspace-1", "run-1", plan.planSha256());

        assertEquals("COMPLETED", execution.status());
        assertEquals(1, execution.contextReceipt().evidence().size());
        verify(planStore).complete(
                any(), any(), anyString(), anyString());
    }

    @Test
    void dmlPreflightCannotCreateRun() {
        when(enterpriseQueryService.authorizeReadOnly(
                grant, "SELECT * FROM sales.orders", null))
                .thenThrow(new IllegalArgumentException("只允许只读 SQL"));

        assertThrows(IllegalArgumentException.class,
                () -> service.plan("workspace-1", request()));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void maxComputeCannotBypassSpecializedPreflight() {
        when(featureGate.isEnabled(
                io.github.lexaquila.lyradb.ai.AiFeature.MAXCOMPUTE_AGENT))
                .thenReturn(true);
        when(enterpriseQueryService.authorizeReadOnly(
                grant, "SELECT * FROM sales.orders", null))
                .thenReturn(readAnalysis());
        grant.setDataSourceId("source-1");
        DataSource source = new DataSource();
        source.setId("source-1");
        source.setWorkspaceId("workspace-1");
        source.setDbType("MAXCOMPUTE");
        when(dataSourceService.getEntity("source-1")).thenReturn(source);
        org.mockito.Mockito.doThrow(new IllegalArgumentException(
                "MaxCompute 计划必须携带专项预检摘要"))
                .when(maxComputePreflightStore).requireAndConsume(
                        any(), anyString(), anyString(), anyString(),
                        anyString(), org.mockito.ArgumentMatchers.anyLong());

        assertThrows(IllegalArgumentException.class,
                () -> service.plan("workspace-1", request()));
        verify(repository, never()).saveAndFlush(any());
    }

    private void stubSave() {
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            AiAgentRun run = invocation.getArgument(0);
            if (run.getId() == null) {
                run.setId("run-1");
            }
            persisted.set(run);
            return run;
        });
    }


    private static AiReadAgentPlanRequest request() {
        AiReadAgentPlanRequest request = new AiReadAgentPlanRequest();
        request.setGrantedSourceName("sales-source");
        request.setQuestion("查询订单");
        request.setSql("SELECT * FROM sales.orders");
        request.setRequestedRows(100);
        request.setEstimatedCostMicros(0L);
        return request;
    }

    private static SqlParseUtil.Analysis readAnalysis() {
        return new SqlParseUtil.Analysis(
                SqlParseUtil.StatementType.READ,
                Set.of("sales.orders"), Map.of(), true);
    }
}
