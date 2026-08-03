package io.github.lexaquila.lyradb.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 轻量 SQL 文本美化器。
 *
 * <p>只规范空白、常用关键字和主要子句，不解析或改写 SQL 语义。字符串、
 * 引号标识符和注释会作为整体保留；遇到方言专有语法时也不会因解析失败而
 * 清空原文。</p>
 */
public final class SqlTextFormatter {

    private static final Set<String> TWO_WORD_CLAUSES = Set.of(
            "ORDER BY", "GROUP BY", "INNER JOIN", "LEFT JOIN",
            "RIGHT JOIN", "FULL JOIN", "CROSS JOIN", "UNION ALL",
            "INSERT INTO", "DELETE FROM", "CREATE TABLE", "ALTER TABLE",
            "DROP TABLE", "PARTITION BY");

    private static final Set<String> TOP_LEVEL = Set.of(
            "SELECT", "FROM", "WHERE", "ORDER BY", "GROUP BY", "HAVING",
            "LIMIT", "OFFSET", "INNER JOIN", "LEFT JOIN", "RIGHT JOIN",
            "FULL JOIN", "CROSS JOIN", "JOIN", "UNION", "UNION ALL",
            "INTERSECT", "EXCEPT", "INSERT INTO", "VALUES", "UPDATE",
            "DELETE FROM", "CREATE TABLE", "ALTER TABLE", "DROP TABLE",
            "WITH", "SET");

    private static final Set<String> KEYWORDS = new HashSet<>(Set.of(
            "SELECT", "DISTINCT", "FROM", "WHERE", "AND", "OR", "NOT",
            "IN", "LIKE", "BETWEEN", "IS", "NULL", "AS", "ORDER", "BY",
            "GROUP", "HAVING", "LIMIT", "OFFSET", "INSERT", "INTO",
            "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "TABLE",
            "ALTER", "DROP", "INDEX", "VIEW", "DATABASE", "JOIN",
            "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "OUTER", "ON",
            "UNION", "ALL", "INTERSECT", "EXCEPT", "CASE", "WHEN",
            "THEN", "ELSE", "END", "IF", "EXISTS", "COUNT", "SUM",
            "AVG", "MIN", "MAX", "SHOW", "TABLES", "COLUMNS",
            "DESCRIBE", "EXPLAIN", "WITH", "RECURSIVE", "ASC", "DESC",
            "DEFAULT", "PRIMARY", "KEY", "FOREIGN", "REFERENCES",
            "UNIQUE", "CONSTRAINT", "CHECK", "CAST", "CONVERT", "OVER",
            "PARTITION", "WINDOW", "OVERWRITE", "LIFECYCLE"));

    private SqlTextFormatter() {
    }

    public static String format(String input) {
        if (input == null || input.isBlank()) {
            return input == null ? "" : input;
        }
        List<Token> tokens = composeClauses(tokenize(input));
        StringBuilder output = new StringBuilder(input.length() + 32);
        int parenthesisDepth = 0;
        boolean clauseJustWritten = false;

        for (Token token : tokens) {
            if (token.kind == TokenKind.SPACE) {
                continue;
            }
            if (token.kind == TokenKind.PUNCTUATION) {
                switch (token.text) {
                    case "(" -> {
                        trimTrailingSpace(output);
                        output.append('(');
                        parenthesisDepth++;
                    }
                    case ")" -> {
                        trimTrailingSpace(output);
                        output.append(')');
                        parenthesisDepth = Math.max(0, parenthesisDepth - 1);
                    }
                    case "," -> {
                        trimTrailingSpace(output);
                        output.append(", ");
                    }
                    case ";" -> {
                        trimTrailingWhitespace(output);
                        output.append(';');
                    }
                    default -> output.append(token.text);
                }
                clauseJustWritten = false;
                continue;
            }
            if (token.kind == TokenKind.OPERATOR) {
                trimTrailingSpace(output);
                if (!output.isEmpty() && output.charAt(output.length() - 1) != '\n') {
                    output.append(' ');
                }
                output.append(token.text).append(' ');
                clauseJustWritten = false;
                continue;
            }

            String upper = token.text.toUpperCase(Locale.ROOT);
            if (TOP_LEVEL.contains(upper)) {
                appendClauseBreak(output, parenthesisDepth);
                output.append(upper);
                clauseJustWritten = true;
                continue;
            }
            if ("AND".equals(upper) || "OR".equals(upper)) {
                appendClauseBreak(output, Math.max(1, parenthesisDepth));
                output.append(upper);
                clauseJustWritten = true;
                continue;
            }

            String rendered = token.kind == TokenKind.WORD
                    && KEYWORDS.contains(upper) ? upper : token.text;
            if (needsSpace(output)) {
                output.append(' ');
            }
            output.append(rendered);
            clauseJustWritten = false;
        }

        trimTrailingWhitespace(output);
        if (output.length() > 0
                && output.charAt(output.length() - 1) != ';') {
            output.append(';');
        }
        return output.toString().trim();
    }

