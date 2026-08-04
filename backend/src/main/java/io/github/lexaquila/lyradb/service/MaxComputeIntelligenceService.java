package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.ai.AiDigest;
import io.github.lexaquila.lyradb.ai.AiFeature;
import io.github.lexaquila.lyradb.ai.maxcompute.MaxComputePartitionInspector;
import io.github.lexaquila.lyradb.ai.model.AiContextReceipt;
import io.github.lexaquila.lyradb.ai.model.AiEvidenceType;
import io.github.lexaquila.lyradb.ai.model.EvidenceRef;
import io.github.lexaquila.lyradb.ai.model.EvidenceTrustLevel;
import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.model.dto.MaxComputeDiagnosticRequest;
import io.github.lexaquila.lyradb.model.dto.MaxComputeDiagnosticView;
import io.github.lexaquila.lyradb.model.dto.MaxComputePartitionCheckView;
import io.github.lexaquila.lyradb.model.dto.MaxComputePreflightRequest;
import io.github.lexaquila.lyradb.model.dto.MaxComputePreflightView;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.model.entity.Grant;
import io.github.lexaquila.lyradb.model.entity.User;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** MaxCompute 专项分区/成本预检与任务诊断。 */
@Service
public class MaxComputeIntelligenceService {

    private final GrantService grantService;
    private final DataSourceService dataSourceService;
    private final EnterpriseQueryService enterpriseQueryService;
    private final SecurityUtil securityUtil;
    private final AiFeatureGate featureGate;
    private final AppProperties properties;
    private final MaxComputePreflightStore preflightStore;
    private final AuditService auditService;
    private final MaxComputeLiveEvidenceService liveEvidenceService;

    public MaxComputeIntelligenceService(
            GrantService grantService,
            DataSourceService dataSourceService,
            EnterpriseQueryService enterpriseQueryService,
            SecurityUtil securityUtil,
            AiFeatureGate featureGate,
            AppProperties properties,
            MaxComputePreflightStore preflightStore,
            AuditService auditService,
            MaxComputeLiveEvidenceService liveEvidenceService) {
        this.grantService = grantService;
        this.dataSourceService = dataSourceService;
        this.enterpriseQueryService = enterpriseQueryService;
        this.securityUtil = securityUtil;
        this.featureGate = featureGate;
        this.properties = properties;
        this.preflightStore = preflightStore;
        this.auditService = auditService;
        this.liveEvidenceService = liveEvidenceService;
    }

