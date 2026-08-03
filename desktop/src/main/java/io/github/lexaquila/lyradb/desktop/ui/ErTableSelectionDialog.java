package io.github.lexaquila.lyradb.desktop.ui;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 搜索并明确选择要进入 ER 图的表，避免默认扫描整个库。 */
final class ErTableSelectionDialog extends JDialog {

    private final Searcher searcher;
    private final JTextField searchField = new JTextField();
    private final DefaultListModel<ErDiagramMetadataLoader.TableChoice>
            resultModel = new DefaultListModel<>();
    private final DefaultListModel<ErDiagramMetadataLoader.TableChoice>
            selectedModel = new DefaultListModel<>();
    private final JList<ErDiagramMetadataLoader.TableChoice> results =
            new JList<>(resultModel);
    private final JList<ErDiagramMetadataLoader.TableChoice> selected =
            new JList<>(selectedModel);
    private final JLabel status = new JLabel("输入表名后搜索，不会扫描字段元数据");
    private final JLabel selectedCount = new JLabel();
    private final Map<String, ErDiagramMetadataLoader.TableChoice>
            selectedByKey = new LinkedHashMap<>();
    private final Timer debounce = new Timer(320, event -> search());
    private SwingWorker<List<ErDiagramMetadataLoader.TableChoice>, Void> worker;
    private long generation;
    private boolean confirmed;

