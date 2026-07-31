package io.github.lexaquila.lyradb.driver;

import io.github.lexaquila.lyradb.model.entity.DriverInfo;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class GenericJdbcDriverConnectionPropertiesTest {

    @Test
    void mysqlConnectionTimeoutUsesMillisecondsAndIsBounded() {
        Properties defaults = driver("MYSQL").properties(Map.of());
        Properties custom = driver("MYSQL").properties(Map.of(
                "connectTimeoutSeconds", 15));
        Properties bounded = driver("MYSQL").properties(Map.of(
                "connectTimeoutSeconds", 999));

        assertThat(defaults)
                .containsEntry("connectTimeout", "10000")
                .containsEntry("tcpKeepAlive", "true")
                .containsEntry("sslMode", "PREFERRED")
                .containsEntry("allowPublicKeyRetrieval", "false")
                .containsEntry("rewriteBatchedStatements", "true")
                .containsEntry("enabledTLSProtocols", "TLSv1.2,TLSv1.3");
        assertThat(custom).containsEntry("connectTimeout", "15000");
        assertThat(bounded).containsEntry("connectTimeout", "120000");
    }

    @Test
    void mysqlConnectionSupportsEmptyDatabaseAndExplicitSslMode() {
        DriverRegistry registry = new DriverRegistry();
        registry.init();
        DriverInfo mysql = registry.getDriverInfo("MYSQL");
        InspectableDriver driver = new InspectableDriver(mysql);

        assertThat(mysql.getMavenCoordinates().getVersion()).isEqualTo("8.2.0");
        assertThat(mysql.getConnectionFormFields())
                .filteredOn(field -> "database".equals(field.getName()))
                .singleElement()
                .extracting("required")
                .isEqualTo(false);
        assertThat(mysql.getConnectionFormFields())
                .filteredOn(field -> "sslMode".equals(field.getName()))
                .singleElement()
                .satisfies(field -> {
                    assertThat(field.getDefaultValue()).isEqualTo("PREFERRED");
                    assertThat(field.getOptions())
                            .extracting("value")
                            .containsExactly(
                                    "PREFERRED", "REQUIRED", "VERIFY_CA",
                                    "VERIFY_IDENTITY", "DISABLED");
                });
        assertThat(driver.buildConnectionUrl(Map.of(
                "host", "47.98.208.142",
                "port", 9002,
                "database", "")))
                .isEqualTo("jdbc:mysql://47.98.208.142:9002/");
        assertThat(mysql.getConnectionFormFields())
                .filteredOn(field -> "allowPublicKeyRetrieval".equals(field.getName()))
                .singleElement()
                .extracting("defaultValue")
                .isEqualTo(false);
        assertThat(driver.properties(Map.of(
                "sslMode", "DISABLED",
                "allowPublicKeyRetrieval", true)))
                .containsEntry("sslMode", "DISABLED")
                .containsEntry("allowPublicKeyRetrieval", "true")
                .doesNotContainKey("enabledTLSProtocols");
    }

    @Test
    void sqlServerConnectionPropertiesIncludeTlsTrustAndLoginTimeout() {
        Properties properties = driver("MSSQL").properties(Map.of(
                "connectTimeoutSeconds", 8,
                "trustServerCertificate", true));

        assertThat(properties)
                .containsEntry("loginTimeout", "8")
                .containsEntry("trustServerCertificate", "true");
    }

    @Test
    void registryAcceptsCommonSqlServerAliases() {
        DriverRegistry registry = new DriverRegistry();
        registry.init();

        assertThat(registry.isSupported("sqlserver")).isTrue();
        assertThat(registry.isSupported("sql_server")).isTrue();
        assertThat(registry.getDriverInfo("SQL-SERVER").getDbType())
                .isEqualTo("MSSQL");
        assertThat(registry.getDriverInfo("MSSQL").getDefaultPort())
                .isEqualTo(1433);
        assertThat(registry.getDriverInfo("MSSQL").getConnectionUrlTemplate())
                .contains("trustServerCertificate={trustServerCertificate}");
        assertThat(registry.getDriverInfo("MSSQL").getConnectionFormFields())
                .extracting("name")
                .contains("connectTimeoutSeconds", "trustServerCertificate");
    }

    private static InspectableDriver driver(String dbType) {
        DriverInfo info = new DriverInfo();
        info.setDbType(dbType);
        return new InspectableDriver(info);
    }

    private static final class InspectableDriver extends GenericJdbcDriver {
        private InspectableDriver(DriverInfo driverInfo) {
            super(driverInfo, GenericJdbcDriverConnectionPropertiesTest.class
                    .getClassLoader());
        }

        private Properties properties(Map<String, Object> params) {
            Properties result = new Properties();
            setExtraConnectionProperties(result, params);
            return result;
        }
    }
}
