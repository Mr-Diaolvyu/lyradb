package io.github.lexaquila.lyradb.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlParseUtilTest {

    @Test
    void extractsQualifiedTablesAndAliasLineage() {
        SqlParseUtil.Analysis analysis = SqlParseUtil.requireReadOnly(
                "SELECT u.email AS mail FROM public.users u");

        assertEquals(Set.of("public.users"), analysis.tables());
        assertTrue(analysis.lineageComplete());
        assertEquals(
                Set.of(new SqlParseUtil.SourceColumn(
                        "public.users", "email")),
                analysis.outputLineage().get("mail"));
    }

    @Test
    void extractsEveryTableFromCommaJoin() {
        SqlParseUtil.Analysis analysis = SqlParseUtil.requireReadOnly(
                "SELECT a.id AS aid, b.id AS bid "
                        + "FROM public.accounts a, public.billing b");

        assertEquals(
                Set.of("public.accounts", "public.billing"),
                analysis.tables());
    }

    @Test
    void rejectsMultipleStatementsAndNonReadRoots() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlParseUtil.requireReadOnly(
                        "SELECT 1; DELETE FROM public.users"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlParseUtil.requireReadOnly(
                        "DELETE FROM public.users"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlParseUtil.requireReadOnly(
                        "EXPLAIN SELECT * FROM public.users"));
    }

    @Test
    void rejectsWriteCteSelectIntoAndLockingRead() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlParseUtil.requireReadOnly(
                        "WITH changed AS ("
                                + "DELETE FROM public.users RETURNING id"
                                + ") SELECT * FROM changed"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlParseUtil.requireReadOnly(
                        "SELECT * INTO archived_users "
                                + "FROM public.users"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlParseUtil.requireReadOnly(
                        "SELECT * FROM public.users FOR UPDATE"));
    }

    @Test
    void rejectsUnknownFromItemsIncludingTableFunctions() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlParseUtil.requireReadOnly(
                        "SELECT * FROM generate_series(1, 10)"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlParseUtil.requireReadOnly(
                        "SELECT * FROM dblink("
                                + "'host=127.0.0.1', "
                                + "'SELECT id FROM users') "
                                + "AS remote_users(id integer)"));
    }

    @Test
    void qualifiedMatcherNeverFallsBackToLastSegment() {
        assertTrue(SqlParseUtil.matchAny(
                "public.users", Set.of("public.users")));
        assertFalse(SqlParseUtil.matchAny(
                "secret.users", Set.of("public.users")));
        assertFalse(SqlParseUtil.matchAny(
                "public.users", Set.of("users")));
        assertTrue(SqlParseUtil.matchAny(
                "public.audit_2026", Set.of("public.audit_*")));
    }
}
