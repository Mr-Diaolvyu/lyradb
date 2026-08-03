package io.github.lexaquila.lyradb.desktop.ui;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 原生 SQL 编辑器补全弹层：关键字立即返回，表和字段在后台读取。
 */
final class SqlCompletionSupport {

    private static final List<String> COMMON_KEYWORDS = List.of(
            "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "IN", "LIKE",
            "BETWEEN", "IS NULL", "IS NOT NULL", "AS", "ORDER BY",
            "GROUP BY", "HAVING", "LIMIT", "OFFSET", "INSERT INTO",
            "VALUES", "UPDATE", "SET", "DELETE FROM", "CREATE TABLE",
            "ALTER TABLE", "DROP TABLE", "CREATE INDEX", "JOIN",
            "INNER JOIN", "LEFT JOIN", "RIGHT JOIN", "FULL JOIN", "ON",
            "UNION ALL", "DISTINCT", "CASE", "WHEN", "THEN", "ELSE",
            "END", "EXISTS", "COUNT", "SUM", "AVG", "MIN", "MAX",
            "SHOW TABLES", "DESCRIBE", "EXPLAIN", "WITH");
    private static final List<String> MAXCOMPUTE_KEYWORDS = List.of(
            "INSERT OVERWRITE TABLE", "PARTITION", "LIFECYCLE",
            "DISTRIBUTE BY", "SORT BY", "CLUSTER BY", "LATERAL VIEW");
    private static final List<String> CLICKHOUSE_KEYWORDS = List.of(
            "PREWHERE", "FINAL", "SAMPLE", "ARRAY JOIN", "FORMAT",
            "SETTINGS", "ENGINE");

    private final JTextArea editor;
    private final String dbType;
    private final CompletionProvider provider;
    private final DefaultListModel<Suggestion> model =
            new DefaultListModel<>();
    private final JList<Suggestion> list = new JList<>(model);
    private final JPopupMenu popup = new JPopupMenu();
    private final Timer autoTimer;
    private long generation;
    private SwingWorker<List<Suggestion>, Void> worker;
    private SqlCompletionContext visibleContext;

    SqlCompletionSupport(
            JTextArea editor, String dbType, CompletionProvider provider) {
        this.editor = editor;
        this.dbType = dbType == null ? "" : dbType;
        this.provider = provider;
        this.autoTimer = new Timer(260, event -> show(false));
        this.autoTimer.setRepeats(false);
        buildPopup();
        installBindings();
    }

    void dispose() {
        generation++;
        autoTimer.stop();
        if (worker != null && !worker.isDone()) {
            worker.cancel(true);
        }
        worker = null;
        visibleContext = null;
        popup.setVisible(false);
        model.clear();
    }

