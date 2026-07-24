package io.github.lexaquila.lyradb.service;

import java.util.HashSet;
import java.util.Set;

/**
 * SQL 解析公共工具
 *
 * <p>
 * 企业查询与 AI 助手共用的 SQL 语句类型判断、CSV 授权项解析、
 * 表名通配匹配逻辑，集中于此避免多处重复实现。
 * </p>
 */
public final class SqlParseUtil {

    private SqlParseUtil() {
    }

    /** DDL 语句前缀（一律禁止） */
    public static final Set<String> DDL_PREFIX = Set.of(
            "CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME", "GRANT", "REVOKE", "COMMENT");

    /** DML 语句前缀（受能力位控制） */
    public static final Set<String> DML_PREFIX = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "CALL", "REPLACE");

    /** 只读语句前缀 */
    public static final Set<String> READ_PREFIX = Set.of(
            "SELECT", "WITH", "EXPLAIN", "SHOW", "DESCRIBE", "DESC", "VALUES");

    /** 取 SQL（已转大写）首个单词，遇空白或左括号即止 */
    public static String firstWord(String upper) {
        if (upper == null)
            return "";
        int i = 0;
        while (i < upper.length() && Character.isWhitespace(upper.charAt(i)))
            i++;
        int s = i;
        while (i < upper.length() && !Character.isWhitespace(upper.charAt(i)) && upper.charAt(i) != '(')
            i++;
        return upper.substring(s, i);
    }

    /** 逗号分隔字符串 → 去空白去空项的集合 */
    public static Set<String> splitCsv(String csv) {
        Set<String> s = new HashSet<>();
        if (csv == null || csv.isBlank())
            return s;
        for (String p : csv.split(",")) {
            String t = p.trim();
            if (!t.isEmpty())
                s.add(t);
        }
        return s;
    }

    /** 通配匹配：entry 末尾 {@code *} 视为前缀；否则全等（含 schema.table 或裸表名） */
    public static boolean matchAny(String table, Set<String> entries) {
        String t = table.toUpperCase();
        String lastSeg = t.contains(".") ? t.substring(t.lastIndexOf('.') + 1) : t;
        for (String e : entries) {
            String eu = e.toUpperCase();
            String el = eu.contains(".") ? eu.substring(eu.lastIndexOf('.') + 1) : eu;
            if (eu.endsWith("*")) {
                String prefix = eu.substring(0, eu.length() - 1);
                if (t.startsWith(prefix) || lastSeg.startsWith(prefix))
                    return true;
            } else if (t.equals(eu) || lastSeg.equals(el)) {
                return true;
            }
        }
        return false;
    }
}
