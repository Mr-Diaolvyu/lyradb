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
                .contains("订单状态=PAID", "（未附加）")
                .doesNotContain("secret-metadata");
        assertThat(attached)
                .contains("订单状态=PAID", "confirmed-metadata");
    }

    @Test
    void emptyManualContextMustBeMarkedAsNotProvided() {
        assertThat(AiContextComposer.compose("", "", false))
                .contains("用户手工输入", "（未提供）", "（未附加）");
    }
}
