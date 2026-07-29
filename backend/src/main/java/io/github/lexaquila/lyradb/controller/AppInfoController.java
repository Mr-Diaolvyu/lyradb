package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.config.AppProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 应用信息（公开端点，供前端探测发行版与是否需登录）
 */
@RestController
@RequestMapping("/app")
public class AppInfoController {

    private final AppProperties appProperties;
    private final BuildProperties buildProperties;

    public AppInfoController(AppProperties appProperties,
                             ObjectProvider<BuildProperties> buildPropertiesProvider) {
        this.appProperties = appProperties;
        this.buildProperties = buildPropertiesProvider.getIfAvailable();
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        String edition = appProperties.getEdition() == null ? "personal" : appProperties.getEdition().toLowerCase();
        String version = buildProperties == null ? appProperties.getVersion() : buildProperties.getVersion();
        return Map.of(
                "edition", edition,
                "version", version,
                "authRequired", "enterprise".equals(edition)
        );
    }
}
