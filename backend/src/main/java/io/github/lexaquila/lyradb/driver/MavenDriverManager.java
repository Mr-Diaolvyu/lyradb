package io.github.lexaquila.lyradb.driver;

import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.model.entity.DriverInfo;
import io.github.lexaquila.lyradb.model.entity.MavenCoordinates;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.*;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.util.filter.DependencyFilterUtils;
import org.eclipse.aether.util.repository.SimpleArtifactDescriptorPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maven动态驱动管理器
 *
 * <p>
 * 基于Maven Resolver API (Aether)实现运行时JDBC/NoSQL驱动JAR的自动下载和加载。
 * 这是整个产品架构的核心特色之一——用户无需手动下载驱动，工具自动处理。
 * </p>
 *
 * <p>
 * 核心流程：
 * </p>
 * <ol>
 * <li>检查本地缓存目录是否有已下载的驱动JAR</li>
 * <li>若没有，通过Maven Resolver从远程仓库下载（含传递依赖）</li>
 * <li>使用URLClassLoader隔离加载驱动JAR，防止不同驱动版本冲突</li>
 * <li>缓存ClassLoader，避免重复加载</li>
 * </ol>
 *
 * <p>
 * 驱动缓存目录：~/.lyradb/drivers/
 * </p>
 * <p>
 * Maven远程仓库：阿里云镜像（国内访问快）
 * </p>
 */
@Component
public class MavenDriverManager {

    private static final Logger log = LoggerFactory.getLogger(MavenDriverManager.class);

    /** 阿里云Maven中央仓库镜像 */
    private static final String ALIYAN_MIRROR = "https://maven.aliyun.com/repository/public";

    /** Maven中央仓库 */
    private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";

    private final AppProperties appProperties;
    private final RepositorySystem repositorySystem;

    /** 按dbType缓存的ClassLoader */
    private final Map<String, URLClassLoader> classLoaderCache = new ConcurrentHashMap<>();

    /** 下载进度回调（前端通过WebSocket接收进度） */
    public interface DownloadProgressListener {
        void onProgress(String dbType, int percent, String message);
    }