    public MaxComputePreflightView preflight(
            String workspaceId, MaxComputePreflightRequest request) {
        featureGate.requireEnabled(AiFeature.MAXCOMPUTE_AGENT);
        if (request == null) {
            throw new IllegalArgumentException("MaxCompute 预检请求不能为空");
        }
        String workspace = requireText(workspaceId, "工作空间 ID", 36);
        String sourceName = requireText(
                request.getGrantedSourceName(), "逻辑数据源", 100);
        String sql = requireText(request.getSql(), "SQL", 50_000);
        User user = securityUtil.requireCurrentUser();
        Grant grant = grantService.resolveForUser(
                user.getId(), workspace, sourceName);
        DataSource source = dataSourceService.getEntity(
                grant.getDataSourceId());
        requireMaxCompute(workspace, source);
        SqlParseUtil.Analysis authorized =
                enterpriseQueryService.authorizeReadOnly(
                        grant, sql, request.getDefaultDatabase());

        MaxComputeLiveEvidenceService.LiveEvidence live =
                MaxComputeLiveEvidenceService.disabled();
        if (properties.getAi().isMaxComputeLiveEvidenceEnabled()) {
            live = liveEvidenceService.inspect(
                    source, Set.copyOf(authorized.tables()), sql);
        }
        boolean liveRequired = properties.getAi()
                .isMaxComputeLiveEvidenceRequired();
        Map<String, List<String>> partitionEvidence =
                live.partitionColumns().isEmpty()
                        ? request.getRequiredPartitionColumns()
                        : live.partitionColumns();
        String evidenceMode = evidenceMode(live, liveRequired);
        var inspection = MaxComputePartitionInspector.inspect(
                sql, partitionEvidence);
        List<MaxComputePartitionCheckView> checks = inspection
                .partitionChecks().stream()
                .map(item -> new MaxComputePartitionCheckView(
                        item.table(), item.requiredColumns(),
                        item.matchedColumns(), item.covered()))
                .toList();
        boolean partitionsCovered = !checks.isEmpty()
                && checks.stream().allMatch(
                MaxComputePartitionCheckView::covered);

        Long declaredInputBytes = request.getEstimatedInputBytes();
        if (declaredInputBytes != null && declaredInputBytes < 0) {
            throw new IllegalArgumentException("预估扫描字节数不能为负数");
        }
        Long declaredCost = request.getEstimatedCostMicros();
        if (declaredCost != null && declaredCost < 0) {
            throw new IllegalArgumentException("预估成本微单位不能为负数");
        }
        Long inputBytes = live.estimatedInputBytes() != null
                ? live.estimatedInputBytes() : declaredInputBytes;
        Long selectedCost = live.estimatedCostMicros() != null
                ? live.estimatedCostMicros() : declaredCost;
        if (selectedCost == null) {
            throw new IllegalArgumentException(
                    "实时 COST SQL 未给出明确微单位时必须提供非负声明成本");
        }
        long estimatedCost = selectedCost;
        long budget = properties.getAi()
                .getReadAgentMaxEstimatedCostMicros();
        boolean costCovered = estimatedCost <= budget;
        boolean liveRequirementCovered = !liveRequired
                || live.completeForDecision();
        boolean eligible = partitionsCovered && costCovered
                && liveRequirementCovered;
        String costStatus = costCovered
                ? "WITHIN_BUDGET" : "OVER_BUDGET";
        List<String> warnings = new ArrayList<>(live.warnings());
        if (!partitionsCovered) {
            warnings.add("所有引用表都必须声明并命中必需分区列");
        }
        if (!costCovered) {
            warnings.add("预估成本超过服务端任务预算");
        }
        if (!liveRequirementCovered) {
            warnings.add("当前环境要求完整实时分区、EXPLAIN 与 cost_micros 证据");
        }
        if (live.partitionColumns().isEmpty()) {
            warnings.add("分区列使用调用方声明值，未冒充实时观测");
        }
        if (live.estimatedCostMicros() == null) {
            warnings.add("成本使用调用方声明微单位，未对 COST SQL 未知单位做换算");
        }
        warnings.add("执行前仍需 Grant、AST、单次令牌与计划摘要再授权");
        warnings.add("本预检未读取业务数据，不代表业务口径或数据质量已核验");

        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(
                properties.getAi().getReadAgentPlanTtlSeconds());
        String sqlSha = AiDigest.sha256(sql);
        String token = null;
        if (eligible) {
            token = preflightStore.issue(
                    workspace, user.getId(), grant.getId(), sqlSha,
                    estimatedCost, expiresAt).tokenSha256();
        }
        String decision = eligible ? "ALLOW_GOVERNED_PLAN"
                : "DENY_SPECIALIZED_PLAN";
        List<EvidenceRef> evidence = new ArrayList<>();
        evidence.add(new EvidenceRef(
                "mc-declaration-" + UUID.randomUUID(),
                AiEvidenceType.SYSTEM_CONSTRAINT,
                "调用方声明的 MaxCompute 分区与成本",
                "request:maxcompute-preflight",
                AiDigest.sha256(String.valueOf(
                        request.getRequiredPartitionColumns())
                        + "\n" + declaredCost + "\n" + declaredInputBytes),
                now, EvidenceTrustLevel.GENERATED));
        evidence.add(new EvidenceRef(
                "mc-policy-" + UUID.randomUUID(),
                AiEvidenceType.POLICY_DECISION,
                "MaxCompute 专项预检决定",
                "policy:maxcompute-v1",
                AiDigest.sha256(decision + "\n" + sqlSha),
                now, EvidenceTrustLevel.OBSERVED));
        if (!"DISABLED".equals(live.status())
                && !"UNAVAILABLE".equals(live.status())) {
            evidence.add(new EvidenceRef(
                    "mc-live-" + UUID.randomUUID(),
                    AiEvidenceType.TOOL_RESULT,
                    "MaxCompute 实时分区、EXPLAIN 与 COST SQL 摘要",
                    "maxcompute:live-preflight",
                    live.digest(), now, EvidenceTrustLevel.OBSERVED));
        }
        List<String> omitted = new ArrayList<>();
        if (live.partitionColumns().isEmpty()) {
            omitted.add("live-partition-metadata-not-read");
        }
        if (live.explainSha256() == null) {
            omitted.add("live-explain-not-read");
        }
        if (live.estimatedCostMicros() == null) {
            omitted.add("live-cost-micros-not-observed");
        }
        omitted.add("sample-data-not-read");
        AiContextReceipt receipt = AiContextReceipt.create(
                UUID.randomUUID().toString(), workspace,
                "MAXCOMPUTE_PREFLIGHT", null, null, now,
                evidence,
                List.of("readonly-ast", "grant-resource-envelope",
                        "required-partition-columns", "cost-budget",
                        "single-use-preflight", evidenceMode), omitted);
        auditService.recordCurrent(workspace,
                "AI_MAXCOMPUTE_PREFLIGHT", source.getId(),
                sourceName, eligible,
                eligible ? null : String.join("；", warnings));
        return new MaxComputePreflightView(
                eligible, decision, token,
                eligible ? expiresAt : null,
                inspection.analysis().tables(), evidenceMode, live.view(),
                checks, inputBytes, estimatedCost, budget,
                costStatus, warnings, receipt);
    }

