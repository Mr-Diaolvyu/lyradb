package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.lineage.DataWorksLineageClient;
import io.github.lexaquila.lyradb.model.dto.ErDiagram;
import io.github.lexaquila.lyradb.model.entity.Grant;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** DataWorks 真实血缘；在遍历前过滤授权，并在返回前复检授权快照。 */
@Service
public class EnterpriseLineageService {
    private final SecurityUtil security;
    private final GrantService grants;
    private final DataSourceService sources;
    private final CredentialService credentials;
    private final EnterpriseMetadataCatalogService metadata;
    private final ApprovalSecurityContextService contexts;
    private final AuditService audit;
    private final ObjectMapper json;

    public EnterpriseLineageService(SecurityUtil security, GrantService grants,
            DataSourceService sources, CredentialService credentials,
            EnterpriseMetadataCatalogService metadata, ApprovalSecurityContextService contexts,
            AuditService audit, ObjectMapper json) {
        this.security = security;
        this.grants = grants;
        this.sources = sources;
        this.credentials = credentials;
        this.metadata = metadata;
        this.contexts = contexts;
        this.audit = audit;
        this.json = json;
    }

    public record Request(String grantedSourceName, String schema, List<String> tables,
                          String column, String direction, Integer maxDepth, Integer maxNodes) {}

    public ErDiagram explore(Request request) throws Exception {
        if (request == null || request.schema() == null || request.schema().isBlank()
                || request.tables() == null || request.tables().isEmpty() || request.tables().size() > 20) {
            throw new IllegalArgumentException("血缘必须指定 Project 和 1–20 张根表");
        }
        boolean columnMode = request.column() != null && !request.column().isBlank();
        if (columnMode && request.tables().size() != 1) {
            throw new IllegalArgumentException("字段血缘必须选择一张根表和一个字段");
        }
        var direction = request.direction() == null ? DataWorksLineageClient.Direction.BOTH
                : DataWorksLineageClient.Direction.valueOf(request.direction());
        String user = security.requireCurrentUser().getId();
        String workspace = security.requireCurrentWorkspace();
        Grant grant = grants.resolveForUser(user, workspace, request.grantedSourceName());
        var source = sources.getEntity(grant.getDataSourceId());
        if (!workspace.equals(source.getWorkspaceId()) || !workspace.equals(grant.getWorkspaceId())) {
            throw new AccessDeniedException("数据源不属于当前工作空间");
        }
        if (!"MAXCOMPUTE".equalsIgnoreCase(source.getDbType())) {
            throw new IllegalArgumentException("DataWorks 血缘仅适用于 MaxCompute");
        }
        String fingerprint = contexts.fingerprint(grant);
        // 复用企业目录的 Schema、白名单、黑名单规则，不因 API 返回关系而扩展权限。
        var catalog = metadata.catalog(request.grantedSourceName(), false);
        Set<String> allowed = new HashSet<>();
        catalog.getTables().forEach(table -> allowed.add(table.getSchema() + "." + table.getName()));
        List<String> roots = new ArrayList<>();
        for (String table : request.tables()) {
            if (!allowed.contains(request.schema() + "." + table)) {
                throw new AccessDeniedException("根表不存在或不在授权范围内");
            }
            if (columnMode && metadata.columns(request.grantedSourceName(), request.schema(), table)
                    .stream().noneMatch(column -> request.column().equals(column.getName()))) {
                throw new IllegalArgumentException("根字段不存在");
            }
            roots.add(columnMode ? DataWorksLineageClient.columnEntityId(request.schema(), table, request.column())
                    : DataWorksLineageClient.tableEntityId(request.schema(), table));
        }
        boolean succeeded = false;
        try {
            Map<String, Object> params = credentials.decryptSensitiveFields(json.readValue(
                    source.getConnectionParamsJson(), new TypeReference<Map<String, Object>>() {}));
            DataWorksLineageClient.LineageResult result;
            try {
                result = client(params).explore(roots, direction,
                        request.maxDepth() == null ? 2 : request.maxDepth(),
                        request.maxNodes() == null ? 120 : Math.min(120, request.maxNodes()),
                        entity -> entity.id().startsWith(columnMode ? "maxcompute-column:::" : "maxcompute-table:::")
                                && allowed.contains(entity.project() + "." + entity.table()));
            } catch (IllegalArgumentException exception) {
                throw exception;
            } catch (Exception exception) {
                // SDK 原始错误可能带签名请求或凭据，不返回给浏览器，也不落入业务日志。
                throw new IllegalStateException("DataWorks 血缘探查失败，请检查官方 Endpoint、RAM 权限及血缘服务状态");
            }
            Grant current = grants.resolveForUser(user, workspace, request.grantedSourceName());
            if (!Objects.equals(fingerprint, contexts.fingerprint(current))) {
                throw new AccessDeniedException("授权已变化，请重新探查血缘");
            }
            ErDiagram diagram = new ErDiagram();
            diagram.setSourceName(request.grantedSourceName());
            diagram.setDbType("MAXCOMPUTE");
            diagram.setSchema(request.schema());
            diagram.setTruncated(catalog.isTruncated() || result.graph().truncated());
            for (var table : result.graph().tables()) {
                var node = new ErDiagram.Table(table.schema() + "." + table.name(), table.schema());
                for (var column : table.columns()) {
                    node.getColumns().add(column.name());
                    node.getColumnDetails().add(new ErDiagram.Column(column.name(), column.typeName(), column.remarks(), false));
                }
                diagram.getTables().add(node);
            }
            result.graph().relations().forEach(edge -> diagram.getEdges().add(
                    new ErDiagram.Edge(edge.from(), edge.to(), edge.fromColumn(), edge.toColumn())));
            succeeded = true;
            return diagram;
        } finally {
            audit.recordCurrent(workspace, "METADATA_LINEAGE", source.getId(), request.grantedSourceName(),
                    succeeded, succeeded ? null : "血缘探查未完成");
        }
    }

    DataWorksLineageClient client(Map<String, Object> params) throws Exception {
        return DataWorksLineageClient.fromParameters(params);
    }
}
