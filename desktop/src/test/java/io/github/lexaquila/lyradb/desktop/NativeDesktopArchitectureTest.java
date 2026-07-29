package io.github.lexaquila.lyradb.desktop;

import io.github.lexaquila.lyradb.desktop.ui.AiSettingsDialog;
import io.github.lexaquila.lyradb.desktop.ui.MainFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class NativeDesktopArchitectureTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldInitializeNativeRuntimeWithAiAndNineDrivers() {
        try (DesktopRuntime runtime = DesktopRuntime.open(tempDirectory)) {
            assertThat(runtime.driverRegistry().getAllDriverInfos()).hasSize(9);
            assertThat(runtime.aiClient()).isNotNull();
            assertThat(runtime.stateStore().getAiProfile()).isNotNull();
            assertThat(MainFrame.class.getSuperclass()).isEqualTo(javax.swing.JFrame.class);
            assertThat(AiSettingsDialog.class.getSuperclass())
                    .isEqualTo(javax.swing.JDialog.class);
        }
    }

    @Test
    void desktopModuleMustNotDependOnBrowserContainers() {
        assertThatThrownByClassName("javafx.scene.web.WebView");
        assertThatThrownByClassName("org.cef.CefApp");
        assertThatThrownByClassName("com.microsoft.playwright.Browser");
    }

    private static void assertThatThrownByClassName(String className) {
        try {
            Class.forName(className);
            throw new AssertionError("桌面模块意外包含浏览器容器: " + className);
        } catch (ClassNotFoundException expected) {
            assertThat(expected).isNotNull();
        }
    }
}
