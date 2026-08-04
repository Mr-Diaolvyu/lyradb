package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.ai.knowledge.KnowledgeAssetStatus;
import io.github.lexaquila.lyradb.metadata.snapshot.MetadataSnapshot;
import io.github.lexaquila.lyradb.model.entity.AiKnowledgeAsset;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.repository.AiKnowledgeAssetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiKnowledgeIngestionServiceTest {

    @Mock private EnterpriseMetadataSnapshotService metadataSnapshotService;
    @Mock private AiKnowledgeAssetRepository repository;
    @Mock private SecurityUtil securityUtil;
    @Mock private AiFeatureGate featureGate;
    @Mock private AuditService auditService;

    @Test
    void metadataCanOnlyBecomeReviewRequiredDraft() {
        AiKnowledgeIngestionService service =
                new AiKnowledgeIngestionService(
                        metadataSnapshotService, repository, securityUtil,
                        featureGate, auditService, new ObjectMapper());
        User user = new User();
        user.setId("user-1");
        when(securityUtil.requireCurrentUser()).thenReturn(user);
        MetadataSnapshot snapshot = MetadataSnapshot.of(List.of(
                new MetadataSnapshot.DataSource(
                        "source-1", "生产销售库", "POSTGRESQL", "",
                        List.of(new MetadataSnapshot.Database(
                                "sales", "", List.of(
                                new MetadataSnapshot.Schema(
                                        "public", "", List.of(
                                        new MetadataSnapshot.Table(
                                                "orders", "TABLE", "订单表",
                                                List.of(new MetadataSnapshot.Column(
                                                        "order_id", "BIGINT",
                                                        "BIGINT", false,
                                                        "", "技术主键")),
                                                List.of("order_id"))))))))));
        MetadataSnapshotSessionStore.SnapshotSession session =
                new MetadataSnapshotSessionStore.SnapshotSession(
                        "snapshot-1", "user-1", "workspace-1",
                        "grant-1", "source-1", "sales-source",
                        "fingerprint",
                        new MetadataSnapshotSessionStore.MapScope(
                                "sales", List.of("public"),
                                List.of("sales.public.orders")),
                        snapshot, 1, 1, 100,
                        LocalDateTime.now().plusMinutes(5), false);
        when(metadataSnapshotService.consumeForKnowledge(
                "workspace-1", user, "snapshot-1")).thenReturn(session);
        when(repository.saveAllAndFlush(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<AiKnowledgeAsset> values = invocation.getArgument(0);
            for (int index = 0; index < values.size(); index++) {
                values.get(index).setId("asset-" + index);
            }
            return values;
        });

        var result = service.ingestMetadata(
                "workspace-1", "snapshot-1");

        assertEquals(1, result.createdDrafts());
        assertTrue(result.reviewRequired());
        assertEquals("DRAFT", result.drafts().get(0).status());
        assertTrue(result.drafts().get(0).sourceRef()
                .contains("sales.public.orders"));
        assertEquals(KnowledgeAssetStatus.DRAFT,
                captured(repository).getStatus());
        assertTrue(captured(repository).getDefinition()
                .contains("禁止仅凭字段名推断业务口径"));
        assertTrue(captured(repository).getLineageJson()
                .contains("不代表业务或字段加工血缘"));
    }

    @SuppressWarnings("unchecked")
    private static AiKnowledgeAsset captured(
            AiKnowledgeAssetRepository repository) {
        org.mockito.ArgumentCaptor<List<AiKnowledgeAsset>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(repository).saveAllAndFlush(captor.capture());
        return captor.getValue().get(0);
    }
}
