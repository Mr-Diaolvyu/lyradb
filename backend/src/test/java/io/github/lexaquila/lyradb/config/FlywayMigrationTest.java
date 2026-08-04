package io.github.lexaquila.lyradb.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

/**
 * 验证空库初始化与历史库就地升级都由同一份 Flyway 基线完成。
 */
class FlywayMigrationTest {

    @Test
    void cleanDatabaseMigratesOnceAndRemainsIdempotent() throws Exception {
        String url = memoryDatabaseUrl("clean");
        Flyway flyway = flyway(url);

        assertEquals(7, flyway.migrate().migrationsExecuted);
        assertEquals(0, flyway.migrate().migrationsExecuted);

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            assertColumnExists(connection, "SYS_USER", "CREDENTIAL_VERSION");
            assertColumnExists(connection, "ENT_AUDIT_LOG", "DETAILS_JSON");
            assertColumnExists(connection, "SYS_WORKSPACE_MEMBERSHIP", "ROLES_CSV");
            assertColumnExists(connection, "ENT_MASKING_RULE", "WORKSPACE_ID");
            assertColumnExists(connection, "ENT_AUDIT_LOG", "ACTION");
            assertColumnExists(connection, "REPORT_SCHEDULE", "OWNER_USERNAME");
            assertColumnExists(connection, "AI_EVALUATION_RUN", "REPORT_JSON");
            assertColumnExists(connection, "AI_GATEWAY_TOKEN", "TOKEN_SHA256");
            assertColumnExists(connection, "AI_KNOWLEDGE_ASSET", "EMBEDDING_JSON");
            assertColumnExists(connection, "AI_EVALUATION_RUN", "EVALUATION_MODE");
            assertColumnExists(connection, "AI_AGENT_RUN", "PLAN_PAYLOAD_CIPHERTEXT");
            assertColumnExists(connection, "AI_AGENT_RUN", "PLAN_CONSUMED");
            assertColumnExists(connection, "AI_AGENT_RUN", "CANCEL_REQUESTED");
            assertColumnExists(connection, "AI_PROVIDER_CONFIG", "DEPLOYMENT_MODE");
            assertColumnExists(connection, "AI_MAXCOMPUTE_PREFLIGHT", "TOKEN_SHA256");
            assertColumnExists(connection, "AI_MAXCOMPUTE_PREFLIGHT", "EXPIRES_AT");
        }
    }

    @Test
    void legacyDatabaseIsBaselinedUpgradedAndDataIsPreserved() throws Exception {
        String url = memoryDatabaseUrl("legacy");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE sys_user (
                        id VARCHAR(36) PRIMARY KEY,
                        username VARCHAR(100) NOT NULL,
                        password_hash VARCHAR(100) NOT NULL,
                        enabled BOOLEAN NOT NULL
                    )
                    """);
            statement.execute("""
                    INSERT INTO sys_user(id, username, password_hash, enabled)
                    VALUES ('user-1', 'legacy-user', 'legacy-hash', TRUE)
                    """);
            statement.execute("""
                    CREATE TABLE sys_workspace (
                        id VARCHAR(36) PRIMARY KEY,
                        name VARCHAR(100) NOT NULL
                    )
                    """);
            statement.execute("""
                    INSERT INTO sys_workspace(id, name)
                    VALUES ('workspace-1', '历史工作空间')
                    """);
            statement.execute("""
                    CREATE TABLE ent_data_source (
                        id VARCHAR(36) PRIMARY KEY,
                        workspace_id VARCHAR(36),
                        db_type VARCHAR(32) NOT NULL,
                        display_name VARCHAR(100) NOT NULL,
                        connection_params_json CLOB NOT NULL
                    )
                    """);
            statement.execute("""
                    INSERT INTO ent_data_source(
                        id, workspace_id, db_type, display_name, connection_params_json)
                    VALUES ('source-1', 'workspace-1', 'H2', '历史数据源', '{}')
                    """);
            statement.execute("""
                    CREATE TABLE ent_masking_rule (
                        id VARCHAR(36) PRIMARY KEY,
                        data_source_id VARCHAR(36)
                    )
                    """);
            statement.execute("""
                    INSERT INTO ent_masking_rule(id, data_source_id)
                    VALUES ('mask-1', 'source-1')
                    """);
        }

        assertEquals(7, flyway(url).migrate().migrationsExecuted);

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            try (ResultSet user = statement.executeQuery("""
                    SELECT username, credential_version
                      FROM sys_user
                     WHERE id = 'user-1'
                    """)) {
                user.next();
                assertEquals("legacy-user", user.getString("username"));
                assertEquals(0L, user.getLong("credential_version"));
            }
            try (ResultSet masking = statement.executeQuery("""
                    SELECT workspace_id
                      FROM ent_masking_rule
                     WHERE id = 'mask-1'
                    """)) {
                masking.next();
                assertEquals("workspace-1", masking.getString("workspace_id"));
            }
            assertColumnExists(connection, "ENT_APPROVAL_REQUEST", "APPROVER_IDS");
            assertColumnExists(connection, "REPORT_SCHEDULE", "WORKSPACE_ID");
        }
    }

    private static Flyway flyway(String url) {
        return Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("0"))
                .cleanDisabled(true)
                .load();
    }

    private static String memoryDatabaseUrl(String label) {
        return "jdbc:h2:mem:" + label + "-" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1;MODE=MySQL";
    }

    private static void assertColumnExists(Connection connection, String table, String column)
            throws Exception {
        try (ResultSet result = connection.getMetaData().getColumns(
                null, "PUBLIC", table, column)) {
            assertNotNull(result);
            assertEquals(true, result.next(), table + "." + column + " 应存在");
        }
    }
}
