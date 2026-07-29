package io.github.lexaquila.lyradb.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

import io.github.lexaquila.lyradb.config.AppProperties;

class AppInfoControllerTest {

    @Test
    void 打包版本应覆盖配置中的开发版本() {
        AppProperties properties = new AppProperties();
        properties.setEdition("enterprise");
        properties.setVersion("3.0.0-dev");

        Properties buildValues = new Properties();
        buildValues.setProperty("version", "3.2.1");
        BuildProperties buildProperties = new BuildProperties(buildValues);

        @SuppressWarnings("unchecked")
        ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(buildProperties);

        Map<String, Object> info = new AppInfoController(properties, provider).info();

        assertThat(info)
                .containsEntry("edition", "enterprise")
                .containsEntry("version", "3.2.1")
                .containsEntry("authRequired", true);
    }

    @Test
    void 开发环境缺少构建信息时使用应用配置版本() {
        AppProperties properties = new AppProperties();
        properties.setVersion("3.0.0");

        @SuppressWarnings("unchecked")
        ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        assertThat(new AppInfoController(properties, provider).info())
                .containsEntry("version", "3.0.0");
    }
}
