package io.github.lexaquila.lyradb.desktop.ui;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从光标前 SQL 中提取当前补全前缀、限定符与表别名。
 */
record SqlCompletionContext(
        String prefix,
        String qualifier,
        int replaceStart,
        int replaceEnd,
        Map<String, TableReference> tableReferences) {

    private static final Pattern REFERENCE = Pattern.compile(
            "(?is)\\b(?:FROM|JOIN|UPDATE|INTO)\\s+"
                    + "([`\"\\[]?[A-Za-z_][\\w$]*[`\"\\]]?"
                    + "(?:\\s*\\.\\s*[`\"\\[]?[A-Za-z_][\\w$]*[`\"\\]]?){0,2})"
                    + "(?:\\s+(?:AS\\s+)?([A-Za-z_][\\w$]*))?");
    private static final Pattern QUALIFIED_PREFIX = Pattern.compile(
            "([A-Za-z_][\\w$]*)\\s*\\.\\s*([A-Za-z_][\\w$]*)?$");
    private static final Pattern WORD_PREFIX = Pattern.compile(
            "([A-Za-z_][\\w$]*)$");

    static SqlCompletionContext at(String sql, int caret) {
        String safe = sql == null ? "" : sql;
        int safeCaret = Math.max(0, Math.min(caret, safe.length()));
        String before = safe.substring(0, safeCaret);
        Map<String, TableReference> references = parseReferences(safe);

        Matcher qualified = QUALIFIED_PREFIX.matcher(before);
        if (qualified.find()) {
            String qualifier = qualified.group(1);
            String prefix = qualified.group(2) == null
                    ? "" : qualified.group(2);
            return new SqlCompletionContext(
                    prefix, qualifier,
                    safeCaret - prefix.length(), safeCaret,
                    references);
        }

        Matcher word = WORD_PREFIX.matcher(before);
        String prefix = word.find() ? word.group(1) : "";
        return new SqlCompletionContext(
                prefix, null,
                safeCaret - prefix.length(), safeCaret,
                references);
    }

    TableReference resolveQualifier() {
        if (qualifier == null || qualifier.isBlank()) {
            return null;
        }
        TableReference reference = tableReferences.get(
                qualifier.toLowerCase(Locale.ROOT));
        return reference == null
                ? new TableReference(null, qualifier) : reference;
    }

    private static Map<String, TableReference> parseReferences(String sql) {
        Map<String, TableReference> references = new LinkedHashMap<>();
        Matcher matcher = REFERENCE.matcher(sql);
        while (matcher.find()) {
            String[] parts = matcher.group(1)
                    .replaceAll("[`\"\\[\\]\\s]", "")
                    .split("\\.");
            String table = parts[parts.length - 1];
            String schema = parts.length <= 1 ? null
                    : String.join(".",
                    java.util.Arrays.copyOf(parts, parts.length - 1));
            TableReference reference = new TableReference(schema, table);
            references.put(table.toLowerCase(Locale.ROOT), reference);
            String alias = matcher.group(2);
            if (alias != null && !isClauseKeyword(alias)) {
                references.put(alias.toLowerCase(Locale.ROOT), reference);
            }
        }
        return Map.copyOf(references);
    }

    private static boolean isClauseKeyword(String value) {
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "WHERE", "JOIN", "LEFT", "RIGHT", "INNER", "FULL",
                    "CROSS", "ON", "GROUP", "ORDER", "HAVING",
                    "LIMIT", "OFFSET", "SET", "VALUES", "RETURNING",
                    "UNION" -> true;
            default -> false;
        };
    }

    record TableReference(String schema, String table) {
    }
}
