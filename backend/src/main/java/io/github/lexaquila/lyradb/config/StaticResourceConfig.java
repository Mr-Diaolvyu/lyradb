package io.github.lexaquila.lyradb.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.catalina.Context;
import org.apache.catalina.WebResourceRoot;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.webresources.DirResourceSet;
import org.apache.catalina.webresources.StandardRoot;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import jakarta.annotation.PreDestroy;

/**
 * BS 架构版本·前端静态资源根上下文配置。
 *
 * <p>后端 API 统一挂在 {@code context-path=/api} 下；内嵌到
 * {@code classpath:/static/} 的 Vite 构建产物由独立根 Context 服务。
 * 根 Context 不经过 Spring Security，因此在 Context 内独立注册浏览器安全头过滤器。</p>
 *
 * <p>Tomcat 10.1 不再提供基于 ClassLoader 的资源集，启动时仅把明确的 dist
 * 白名单资源抽取到临时目录，再用 {@link DirResourceSet} 服务。临时目录由应用
 * 生命周期统一清理，不依赖 JVM 的 {@code deleteOnExit}。</p>
 */
@Configuration
public class StaticResourceConfig {

    private static final Logger log = LoggerFactory.getLogger(StaticResourceConfig.class);
    private static final String STATIC_PREFIX = "/static/";
    private static final Pattern SAFE_STATIC_PATH =
            Pattern.compile("^[A-Za-z0-9._/-]+$");

    private final Set<Path> extractedRoots = ConcurrentHashMap.newKeySet();

    @Bean
    public TomcatServletWebServerFactory tomcatServletWebServerFactory() {
        return new TomcatServletWebServerFactory() {
            @Override
            protected TomcatWebServer getTomcatWebServer(Tomcat tomcat) {
                File webRoot = extractStaticResources();
                if (webRoot != null) {
                    Context rootContext = tomcat.addContext("", webRoot.getAbsolutePath());
                    WebResourceRoot resources = new StandardRoot(rootContext);
                    resources.addPreResources(new DirResourceSet(
                            resources, "/", webRoot.getAbsolutePath(), "/"));
                    rootContext.setResources(resources);

                    addSecurityHeadersFilter(rootContext);
                    Wrapper wrapper = Tomcat.addServlet(
                            rootContext, "frontendStatic",
                            "org.apache.catalina.servlets.DefaultServlet");
                    wrapper.addInitParameter("listings", "false");
                    wrapper.setLoadOnStartup(1);
                    rootContext.addServletMappingDecoded("/", "frontendStatic");
                    rootContext.addWelcomeFile("index.html");
                    addMimeMappings(rootContext);
                    log.info("BS 部署：前端静态资源根上下文已挂载于 /，来源 {}",
                            webRoot.getAbsolutePath());
                } else {
                    log.info("未发现 classpath:/static/ 前端资源，"
                            + "跳过根上下文（纯 API 或开发模式）");
                }
                return super.getTomcatWebServer(tomcat);
            }
        };
    }

    private void addSecurityHeadersFilter(Context context) {
        FilterDef definition = new FilterDef();
        definition.setFilterName("frontendSecurityHeaders");
        definition.setFilter(new FrontendSecurityHeadersFilter());
        definition.setAsyncSupported("true");
        context.addFilterDef(definition);

        FilterMap mapping = new FilterMap();
        mapping.setFilterName("frontendSecurityHeaders");
        mapping.addURLPattern("/*");
        context.addFilterMapBefore(mapping);
    }

    /** 为独立根上下文补充常见静态资源 MIME 映射。 */
    private void addMimeMappings(Context context) {
        context.addMimeMapping("js", "application/javascript");
        context.addMimeMapping("mjs", "application/javascript");
        context.addMimeMapping("css", "text/css");
        context.addMimeMapping("html", "text/html");
        context.addMimeMapping("htm", "text/html");
        context.addMimeMapping("json", "application/json");
        context.addMimeMapping("svg", "image/svg+xml");
        context.addMimeMapping("png", "image/png");
        context.addMimeMapping("jpg", "image/jpeg");
        context.addMimeMapping("jpeg", "image/jpeg");
        context.addMimeMapping("gif", "image/gif");
        context.addMimeMapping("ico", "image/x-icon");
        context.addMimeMapping("woff", "font/woff");
        context.addMimeMapping("woff2", "font/woff2");
        context.addMimeMapping("ttf", "font/ttf");
        context.addMimeMapping("eot", "application/vnd.ms-fontobject");
        context.addMimeMapping("map", "application/json");
        context.addMimeMapping("txt", "text/plain");
        context.addMimeMapping("xml", "application/xml");
        context.addMimeMapping("webp", "image/webp");
    }

    /**
     * 将 classpath:/static/** 中的 dist 白名单资源抽取到临时目录；
     * 若不存在可用前端资源则返回 null。
     */
    private File extractStaticResources() {
        Path target = null;
        try {
            PathMatchingResourcePatternResolver resolver =
                    new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(
                    PathMatchingResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX
                            + "static/**");
            target = Files.createTempDirectory("lyradb-web-");
            boolean any = false;
            for (Resource resource : resources) {
                String uri = resource.getURI().toString();
                if (uri.endsWith("/")) {
                    continue;
                }
                int prefixIndex = uri.lastIndexOf(STATIC_PREFIX);
                if (prefixIndex < 0) {
                    continue;
                }
                String relativePath =
                        uri.substring(prefixIndex + STATIC_PREFIX.length());
                if (!isAllowedStaticPath(relativePath)) {
                    log.debug("忽略非 dist 白名单静态资源：{}", relativePath);
                    continue;
                }

                Path output = target.resolve(relativePath).normalize();
                if (!output.startsWith(target)) {
                    continue;
                }
                Files.createDirectories(output.getParent());
                try (InputStream input = resource.getInputStream()) {
                    Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
                }
                any = true;
            }
            if (!any) {
                deleteRecursively(target);
                return null;
            }
            extractedRoots.add(target);
            return target.toFile();
        } catch (IOException exception) {
            deleteRecursively(target);
            log.warn("抽取前端静态资源失败，BS 根上下文将不可用：{}",
                    exception.getMessage());
            return null;
        }
    }

    static boolean isAllowedStaticPath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/")
                || path.contains("..") || path.indexOf('\\') >= 0
                || !SAFE_STATIC_PATH.matcher(path).matches()) {
            return false;
        }
        return "index.html".equals(path)
                || "theme-init.js".equals(path)
                || "favicon.svg".equals(path)
                || (path.startsWith("assets/") && path.length() > "assets/".length());
    }

    @PreDestroy
    void cleanupExtractedResources() {
        for (Path root : Set.copyOf(extractedRoots)) {
            deleteRecursively(root);
            extractedRoots.remove(root);
        }
    }

    private void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    log.debug("清理前端临时资源失败：{}", path, exception);
                }
            });
        } catch (IOException exception) {
            log.warn("遍历前端临时资源失败：{}", root, exception);
        }
    }
}
