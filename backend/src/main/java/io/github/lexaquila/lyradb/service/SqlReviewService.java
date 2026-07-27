package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.dto.SqlReviewFinding;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * SQL 审核规则引擎（迭代二 E2）
 *
 * <p>
 * 在 SQL 执行前对语句做静态审查，内置 6 条规则，三级处置：
 * </p>
 * <ul>
 * <li>R1 UPDATE 无 WHERE（HIGH）</li>
 * <li>R2 DELETE 无 WHERE（HIGH）</li>
 * <li>R3 DROP 语句（HIGH）</li>
 * <li>R4 TRUNCATE 语句（HIGH）</li>
 * <li>R5 ALTER ... DROP 删列（MEDIUM）</li>
 * <li>R6 SELECT 无 LIMIT（LOW，仅提醒）</li>
 * </ul>
 *
 * <p>
 * 仅针对 SQL 型库（JDBC 方言），MongoDB/Redis 的 DSL 命令不做审查。
 * 个人版：HIGH/MEDIUM 拦截但保留"仍要执行"逃生门（force）；
 * 企业版：HIGH 级 DML 走审批流（见 {@link EnterpriseQueryService}）。
 * </p>
 */
@Service
public class SqlReviewService {

    /** 审查前剥离的注释与字符串字面量（避免 WHERE 出现在字符串中造成误判） */
    private static final Pattern STRIP_PATTERN = Pattern.compile(
            "'(?:[^']|'')*'|--[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

    private static final Pattern LIMIT_PATTERN = Pattern.compile(
            "(?i)\\b(?:LIMIT\\s+\\d|FETCH\\s+(?:FIRST|NEXT)|TOP\\s+\\d|ROWNUM\\b)");

    private static final Pattern ALTER_DROP_PATTERN = Pattern.compile(
            "(?i)^ALTER\\b.*\\bDROP\\b");

    /** 非 SQL 型库（NoSQL DSL），不做审查 */
    private static final List<String> NON_SQL_DB_TYPES = List.of("MONGODB", "REDIS");

    /**
     * 审查单条 SQL，返回全部命中规则（可能为空）
     *
     * @param sql    原始 SQL
     * @param dbType 数据库类型（可为 null；MongoDB/Redis 直接跳过）
     */
    public List<SqlReviewFinding> review(String sql, String dbType) {
        List<SqlReviewFinding> findings = new ArrayList<>();
        if (sql == null || sql.isBlank()) {
            return findings;
        }
        if (dbType != null && NON_SQL_DB_TYPES.contains(dbType.toUpperCase())) {
            return findings;
        }

        // 剥离字符串/注释后按分号切分多语句逐条审查
        String stripped = STRIP_PATTERN.matcher(sql).replaceAll(" ");
        for (String stmt : stripped.split(";")) {
            String trimmed = stmt.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            reviewStatement(trimmed, findings);
        }
        return findings;
    }

    /** 是否存在需要拦截的命中（HIGH/MEDIUM） */
    public boolean hasBlocking(List<SqlReviewFinding> findings) {
        return findings.stream().anyMatch(f -> !"LOW".equals(f.getSeverity()));
    }

    /** 是否存在 HIGH 级命中 */
    public boolean hasHigh(List<SqlReviewFinding> findings) {
        return findings.stream().anyMatch(f -> "HIGH".equals(f.getSeverity()));
    }

    private void reviewStatement(String stmt, List<SqlReviewFinding> findings) {
        String upper = stmt.toUpperCase();
        String first = SqlParseUtil.firstWord(upper);
        boolean hasWhere = upper.contains("WHERE");

        switch (first) {
            case "UPDATE" -> {
                if (!hasWhere) {
                    findings.add(new SqlReviewFinding("R1_UPDATE_NO_WHERE", "HIGH",
                            "UPDATE 语句缺少 WHERE 条件，将更新全表数据"));
                }
            }
            case "DELETE" -> {
                if (!hasWhere) {
                    findings.add(new SqlReviewFinding("R2_DELETE_NO_WHERE", "HIGH",
                            "DELETE 语句缺少 WHERE 条件，将删除全表数据"));
                }
            }
            case "DROP" -> findings.add(new SqlReviewFinding("R3_DROP", "HIGH",
                    "DROP 语句将永久删除对象及其全部数据，不可恢复"));
            case "TRUNCATE" -> findings.add(new SqlReviewFinding("R4_TRUNCATE", "HIGH",
                    "TRUNCATE 语句将清空全表数据且通常不可回滚"));
            case "ALTER" -> {
                if (ALTER_DROP_PATTERN.matcher(upper).find()) {
                    findings.add(new SqlReviewFinding("R5_ALTER_DROP_COLUMN", "MEDIUM",
                            "ALTER ... DROP 将删除列/约束，列上数据将丢失"));
                }
            }
            case "SELECT", "WITH" -> {
                if (!LIMIT_PATTERN.matcher(upper).find()) {
                    findings.add(new SqlReviewFinding("R6_SELECT_NO_LIMIT", "LOW",
                            "SELECT 语句未指定 LIMIT，大表查询可能耗时较长（已由系统限行保护）"));
                }
            }
            default -> {
                // 其他语句类型不在内置规则范围
            }
        }
    }
}
