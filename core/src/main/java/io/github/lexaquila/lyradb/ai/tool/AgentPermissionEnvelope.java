package io.github.lexaquila.lyradb.ai.tool;

import java.time.Instant;
import java.util.Set;

/**
 * 单次 Agent 运行的最小权限交集。包络不可扩权，工具执行前必须逐次校验。
 */
public record AgentPermissionEnvelope(
        String principalId,
        String workspaceId,
        String grantId,
        Set<String> allowedTools,
        Set<String> allowedResources,
        int maxRows,
        long maxEstimatedCostMicros,
        Instant expiresAt,
        AgentRiskLevel maxRisk) {

    public AgentPermissionEnvelope {
        principalId = requireText(principalId, "主体 ID", 128);
        workspaceId = requireText(workspaceId, "工作空间 ID", 128);
        grantId = requireText(grantId, "授权 ID", 128);
        allowedTools = normalizedSet(allowedTools, "工具白名单");
        allowedResources = normalizedSet(allowedResources, "资源白名单");
        if (maxRows < 0) {
            throw new IllegalArgumentException("最大行数不能为负数");
        }
        if (maxEstimatedCostMicros < 0) {
            throw new IllegalArgumentException("最大预估成本不能为负数");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("权限包络过期时间不能为空");
        }
        if (maxRisk == null) {
            throw new IllegalArgumentException("权限包络风险上限不能为空");
        }
    }

    public PolicyDecision authorize(
            AgentToolDefinition definition, AgentToolCall call, Instant now) {
        if (definition == null || call == null || now == null) {
            return PolicyDecision.deny("INVALID_REQUEST", "工具授权请求不完整");
        }
        if (!definition.name().equals(call.toolName())) {
            return PolicyDecision.deny("TOOL_CONTRACT_MISMATCH", "工具调用与声明不一致");
        }
        if (!now.isBefore(expiresAt)) {
            return PolicyDecision.deny("ENVELOPE_EXPIRED", "任务权限包络已过期");
        }
        if (!definition.effect().riskLevel().atMost(maxRisk)) {
            return PolicyDecision.deny("RISK_EXCEEDED", "工具风险超过任务上限");
        }
        if (!allowedTools.contains(call.toolName())) {
            return PolicyDecision.deny("TOOL_NOT_ALLOWED", "工具不在任务白名单内");
        }
        if (!allowedResources.containsAll(call.resources())) {
            return PolicyDecision.deny("RESOURCE_NOT_ALLOWED", "工具引用了任务范围外资源");
        }
        if (call.requestedRows() > maxRows) {
            return PolicyDecision.deny("ROW_LIMIT_EXCEEDED", "工具请求行数超过任务上限");
        }
        if (call.estimatedCostMicros() > maxEstimatedCostMicros) {
            return PolicyDecision.deny("COST_LIMIT_EXCEEDED", "工具预估成本超过任务预算");
        }
        return PolicyDecision.allow();
    }

    private static Set<String> normalizedSet(Set<String> values, String field) {
        if (values == null) {
            return Set.of();
        }
        if (values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(field + "不得包含空值");
        }
        return values.stream().map(String::trim)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + "必填且长度不得超过 " + maxLength);
        }
        return value.trim();
    }
}
