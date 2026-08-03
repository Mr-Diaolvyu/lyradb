package io.github.lexaquila.lyradb.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlTextFormatterTest {

    @Test
    void formatsClausesWithoutChangingQuotedText() {
        String formatted = SqlTextFormatter.format(
                "select id,name from users where name='from here' and id>1");

        assertThat(formatted).isEqualTo("""
                SELECT id, name
                FROM users
                WHERE name = 'from here'
                  AND id > 1;""");
    }

    @Test
    void preservesCommentsAndVendorIdentifiers() {
        String formatted = SqlTextFormatter.format(
                "select `order`, /* keep from */ value from `sales-order`");

        assertThat(formatted)
                .contains("`order`")
                .contains("/* keep from */")
                .contains("`sales-order`")
                .endsWith(";");
    }

    @Test
    void doesNotAddDuplicateSemicolon() {
        assertThat(SqlTextFormatter.format("show tables;"))
                .isEqualTo("SHOW TABLES;");
    }
}
