package io.github.lexaquila.lyradb.desktop.ui;

import io.github.lexaquila.lyradb.desktop.DesktopRuntime;
import io.github.lexaquila.lyradb.desktop.model.DesktopConnection;
import io.github.lexaquila.lyradb.model.entity.DriverInfo;
import io.github.lexaquila.lyradb.model.entity.FormField;
import io.github.lexaquila.lyradb.model.entity.FormFieldOption;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 驱动元数据驱动的原生连接配置窗口。
 */
public final class ConnectionDialog extends JDialog {

    private final DesktopRuntime runtime;
    private final DesktopConnection original;
    private final JTextField nameField = new JTextField();
    private final JTextField groupField = new JTextField();
    private final JCheckBox favoriteBox = new JCheckBox("收藏连接");
    private final JComboBox<DriverInfo> driverBox;
    private final JPanel fieldsPanel = new JPanel(new GridBagLayout());
    private final JLabel statusLabel = new JLabel("尚未测试连接");
    private final JButton browseButton = UiKit.button(
            "选择 SQLite 文件", LyraIcons.of(LyraIcons.Kind.DATABASE),
            UiKit.ButtonStyle.GHOST);
    private final Map<String, Component> fields = new LinkedHashMap<>();
    private DesktopConnection result;

    private ConnectionDialog(JFrame owner, DesktopRuntime runtime,
            DesktopConnection original) {
        super(owner, original == null ? "新建数据库连接" : "编辑数据库连接", true);
        this.runtime = runtime;
        this.original = original == null ? null : original.copy();
        List<DriverInfo> drivers = runtime.driverRegistry().getAllDriverInfos().stream()
                .sorted(Comparator.comparing(DriverInfo::getDisplayName))
                .toList();
        this.driverBox = new JComboBox<>(drivers.toArray(DriverInfo[]::new));
        this.driverBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean selected, boolean focused) {
                DriverInfo driver = value instanceof DriverInfo info ? info : null;
                return super.getListCellRendererComponent(list,
                        driver == null ? "" : driver.getDisplayName(),
                        index, selected, focused);
            }
        });
        setIconImage(LyraIcons.applicationImage());
        buildUi();
        populate();
        setMinimumSize(new Dimension(680, 640));
        setSize(760, 740);
        setLocationRelativeTo(owner);
    }

    public static DesktopConnection show(JFrame owner, DesktopRuntime runtime,
            DesktopConnection original) {
        ConnectionDialog dialog = new ConnectionDialog(owner, runtime, original);
        dialog.setVisible(true);
        return dialog.result == null ? null : dialog.result.copy();
    }

    private void buildUi() {
        getContentPane().setBackground(NativeTheme.BACKGROUND);

        JPanel header = new JPanel(new BorderLayout(14, 0));
        header.setBackground(NativeTheme.SURFACE);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(
                        0, 0, 1, 0, NativeTheme.BORDER_SOFT),
                BorderFactory.createEmptyBorder(16, 18, 16, 18)));
        header.add(new JLabel(LyraIcons.of(
                LyraIcons.Kind.ADD_DATABASE, 28, NativeTheme.ACCENT_LIGHT)),
                BorderLayout.WEST);
        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        JLabel title = new JLabel(original == null ? "新建数据库连接" : "编辑数据库连接");
        title.setFont(NativeTheme.FONT_TITLE);
        title.setForeground(NativeTheme.FOREGROUND);
        JLabel subtitle = new JLabel(
                "数据库凭据加密保存在当前设备；编辑时可按需显示或复制");
        subtitle.setFont(NativeTheme.FONT_CAPTION);
        subtitle.setForeground(NativeTheme.MUTED);
        heading.add(title);
        heading.add(Box.createVerticalStrut(3));
        heading.add(subtitle);
        header.add(heading, BorderLayout.CENTER);

        JPanel identity = new JPanel(new GridBagLayout());
        identity.setOpaque(false);
        GridBagConstraints c = constraints();
        addRow(identity, c, 0, "连接名称 *", nameField);
        addRow(identity, c, 1, "数据库类型 *", driverBox);
        addRow(identity, c, 2, "分组", groupField);
        c = constraints();
        c.gridx = 1;
        c.gridy = 3;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        favoriteBox.setOpaque(false);
        identity.add(favoriteBox, c);
        JPanel identitySection = UiKit.section(
                "基本信息", "用于在本机组织和识别数据库连接", identity);

        fieldsPanel.setOpaque(false);
        fieldsPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        JScrollPane fieldsScroll = UiKit.scroll(fieldsPanel);
        fieldsScroll.setPreferredSize(new Dimension(560, 280));
        JPanel parameterSection = UiKit.section(
                "连接参数", "参数会根据数据库类型自动切换", fieldsScroll);

        JPanel content = new JPanel();
        content.setBackground(NativeTheme.BACKGROUND);
        content.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        identitySection.setAlignmentX(Component.LEFT_ALIGNMENT);
        parameterSection.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(identitySection);
        content.add(Box.createVerticalStrut(12));
        content.add(parameterSection);
        content.add(Box.createVerticalGlue());

        driverBox.addActionListener(event -> rebuildFields(null));
        browseButton.addActionListener(event -> browseSqlite());
        JButton testButton = UiKit.button(
                "测试连接", LyraIcons.of(LyraIcons.Kind.CONNECT),
                UiKit.ButtonStyle.SECONDARY);
        testButton.addActionListener(event -> testConnection(testButton));
        JButton saveButton = UiKit.button(
                "保存连接", LyraIcons.of(LyraIcons.Kind.SHIELD),
                UiKit.ButtonStyle.PRIMARY);
        saveButton.addActionListener(event -> save());
        JButton cancelButton = UiKit.button(
                "取消", null, UiKit.ButtonStyle.GHOST);
        cancelButton.addActionListener(event -> dispose());

        JPanel footer = new JPanel(new BorderLayout(16, 0));
        footer.setBackground(NativeTheme.SURFACE);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(
                        1, 0, 0, 0, NativeTheme.BORDER_SOFT),
                BorderFactory.createEmptyBorder(10, 16, 10, 16)));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        left.add(browseButton);
        statusLabel.setForeground(NativeTheme.MUTED);
        statusLabel.setFont(NativeTheme.FONT_CAPTION);
        left.add(statusLabel);
        footer.add(left, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(cancelButton);
        actions.add(testButton);
        actions.add(saveButton);
        footer.add(actions, BorderLayout.EAST);

        setLayout(new BorderLayout());
        add(header, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
        UiKit.configureDialog(this, saveButton);
    }

    private void populate() {
        if (original == null) {
            rebuildFields(null);
            return;
        }
        nameField.setText(original.getName());
        groupField.setText(original.getGroup());
        favoriteBox.setSelected(original.isFavorite());
        for (int i = 0; i < driverBox.getItemCount(); i++) {
            if (driverBox.getItemAt(i).getDbType()
                    .equalsIgnoreCase(original.getDbType())) {
                driverBox.setSelectedIndex(i);
                break;
            }
        }
        rebuildFields(original.getParams());
        driverBox.setEnabled(false);
    }

    private void rebuildFields(Map<String, Object> values) {
        fields.clear();
        fieldsPanel.removeAll();
        DriverInfo driver = (DriverInfo) driverBox.getSelectedItem();
        if (driver == null) {
            browseButton.setVisible(false);
            return;
        }
        browseButton.setVisible("SQLITE".equalsIgnoreCase(driver.getDbType()));
        int row = 0;
        for (FormField field : driver.getConnectionFormFields()) {
            Object value = values != null && values.containsKey(field.getName())
                    ? values.get(field.getName()) : field.getDefaultValue();
            Component component = createField(field, value);
            fields.put(field.getName(), component);
            addRow(fieldsPanel, constraints(), row++,
                    field.getLabel() + (field.isRequired() ? " *" : ""), component);
        }
        GridBagConstraints spacer = constraints();
        spacer.gridx = 0;
        spacer.gridy = row;
        spacer.gridwidth = 2;
        spacer.weighty = 1;
        spacer.fill = GridBagConstraints.BOTH;
        JPanel spacerPanel = new JPanel();
        spacerPanel.setOpaque(false);
        fieldsPanel.add(spacerPanel, spacer);
        fieldsPanel.revalidate();
        fieldsPanel.repaint();
    }

    private Component createField(FormField field, Object value) {
        return switch (field.getType()) {
            case "password" -> new SensitiveFieldEditor(value);
            case "number" -> new JSpinner(new SpinnerNumberModel(
                    parseInt(value), 0, 65535, 1));
            case "boolean" -> {
                JCheckBox box = new JCheckBox();
                box.setOpaque(false);
                box.setSelected(Boolean.parseBoolean(String.valueOf(value)));
                yield box;
            }
            case "select" -> {
                JComboBox<FormFieldOption> box = new JComboBox<>(
                        field.getOptions().toArray(FormFieldOption[]::new));
                box.setRenderer(new DefaultListCellRenderer() {
                    @Override
                    public Component getListCellRendererComponent(JList<?> list,
                            Object option, int index, boolean selected, boolean focused) {
                        FormFieldOption item = option instanceof FormFieldOption valueOption
                                ? valueOption : null;
                        return super.getListCellRendererComponent(list,
                                item == null ? "" : item.getLabel(),
                                index, selected, focused);
                    }
                });
                if (value != null) {
                    for (int i = 0; i < box.getItemCount(); i++) {
                        if (box.getItemAt(i).getValue().equals(value.toString())) {
                            box.setSelectedIndex(i);
                        }
                    }
                }
                yield box;
            }
            default -> {
                JTextField text = new JTextField();
                text.setText(value == null ? "" : value.toString());
                yield text;
            }
        };
    }

    private DesktopConnection collect() {
        DriverInfo driver = (DriverInfo) driverBox.getSelectedItem();
        if (driver == null) {
            throw new IllegalArgumentException("请选择数据库类型");
        }
        DesktopConnection connection =
                original == null ? new DesktopConnection() : original.copy();
        connection.setName(nameField.getText());
        connection.setDbType(driver.getDbType());
        connection.setGroup(groupField.getText());
        connection.setFavorite(favoriteBox.isSelected());
        Map<String, Object> params = new LinkedHashMap<>();
        for (FormField field : driver.getConnectionFormFields()) {
            Object value = componentValue(fields.get(field.getName()));
            if (field.isRequired() && (value == null || value.toString().isBlank())) {
                throw new IllegalArgumentException(field.getLabel() + "不能为空");
            }
            params.put(field.getName(), value);
        }
        connection.setParams(params);
        return connection;
    }

    private Object componentValue(Component component) {
        if (component instanceof SensitiveFieldEditor sensitive) {
            return sensitive.value();
        }
        if (component instanceof JPasswordField password) {
            char[] chars = password.getPassword();
            try {
                return new String(chars);
            } finally {
                Arrays.fill(chars, '\0');
            }
        }
        if (component instanceof JTextField text) {
            return text.getText().trim();
        }
        if (component instanceof JSpinner spinner) {
            return spinner.getValue();
        }
        if (component instanceof JCheckBox box) {
            return box.isSelected();
        }
        if (component instanceof JComboBox<?> box
                && box.getSelectedItem() instanceof FormFieldOption option) {
            return option.getValue();
        }
        return "";
    }

    private void testConnection(JButton button) {
        final DesktopConnection connection;
        try {
            connection = collect();
        } catch (Exception exception) {
            showError(exception);
            return;
        }
        button.setEnabled(false);
        statusLabel.setForeground(NativeTheme.WARNING);
        statusLabel.setText("正在加载驱动并测试…");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                runtime.connectionManager().test(connection);
                return null;
            }

            @Override
            protected void done() {
                button.setEnabled(true);
                try {
                    get();
                    statusLabel.setForeground(NativeTheme.SUCCESS);
                    statusLabel.setText("连接成功");
                } catch (Exception exception) {
                    statusLabel.setForeground(NativeTheme.ERROR);
                    statusLabel.setText("连接失败");
                    ConnectionErrorAdvisor.show(
                            ConnectionDialog.this, connection, exception);
                }
            }
        }.execute();
    }

    private void save() {
        try {
            result = runtime.stateStore().saveConnection(collect());
            dispose();
        } catch (Exception exception) {
            showError(exception);
        }
    }

    private void browseSqlite() {
        DriverInfo driver = (DriverInfo) driverBox.getSelectedItem();
        if (driver == null || !"SQLITE".equalsIgnoreCase(driver.getDbType())) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择或新建 SQLite 数据库");
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            Component field = fields.get("filePath");
            if (field instanceof JTextField text) {
                File file = chooser.getSelectedFile();
                text.setText(file.getAbsolutePath());
            }
        }
    }

    private final class SensitiveFieldEditor extends JPanel {
        private final JPasswordField passwordField = new JPasswordField();
        private final JButton revealButton = UiKit.button(
                "显示", null, UiKit.ButtonStyle.GHOST);
        private final char hiddenEchoChar;

        private SensitiveFieldEditor(Object value) {
            super(new BorderLayout(6, 0));
            setOpaque(false);
            passwordField.setText(value == null ? "" : value.toString());
            char echo = passwordField.getEchoChar();
            hiddenEchoChar = echo == 0 ? '\u2022' : echo;
            revealButton.addActionListener(event -> toggleReveal());
            JButton copyButton = UiKit.button(
                    "复制", null, UiKit.ButtonStyle.GHOST);
            copyButton.addActionListener(event -> copyValue());
            JPanel actions = new JPanel(new FlowLayout(
                    FlowLayout.RIGHT, 4, 0));
            actions.setOpaque(false);
            actions.add(revealButton);
            actions.add(copyButton);
            add(passwordField, BorderLayout.CENTER);
            add(actions, BorderLayout.EAST);
        }

        private String value() {
            char[] chars = passwordField.getPassword();
            try {
                return new String(chars);
            } finally {
                Arrays.fill(chars, '\0');
            }
        }

        private void toggleReveal() {
            boolean reveal = passwordField.getEchoChar() != 0;
            passwordField.setEchoChar(reveal ? (char) 0 : hiddenEchoChar);
            revealButton.setText(reveal ? "隐藏" : "显示");
            if (reveal) {
                statusLabel.setForeground(NativeTheme.WARNING);
                statusLabel.setText("凭据正在明文显示，请注意旁观风险");
            } else {
                statusLabel.setForeground(NativeTheme.MUTED);
                statusLabel.setText("凭据已隐藏");
            }
        }

        private void copyValue() {
            char[] chars = passwordField.getPassword();
            try {
                if (chars.length == 0) {
                    statusLabel.setForeground(NativeTheme.WARNING);
                    statusLabel.setText("当前凭据为空");
                    return;
                }
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                        new StringSelection(new String(chars)), null);
                statusLabel.setForeground(NativeTheme.SUCCESS);
                statusLabel.setText("凭据已复制到系统剪贴板，请注意保管");
            } catch (RuntimeException exception) {
                showError(new IllegalStateException(
                        "无法访问系统剪贴板", exception));
            } finally {
                Arrays.fill(chars, '\0');
            }
        }
    }

    private static void addRow(JPanel panel, GridBagConstraints c,
            int row, String label, Component component) {
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.LINE_START;
        JLabel labelComponent = new JLabel(label);
        labelComponent.setForeground(NativeTheme.MUTED);
        labelComponent.setPreferredSize(new Dimension(130, 32));
        labelComponent.setLabelFor(component);
        panel.add(labelComponent, c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        component.setPreferredSize(new Dimension(500, 34));
        panel.add(component, c);
    }

    private static GridBagConstraints constraints() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 4, 5, 4);
        c.anchor = GridBagConstraints.WEST;
        return c;
    }

    private static int parseInt(Object value) {
        try {
            return value == null ? 0 : Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void showError(Throwable throwable) {
        JOptionPane.showMessageDialog(this, throwable.getMessage(),
                "连接配置错误", JOptionPane.ERROR_MESSAGE);
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
