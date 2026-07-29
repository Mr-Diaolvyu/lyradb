
package io.github.lexaquila.lyradb.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 从空 H2 数据库启动完整 Spring 上下文，验证 Flyway 先迁移、Hibernate 后校验。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:lyradb-startup;DB_CLOSE_DELAY=-1;MODE=MySQL",
                "jasypt.encryptor.password=integration-test-only-master-key",
                "app.edition=personal",
                "app.driver-cache-dir=target/test-driver-cache"
        })
@ActiveProfiles("dev")
class FlywayApplicationStartupTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void applicationStartsWithMigratedSchema() {
        Integer migrationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" "
                        + "WHERE \"version\" = '1' AND \"success\" = TRUE",
                Integer.class);
        Integer membershipTableCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM information_schema.tables
                 WHERE table_schema = 'PUBLIC'
                   AND table_name = 'SYS_WORKSPACE_MEMBERSHIP'
                """, Integer.class);

        assertEquals(1, migrationCount);
        assertEquals(1, membershipTableCount);
    }
}
