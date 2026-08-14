package io.github.lexaquila.lyradb.desktop.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiContextComposerTest {

    @Test
    void metadataMustOnlyBeIncludedAfterExplicitAttachment() {
        String detached = AiContextComposer.compose(
                "订单状态=PAID", "# secret-metadata", false);
        String attached = AiContextComposer.compose(
                "订单状态=PAID", "# confirmed-metadata", true);

        assertThat(detached)
                .contains("订单状态=PAID", "（未附加）", "元数据状态：未附加")
                .doesNotContain("secret-metadata");
        assertThat(attached)
                .contains("订单状态=PAID", "confirmed-metadata", "元数据状态：已附加");
    }

    @Test
    void emptyManualContextMustBeMarkedAsNotProvided() {
        assertThat(AiContextComposer.compose("", "", false))
                .contains("用户明确输入", "（未提供）", "（未附加）");
    }

    @Test
    void contextMustStateThatMissingMetadataCannotBeTreatedAsFact() {
        assertThat(AiContextComposer.compose(null, "# hidden", false))
                .contains("未提供的表、字段、注释、口径和血缘不得推断为事实")
                .doesNotContain("hidden");
    }

    @Test
    void blankMetadataMustNotBeReportedAsAttached() {
        assertThat(AiContextComposer.compose(null, "  ", true))
                .contains("元数据状态：未附加", "（未附加）");
    }
}
