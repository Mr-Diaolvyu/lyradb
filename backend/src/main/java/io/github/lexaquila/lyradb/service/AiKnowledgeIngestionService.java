package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.ai.AiDigest;
import io.github.lexaquila.lyradb.ai.AiFeature;
import io.github.lexaquila.lyradb.ai.knowledge.KnowledgeAssetStatus;
import io.github.lexaquila.lyradb.ai.knowledge.KnowledgeAssetType;
import io.github.lexaquila.lyradb.metadata.snapshot.MetadataSnapshot;
import io.github.lexaquila.lyradb.model.dto.AiKnowledgeIngestedDraftView;
import io.github.lexaquila.lyradb.model.dto.AiKnowledgeIngestionView;
import io.github.lexaquila.lyradb.model.entity.AiKnowledgeAsset;
import io.github.lexaquila.lyradb.model.entity.User;
import io.github.lexaquila.lyradb.repository.AiKnowledgeAssetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 把授权元数据快照摄取为可追溯草稿，绝不自动审核或推断业务口径。 */
@Service
public class AiKnowledgeIngestionService {

    private static final int MAX_DRAFTS_PER_INGESTION = 200;
    private final EnterpriseMetadataSnapshotService metadataSnapshotService;
    private final AiKnowledgeAssetRepository repository;
    private final SecurityUtil securityUtil;
    private final AiFeatureGate featureGate;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public AiKnowledgeIngestionService(
            EnterpriseMetadataSnapshotService metadataSnapshotService,
            AiKnowledgeAssetRepository repository,
            SecurityUtil securityUtil,
            AiFeatureGate featureGate,
            AuditService auditService,
            ObjectMapper objectMapper) {
        this.metadataSnapshotService = metadataSnapshotService;
        this.repository = repository;
        this.securityUtil = securityUtil;
        this.featureGate = featureGate;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AiKnowledgeIngestionView ingestMetadata(
            String workspaceId, String snapshotId) {
        featureGate.requireEnabled(AiFeature.TEAM_KNOWLEDGE_LOOP);
        String workspace = requireText(workspaceId, "工作空间 ID", 36);
        String snapshot = requireText(snapshotId, "元数据快照 ID", 128);
        User user = securityUtil.requireCurrentUser();
        MetadataSnapshotSessionStore.SnapshotSession session =
                metadataSnapshotService.consumeForKnowledge(
                        workspace, user, snapshot);
        MetadataSnapshot content = session.snapshot();
        if (content == null) {
            throw new IllegalStateException("元数据快照内容不存在");
        }

        List<AiKnowledgeAsset> drafts = new ArrayList<>();
        int discovered = 0;
        outer:
        for (MetadataSnapshot.DataSource source : content.dataSources()) {
            for (MetadataSnapshot.Database database : source.databases()) {
                for (MetadataSnapshot.Schema schema : database.schemas()) {
                    for (MetadataSnapshot.Table table : schema.tables()) {
                        discovered++;
                        if (drafts.size() >= MAX_DRAFTS_PER_INGESTION) {
                            continue;
                        }
                        drafts.add(draft(session, user, source, database,
                                schema, table));
                    }
                }
            }
        }
        List<AiKnowledgeAsset> saved = repository.saveAllAndFlush(drafts);
        auditService.recordCurrent(workspace,
                "AI_KNOWLEDGE_METADATA_INGEST", session.dataSourceId(),
                session.grantedSourceName(), true,
                "snapshot=" + session.id() + ",drafts=" + saved.size());
        List<AiKnowledgeIngestedDraftView> views = saved.stream()
                .map(asset -> new AiKnowledgeIngestedDraftView(
                        asset.getId(), asset.getTitle(), asset.getSourceRef(),
                        asset.getStatus().name()))
                .toList();
        return new AiKnowledgeIngestionView(
                session.id(), session.grantedSourceName(), saved.size(),
                Math.max(0, discovered - saved.size()), views, true);
    }

    private AiKnowledgeAsset draft(
            MetadataSnapshotSessionStore.SnapshotSession session,
            User user,
            MetadataSnapshot.DataSource source,
            MetadataSnapshot.Database database,
            MetadataSnapshot.Schema schema,
            MetadataSnapshot.Table table) {
        String qualified = qualified(database.name(), schema.name(), table.name());
        String sourceRef = bounded(
                "metadata:" + session.id() + "#" + qualified, 1_000);
        String definition = definition(table);
        String lineageJson = serializeLineage(session, source, database,
                schema, table);
        AiKnowledgeAsset asset = new AiKnowledgeAsset();
        asset.setWorkspaceId(session.workspaceId());
        asset.setType(KnowledgeAssetType.TABLE_NOTE);
        asset.setStatus(KnowledgeAssetStatus.DRAFT);
        asset.setTitle(bounded("元数据草稿 · " + qualified, 200));
        asset.setDefinition(definition);
        asset.setDbType(source.dbType());
        asset.setGrantedSourceName(session.grantedSourceName());
        asset.setDefaultDatabase(database.name());
        asset.setKeywords(bounded(String.join(",",
                database.name(), schema.name(), table.name(), table.type()),
                1_000));
        asset.setSourceRef(sourceRef);
        asset.setIngestionSource("METADATA_SNAPSHOT");
        asset.setLineageJson(lineageJson);
        asset.setCreatedBy(user.getId());
        asset.setContentSha256(AiDigest.sha256(String.join("\n",
                asset.getType().name(), asset.getTitle(), definition,
                source.dbType(), session.grantedSourceName(),
                database.name(), asset.getKeywords(), sourceRef,
                lineageJson, asset.getIngestionSource())));
        return asset;
    }

    private String serializeLineage(
            MetadataSnapshotSessionStore.SnapshotSession session,
            MetadataSnapshot.DataSource source,
            MetadataSnapshot.Database database,
            MetadataSnapshot.Schema schema,
            MetadataSnapshot.Table table) {
        try {
            Map<String, Object> lineage = new LinkedHashMap<>();
            lineage.put("kind", "OBSERVED_METADATA_PARENTAGE");
            lineage.put("snapshotId", session.id());
            lineage.put("dataSource", source.name());
            lineage.put("database", database.name());
            lineage.put("schema", schema.name());
            lineage.put("table", table.name());
            lineage.put("capturedAt", session.snapshot().capturedAt());
            lineage.put("note", "仅表示快照层级，不代表业务或字段加工血缘");
            return objectMapper.writeValueAsString(lineage);
        } catch (Exception exception) {
            throw new IllegalStateException("元数据草稿血缘无法序列化", exception);
        }
    }

    private static String definition(MetadataSnapshot.Table table) {
        StringBuilder builder = new StringBuilder();
        builder.append("这是从授权元数据快照生成的建议草稿，必须经人工核验后才可成为可信知识。\n")
                .append("禁止仅凭字段名推断业务口径或实际数据分布。\n")
                .append("对象类型: ").append(table.type()).append('\n');
        if (!table.remarks().isBlank()) {
            builder.append("技术备注: ").append(table.remarks()).append('\n');
        }
        builder.append("字段结构:\n");
        int count = 0;
        for (MetadataSnapshot.Column column : table.columns()) {
            if (count++ >= 256 || builder.length() > 18_000) {
                builder.append("- 其余字段因草稿长度上限省略\n");
                break;
            }
            builder.append("- ").append(column.name()).append(": ")
                    .append(column.typeName().isBlank()
                            ? column.dataType() : column.typeName())
                    .append(column.nullable() ? ", 可空" : ", 非空");
            if (!column.remarks().isBlank()) {
                builder.append(", 技术备注=").append(column.remarks());
            }
            builder.append('\n');
        }
        if (!table.primaryKeyColumns().isEmpty()) {
            builder.append("元数据主键: ")
                    .append(String.join(", ", table.primaryKeyColumns()))
                    .append('\n');
        }
        return bounded(builder.toString(), 20_000);
    }

    private static String qualified(
            String database, String schema, String table) {
        List<String> parts = new ArrayList<>();
        if (database != null && !database.isBlank()) {
            parts.add(database);
        }
        if (schema != null && !schema.isBlank()) {
            parts.add(schema);
        }
        parts.add(table);
        return String.join(".", parts);
    }

    private static String requireText(
            String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(
                    field + "必填且长度不得超过 " + maximum);
        }
        return value.trim();
    }

    private static String bounded(String value, int maximum) {
        String safe = value == null ? "" : value;
        return safe.length() <= maximum ? safe : safe.substring(0, maximum);
    }
}
