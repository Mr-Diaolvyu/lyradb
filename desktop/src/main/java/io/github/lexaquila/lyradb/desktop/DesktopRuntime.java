package io.github.lexaquila.lyradb.desktop;

import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.desktop.ai.OpenAiCompatibleClient;
import io.github.lexaquila.lyradb.desktop.db.NativeConnectionManager;
import io.github.lexaquila.lyradb.desktop.storage.DesktopStateStore;
import io.github.lexaquila.lyradb.desktop.storage.DesktopVault;
import io.github.lexaquila.lyradb.driver.DriverFactory;
import io.github.lexaquila.lyradb.driver.DriverRegistry;
import io.github.lexaquila.lyradb.driver.MavenDriverManager;
import io.github.lexaquila.lyradb.service.SqlReviewService;

import java.nio.file.Path;

/**
 * 个人版桌面进程的组合根。
 *
 * <p>所有能力都在当前进程内运行，数据库连接不会经过 HTTP 服务或浏览器。</p>
 */
public final class DesktopRuntime implements AutoCloseable {

    private final Path dataDirectory;
    private final DriverRegistry driverRegistry;
    private final DesktopVault vault;
    private final DesktopStateStore stateStore;
    private final NativeConnectionManager connectionManager;
    private final OpenAiCompatibleClient aiClient;
    private final MavenDriverManager mavenDriverManager;

    private DesktopRuntime(Path dataDirectory) {
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
        System.setProperty("lyradb.log.dir",
                this.dataDirectory.resolve("logs").toString());

        AppProperties properties = new AppProperties();
        properties.setEdition("personal");
        properties.setVersion(NativeDesktopApplication.VERSION);
        properties.setDriverCacheDir(this.dataDirectory.resolve("drivers").toString());
        properties.setMaxQueryRows(10_000);
        properties.setQueryTimeoutSeconds(60);

        this.driverRegistry = new DriverRegistry();
        this.driverRegistry.init();
        this.mavenDriverManager = new MavenDriverManager(properties);
        DriverFactory driverFactory =
                new DriverFactory(driverRegistry, this.mavenDriverManager, properties);
        this.vault = new DesktopVault(this.dataDirectory);
        this.stateStore = new DesktopStateStore(this.dataDirectory, vault);
        this.connectionManager = new NativeConnectionManager(
                driverFactory, stateStore, new SqlReviewService(), properties);
        this.aiClient = new OpenAiCompatibleClient();
    }

    public static DesktopRuntime openDefault() {
        String configured = System.getProperty("lyradb.data.dir", "").trim();
        Path directory = configured.isEmpty()
                ? Path.of(System.getProperty("user.home"), ".lyradb", "desktop")
                : Path.of(configured);
        return new DesktopRuntime(directory);
    }

    public static DesktopRuntime open(Path dataDirectory) {
        return new DesktopRuntime(dataDirectory);
    }

    public Path dataDirectory() {
        return dataDirectory;
    }

    public DriverRegistry driverRegistry() {
        return driverRegistry;
    }

    public DesktopStateStore stateStore() {
        return stateStore;
    }

    public NativeConnectionManager connectionManager() {
        return connectionManager;
    }

    public OpenAiCompatibleClient aiClient() {
        return aiClient;
    }

    @Override
    public void close() {
        connectionManager.close();
        mavenDriverManager.close();
        vault.close();
    }
}
