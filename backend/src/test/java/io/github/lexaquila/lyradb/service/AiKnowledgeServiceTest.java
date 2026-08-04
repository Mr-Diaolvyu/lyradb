package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.ai.AiDigest;
import io.github.lexaquila.lyradb.ai.knowledge.KnowledgeAssetStatus;
import io.github.lexaquila.lyradb.ai.knowledge.KnowledgeAssetType;
import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.model.dto.AiKnowledgeDraftRequest;
import io.github.lexaquila.lyradb.model.dto.AiKnowledgeReviewRequest;
import io.github.lexaquila.lyradb.model.entity.AiKnowledgeAsset;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.repository.AiKnowledgeAssetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiKnowledgeServiceTest {

    @Mock
    private AiKnowledgeAssetRepository repository;
    @Mock
    private SecurityUtil securityUtil;
    @Mock
    private AiFeatureGate featureGate;
    @Mock
    private AuditService auditService;
    @Mock
    private AiKnowledgeEmbeddingService embeddingService;
    private AiKnowledgeService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new AiKnowledgeService(repository, securityUtil, featureGate,
                auditService, new AppProperties(), new ObjectMapper(),
                embeddingService);
        user = new User();
        user.setId("user-1");
        user.setUsername("analyst");
    }

    @Test
    void verifiedQueryDraftMustBeReadOnlyAndBoundToLogicalSource() {
        when(securityUtil.requireCurrentUser()).thenReturn(user);
        AiKnowledgeDraftRequest request = new AiKnowledgeDraftRequest();
        request.setType(KnowledgeAssetType.VERIFIED_QUERY);
        request.setTitle("订单状态修复");
        request.setDefinition("修复订单状态");
        request.setGrantedSourceName("sales-source");
        request.setVerifiedSql("UPDATE sales.orders SET status='done'");

        assertThrows(IllegalArgumentException.class,
                () -> service.createDraft("workspace-1", request));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void contributorCanListOnlyTheirOwnWorkspaceAssets() {
        AiKnowledgeAsset mine = asset(
                "asset-1", "订单指标口径", "订单数量按订单主键去重",
                "sales-source");
        mine.setStatus(KnowledgeAssetStatus.DRAFT);
        when(securityUtil.requireCurrentUser()).thenReturn(user);
        when(repository.findByWorkspaceIdAndCreatedByOrderByUpdatedAtDesc(
                anyString(), anyString(), any())).thenReturn(List.of(mine));

        var result = service.listMine("workspace-1");

        assertEquals(1, result.size());
        assertEquals("asset-1", result.get(0).id());
        verify(repository).findByWorkspaceIdAndCreatedByOrderByUpdatedAtDesc(
                eq("workspace-1"), eq("user-1"), any());
    }

    @Test
    void retrievalUsesOnlyRelevantVerifiedAssetsForCurrentSource() {
        AiKnowledgeAsset relevant = asset(
                "asset-1", "订单指标口径", "订单数量按订单主键去重",
                "sales-source");
        AiKnowledgeAsset otherSource = asset(
                "asset-2", "订单指标口径", "另一数据源口径",
                "finance-source");
        when(repository.findByWorkspaceIdAndStatusOrderByUpdatedAtDesc(
                anyString(), any(), any())).thenReturn(
                List.of(relevant, otherSource));

        AiKnowledgeService.KnowledgeContext context =
                service.retrieveVerified(
                        "workspace-1", "sales-source", "统计订单数量");

        assertEquals(1, context.evidence().size());
        assertEquals("asset-1", context.evidence().get(0).id());
        assertEquals(false, context.promptJson().contains("另一数据源口径"));
    }

    @Test
    void onlyStewardCanVerifySubmittedAsset() {
        AiKnowledgeAsset asset = asset(
                "asset-1", "订单指标口径", "订单数量按订单主键去重",
                null);
        asset.setStatus(KnowledgeAssetStatus.IN_REVIEW);
        when(repository.findByIdAndWorkspaceId("asset-1", "workspace-1"))
                .thenReturn(Optional.of(asset));
        when(securityUtil.hasRole("STEWARD")).thenReturn(true);
        when(securityUtil.requireCurrentUser()).thenReturn(user);
        when(repository.saveAndFlush(asset)).thenReturn(asset);
        AiKnowledgeReviewRequest request = new AiKnowledgeReviewRequest();
        request.setDecision("VERIFY");
        request.setComment("口径已与业务负责人核验");

        service.review("workspace-1", "asset-1", request);

        assertEquals(KnowledgeAssetStatus.VERIFIED, asset.getStatus());
        assertEquals("user-1", asset.getReviewedBy());
        verify(auditService).recordCurrent(
                "workspace-1", "AI_KNOWLEDGE_VERIFY",
                null, null, true, null);
    }

    private static AiKnowledgeAsset asset(
            String id, String title, String definition, String source) {
        AiKnowledgeAsset asset = new AiKnowledgeAsset();
        asset.setId(id);
        asset.setWorkspaceId("workspace-1");
        asset.setType(KnowledgeAssetType.METRIC);
        asset.setStatus(KnowledgeAssetStatus.VERIFIED);
        asset.setTitle(title);
        asset.setDefinition(definition);
        asset.setGrantedSourceName(source);
        asset.setContentSha256(AiDigest.sha256(title + definition));
        asset.setCreatedBy("user-1");
        asset.setAssetVersion(2);
        asset.setCreatedAt(LocalDateTime.of(2026, 8, 3, 12, 0));
        asset.setUpdatedAt(LocalDateTime.of(2026, 8, 3, 12, 0));
        return asset;
    }
}
