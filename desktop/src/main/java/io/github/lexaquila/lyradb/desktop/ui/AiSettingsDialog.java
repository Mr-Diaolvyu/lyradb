package io.github.lexaquila.lyradb.desktop.ui;

import io.github.lexaquila.lyradb.desktop.DesktopRuntime;
import io.github.lexaquila.lyradb.desktop.ai.AiProviderPreset;
import io.github.lexaquila.lyradb.desktop.ai.OpenAiCompatibleClient;
import io.github.lexaquila.lyradb.desktop.model.AiProfile;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
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
import java.util.Arrays;

/**
 * 个人版本地 AI Provider 配置。
 */
public final class AiSettingsDialog extends JDialog {

    private final DesktopRuntime runtime;
    private final JComboBox<AiProviderPreset> providerBox =
            new JComboBox<>(AiProviderPreset.all().toArray(AiProviderPreset[]::new));
    private final JTextField baseUrlField = new JTextField();
    private final JTextField modelField = new JTextField();
    private final JPasswordField keyField = new JPasswordField();
    private final JSpinner temperatureSpinner =
            new JSpinner(new SpinnerNumberModel(0.2D, 0D, 2D, 0.1D));
    private final JSpinner maxTokensSpinner =
            new JSpinner(new SpinnerNumberModel(4096, 256, 32768, 256));
    private final JLabel statusLabel =
            new JLabel("API Key 将加密保存在本机，不进入企业服务");
    private boolean populating;
    private String selectedProviderKey;

