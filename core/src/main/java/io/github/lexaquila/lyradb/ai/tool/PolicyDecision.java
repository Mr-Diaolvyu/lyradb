package io.github.lexaquila.lyradb.ai.tool;

/** 权限包络的机器可判定结果。 */
public record PolicyDecision(boolean allowed, String code, String reason) {

    public PolicyDecision {
        if (code == null || code.isBlank() || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("策略决策必须包含代码和原因");
        }
        code = code.trim();
        reason = reason.trim();
    }

    public static PolicyDecision allow() {
        return new PolicyDecision(true, "ALLOW", "工具调用位于权限包络内");
    }

    public static PolicyDecision deny(String code, String reason) {
        return new PolicyDecision(false, code, reason);
    }
}
