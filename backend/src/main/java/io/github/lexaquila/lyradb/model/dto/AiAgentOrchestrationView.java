package io.github.lexaquila.lyradb.model.dto;

import io.github.lexaquila.lyradb.ai.model.AiContextReceipt;
import io.github.lexaquila.lyradb.ai.model.EvidenceRef;

import java.util.List;

/** 受限模型编排结果：只回答，或返回等待人工确认的只读计划。 */
public record AiAgentOrchestrationView(
        String status,
        String answer,
        AiReadAgentPlanView plan,
        List<EvidenceRef> evidence,
        AiContextReceipt contextReceipt,
        List<AiAgentToolTraceView> toolTrace,
        int steps,
        String provider,
        String model,
        AiAgentUsageView usage) {

    public AiAgentOrchestrationView {
        evidence = List.copyOf(evidence == null ? List.of() : evidence);
        toolTrace = List.copyOf(toolTrace == null ? List.of() : toolTrace);
    }
}