    public MaxComputeDiagnosticView diagnose(
            String workspaceId, MaxComputeDiagnosticRequest request) {
        featureGate.requireEnabled(AiFeature.MAXCOMPUTE_AGENT);
        if (request == null) {
            throw new IllegalArgumentException("任务诊断请求不能为空");
        }
        String workspace = requireText(workspaceId, "工作空间 ID", 36);
        String status = optionalText(request.getTaskStatus(), 32);
        status = status == null ? "UNKNOWN" : status.toUpperCase(Locale.ROOT);
        if (!Set.of("RUNNING", "SUCCESS", "FAILED", "CANCELLED", "UNKNOWN")
                .contains(status)) {
            throw new IllegalArgumentException("不支持的任务状态: " + status);
        }
        String code = optionalText(request.getErrorCode(), 100);
        String message = optionalText(request.getErrorMessage(), 2_000);
        Diagnosis diagnosis = diagnosis(status, code, message);
        Instant now = Instant.now();
        String inputDigest = AiDigest.sha256(
                status + "\n" + safe(code) + "\n" + safe(message));
        AiContextReceipt receipt = AiContextReceipt.create(
                UUID.randomUUID().toString(), workspace,
                "MAXCOMPUTE_TASK_DIAGNOSIS", null, null, now,
                List.of(new EvidenceRef(
                        "mc-task-" + UUID.randomUUID(),
                        AiEvidenceType.TOOL_RESULT,
                        "调用方提供的任务状态摘要",
                        "request:maxcompute-task-status",
                        inputDigest, now, EvidenceTrustLevel.GENERATED)),
                List.of("deterministic-diagnosis",
                        "no-automatic-retry", "message-not-persisted"),
                List.of("live-task-status-not-read",
                        "task-log-not-read"));
        auditService.recordCurrent(workspace,
                "AI_MAXCOMPUTE_DIAGNOSE", null, null,
                true, null);
        return new MaxComputeDiagnosticView(
                status, diagnosis.category(), diagnosis.summary(),
                diagnosis.recommendations(), false, receipt);
    }

    private static Diagnosis diagnosis(
            String status, String code, String message) {
        String text = (safe(code) + " " + safe(message))
                .toLowerCase(Locale.ROOT);
        if ("SUCCESS".equals(status)) {
            return new Diagnosis("SUCCESS", "任务已成功",
                    List.of("核对输出行数、分区范围和 Context Receipt"));
        }
        if ("RUNNING".equals(status)) {
            return new Diagnosis("RUNNING", "任务仍在运行",
                    List.of("继续观察服务端任务状态", "不要并发重复提交同一计划"));
        }
        if ("CANCELLED".equals(status)) {
            return new Diagnosis("CANCELLED", "任务已取消",
                    List.of("确认取消原因后重新生成预检与计划"));
        }
        if (containsAny(text, "signature", "accesskey", "unauthorized", "403")) {
            return new Diagnosis("AUTHENTICATION", "身份签名或访问凭据异常",
                    List.of("由管理员核对 AccessKey、Endpoint 与系统时间",
                            "不要在诊断请求中粘贴密钥"));
        }
        if (containsAny(text, "project", "namespace")
                && containsAny(text, "not exist", "not found", "unknown")) {
            return new Diagnosis("NAMESPACE", "执行 Project 或命名空间不可用",
                    List.of("核对授权数据源的执行 Project 与默认 Schema"));
        }
        if (containsAny(text, "partition", "full scan")) {
            return new Diagnosis("PARTITION", "分区裁剪不足或分区声明异常",
                    List.of("重新读取并核验分区元数据",
                            "在 WHERE 中使用明确表别名限定分区列"));
        }
        if (containsAny(text, "quota", "resource", "out of memory")) {
            return new Diagnosis("CAPACITY", "资源或配额不足",
                    List.of("缩小扫描范围并降低并发", "由平台侧核对配额"));
        }
        if (containsAny(text, "timeout", "timed out")) {
            return new Diagnosis("TIMEOUT", "任务超时",
                    List.of("检查分区裁剪与扫描量", "重新预检后由用户决定是否重试"));
        }
        return new Diagnosis("UNKNOWN", "现有摘要不足以确定根因",
                List.of("补充脱敏后的错误码和错误摘要",
                        "人工查看 MaxCompute 控制台任务日志"));
    }

    private static void requireMaxCompute(
            String workspaceId, DataSource source) {
        if (!workspaceId.equals(source.getWorkspaceId())) {
            throw new IllegalStateException("数据源不属于当前工作空间");
        }
        if (!"MAXCOMPUTE".equalsIgnoreCase(source.getDbType())) {
            throw new IllegalArgumentException(
                    "该逻辑数据源不是 MaxCompute");
        }
    }

    private static String evidenceMode(
            MaxComputeLiveEvidenceService.LiveEvidence live,
            boolean required) {
        if (live.completeForDecision()) {
            return "LIVE_COMPLETE";
        }
        if (required) {
            return "LIVE_REQUIRED_INCOMPLETE";
        }
        if ("PARTIAL".equals(live.status())) {
            return "LIVE_PARTIAL_WITH_DECLARED_FALLBACK";
        }
        return "DECLARED_ONLY";
    }

    private static boolean containsAny(
            String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String requireText(
            String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + "必填且长度不得超过 " + maxLength);
        }
        return value.trim();
    }

    private static String optionalText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(
                    "文本长度不得超过 " + maxLength);
        }
        return value.trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private record Diagnosis(
            String category,
            String summary,
            List<String> recommendations) {
    }
}