    private ErTableSelectionDialog(
            Window owner,
            String sourceLabel,
            List<ErDiagramMetadataLoader.TableChoice> initial,
            Searcher searcher) {
        super(owner, "选择 ER 图表 · " + sourceLabel,
                ModalityType.APPLICATION_MODAL);
        this.searcher = searcher;
        debounce.setRepeats(false);
        if (initial != null) {
            initial.forEach(choice -> selectedByKey.put(
                    choice.key(), choice));
        }
        rebuildSelectedModel();
        buildUi();
        setMinimumSize(new Dimension(720, 500));
        setSize(860, 590);
        setLocationRelativeTo(owner);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                cancelWork();
            }
        });
        SwingUtilities.invokeLater(searchField::requestFocusInWindow);
    }

    static List<ErDiagramMetadataLoader.TableChoice> choose(
            Window owner,
            String sourceLabel,
            List<ErDiagramMetadataLoader.TableChoice> initial,
            Searcher searcher) {
        ErTableSelectionDialog dialog = new ErTableSelectionDialog(
                owner, sourceLabel, initial, searcher);
        dialog.setVisible(true);
        if (!dialog.confirmed) {
            return null;
        }
        return new ArrayList<>(dialog.selectedByKey.values());
    }

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBackground(NativeTheme.BACKGROUND);
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 12, 14));
        setContentPane(root);

        JPanel searchBar = new JPanel(new BorderLayout(8, 0));
        searchBar.setOpaque(false);
        searchField.putClientProperty("JTextField.placeholderText",
                "输入表名，例如 order、user、dws_630");
        searchField.putClientProperty("JTextField.leadingIcon",
                LyraIcons.of(LyraIcons.Kind.SEARCH, NativeTheme.MUTED));
        searchField.putClientProperty("JTextField.showClearButton", true);
        searchBar.add(searchField, BorderLayout.CENTER);
        JButton searchButton = UiKit.button(
                "搜索", LyraIcons.of(LyraIcons.Kind.SEARCH),
                UiKit.ButtonStyle.SECONDARY);
        searchButton.addActionListener(event -> search());
        searchBar.add(searchButton, BorderLayout.EAST);
        root.add(searchBar, BorderLayout.NORTH);

        configureList(results);
        configureList(selected);
        results.setSelectionMode(
                ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        selected.setSelectionMode(
                ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        JPanel availablePanel = listPanel("搜索结果", results);
        JPanel selectedPanel = listPanel("已选择", selected);
        JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, availablePanel, selectedPanel);
        split.setResizeWeight(0.54D);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setDividerSize(6);

        JPanel listArea = new JPanel(new BorderLayout(8, 8));
        listArea.setOpaque(false);
        listArea.add(split, BorderLayout.CENTER);
        JPanel transfer = new JPanel(new FlowLayout(
                FlowLayout.CENTER, 8, 0));
        transfer.setOpaque(false);
        JButton add = UiKit.button(
                "添加 →", LyraIcons.of(LyraIcons.Kind.TABLE),
                UiKit.ButtonStyle.SECONDARY);
        add.addActionListener(event -> addSelectedResults());
        JButton remove = UiKit.button(
                "移除", LyraIcons.of(LyraIcons.Kind.CLOSE),
                UiKit.ButtonStyle.GHOST);
        remove.addActionListener(event -> removeSelectedTables());
        transfer.add(add);
        transfer.add(remove);
        listArea.add(transfer, BorderLayout.SOUTH);
        root.add(listArea, BorderLayout.CENTER);

        results.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) {
                    addSelectedResults();
                }
            }
        });
        selected.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) {
                    removeSelectedTables();
                }
            }
        });

        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.setOpaque(false);
        JPanel messages = new JPanel(new BorderLayout(8, 0));
        messages.setOpaque(false);
        status.setFont(NativeTheme.FONT_CAPTION);
        status.setForeground(NativeTheme.MUTED);
        selectedCount.setFont(NativeTheme.FONT_CAPTION);
        selectedCount.setForeground(NativeTheme.ACCENT_LIGHT);
        messages.add(status, BorderLayout.CENTER);
        messages.add(selectedCount, BorderLayout.EAST);
        footer.add(messages, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(
                FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        JButton cancel = UiKit.button(
                "取消", LyraIcons.of(LyraIcons.Kind.CLOSE),
                UiKit.ButtonStyle.GHOST);
        cancel.addActionListener(event -> dispose());
        JButton apply = UiKit.button(
                "应用并加载", LyraIcons.of(LyraIcons.Kind.ER),
                UiKit.ButtonStyle.PRIMARY);
        apply.addActionListener(event -> confirmSelection());
        actions.add(cancel);
        actions.add(apply);
        footer.add(actions, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(apply);

        searchField.addActionListener(event -> search());
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) {
                scheduleSearch();
            }
            @Override public void removeUpdate(DocumentEvent event) {
                scheduleSearch();
            }
            @Override public void changedUpdate(DocumentEvent event) {
                scheduleSearch();
            }
        });
    }

    private JPanel listPanel(String title, JList<?> list) {
        JPanel panel = UiKit.glass(new BorderLayout(0, 8), 0);
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JLabel label = new JLabel(title);
        label.setFont(NativeTheme.FONT_BODY.deriveFont(
                java.awt.Font.BOLD));
        panel.add(label, BorderLayout.NORTH);
        JScrollPane scroll = UiKit.scroll(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private void configureList(
            JList<ErDiagramMetadataLoader.TableChoice> list) {
        list.setCellRenderer(new ChoiceRenderer());
        list.setFixedCellHeight(36);
    }

    private void scheduleSearch() {
        debounce.restart();
    }

    private void search() {
        debounce.stop();
        String query = searchField.getText().trim();
        long request = ++generation;
        if (worker != null && !worker.isDone()) {
            worker.cancel(true);
        }
        resultModel.clear();
        if (query.isEmpty()) {
            status.setText("输入至少 1 个字符开始搜索");
            return;
        }
        status.setForeground(NativeTheme.WARNING);
        status.setText("正在当前数据源和命名空间内搜索…");
        worker = new SwingWorker<>() {
            @Override
            protected List<ErDiagramMetadataLoader.TableChoice>
                    doInBackground() throws Exception {
                return searcher.search(query);
            }

            @Override
            protected void done() {
                if (isCancelled() || request != generation) {
                    return;
                }
                try {
                    List<ErDiagramMetadataLoader.TableChoice> values = get();
                    values.forEach(resultModel::addElement);
                    status.setForeground(NativeTheme.MUTED);
                    status.setText(values.isEmpty()
                            ? "没有匹配表，请换一个关键词"
                            : "找到 " + values.size()
                                    + " 个结果；双击或多选后添加");
                } catch (Exception exception) {
                    status.setForeground(NativeTheme.ERROR);
                    status.setText("搜索失败：" + safeMessage(exception));
                }
            }
        };
        worker.execute();
    }

    private void addSelectedResults() {
        List<ErDiagramMetadataLoader.TableChoice> values =
                results.getSelectedValuesList();
        if (values.isEmpty()) {
            return;
        }
        int newCount = (int) values.stream()
                .filter(choice -> !selectedByKey.containsKey(choice.key()))
                .count();
        if (selectedByKey.size() + newCount
                > ErDiagramMetadataLoader.MAX_SELECTED_TABLES) {
            JOptionPane.showMessageDialog(this,
                    "一次最多选择 "
                            + ErDiagramMetadataLoader.MAX_SELECTED_TABLES
                            + " 张表。请缩小范围后再加载。",
                    "表数量超过上限", JOptionPane.WARNING_MESSAGE);
            return;
        }
        values.forEach(choice -> selectedByKey.put(
                choice.key(), choice));
        rebuildSelectedModel();
    }

    private void removeSelectedTables() {
        selected.getSelectedValuesList()
                .forEach(choice -> selectedByKey.remove(choice.key()));
        rebuildSelectedModel();
    }

    private void rebuildSelectedModel() {
        selectedModel.clear();
        selectedByKey.values().forEach(selectedModel::addElement);
        selectedCount.setText(selectedByKey.size() + " / "
                + ErDiagramMetadataLoader.MAX_SELECTED_TABLES + " 张表");
    }

    private void confirmSelection() {
        if (selectedByKey.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "请至少选择 1 张表。", "尚未选择表",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        confirmed = true;
        dispose();
    }

    private void cancelWork() {
        generation++;
        debounce.stop();
        if (worker != null && !worker.isDone()) {
            worker.cancel(true);
        }
    }

    private static String safeMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null
                ? root.getClass().getSimpleName() : root.getMessage();
    }

    @FunctionalInterface
    interface Searcher {
        List<ErDiagramMetadataLoader.TableChoice> search(String query)
                throws Exception;
    }

    private static final class ChoiceRenderer
            extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index,
                boolean selected, boolean focused) {
            JLabel label = (JLabel) super.getListCellRendererComponent(
                    list, value, index, selected, focused);
            if (value instanceof ErDiagramMetadataLoader.TableChoice choice) {
                label.setText(choice.label());
                label.setToolTipText(choice.path());
                label.setIcon(LyraIcons.of(
                        "VIEW".equals(choice.objectType())
                                ? LyraIcons.Kind.VIEW : LyraIcons.Kind.TABLE,
                        16, NativeTheme.ACCENT_LIGHT));
                label.setBorder(BorderFactory.createEmptyBorder(
                        3, 6, 3, 6));
            }
            return label;
        }
    }
}
