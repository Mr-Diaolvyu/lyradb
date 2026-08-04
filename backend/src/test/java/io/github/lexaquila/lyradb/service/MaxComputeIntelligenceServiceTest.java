package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.model.dto.MaxComputePreflightRequest;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MaxComputeIntelligenceServiceTest {

    @Mock
    private GrantService grantService;
    @Mock
    private DataSourceService dataSourceService;
    @Mock
    private EnterpriseQueryService enterpriseQueryService;
    @Mock
    private SecurityUtil securityUtil;
    @Mock
    private AiFeatureGate featureGate;
    @Mock
    private AuditService auditService;
    @Mock
    private MaxComputeLiveEvidenceService liveEvidenceService;
    @Mock
    private MaxComputePreflightStore preflightStore;
    private MaxComputeIntelligenceService service;
    private Grant grant;
    private AppProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        properties.getAi().setReadAgentPlanTtlSeconds(300);
        properties.getAi().setReadAgentMaxEstimatedCostMicros(10L);
        service = new MaxComputeIntelligenceService(
                grantService, dataSourceService, enterpriseQueryService,
                securityUtil, featureGate, properties,
                preflightStore, auditService,
                liveEvidenceService);
        org.mockito.Mockito.lenient().when(preflightStore.issue(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenAnswer(invocation ->
                        new MaxComputePreflightStore.PreflightSession(
                                "c".repeat(64), invocation.getArgument(0),
                                invocation.getArgument(1), invocation.getArgument(2),
                                invocation.getArgument(3), invocation.getArgument(4),
                                invocation.getArgument(5)));
        User user = new User();
        user.setId("user-1");
        grant = new Grant();
        grant.setId("grant-1");
        grant.setWorkspaceId("workspace-1");
        grant.setDataSourceId("source-1");
        grant.setGrantedSourceName("mc-source");
        DataSource source = new DataSource();
        source.setId("source-1");
        source.setWorkspaceId("workspace-1");
        source.setDbType("MAXCOMPUTE");
        when(securityUtil.requireCurrentUser()).thenReturn(user);
        when(grantService.resolveForUser(
                "user-1", "workspace-1", "mc-source"))
                .thenReturn(grant);
        when(dataSourceService.getEntity("source-1")).thenReturn(source);
        when(enterpriseQueryService.authorizeReadOnly(
                grant, sql(), null)).thenReturn(new SqlParseUtil.Analysis(
                SqlParseUtil.StatementType.READ,
                Set.of("sales.orders"), Map.of(), true));
    }

    @Test
    void coveredPartitionAndBudgetIssueSingleUseToken() {
        MaxComputePreflightRequest request = request(
                Map.of("sales.orders", List.of("ds")), 8L);

        var result = service.preflight("workspace-1", request);

        assertTrue(result.planEligible());
        assertNotNull(result.preflightSha256());
        assertTrue(result.partitionChecks().get(0).covered());
        assertNotNull(result.contextReceipt());
    }

    @Test
    void missingPartitionOrOverBudgetFailsClosed() {
        MaxComputePreflightRequest request = request(
                Map.of("sales.orders", List.of("hour")), 11L);

        var result = service.preflight("workspace-1", request);

        assertFalse(result.planEligible());
        assertNull(result.preflightSha256());
        assertFalse(result.partitionChecks().get(0).covered());
    }

    @Test
    void completeLiveEvidenceOverridesDeclaredValues() {
        properties.getAi().setMaxComputeLiveEvidenceEnabled(true);
        properties.getAi().setMaxComputeLiveEvidenceRequired(true);
        when(liveEvidenceService.inspect(any(), any(), eq(sql())))
                .thenReturn(new MaxComputeLiveEvidenceService.LiveEvidence(
                        "OBSERVED",
                        Map.of("sales.orders", List.of("ds")),
                        2_048L, 9L, "a".repeat(64), "b".repeat(64),
                        List.of(), true));
        MaxComputePreflightRequest request = request(
                Map.of("sales.orders", List.of("hour")), 11L);

        var result = service.preflight("workspace-1", request);

        assertTrue(result.planEligible());
        assertTrue(result.partitionChecks().get(0).covered());
        assertTrue("LIVE_COMPLETE".equals(result.evidenceMode()));
        assertTrue(Long.valueOf(9L).equals(
                result.liveEvidence().estimatedCostMicros()));
    }

    private static MaxComputePreflightRequest request(
            Map<String, List<String>> partitions, long cost) {
        MaxComputePreflightRequest request =
                new MaxComputePreflightRequest();
        request.setGrantedSourceName("mc-source");
        request.setSql(sql());
        request.setRequiredPartitionColumns(partitions);
        request.setEstimatedInputBytes(1_024L);
        request.setEstimatedCostMicros(cost);
        return request;
    }

    private static String sql() {
        return "SELECT o.id FROM sales.orders o "
                + "WHERE o.ds = '20260803'";
    }
}
