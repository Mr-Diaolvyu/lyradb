package io.github.lexaquila.lyradb.driver;

import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.model.entity.DriverInfo;
import io.github.lexaquila.lyradb.model.entity.MavenCoordinates;
import jakarta.annotation.PreDestroy;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.util.filter.DependencyFilterUtils;
import org.eclipse.aether.util.repository.SimpleArtifactDescriptorPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maven 动态驱动管理器。
 *
 * <p>使用 Maven Resolver 下载主驱动及完整 runtime 传递依赖，并为每组
 * 驱动坐标创建独立的 {@link URLClassLoader}。类加载器由本管理器统一持有，
 * 在应用关闭时释放，避免 Windows 上驱动 JAR 长期被占用。</p>
 */
@Component
public class MavenDriverManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MavenDriverManager.class);

    /** 阿里云 Maven 中央仓库镜像。 */
    private static final String ALIYUN_MIRROR =
            "https://maven.aliyun.com/repository/public";

    /** Maven 中央仓库。 */
    private static final String MAVEN_CENTRAL =
            "https://repo1.maven.org/maven2/";

    private final AppProperties appProperties;
    private final RepositorySystem repositorySystem;
    private final List<RemoteRepository> remoteRepositories;

    /** 按数据库类型和完整 Maven 坐标缓存类加载器。 */
    private final Map<String, DriverClassLoader> classLoaderCache =
            new ConcurrentHashMap<>();

    private boolean closed;

    /** 下载进度回调（前端通过 WebSocket 接收进度）。 */
    public interface DownloadProgressListener {
        void onProgress(String dbType, int percent, String message);
    }

    @Autowired
    public MavenDriverManager(AppProperties appProperties) {
        this(appProperties, defaultRepositories());
    }

    /**
     * 测试与离线环境可注入受控仓库，生产使用默认官方镜像列表。
     */
    MavenDriverManager(AppProperties appProperties,
            List<RemoteRepository> remoteRepositories) {
        this.appProperties = Objects.requireNonNull(
                appProperties, "应用配置不能为空");
        this.remoteRepositories = List.copyOf(Objects.requireNonNull(
                remoteRepositories, "Maven 仓库列表不能为空"));
        this.repositorySystem = new RepositorySystemSupplier().get();

        File cacheDir = new File(getDriverCacheDir());
        if (cacheDir.exists() && !cacheDir.isDirectory()) {
            throw new IllegalStateException(
                    "驱动缓存路径不是目录: " + cacheDir.getAbsolutePath());
        }
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new IllegalStateException(
                    "无法创建驱动缓存目录: " + cacheDir.getAbsolutePath());
        }
    }

    /**
     * 获取或下载驱动 JAR，返回受管理的隔离 ClassLoader。
     */
    public synchronized ClassLoader getOrCreateClassLoader(DriverInfo driverInfo)
            throws Exception {
        ensureOpen();
        String cacheKey = cacheKey(driverInfo);
        DriverClassLoader cached = classLoaderCache.get(cacheKey);
        if (cached != null) {
            log.debug("使用缓存的 ClassLoader: {}", cacheKey);
            return cached;
        }

        log.info("准备加载驱动: {} {}",
                driverInfo.getDisplayName(), driverInfo.getMavenCoordinates());
        List<File> jarFiles = resolveArtifacts(driverInfo.getMavenCoordinates());
        if (jarFiles.isEmpty()) {
            throw new IllegalStateException(
                    "无法下载驱动 JAR: " + driverInfo.getMavenCoordinates());
        }

        DriverClassLoader classLoader = createClassLoader(jarFiles);
        classLoaderCache.put(cacheKey, classLoader);
        log.info("驱动加载成功: {} ({} 个 JAR)",
                driverInfo.getDisplayName(), jarFiles.size());
        return classLoader;
    }

    /**
     * 检查主驱动 JAR 是否已下载。
     */
    public boolean isDriverDownloaded(DriverInfo driverInfo) {
        try {
            File localRepo = new File(getDriverCacheDir());
            MavenCoordinates coordinates = driverInfo.getMavenCoordinates();
            String groupPath = coordinates.getGroupId().replace('.', '/');
            String artifactId = coordinates.getArtifactId();
            String version = coordinates.getVersion();
            String classifier = coordinates.getClassifier();
            String suffix = classifier == null || classifier.isBlank()
                    ? "" : "-" + classifier;
            File jarFile = new File(localRepo,
                    groupPath + "/" + artifactId + "/" + version + "/"
                            + artifactId + "-" + version + suffix + ".jar");
            return jarFile.isFile();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * 下载驱动并报告进度。
     */
    public synchronized void downloadDriverWithProgress(
            DriverInfo driverInfo, DownloadProgressListener listener)
            throws Exception {
        ensureOpen();
        Objects.requireNonNull(listener, "下载进度监听器不能为空");
        String dbType = normalizedDbType(driverInfo);
        String cacheKey = cacheKey(driverInfo);
        if (classLoaderCache.containsKey(cacheKey)) {
            listener.onProgress(dbType, 100, "驱动已加载");
            return;
        }
        listener.onProgress(dbType, 10,
                isDriverDownloaded(driverInfo)
                        ? "主驱动已存在，正在校验传递依赖..."
                        : "正在解析依赖...");
        List<File> jarFiles = resolveArtifacts(driverInfo.getMavenCoordinates());
        if (jarFiles.isEmpty()) {
            throw new IllegalStateException(
                    "无法下载驱动 JAR: " + driverInfo.getMavenCoordinates());
        }
        listener.onProgress(dbType, 80, "正在加载驱动 JAR...");

        DriverClassLoader classLoader = createClassLoader(jarFiles);
        classLoaderCache.put(cacheKey, classLoader);
        listener.onProgress(dbType, 100, "驱动加载完成");
        log.info("驱动下载完成: {}", driverInfo.getDisplayName());
    }

    /**
     * 使用 Maven Resolver 解析主制品与完整 runtime 传递依赖。
     */
    private List<File> resolveArtifacts(MavenCoordinates coordinates)
            throws Exception {
        org.eclipse.aether.artifact.Artifact artifact = new DefaultArtifact(
                coordinates.getGroupId(),
                coordinates.getArtifactId(),
                coordinates.getClassifier() == null
                        ? "" : coordinates.getClassifier(),
                "jar",
                coordinates.getVersion());

        RepositorySystemSession session = newSession();
        ArtifactRequest artifactRequest = new ArtifactRequest();
        artifactRequest.setArtifact(artifact);
        for (RemoteRepository repository : remoteRepositories) {
            artifactRequest.addRepository(repository);
        }
        ArtifactResult mainResult =
                repositorySystem.resolveArtifact(session, artifactRequest);

        CollectRequest collectRequest = new CollectRequest();
        collectRequest.setRoot(new Dependency(artifact, "runtime"));
        for (RemoteRepository repository : remoteRepositories) {
            collectRequest.addRepository(repository);
        }
        DependencyFilter filter = DependencyFilterUtils.classpathFilter("runtime");
        DependencyRequest dependencyRequest =
                new DependencyRequest(collectRequest, filter);
        DependencyResult dependencyResult =
                repositorySystem.resolveDependencies(session, dependencyRequest);

        /*
         * DependencyNode#getChildren() 只包含直接依赖。ArtifactResult 列表包含
         * Resolver 实际解析到的所有层级，因此必须从这里收集传递依赖。
         */
        Map<String, File> jarFiles = new LinkedHashMap<>();
        addJar(jarFiles, mainResult.getArtifact());
        for (ArtifactResult result : dependencyResult.getArtifactResults()) {
            addJar(jarFiles, result.getArtifact());
        }

        log.info("解析 {} 完成，共 {} 个 JAR",
                coordinates, jarFiles.size());
        return new ArrayList<>(jarFiles.values());
    }

    private static void addJar(Map<String, File> jarFiles,
            org.eclipse.aether.artifact.Artifact artifact) throws IOException {
        if (artifact == null || artifact.getFile() == null) {
            return;
        }
        File file = artifact.getFile().getCanonicalFile();
        if (file.isFile() && "jar".equalsIgnoreCase(artifact.getExtension())) {
            jarFiles.putIfAbsent(file.getAbsolutePath(), file);
        }
    }

    private DriverClassLoader createClassLoader(List<File> jarFiles) {
        URL[] urls = jarFiles.stream()
                .map(File::toURI)
                .map(uri -> {
                    try {
                        return uri.toURL();
                    } catch (Exception exception) {
                        throw new IllegalArgumentException(
                                "驱动 JAR 路径无效: " + uri, exception);
                    }
                })
                .toArray(URL[]::new);
        return new DriverClassLoader(
                urls, ClassLoader.getSystemClassLoader());
    }

    private String cacheKey(DriverInfo driverInfo) {
        MavenCoordinates coordinates = driverInfo.getMavenCoordinates();
        return normalizedDbType(driverInfo)
                + ":" + coordinates.getGroupId()
                + ":" + coordinates.getArtifactId()
                + ":" + coordinates.getVersion()
                + ":" + Objects.toString(coordinates.getClassifier(), "");
    }

    private static String normalizedDbType(DriverInfo driverInfo) {
        return driverInfo.getDbType().toUpperCase(Locale.ROOT);
    }

    private String getDriverCacheDir() {
        String dir = appProperties.getDriverCacheDir();
        if (dir == null || dir.isBlank()) {
            dir = System.getProperty("user.home") + "/.lyradb/drivers";
        }
        return dir;
    }

    private RepositorySystemSession newSession() {
        DefaultRepositorySystemSession session =
                new DefaultRepositorySystemSession();
        LocalRepository localRepository =
                new LocalRepository(getDriverCacheDir());
        session.setLocalRepositoryManager(
                repositorySystem.newLocalRepositoryManager(
                        session, localRepository));
        session.setArtifactDescriptorPolicy(
                new SimpleArtifactDescriptorPolicy(true, true));
        return session;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Maven 驱动管理器已关闭");
        }
    }

    /**
     * 关闭所有受管理的 URLClassLoader。Spring 容器与桌面运行时都会调用。
     */
    @PreDestroy
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        List<DriverClassLoader> loaders =
                new ArrayList<>(classLoaderCache.values());
        classLoaderCache.clear();
        for (DriverClassLoader loader : loaders) {
            try {
                loader.close();
            } catch (IOException exception) {
                log.warn("关闭驱动 ClassLoader 失败", exception);
            }
        }
    }

    private static List<RemoteRepository> defaultRepositories() {
        return List.of(
                new RemoteRepository.Builder(
                        "aliyun", "default", ALIYUN_MIRROR).build(),
                new RemoteRepository.Builder(
                        "central", "default", MAVEN_CENTRAL).build());
    }

    /**
     * 可观测关闭状态仅用于生命周期回归测试。
     */
    static final class DriverClassLoader extends URLClassLoader {
        private volatile boolean closed;

        DriverClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            try {
                super.close();
            } finally {
                closed = true;
            }
        }

        boolean isClosed() {
            return closed;
        }
    }
}
