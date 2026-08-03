package io.github.lexaquila.lyradb.desktop.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlCompletionContextTest {

    @Test
    void resolvesAliasForColumnCompletion() {
        String sql = "SELECT u.na FROM app_user AS u WHERE u.id > 0";
        SqlCompletionContext context = SqlCompletionContext.at(
                sql, sql.indexOf("u.na") + "u.na".length());

        assertThat(context.prefix()).isEqualTo("na");
        assertThat(context.qualifier()).isEqualTo("u");
        assertThat(context.resolveQualifier().schema()).isNull();
        assertThat(context.resolveQualifier().table()).isEqualTo("app_user");
    }

    @Test
    void keepsQualifiedSchemaForAlias() {
        String sql = "SELECT o. FROM erp.sales_order o";
        SqlCompletionContext context = SqlCompletionContext.at(
                sql, sql.indexOf("o.") + 2);

        assertThat(context.resolveQualifier().schema()).isEqualTo("erp");
        assertThat(context.resolveQualifier().table()).isEqualTo("sales_order");
    }
}
