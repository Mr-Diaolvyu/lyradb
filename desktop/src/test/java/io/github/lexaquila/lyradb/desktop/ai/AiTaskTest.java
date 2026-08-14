package io.github.lexaquila.lyradb.desktop.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiTaskTest {

    @Test
    void findTableMustHaveStableSafeContract() {
        assertThat(AiTask.valueOf("FIND_TABLE")).isSameAs(AiTask.FIND_TABLE);
        assertThat(AiTask.FIND_TABLE.displayName()).isEqualTo("智能找表");
        assertThat(AiTask.FIND_TABLE.requiresMetadata()).isTrue();
        assertThat(AiTask.FIND_TABLE.requiresRequest()).isTrue();
        assertThat(AiTask.FIND_TABLE.instruction())
                .contains("明确附加", "禁止编造", "表", "注释", "血缘");
    }

    @Test
    void metadataTasksMustDeclareTheirEvidenceBoundary() {
        assertThat(AiTask.EXPLAIN_TABLE.requiresMetadata()).isTrue();
        assertThat(AiTask.LINEAGE_IMPACT.requiresMetadata()).isTrue();
        assertThat(AiTask.DATA_QUALITY.requiresMetadata()).isTrue();
        assertThat(AiTask.LINEAGE_IMPACT.instruction())
                .contains("无法判断", "禁止编造依赖关系");
        assertThat(AiTask.DATA_QUALITY.instruction())
                .contains("非空率", "枚举分布", "不得声称已经检查真实数据");
    }

    @Test
    void everyTaskMustProvideAVisibleInputHint() {
        assertThat(AiTask.values())
                .allSatisfy(task -> assertThat(task.requestHint()).isNotBlank());
    }
}
