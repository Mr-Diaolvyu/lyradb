package io.github.lexaquila.lyradb.desktop.ui;

import io.github.lexaquila.lyradb.desktop.model.DesktopConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionErrorAdvisorTest {

    @Test
    void mysqlHandshakeFailureMustExplainPortAndProtocolChecks() {
        DesktopConnection connection = connection("MYSQL");
        Throwable failure = new RuntimeException("outer",
                new IOException("Can not read response from server. "
                        + "Expected to read 4 bytes, read 0 bytes before connection "
                        + "was unexpectedly lost."));

        String explanation = ConnectionErrorAdvisor.explain(connection, failure);

        assertThat(explanation)
                .contains("MySQL 测试（MYSQL）")
                .contains("127.0.0.1:3306")
                .contains("协议握手完成前主动关闭")
                .contains("MySQL 默认端口为 3306")
                .contains("SSL 模式")
                .contains("RSA 公钥")
                .contains("IOException");
    }

    @Test
    void sqlServerTlsFailureMustSuggestCertificateOption() {
        DesktopConnection connection = connection("MSSQL");

        String explanation = ConnectionErrorAdvisor.explain(connection,
                new SQLException("PKIX certificate validation failed", "08001", 0));

        assertThat(explanation)
                .contains("SQL Server")
                .contains("TLS/SSL")
                .contains("信任服务器证书")
                .contains("SQLState=08001");
    }

    @Test
    void technicalDetailMustRedactSensitiveValues() {
        String explanation = ConnectionErrorAdvisor.explain(connection("MYSQL"),
                new SQLException(
                        "password=plain-secret apiKey=api-secret access denied"));

        assertThat(explanation)
                .contains("password=***")
                .contains("apiKey=***")
                .doesNotContain("plain-secret")
                .doesNotContain("api-secret");
    }

    private static DesktopConnection connection(String dbType) {
        DesktopConnection connection = new DesktopConnection();
        connection.setName(dbType.equals("MSSQL")
                ? "SQL Server 测试" : "MySQL 测试");
        connection.setDbType(dbType);
        connection.setParams(Map.of("host", "127.0.0.1", "port", 3306));
        return connection;
    }
}
