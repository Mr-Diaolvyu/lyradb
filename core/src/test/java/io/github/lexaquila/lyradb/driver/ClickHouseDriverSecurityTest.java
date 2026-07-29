package io.github.lexaquila.lyradb.driver;

import io.github.lexaquila.lyradb.model.entity.DriverInfo;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClickHouseDriverSecurityTest {

    @Test
    void shouldRequireTlsForRemoteClickHouse() {
        DriverInfo info = clickHouseInfo();
        ClickHouseDriver driver = new ClickHouseDriver(
                info, getClass().getClassLoader());

        Map<String, Object> remote = params("db.example.com", "https", 8443);
        assertThat(driver.buildConnectionUrl(remote))
                .isEqualTo("jdbc:ch:https://db.example.com:8443/default");

        remote.put("protocol", "http");
        assertThatThrownBy(() -> driver.buildConnectionUrl(remote))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须使用 HTTPS");
    }

    @Test
    void shouldAllowExplicitHttpOnlyForLoopback() {
        ClickHouseDriver driver = new ClickHouseDriver(
                clickHouseInfo(), getClass().getClassLoader());

        assertThat(driver.buildConnectionUrl(params("127.0.0.1", "http", 8123)))
                .isEqualTo("jdbc:ch:http://127.0.0.1:8123/default");
    }

    @Test
    void catalogShouldExposeSecureProtocolChoice() {
        DriverInfo info = clickHouseInfo();

        assertThat(info.getDefaultPort()).isEqualTo(8443);
        assertThat(info.getConnectionFormFields())
                .anySatisfy(field -> {
                    assertThat(field.getName()).isEqualTo("protocol");
                    assertThat(field.getDefaultValue()).isEqualTo("https");
                });
    }

    private static DriverInfo clickHouseInfo() {
        DriverRegistry registry = new DriverRegistry();
        registry.init();
        return registry.getDriverInfo("CLICKHOUSE");
    }

    private static Map<String, Object> params(
            String host, String protocol, int port) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocol", protocol);
        params.put("host", host);
        params.put("port", port);
        params.put("database", "default");
        return params;
    }
}
