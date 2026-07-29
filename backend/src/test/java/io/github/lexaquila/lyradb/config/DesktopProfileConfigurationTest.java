package io.github.lexaquila.lyradb.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class DesktopProfileConfigurationTest {

    @Test
    void 桌面配置必须使用随机回环端口并关闭数据库控制台() throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load(
                "desktop",
                new ClassPathResource("application-desktop.yml"));

        assertThat(sources).hasSize(1);
        PropertySource<?> source = sources.get(0);
        assertThat(source.getProperty("server.address")).isEqualTo("127.0.0.1");
        assertThat(source.getProperty("server.port")).isEqualTo(0);
        assertThat(source.getProperty("spring.h2.console.enabled")).isEqualTo(false);
        assertThat(source.getProperty("app.edition")).isEqualTo("personal");
        assertThat(source.getProperty("app.desktop.tray-enabled")).isEqualTo(true);
    }
}
