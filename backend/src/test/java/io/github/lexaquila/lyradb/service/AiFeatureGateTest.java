package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.ai.AiFeature;
import io.github.lexaquila.lyradb.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiFeatureGateTest {

    @Test
    void defaultsKeepExistingAskEnabledAndAdvancedFeaturesClosed() {
        AiFeatureGate gate = gate(new AppProperties());

        assertTrue(gate.isEnabled(AiFeature.ASK_LYRA));
        assertFalse(gate.isEnabled(AiFeature.KNOWLEDGE_CORE));
        assertFalse(gate.isEnabled(AiFeature.GOVERNED_READ_AGENT));
        assertFalse(gate.isEnabled(AiFeature.WRITE_AGENT));
    }

    @Test
    void writeAgentCannotBeEnabledInThreeX() {
        AppProperties properties = new AppProperties();
        properties.getAi().setWriteAgentEnabled(true);

        assertThrows(IllegalStateException.class, () -> gate(properties));
    }

    @Test
    void gatewayCannotBypassGovernedRuntime() {
        AppProperties properties = new AppProperties();
        properties.getAi().setAgentGatewayEnabled(true);

        assertThrows(IllegalStateException.class, () -> gate(properties));
    }

    @Test
    void governedReadCannotBypassAskLyra() {
        AppProperties properties = new AppProperties();
        properties.getAi().setAskLyraEnabled(false);
        properties.getAi().setGovernedReadAgentEnabled(true);

        assertThrows(IllegalStateException.class, () -> gate(properties));
    }

    @Test
    void teamLoopCannotBypassKnowledgeCore() {
        AppProperties properties = new AppProperties();
        properties.getAi().setTeamKnowledgeLoopEnabled(true);

        assertThrows(IllegalStateException.class, () -> gate(properties));
    }

    @Test
    void gatewayCannotBypassKnowledgeCore() {
        AppProperties properties = new AppProperties();
        properties.getAi().setGovernedReadAgentEnabled(true);
        properties.getAi().setAgentGatewayEnabled(true);

        assertThrows(IllegalStateException.class, () -> gate(properties));
    }

    @Test
    void privateModelRequiresExplicitExactHostAllowlist() {
        AppProperties properties = new AppProperties();
        properties.getAi().setPrivateModelEnabled(true);

        assertThrows(IllegalStateException.class, () -> gate(properties));
    }

    @Test
    void validPrivateModelAndExecutionSettingsPassValidation() {
        AppProperties properties = new AppProperties();
        properties.getAi().setPrivateModelEnabled(true);
        properties.getAi().setPrivateModelAllowedHosts(
                "model.internal.example");
        properties.getAi().setExecutionNodeId("lyradb-node-a");
        properties.getAi().setCancelPollIntervalMs(500);

        AiFeatureGate gate = gate(properties);

        assertTrue(gate.isEnabled(AiFeature.ASK_LYRA));
    }

    @Test
    void crossNodeCancellationPollingIsBounded() {
        AppProperties properties = new AppProperties();
        properties.getAi().setCancelPollIntervalMs(100);

        assertThrows(IllegalStateException.class, () -> gate(properties));
    }

    private static AiFeatureGate gate(AppProperties properties) {
        AiFeatureGate gate = new AiFeatureGate(properties);
        gate.validateConfiguration();
        return gate;
    }
}
