package io.github.lexaquila.lyradb.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.desktop.ui.MainFrame;
import io.github.lexaquila.lyradb.desktop.ui.NativeTheme;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LyraDB 个人版原生桌面入口。
 */
public final class NativeDesktopApplication {

    public static final String VERSION = resolveVersion();

    private static String resolveVersion() {
        String implementationVersion = NativeDesktopApplication.class
                .getPackage().getImplementationVersion();
        return implementationVersion == null || implementationVersion.isBlank()
                ? "3.1.1"
                : implementationVersion;
    }

    private NativeDesktopApplication() {
    }

    public static void main(String[] args) {
        Arguments arguments = Arguments.parse(args);
        if (arguments.smokeMarker != null) {
            runSmokeTest(arguments);
            return;
        }
        DesktopRuntime runtime = null;
        try {
            runtime = arguments.dataDirectory == null
                    ? DesktopRuntime.openDefault()
                    : DesktopRuntime.open(arguments.dataDirectory);
            NativeTheme.install(NativeTheme.Mode.from(
                    runtime.stateStore().getThemeMode()));
        } catch (Throwable throwable) {
            NativeTheme.install();
            if (runtime != null) {
                try {
                    runtime.close();
                } catch (Throwable closeFailure) {
                    throwable.addSuppressed(closeFailure);
                }
            }
            showStartupFailure(throwable);
            return;
        }

        DesktopRuntime startedRuntime = runtime;
        SwingUtilities.invokeLater(() -> {
            try {
                new MainFrame(startedRuntime).setVisible(true);
            } catch (Throwable throwable) {
                try {
                    startedRuntime.close();
                } catch (Throwable closeFailure) {
                    throwable.addSuppressed(closeFailure);
                }
                showStartupFailure(throwable);
            }
        });
    }

    private static void showStartupFailure(Throwable throwable) {
        Runnable show = () -> {
            JOptionPane.showMessageDialog(null,
                    "LyraDB 启动失败：\n" + rootCause(throwable).getMessage(),
                    "LyraDB 启动错误", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        };
        if (SwingUtilities.isEventDispatchThread()) {
            show.run();
        } else {
            SwingUtilities.invokeLater(show);
        }
    }

    private static void runSmokeTest(Arguments arguments) {
        System.setProperty("java.awt.headless", "true");
        int exit = 0;
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("version", VERSION);
        marker.put("architecture", "native-swing");
        marker.put("nativeUiToolkit", "javax.swing");
        marker.put("browserLaunched", false);
        marker.put("webViewEmbedded", false);
        marker.put("localHttpServerStarted", false);
        marker.put("aiConfigAvailable", true);
        try (DesktopRuntime runtime = arguments.dataDirectory == null
                ? DesktopRuntime.openDefault()
                : DesktopRuntime.open(arguments.dataDirectory)) {
            Class.forName(MainFrame.class.getName());
            Class.forName(NativeTheme.class.getName());
            marker.put("driverCount",
                    runtime.driverRegistry().getAllDriverInfos().size());
            marker.put("stateStoreReady", runtime.stateStore() != null);
            marker.put("status", "ok");
        } catch (Throwable throwable) {
            exit = 1;
            marker.put("status", "error");
            marker.put("errorType", rootCause(throwable).getClass().getSimpleName());
            marker.put("error", rootCause(throwable).getMessage());
        }
        try {
            Path parent = arguments.smokeMarker.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            new ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValue(arguments.smokeMarker.toFile(), marker);
        } catch (Exception exception) {
            exception.printStackTrace(System.err);
            exit = 2;
        }
        System.exit(exit);
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static final class Arguments {
        private Path smokeMarker;
        private Path dataDirectory;

        static Arguments parse(String[] args) {
            Arguments parsed = new Arguments();
            if (args == null) {
                return parsed;
            }
            for (String argument : args) {
                if (argument.startsWith("--smoke-test=")) {
                    parsed.smokeMarker = Path.of(
                            argument.substring("--smoke-test=".length()));
                } else if (argument.startsWith("--data-dir=")) {
                    parsed.dataDirectory = Path.of(
                            argument.substring("--data-dir=".length()));
                }
            }
            return parsed;
        }
    }
}
