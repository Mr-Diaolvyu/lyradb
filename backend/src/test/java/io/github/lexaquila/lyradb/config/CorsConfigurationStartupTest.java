package io.github.lexaquila.lyradb.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 凭据型 CORS 配置必须在应用启动阶段拒绝通配来源。
 */
class CorsConfigurationStartupTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            CorsTestConfiguration.class);

    @Test
    void wildcardCredentialOriginPreventsStartup() {
        contextRunner
                .withPropertyValues(
                        "app.cors.allowed-origins=*")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(
                                    IllegalStateException.class)
                            .hasRootCauseMessage(
                                    "启用凭据时禁止配置通配 CORS 来源");
                });
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties.class)
    @Import(CorsConfig.class)
    static class CorsTestConfiguration {
    }
}
