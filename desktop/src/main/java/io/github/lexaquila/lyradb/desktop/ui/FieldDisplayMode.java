package io.github.lexaquila.lyradb.desktop.ui;

/**
 * 字段标题展示方式。切换只影响界面文字，不改变真实 SQL 标识符。
 */
enum FieldDisplayMode {
    PHYSICAL("字段名"),
    COMMENT("注释名"),
    BOTH("字段名 + 注释");

    private final String label;

    FieldDisplayMode(String label) {
        this.label = label;
    }

    String title(String physicalName, String remarks) {
        String physical = physicalName == null ? "" : physicalName;
        String comment = remarks == null ? "" : remarks.trim();
        if (comment.isEmpty()) {
            return physical;
        }
        return switch (this) {
            case PHYSICAL -> physical;
            case COMMENT -> comment;
            case BOTH -> physical + " · " + comment;
        };
    }

    @Override
    public String toString() {
        return label;
    }
}
