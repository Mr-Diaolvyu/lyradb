package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.config.AppProperties;
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

    public AppInfoController(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        String edition = appProperties.getEdition() == null ? "personal" : appProperties.getEdition().toLowerCase();
        return Map.of(
                "edition", edition,
                "version", "3.0.0",
                "authRequired", "enterprise".equals(edition)
        );
    }
}
