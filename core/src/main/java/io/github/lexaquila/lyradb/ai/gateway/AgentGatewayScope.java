package io.github.lexaquila.lyradb.ai.gateway;

/** Agent Gateway 细粒度白名单权限；不存在通配或任意工具权限。 */
public enum AgentGatewayScope {
    KNOWLEDGE_READ("knowledge.read"),
    READ_PLAN("read.plan"),
    READ_EXECUTE("read.execute"),
    MAXCOMPUTE_ANALYZE("maxcompute.analyze");

    private final String wireName;

    AgentGatewayScope(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
