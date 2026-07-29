package io.github.lexaquila.lyradb.driver;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * 驱动目录中的默认云端 Endpoint 不得把数据库凭据发送到明文 HTTP。
 */
class DriverCatalogSecurityTest {

    @Test
    void maxCompute默认端点必须使用Https() throws Exception {
        try (var stream = getClass().getResourceAsStream("/drivers.json")) {
            assertThat(stream).isNotNull();
            String catalog = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(catalog).doesNotContain("\"value\": \"http://service.");
            assertThat(catalog).contains(
                    "\"value\": \"https://service.cn-hangzhou.maxcompute.aliyun.com/api\"");
        }
    }
}
