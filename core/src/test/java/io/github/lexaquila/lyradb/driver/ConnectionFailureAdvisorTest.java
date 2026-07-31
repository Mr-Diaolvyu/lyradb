package io.github.lexaquila.lyradb.driver;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionFailureAdvisorTest {

    @Test
    void shouldClassifyMissingDriverArtifact() {
        RuntimeException failure = new RuntimeException(
                "Could not find artifact com.aliyun.odps:odps-jdbc:jar:bad");

        assertThat(ConnectionFailureAdvisor.message("MAXCOMPUTE", failure))
                .contains("驱动依赖不存在");
    }

    @Test
    void shouldClassifyProtocolHandshakeFailure() {
        IOException failure = new IOException(
                "Expected to read 4 bytes, read 0 bytes before connection was unexpectedly lost");

        assertThat(ConnectionFailureAdvisor.message("MYSQL", failure))
                .contains("MySQL 握手")
                .contains("SSL 模式");
    }

    @Test
    void shouldClassifyAuthenticationFailure() {
        SQLException failure = new SQLException(
                "Access denied for user", "28000");

        assertThat(ConnectionFailureAdvisor.message("MYSQL", failure))
                .contains("身份验证失败");
    }

    @Test
    void shouldExplainMaxComputeExecutionProjectFailure() {
        SQLException failure = new SQLException(
                "Code=NoSuchObject, Database not found - "
                        + "Schema maxcompute_public_data does not exist");

        assertThat(ConnectionFailureAdvisor.message(
                "MAXCOMPUTE", failure))
                .contains("执行 Project")
                .contains("BIGDATA_PUBLIC_DATASET");
    }
}
