package io.github.lexaquila.lyradb.desktop.ai;

/**
 * 组合 AI 请求中的手工上下文与一次性元数据附件。
 */
public final class AiContextComposer {

    private AiContextComposer() {
    }

    public static String compose(String manualContext,
            String metadataMarkdown, boolean metadataAttached) {
        String manual = manualContext == null || manualContext.isBlank()
                ? "（未提供）" : manualContext.trim();
        boolean included = metadataAttached
                && metadataMarkdown != null && !metadataMarkdown.isBlank();
        String metadata = included ? metadataMarkdown.trim() : "（未附加）";
        return """
                ## 用户明确输入的结构与业务口径
                %s

                ## 用户确认附加的只读元数据
                %s

                ## 上下文使用边界
                - 元数据状态：%s
                - 只能依据以上已提供内容回答；未提供的表、字段、注释、口径和血缘不得推断为事实。
                """.formatted(manual, metadata,
                        included ? "已附加" : "未附加");
    }
}
