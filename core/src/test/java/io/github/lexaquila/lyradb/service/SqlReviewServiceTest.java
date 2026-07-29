package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.dto.SqlReviewFinding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlReviewServiceTest {

    private final SqlReviewService service = new SqlReviewService();

    @Test
    void shouldNotTreatTableOrStringTextAsWhereClause() {
        assertThat(ruleIds(service.review(
                "UPDATE somewhere_table SET enabled = false", "POSTGRESQL")))
                .contains("R1_UPDATE_NO_WHERE");
        assertThat(ruleIds(service.review(
                "UPDATE account SET note = 'where'", "MYSQL")))
                .contains("R1_UPDATE_NO_WHERE");
        assertThat(ruleIds(service.review(
                "DELETE FROM somewhere_archive", "POSTGRESQL")))
                .contains("R2_DELETE_NO_WHERE");
    }

    @Test
    void shouldAcceptActualWhereClause() {
        assertThat(ruleIds(service.review(
                "UPDATE account SET enabled = false WHERE id = 1", "POSTGRESQL")))
                .doesNotContain("R1_UPDATE_NO_WHERE");
        assertThat(ruleIds(service.review(
                "DELETE FROM account WHERE id = 1", "POSTGRESQL")))
                .doesNotContain("R2_DELETE_NO_WHERE");
    }

    @Test
    void shouldBlockDataModifyingCte() {
        List<SqlReviewFinding> findings = service.review(
                "WITH removed AS (DELETE FROM account RETURNING *) "
                        + "SELECT * FROM removed",
                "POSTGRESQL");

        assertThat(ruleIds(findings)).contains("R7_DATA_MODIFYING_CTE");
        assertThat(service.hasBlocking(findings)).isTrue();
    }

    @Test
    void shouldBlockRedisDatabaseFlush() {
        List<SqlReviewFinding> flushDb = service.review("FLUSHDB ASYNC", "REDIS");
        List<SqlReviewFinding> flushAll = service.review("flushall;", "redis");

        assertThat(ruleIds(flushDb)).containsExactly("R8_REDIS_FLUSH");
        assertThat(ruleIds(flushAll)).containsExactly("R8_REDIS_FLUSH");
        assertThat(service.hasHigh(flushDb)).isTrue();
    }

    @Test
    void shouldFailClosedForDangerousMongoWriteDsl() {
        List<SqlReviewFinding> emptyDelete = service.review(
                "{\"op\":\"delete\",\"db\":\"app\","
                        + "\"collection\":\"customer\",\"filter\":{}}",
                "MONGODB");
        List<SqlReviewFinding> missingUpdateFilter = service.review(
                "{\"op\":\"update\",\"db\":\"app\","
                        + "\"collection\":\"customer\","
                        + "\"update\":{\"$set\":{\"active\":false}}}",
                "MONGODB");
        List<SqlReviewFinding> drop = service.review(
                "{\"op\":\"drop_collection\",\"db\":\"app\","
                        + "\"collection\":\"customer\"}",
                "MONGODB");
        List<SqlReviewFinding> malformed = service.review(
                "{\"op\":\"delete\"", "MONGODB");

        assertThat(ruleIds(emptyDelete))
                .containsExactly("R9_MONGODB_EMPTY_FILTER");
        assertThat(ruleIds(missingUpdateFilter))
                .containsExactly("R9_MONGODB_EMPTY_FILTER");
        assertThat(ruleIds(drop)).containsExactly("R10_MONGODB_DROP");
        assertThat(ruleIds(malformed))
                .containsExactly("R11_MONGODB_INVALID_DSL");
        assertThat(service.hasBlocking(emptyDelete)).isTrue();
    }

    @Test
    void shouldAllowMongoReadAndExplicitFilter() {
        assertThat(service.review("app.customer", "MONGODB")).isEmpty();
        assertThat(service.review(
                "{\"op\":\"delete\",\"db\":\"app\","
                        + "\"collection\":\"customer\",\"filter\":{\"id\":1}}",
                "MONGODB")).isEmpty();
    }

    private static List<String> ruleIds(List<SqlReviewFinding> findings) {
        return findings.stream().map(SqlReviewFinding::getRuleId).toList();
    }
}
