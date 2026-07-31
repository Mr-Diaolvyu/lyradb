package io.github.lexaquila.lyradb.desktop.ui;

import io.github.lexaquila.lyradb.desktop.model.DesktopConnection;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionErrorAdvisorMaxComputeTest {

    @Test
    void noSuchObjectExplainsExecutionProjectAndPublicDatasetBoundary() {
        DesktopConnection connection = new DesktopConnection();
        connection.setName("旧数仓");
        connection.setDbType("MAXCOMPUTE");
        connection.setParams(Map.of(
                "endpoint",
                "https://service.cn-hangzhou.maxcompute.aliyun.com/api",
                "project", "maxcompute_public_data"));

        String explanation = ConnectionErrorAdvisor.explain(
                connection,
                new SQLException(
                        "Code=NoSuchObject, Database not found - "
                                + "Schema maxcompute_public_data does not exist"));

        assertThat(explanation)
                .contains("执行 Project")
                .contains("CREATE INSTANCE")
                .contains("Endpoint")
                .contains("BIGDATA_PUBLIC_DATASET")
                .contains("不能作为执行 Project");
    }
}