    private static boolean needsSpace(StringBuilder output) {
        if (output.isEmpty()) {
            return false;
        }
        char last = output.charAt(output.length() - 1);
        return last != '(' && last != '\n' && last != ' ';
    }

    private static void appendClauseBreak(
            StringBuilder output, int parenthesisDepth) {
        trimTrailingWhitespace(output);
        if (!output.isEmpty()) {
            output.append('\n');
            output.append("  ".repeat(Math.max(0, parenthesisDepth)));
        }
    }

    private static void trimTrailingSpace(StringBuilder output) {
        while (!output.isEmpty()
                && output.charAt(output.length() - 1) == ' ') {
            output.deleteCharAt(output.length() - 1);
        }
    }

    private static void trimTrailingWhitespace(StringBuilder output) {
        while (!output.isEmpty()
                && Character.isWhitespace(
                output.charAt(output.length() - 1))) {
            output.deleteCharAt(output.length() - 1);
        }
    }

    private static List<Token> composeClauses(List<Token> source) {
        List<Token> result = new ArrayList<>(source.size());
        for (int index = 0; index < source.size(); index++) {
            Token current = source.get(index);
            if (current.kind != TokenKind.WORD || index + 2 >= source.size()
                    || source.get(index + 1).kind != TokenKind.SPACE
                    || source.get(index + 2).kind != TokenKind.WORD) {
                result.add(current);
                continue;
            }
            String combined = (current.text + " "
                    + source.get(index + 2).text)
                    .toUpperCase(Locale.ROOT);
            if (TWO_WORD_CLAUSES.contains(combined)) {
                result.add(new Token(combined, TokenKind.WORD));
                index += 2;
            } else {
                result.add(current);
            }
        }
        return result;
    }

    private static List<Token> tokenize(String sql) {
        List<Token> tokens = new ArrayList<>();
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '-' && nextIs(sql, index, '-')) {
                int end = index + 2;
                while (end < sql.length() && sql.charAt(end) != '\n') {
                    end++;
                }
                tokens.add(new Token(
                        sql.substring(index, end), TokenKind.LITERAL));
                index = end;
                continue;
            }
            if (current == '/' && nextIs(sql, index, '*')) {
                int end = index + 2;
                while (end + 1 < sql.length()
                        && !(sql.charAt(end) == '*'
                        && sql.charAt(end + 1) == '/')) {
                    end++;
                }
                end = Math.min(sql.length(), end + 2);
                tokens.add(new Token(
                        sql.substring(index, end), TokenKind.LITERAL));
                index = end;
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                int end = quotedEnd(sql, index, current);
                tokens.add(new Token(
                        sql.substring(index, end), TokenKind.LITERAL));
                index = end;
                continue;
            }
            if (Character.isWhitespace(current)) {
                int end = index + 1;
                while (end < sql.length()
                        && Character.isWhitespace(sql.charAt(end))) {
                    end++;
                }
                tokens.add(new Token(" ", TokenKind.SPACE));
                index = end;
                continue;
            }
            if ("(),;".indexOf(current) >= 0) {
                tokens.add(new Token(
                        String.valueOf(current), TokenKind.PUNCTUATION));
                index++;
                continue;
            }
            if ("=<>!".indexOf(current) >= 0) {
                int end = index + 1;
                while (end < sql.length()
                        && "=<>!".indexOf(sql.charAt(end)) >= 0) {
                    end++;
                }
                tokens.add(new Token(
                        sql.substring(index, end), TokenKind.OPERATOR));
                index = end;
                continue;
            }

            int end = index + 1;
            while (end < sql.length()
                    && !Character.isWhitespace(sql.charAt(end))
                    && "(),;'\"`=<>!".indexOf(sql.charAt(end)) < 0) {
                end++;
            }
            tokens.add(new Token(
                    sql.substring(index, end), TokenKind.WORD));
            index = end;
        }
        return tokens;
    }

    private static int quotedEnd(
            String sql, int start, char quote) {
        int index = start + 1;
        while (index < sql.length()) {
            if (sql.charAt(index) == quote) {
                if (index + 1 < sql.length()
                        && sql.charAt(index + 1) == quote) {
                    index += 2;
                    continue;
                }
                return index + 1;
            }
            if (sql.charAt(index) == '\\' && index + 1 < sql.length()) {
                index += 2;
            } else {
                index++;
            }
        }
        return sql.length();
    }

    private static boolean nextIs(
            String value, int index, char expected) {
        return index + 1 < value.length()
                && value.charAt(index + 1) == expected;
    }

    private enum TokenKind {
        WORD,
        LITERAL,
        SPACE,
        PUNCTUATION,
        OPERATOR
    }

    private record Token(String text, TokenKind kind) {
    }
}
