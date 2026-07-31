package io.github.lexaquila.lyradb.driver;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MaxComputeConnectionOptionsTest {

    @Test
    void mapsProjectEndpointSchemaAndTimeoutToOfficialJdbcProperties() {
        Properties properties = new Properties();

        MaxComputeConnectionOptions.apply(properties, Map.of(
                "endpoint",
                "https://service.cn-hangzhou.maxcompute.aliyun.com/api",
                "project", "my_execution_project",
                "schema", "analytics",
                "schemaMode", "ENABLED",
                "connectTimeoutSeconds", 20));

        assertThat(properties)
                .containsEntry("end_point",
                        "https://service.cn-hangzhou.maxcompute.aliyun.com/api")
                .containsEntry("project_name", "my_execution_project")
                .containsEntry("schema", "analytics")
                .containsEntry("odps_namespace_schema", "true")
                .containsEntry("connect_timeout", "20000")
                .containsEntry("read_timeout", "80000")
                .containsEntry("charset", "UTF-8");
    }

    @Test
    void automaticSchemaModeDoesNotOverrideProjectConfiguration() {
        Properties properties = new Properties();

        MaxComputeConnectionOptions.apply(properties, Map.of(
                "endpoint", "https://service.example/api",
                "project", "my_project",
                "schemaMode", "AUTO"));

        assertThat(properties)
                .doesNotContainKey("odps_namespace_schema")
                .doesNotContainKey("schema");
    }

    @Test
    void publicDatasetCannotBeUsedAsExecutionProject() {
        assertThatThrownBy(() -> MaxComputeConnectionOptions.apply(
                new Properties(), Map.of(
                        "endpoint", "https://service.example/api",
                        "project", "maxcompute_public_data")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("公共数据集 Project")
                .hasMessageContaining("CREATE INSTANCE");
    }

    @Test
    void rejectsPublicDatasetProjectUsedAsDefaultSchema() {
        assertThatThrownBy(() -> MaxComputeConnectionOptions.apply(
                new Properties(),
                Map.of(
                        "endpoint", "https://service.odps.aliyun.com/api",
                        "project", "my_execution_project",
                        "schema", "maxcompute_public_data")))
                .hasMessageContaining("默认 Schema")
                .hasMessageContaining("BIGDATA_PUBLIC_DATASET");
    }
}
