package io.github.lexaquila.lyradb.ai.tool;

/** 对模型和宿主同时可见的稳定工具契约。 */
public record AgentToolDefinition(
        String name,
        String version,
        String description,
        AgentToolEffect effect,
        boolean idempotent,
        boolean cancellable) {

    public AgentToolDefinition {
        name = requireText(name, "工具名", 120);
        version = requireText(version, "工具版本", 32);
        description = requireText(description, "工具说明", 500);
        if (effect == null) {
            throw new IllegalArgumentException("工具影响类型不能为空");
        }
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + "必填且长度不得超过 " + maxLength);
        }
        return value.trim();
    }
}
