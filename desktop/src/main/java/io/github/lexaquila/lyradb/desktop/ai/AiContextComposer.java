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
        String metadata = metadataAttached
                && metadataMarkdown != null && !metadataMarkdown.isBlank()
                ? metadataMarkdown.trim() : "（未附加）";
        return """
                ## 用户手工输入的结构与业务上下文
                %s

                ## 用户手动采集并确认附加的元数据
                %s
                """.formatted(manual, metadata);
    }
}
