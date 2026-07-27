package io.github.lexaquila.lyradb.config;

import org.apache.catalina.Context;
import org.apache.catalina.WebResourceRoot;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.webresources.DirResourceSet;
import org.apache.catalina.webresources.StandardRoot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * BS 架构版本·前端静态资源根上下文配置。
 *
 * <p>
 * 后端 API 统一挂在 {@code context-path=/api} 下（控制器使用相对路径，依赖该前缀）。
 * 当 BS 版本把前端构建产物内嵌到 {@code classpath:/static/} 与后端同 jar 部署时，
 * 需要一个挂在根路径 {@code /} 的独立上下文来服务前端页面与静态资源，
 * 使浏览器访问 {@code http://<host>:<port>/} 即得前端，且 {@code index.html}
 * 中以 {@code /} 开头的资源绝对路径（如 {@code /assets/xxx.js}）能正确解析。
 * </p>
 *
 * <p>
 * 该根上下文仅服务静态资源，不注册 Spring Security 过滤链，与 {@code /api} 上下文互不干扰；
 * API 与 WebSocket（{@code /api/ws/**}）仍由主上下文处理。前端使用 Hash 路由，
 * 仅需根路径返回 {@code index.html}，无需 SPA fallback。
 * </p>
 *
 * <p>
 * Tomcat 10.1 不再提供基于 ClassLoader 的资源集，故启动时先把 {@code classpath:/static/**}
 * 抽取到临时目录，再用 {@link DirResourceSet} 服务，确保 fat jar（嵌套 jar）与 IDE 展开目录均可用。
 * </p>
 */
@Configuration
public class StaticResourceConfig {

    private static final Logger log = LoggerFactory.getLogger(StaticResourceConfig.class);

    @Bean
    public TomcatServletWebServerFactory tomcatServletWebServerFactory() {
        return new TomcatServletWebServerFactory() {
            @Override
            protected TomcatWebServer getTomcatWebServer(Tomcat tomcat) {
                File webRoot = extractStaticResources();
                if (webRoot != null) {
                    // 根上下文：docBase 指向抽取出的前端静态目录
                    Context rootContext = tomcat.addContext("", webRoot.getAbsolutePath());
                    WebResourceRoot resources = new StandardRoot(rootContext);
                    resources.addPreResources(new DirResourceSet(
                            resources, "/", webRoot.getAbsolutePath(), "/"));
                    rootContext.setResources(resources);
                    // 用 Tomcat DefaultServlet 服务静态资源
                    Wrapper wrapper = Tomcat.addServlet(
                            rootContext, "frontendStatic", "org.apache.catalina.servlets.DefaultServlet");
                    wrapper.addInitParameter("listings", "false");
                    wrapper.setLoadOnStartup(1);
                    rootContext.addServletMappingDecoded("/", "frontendStatic");
                    // 欢迎页：访问 / 返回 index.html
                    rootContext.addWelcomeFile("index.html");
                    // 独立上下文未继承默认 web.xml 的 mime-mapping，需显式补充，
                    // 否则 .js/.css 等返回空 MIME 被浏览器按严格 MIME 检查拒绝
                    addMimeMappings(rootContext);
                    log.info("BS 部署：前端静态资源根上下文已挂载于 /，来源 {}", webRoot.getAbsolutePath());
                } else {
                    log.info("未发现 classpath:/static/ 前端资源，跳过根上下文（纯 API 或开发模式）");
                }
                return super.getTomcatWebServer(tomcat);
            }
        };
    }

    /** 为独立根上下文补充常见静态资源 MIME 映射。 */
    private void addMimeMappings(Context ctx) {
        ctx.addMimeMapping("js", "application/javascript");
        ctx.addMimeMapping("mjs", "application/javascript");
        ctx.addMimeMapping("css", "text/css");
        ctx.addMimeMapping("html", "text/html");
        ctx.addMimeMapping("htm", "text/html");
        ctx.addMimeMapping("json", "application/json");
        ctx.addMimeMapping("svg", "image/svg+xml");
        ctx.addMimeMapping("png", "image/png");
        ctx.addMimeMapping("jpg", "image/jpeg");
        ctx.addMimeMapping("jpeg", "image/jpeg");
        ctx.addMimeMapping("gif", "image/gif");
        ctx.addMimeMapping("ico", "image/x-icon");
        ctx.addMimeMapping("woff", "font/woff");
        ctx.addMimeMapping("woff2", "font/woff2");
        ctx.addMimeMapping("ttf", "font/ttf");
        ctx.addMimeMapping("eot", "application/vnd.ms-fontobject");
        ctx.addMimeMapping("map", "application/json");
        ctx.addMimeMapping("txt", "text/plain");
        ctx.addMimeMapping("xml", "application/xml");
        ctx.addMimeMapping("webp", "image/webp");
    }

    /**
     * 将 classpath:/static/** 抽取到临时目录；若不存在前端资源则返回 null。
     */
    private File extractStaticResources() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(
                    PathMatchingResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX + "static/**");
            Path target = Files.createTempDirectory("lyradb-web-");
            boolean any = false;
            for (Resource res : resources) {
                String uri = res.getURI().toString();
                if (uri.endsWith("/")) {
                    continue; // 跳过目录条目
                }
                // 截取 static/ 之后的相对路径
                String path = uri.substring(uri.indexOf("/static/") + "/static/".length());
                if (path.isEmpty()) {
                    continue;
                }
                Path out = target.resolve(path);
                Files.createDirectories(out.getParent());
                try (InputStream in = res.getInputStream()) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
                any = true;
            }
            if (!any) {
                return null;
            }
            target.toFile().deleteOnExit();
            return target.toFile();
        } catch (IOException e) {
            log.warn("抽取前端静态资源失败，BS 根上下文将不可用：{}", e.getMessage());
            return null;
        }
    }
}