    private void buildPopup() {
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(36);
        list.setVisibleRowCount(8);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> valueList, Object value, int index,
                    boolean selected, boolean focused) {
                JLabel label = (JLabel) super.getListCellRendererComponent(
                        valueList, value, index, selected, focused);
                if (value instanceof Suggestion suggestion) {
                    label.setText("<html><b>"
                            + escape(suggestion.label()) + "</b>"
                            + (suggestion.detail().isBlank() ? ""
                            : " <span style='color:#8f94a8'>"
                            + escape(suggestion.detail()) + "</span></html>"));
                    label.setIcon(LyraIcons.of(switch (suggestion.kind()) {
                        case TABLE -> LyraIcons.Kind.TABLE;
                        case VIEW -> LyraIcons.Kind.VIEW;
                        case COLUMN -> LyraIcons.Kind.COLUMN;
                        case SCHEMA -> LyraIcons.Kind.SCHEMA;
                        case KEYWORD -> LyraIcons.Kind.SQL;
                    }, 14, selected
                            ? label.getForeground() : NativeTheme.MUTED));
                    label.setBorder(BorderFactory.createEmptyBorder(
                            2, 8, 2, 8));
                }
                return label;
            }
        });
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() == 2) {
                    acceptSelected();
                }
            }
        });

        JScrollPane scroll = UiKit.scroll(list);
        scroll.setPreferredSize(new Dimension(360, 290));
        scroll.setBorder(BorderFactory.createEmptyBorder());
        popup.setLayout(new BorderLayout());
        popup.setBorder(BorderFactory.createLineBorder(
                NativeTheme.BORDER));
        popup.add(scroll, BorderLayout.CENTER);
    }

    private void installBindings() {
        editor.getInputMap().put(KeyStroke.getKeyStroke(
                KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK),
                "sqlCompletion");
        editor.getActionMap().put("sqlCompletion", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                show(true);
            }
        });
        bindPopupKey(KeyEvent.VK_DOWN, "completionDown", 1);
        bindPopupKey(KeyEvent.VK_UP, "completionUp", -1);
        editor.getInputMap().put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                "completionEscape");
        editor.getActionMap().put("completionEscape", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                popup.setVisible(false);
            }
        });
        editor.getInputMap().put(
                KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0),
                "completionAccept");
        editor.getActionMap().put("completionAccept", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (popup.isVisible()) {
                    acceptSelected();
                } else {
                    editor.replaceSelection("    ");
                }
            }
        });

        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) {
                autoTimer.restart();
            }
            @Override public void removeUpdate(DocumentEvent event) {
                popup.setVisible(false);
            }
            @Override public void changedUpdate(DocumentEvent event) {
                popup.setVisible(false);
            }
        });
        editor.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent event) {
                if (!list.isFocusOwner()) {
                    popup.setVisible(false);
                }
            }
        });
    }

    private void bindPopupKey(int keyCode, String actionName, int delta) {
        editor.getInputMap().put(
                KeyStroke.getKeyStroke(keyCode, 0), actionName);
        editor.getActionMap().put(actionName, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (!popup.isVisible()) {
                    javax.swing.Action fallback =
                            editor.getActionMap().get(delta > 0
                                    ? "caret-down" : "caret-up");
                    if (fallback != null) {
                        fallback.actionPerformed(event);
                    }
                    return;
                }
                int size = model.size();
                if (size == 0) {
                    return;
                }
                int next = Math.max(0, Math.min(size - 1,
                        list.getSelectedIndex() + delta));
                list.setSelectedIndex(next);
                list.ensureIndexIsVisible(next);
            }
        });
    }

    private void show(boolean explicit) {
        if (!editor.isFocusOwner()) {
            return;
        }
        SqlCompletionContext context = SqlCompletionContext.at(
                editor.getText(), editor.getCaretPosition());
        if (!explicit && context.qualifier() == null
                && context.prefix().length() < 2) {
            popup.setVisible(false);
            return;
        }
        long request = ++generation;
        if (worker != null && !worker.isDone()) {
            worker.cancel(true);
        }
        visibleContext = context;
        replaceModel(keywordSuggestions(context.prefix()));
        showPopup();

        worker = new SwingWorker<>() {
            @Override
            protected List<Suggestion> doInBackground() throws Exception {
                return provider.complete(context);
            }

            @Override
            protected void done() {
                if (isCancelled() || request != generation
                        || visibleContext != context) {
                    return;
                }
                try {
                    List<Suggestion> merged = new ArrayList<>(
                            keywordSuggestions(context.prefix()));
                    merged.addAll(get());
                    replaceModel(deduplicate(merged));
                    showPopup();
                } catch (Exception ignored) {
                    // 元数据补全失败时仍保留关键字，不打断编辑。
                }
            }
        };
        worker.execute();
    }

    private List<Suggestion> keywordSuggestions(String prefix) {
        String normalized = prefix == null ? ""
                : prefix.toUpperCase(Locale.ROOT);
        List<String> source = new ArrayList<>(COMMON_KEYWORDS);
        if ("MAXCOMPUTE".equalsIgnoreCase(dbType)) {
            source.addAll(MAXCOMPUTE_KEYWORDS);
        } else if ("CLICKHOUSE".equalsIgnoreCase(dbType)) {
            source.addAll(CLICKHOUSE_KEYWORDS);
        }
        return source.stream()
                .filter(value -> normalized.isEmpty()
                        || value.startsWith(normalized))
                .map(value -> new Suggestion(
                        value, value, "关键字", Kind.KEYWORD))
                .limit(40)
                .toList();
    }

    private void replaceModel(List<Suggestion> values) {
        model.clear();
        values.stream()
                .sorted(Comparator
                        .comparing((Suggestion value) ->
                                value.kind() == Kind.KEYWORD ? 1 : 0)
                        .thenComparing(Suggestion::label,
                                String.CASE_INSENSITIVE_ORDER))
                .forEach(model::addElement);
        if (!model.isEmpty()) {
            list.setSelectedIndex(0);
        }
    }

    private void showPopup() {
        if (model.isEmpty() || !editor.isShowing()) {
            popup.setVisible(false);
            return;
        }
        try {
            Rectangle caret = editor.modelToView2D(
                    editor.getCaretPosition()).getBounds();
            popup.show(editor, caret.x,
                    caret.y + caret.height + 2);
        } catch (Exception ignored) {
            popup.setVisible(false);
        }
    }

    private void acceptSelected() {
        Suggestion selected = list.getSelectedValue();
        SqlCompletionContext context = visibleContext;
        if (selected == null || context == null) {
            return;
        }
        editor.select(context.replaceStart(), context.replaceEnd());
        editor.replaceSelection(selected.insertText());
        popup.setVisible(false);
        editor.requestFocusInWindow();
    }

    private static List<Suggestion> deduplicate(List<Suggestion> source) {
        Map<String, Suggestion> values = new LinkedHashMap<>();
        for (Suggestion suggestion : source) {
            values.putIfAbsent(suggestion.label()
                    .toLowerCase(Locale.ROOT), suggestion);
        }
        return List.copyOf(values.values());
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    @FunctionalInterface
    interface CompletionProvider {
        List<Suggestion> complete(SqlCompletionContext context)
                throws Exception;
    }

    enum Kind {
        KEYWORD,
        SCHEMA,
        TABLE,
        VIEW,
        COLUMN
    }

    record Suggestion(
            String label, String insertText, String detail, Kind kind) {
        Suggestion {
            label = label == null ? "" : label;
            insertText = insertText == null ? label : insertText;
            detail = detail == null ? "" : detail;
            kind = kind == null ? Kind.KEYWORD : kind;
        }
    }
}
