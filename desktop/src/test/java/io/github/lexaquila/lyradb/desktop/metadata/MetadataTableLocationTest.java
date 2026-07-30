package io.github.lexaquila.lyradb.desktop.metadata;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataTableLocationTest {

    @ParameterizedTest(name = "{0} · {1}")
    @MethodSource("tableLocations")
    void shouldMapDriverSpecificTablePaths(
            String dbType, String path,
            String expectedDatabase, String expectedSchema,
            String expectedMetadataNamespace) {
        MetadataTableLocation location = MetadataTableLocation.resolve(
                dbType, "连接级数据库", path);

        assertThat(location.databaseName()).isEqualTo(expectedDatabase);
        assertThat(location.schemaName()).isEqualTo(expectedSchema);
        assertThat(location.metadataNamespace())
                .isEqualTo(expectedMetadataNamespace);
    }

    private static Stream<Arguments> tableLocations() {
        return Stream.of(
                Arguments.of("MSSQL", "sales/dbo/orders",
                        "sales", "dbo", "sales/dbo"),
                Arguments.of("MYSQL", "sales/orders",
                        "sales", "", "sales"),
                Arguments.of("CLICKHOUSE", "analytics/events",
                        "analytics", "", "analytics"),
                Arguments.of("MONGODB", "sales/orders",
                        "sales", "", "sales"),
                Arguments.of("POSTGRESQL", "public/orders",
                        "连接级数据库", "public", "public"),
                Arguments.of("ORACLE", "APP/ORDERS",
                        "连接级数据库", "APP", "APP"),
                Arguments.of("SQLITE", "orders",
                        "连接级数据库", "", ""),
                Arguments.of("MAXCOMPUTE", "orders",
                        "连接级数据库", "", "")
        );
    }
}