    public AiSettingsDialog(JFrame owner, DesktopRuntime runtime) {
        super(owner, "AI 服务设置 · 个人版", true);
        this.runtime = runtime;
        setIconImage(LyraIcons.applicationImage());
        buildUi();
        populate();
        setMinimumSize(new Dimension(680, 560));
        setSize(760, 630);
        setLocationRelativeTo(owner);
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
                LyraIcons.Kind.SETTINGS, 28, NativeTheme.ACCENT_LIGHT)),
                BorderLayout.WEST);
        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("AI 服务设置");
        title.setFont(NativeTheme.FONT_TITLE);
        title.setForeground(NativeTheme.FOREGROUND);
        JLabel subtitle = new JLabel("配置个人版使用的模型服务，凭据仅保存在当前设备");
        subtitle.setFont(NativeTheme.FONT_CAPTION);
        subtitle.setForeground(NativeTheme.MUTED);
        heading.add(title);
        heading.add(Box.createVerticalStrut(3));
        heading.add(subtitle);
        header.add(heading, BorderLayout.CENTER);
        header.add(UiKit.badge("本地配置",
                NativeTheme.SUCCESS, NativeTheme.SUCCESS_SOFT),
                BorderLayout.EAST);

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        addRow(form, 0, "服务商", providerBox);
        addRow(form, 1, "Base URL", baseUrlField);
        addRow(form, 2, "模型 / Endpoint ID", modelField);
        addRow(form, 3, "API Key", keyField);
        addRow(form, 4, "Temperature", temperatureSpinner);
        addRow(form, 5, "最大输出 Token", maxTokensSpinner);

        JPanel providerSection = UiKit.section(
                "模型服务",
                "支持 OpenAI Chat Completions 兼容接口",
                form);

        JPanel securityContent = new JPanel(new BorderLayout(12, 0));
        securityContent.setOpaque(false);
        securityContent.add(new JLabel(LyraIcons.of(
                LyraIcons.Kind.SHIELD, 24, NativeTheme.SUCCESS)),
                BorderLayout.WEST);
        JLabel securityText = new JLabel("""
                <html><body style='width:520px'>
                API Key 使用 AES-256-GCM 加密后存放在本机。
                远程地址默认必须使用 HTTPS；仅本机 Ollama 可使用
                127.0.0.1 / localhost 的 HTTP。AI 输出永远不会被自动执行。
                </body></html>
                """);
        securityText.setFont(NativeTheme.FONT_CAPTION);
        securityText.setForeground(NativeTheme.MUTED);
        securityContent.add(securityText, BorderLayout.CENTER);
        JPanel securitySection = UiKit.section(
                "安全边界", null, securityContent);

        JPanel content = new JPanel();
        content.setBackground(NativeTheme.BACKGROUND);
        content.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        providerSection.setAlignmentX(Component.LEFT_ALIGNMENT);
        securitySection.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(providerSection);
        content.add(Box.createVerticalStrut(12));
        content.add(securitySection);
        content.add(Box.createVerticalGlue());

        providerBox.addActionListener(event -> applyPreset());

        JButton test = UiKit.button("测试连接",
                LyraIcons.of(LyraIcons.Kind.CONNECT),
                UiKit.ButtonStyle.SECONDARY);
        test.addActionListener(event -> test(test));
        JButton save = UiKit.button("加密保存",
                LyraIcons.of(LyraIcons.Kind.SHIELD),
                UiKit.ButtonStyle.PRIMARY);
        save.addActionListener(event -> save());
        JButton cancel = UiKit.button("取消", null, UiKit.ButtonStyle.GHOST);
        cancel.addActionListener(event -> dispose());

        JPanel footer = new JPanel(new BorderLayout(16, 0));
        footer.setBackground(NativeTheme.SURFACE);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(
                        1, 0, 0, 0, NativeTheme.BORDER_SOFT),
                BorderFactory.createEmptyBorder(10, 16, 10, 16)));
        statusLabel.setForeground(NativeTheme.MUTED);
        statusLabel.setFont(NativeTheme.FONT_CAPTION);
        footer.add(statusLabel, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(cancel);
        actions.add(test);
        actions.add(save);
        footer.add(actions, BorderLayout.EAST);

        setLayout(new BorderLayout());
        add(header, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
        UiKit.configureDialog(this, save);
    }

    private void populate() {
        AiProfile profile = runtime.stateStore().getAiProfile();
        populating = true;
        try {
            for (int i = 0; i < providerBox.getItemCount(); i++) {
                if (providerBox.getItemAt(i).key()
                        .equalsIgnoreCase(profile.getProviderKey())) {
                    providerBox.setSelectedIndex(i);
                    break;
                }
            }
            baseUrlField.setText(profile.getBaseUrl());
            modelField.setText(profile.getModel());
            keyField.setText(profile.getApiKey());
            temperatureSpinner.setValue(profile.getTemperature());
            maxTokensSpinner.setValue(profile.getMaxTokens());
            selectedProviderKey = profile.getProviderKey();
        } finally {
            populating = false;
        }
    }

    private void applyPreset() {
        AiProviderPreset preset = (AiProviderPreset) providerBox.getSelectedItem();
        if (preset == null) {
            return;
        }
        if (!populating && selectedProviderKey != null
                && !selectedProviderKey.equalsIgnoreCase(preset.key())) {
            keyField.setText("");
        }
        selectedProviderKey = preset.key();
        baseUrlField.setText(preset.baseUrl());
        modelField.setText(preset.defaultModel());
        statusLabel.setForeground(NativeTheme.MUTED);
        statusLabel.setText(preset.apiKeyOptional()
                ? "本地 Ollama 可不填写 API Key"
                : "切换服务商会清空旧密钥，避免误发给新的服务地址");
    }

    private AiProfile collect() {
        AiProviderPreset preset = (AiProviderPreset) providerBox.getSelectedItem();
        if (preset == null) {
            throw new IllegalArgumentException("请选择 AI 服务商");
        }
        AiProfile profile = new AiProfile();
        profile.setProviderKey(preset.key());
        profile.setDisplayName(preset.displayName());
        profile.setBaseUrl(baseUrlField.getText());
        profile.setModel(modelField.getText());
        char[] chars = keyField.getPassword();
        try {
            profile.setApiKey(new String(chars));
        } finally {
            Arrays.fill(chars, '\0');
        }
        profile.setTemperature(((Number) temperatureSpinner.getValue()).doubleValue());
        profile.setMaxTokens(((Number) maxTokensSpinner.getValue()).intValue());
        OpenAiCompatibleClient.validateProfile(profile);
        return profile;
    }

    private void test(JButton button) {
        final AiProfile profile;
        try {
            profile = collect();
        } catch (Exception exception) {
            showError(exception);
            return;
        }
        button.setEnabled(false);
        statusLabel.setForeground(NativeTheme.WARNING);
        statusLabel.setText("正在测试 AI 服务…");
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return runtime.aiClient().test(profile);
            }

            @Override
            protected void done() {
                button.setEnabled(true);
                try {
                    get();
                    statusLabel.setForeground(NativeTheme.SUCCESS);
                    statusLabel.setText("AI 连接成功，可以加密保存");
                } catch (Exception exception) {
                    statusLabel.setForeground(NativeTheme.ERROR);
                    statusLabel.setText("AI 连接失败");
                    showError(rootCause(exception));
                }
            }
        }.execute();
    }

    private void save() {
        try {
            runtime.stateStore().saveAiProfile(collect());
            JOptionPane.showMessageDialog(this, "AI 配置已在本机加密保存。");
            dispose();
        } catch (Exception exception) {
            showError(exception);
        }
    }

    private static void addRow(JPanel panel, int row,
            String label, Component component) {
        GridBagConstraints c = constraints();
        c.gridx = 0;
        c.gridy = row;
        JLabel labelComponent = new JLabel(label);
        labelComponent.setForeground(NativeTheme.MUTED);
        labelComponent.setPreferredSize(new Dimension(140, 32));
        labelComponent.setLabelFor(component);
        panel.add(labelComponent, c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        component.setPreferredSize(new Dimension(390, 34));
        panel.add(component, c);
    }

    private static GridBagConstraints constraints() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 4, 6, 4);
        c.anchor = GridBagConstraints.WEST;
        return c;
    }

    private void showError(Throwable throwable) {
        JOptionPane.showMessageDialog(this, throwable.getMessage(),
                "AI 配置错误", JOptionPane.ERROR_MESSAGE);
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