    public MavenDriverManager(AppProperties appProperties) {
        this.appProperties = appProperties;
        this.repositorySystem = newRepositorySystem();

        // 确保缓存目录存在
        File cacheDir = new File(getDriverCacheDir());
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
            log.info("创建驱动缓存目录: {}", cacheDir.getAbsolutePath());
        }
    }

    /**
     * 获取或下载驱动JAR，返回隔离的ClassLoader
     *
     * @param driverInfo 驱动配置
     * @return 包含驱动JAR的URLClassLoader
     */
    public ClassLoader getOrCreateClassLoader(DriverInfo driverInfo) throws Exception {
        String dbType = driverInfo.getDbType().toUpperCase();
        String cacheKey = dbType + ":" + driverInfo.getMavenCoordinates().getVersion();

        // 检查缓存
        URLClassLoader cached = classLoaderCache.get(cacheKey);
        if (cached != null) {
            log.debug("使用缓存的ClassLoader: {}", cacheKey);
            return cached;
        }

        log.info("准备加载驱动: {} {}", driverInfo.getDisplayName(), driverInfo.getMavenCoordinates());

        // 下载驱动及其依赖
        List<File> jarFiles = resolveArtifacts(driverInfo.getMavenCoordinates());

        if (jarFiles.isEmpty()) {
            throw new RuntimeException("无法下载驱动JAR: " + driverInfo.getMavenCoordinates());
        }

        // 构建URLClassLoader
        URL[] urls = jarFiles.stream()
                .map(File::toURI)
                .map(uri -> {
                    try {
                        return uri.toURL();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .toArray(URL[]::new);

        URLClassLoader classLoader = new URLClassLoader(urls, ClassLoader.getSystemClassLoader()) {
            @Override
            public Class<?> loadClass(String name) throws ClassNotFoundException {
                try {
                    // 先尝试从自己的JAR加载
                    return super.loadClass(name, false);
                } catch (ClassNotFoundException e) {
                    // 找不到则从系统ClassLoader加载
                    return ClassLoader.getSystemClassLoader().loadClass(name);
                }
            }
        };

        classLoaderCache.put(cacheKey, classLoader);
        log.info("驱动加载成功: {} ({}个JAR)", driverInfo.getDisplayName(), jarFiles.size());

        return classLoader;
    }

    /**
     * 检查驱动是否已下载
     */
    public boolean isDriverDownloaded(DriverInfo driverInfo) {
        try {
            File localRepo = new File(getDriverCacheDir());
            String groupPath = driverInfo.getMavenCoordinates().getGroupId().replace('.', '/');
            String artifactId = driverInfo.getMavenCoordinates().getArtifactId();
            String version = driverInfo.getMavenCoordinates().getVersion();

            File jarFile = new File(localRepo,
                    groupPath + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version + ".jar");
            return jarFile.exists();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取下载进度（通过WebSocket推送）
     */
    public void downloadDriverWithProgress(DriverInfo driverInfo, DownloadProgressListener listener) throws Exception {
        String dbType = driverInfo.getDbType().toUpperCase();
        if (isDriverDownloaded(driverInfo)) {
            listener.onProgress(dbType, 100, "驱动已存在，直接加载");
            return;
        }

        listener.onProgress(dbType, 10, "正在解析依赖...");
        List<File> jarFiles = resolveArtifacts(driverInfo.getMavenCoordinates());

        listener.onProgress(dbType, 80, "正在加载驱动JAR...");
        URL[] urls = jarFiles.stream()
                .map(File::toURI)
                .map(uri -> {
                    try {
                        return uri.toURL();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .toArray(URL[]::new);

        URLClassLoader classLoader = new URLClassLoader(urls, ClassLoader.getSystemClassLoader());
        String cacheKey = dbType + ":" + driverInfo.getMavenCoordinates().getVersion();
        classLoaderCache.put(cacheKey, classLoader);

        listener.onProgress(dbType, 100, "驱动加载完成");
        log.info("驱动下载完成: {}", driverInfo.getDisplayName());
    }

    /**
     * 使用Maven Resolver解析并下载制品
     */
    private List<File> resolveArtifacts(MavenCoordinates coords) throws Exception {
        String artifactId = coords.getArtifactId();
        String version = coords.getVersion();
        String classifier = coords.getClassifier();

        org.eclipse.aether.artifact.Artifact artifact = new DefaultArtifact(
                coords.getGroupId(), artifactId,
                classifier != null ? classifier : "",
                "jar",
                version);

        RepositorySystemSession session = newSession();

        // 解析制品及其传递依赖
        CollectRequest collectRequest = new CollectRequest();
        collectRequest.setRoot(new Dependency(artifact, "runtime"));

        collectRequest.addRepository(aliyunRepo());
        collectRequest.addRepository(centralRepo());

        // 过滤：只要runtime范围的依赖
        DependencyFilter filter = DependencyFilterUtils.classpathFilter("runtime");

        DependencyRequest depRequest = new DependencyRequest(collectRequest, filter);
        DependencyResult depResult = repositorySystem.resolveDependencies(session, depRequest);

        // 收集所有JAR文件
        List<File> jarFiles = new ArrayList<>();
        for (org.eclipse.aether.graph.DependencyNode node : depResult.getRoot().getChildren()) {
            if (node.getArtifact() != null) {
                File file = node.getArtifact().getFile();
                if (file != null && file.exists() && file.getName().endsWith(".jar")) {
                    jarFiles.add(file);
                }
            }
        }

        // 确保主JAR在列表中
        ArtifactRequest artifactRequest = new ArtifactRequest();
        artifactRequest.setArtifact(artifact);
        artifactRequest.addRepository(aliyunRepo());
        artifactRequest.addRepository(centralRepo());

        ArtifactResult artifactResult = repositorySystem.resolveArtifact(session, artifactRequest);
        if (artifactResult.getArtifact().getFile() != null) {
            File mainJar = artifactResult.getArtifact().getFile();
            if (!jarFiles.contains(mainJar)) {
                jarFiles.add(0, mainJar);
            }
        }

        log.info("解析 {} 完成，共 {} 个JAR", coords, jarFiles.size());
        return jarFiles;
    }

    /**
     * 获取驱动缓存目录
     */
    private String getDriverCacheDir() {
        String dir = appProperties.getDriverCacheDir();
        if (dir == null || dir.isEmpty()) {
            dir = System.getProperty("user.home") + "/.lyradb/drivers";
        }
        return dir;
    }

    // === Maven Resolver 基础设施 ===

    private RepositorySystem newRepositorySystem() {
        return new RepositorySystemSupplier().get();
    }

    private RepositorySystemSession newSession() {
        DefaultRepositorySystemSession session = new DefaultRepositorySystemSession();
        LocalRepository localRepo = new LocalRepository(getDriverCacheDir());
        session.setLocalRepositoryManager(repositorySystem.newLocalRepositoryManager(session, localRepo));
        session.setArtifactDescriptorPolicy(new SimpleArtifactDescriptorPolicy(true, true));
        return session;
    }

    private RemoteRepository aliyunRepo() {
        return new RemoteRepository.Builder("aliyun", "default", ALIYAN_MIRROR).build();
    }

    private RemoteRepository centralRepo() {
        return new RemoteRepository.Builder("central", "default", MAVEN_CENTRAL).build();
    }
}
