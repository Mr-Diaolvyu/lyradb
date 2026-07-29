package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.model.dto.SqlReviewFinding;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.delete.ParenthesedDelete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.insert.ParenthesedInsert;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.ParenthesedUpdate;
import net.sf.jsqlparser.statement.update.Update;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * SQL 审核规则引擎（迭代二 E2）
 *
 * <p>
 * 在 SQL 执行前对语句做静态审查，内置规则与三级处置：
 * </p>
 * <ul>
 * <li>R1 UPDATE 无 WHERE（HIGH）</li>
 * <li>R2 DELETE 无 WHERE（HIGH）</li>
 * <li>R3 DROP 语句（HIGH）</li>
 * <li>R4 TRUNCATE 语句（HIGH）</li>
 * <li>R5 ALTER ... DROP 删列（MEDIUM）</li>
 * <li>R6 SELECT 无 LIMIT（LOW，仅提醒）</li>
 * <li>R7 数据修改 CTE（HIGH）</li>
 * <li>R9 MongoDB update/delete 空过滤器（HIGH）</li>
 * <li>R10 MongoDB drop 类操作（HIGH）</li>
 * <li>R11 MongoDB 非法/未知写 DSL（HIGH）</li>
 * <li>R8 Redis FLUSHDB/FLUSHALL（HIGH）</li>
 * </ul>
 *
 * <p>
 * MongoDB JSON 写 DSL 与 Redis 整库清空命令使用专用规则审查。
 * 个人版：HIGH/MEDIUM 拦截但保留“仍要执行”逃生门（force）；
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

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Pattern ALTER_DROP_PATTERN = Pattern.compile(
            "(?i)^ALTER\\b.*\\bDROP\\b");

    private static final Pattern WHERE_PATTERN = Pattern.compile("(?i)\\bWHERE\\b");

    private static final Pattern DATA_MODIFYING_CTE_PATTERN = Pattern.compile(
            "(?i)\\b(?:UPDATE|DELETE|INSERT|MERGE)\\b");

    /** MongoDB JSON DSL 暂不做 SQL 审查。 */
    private static final String MONGODB = "MONGODB";

    /**
     * 审查一条或多条命令，返回全部命中规则（可能为空）。
     *
     * @param sql    原始 SQL 或 NoSQL 命令
     * @param dbType 数据库类型（可为 null）
     */
    public List<SqlReviewFinding> review(String sql, String dbType) {
        List<SqlReviewFinding> findings = new ArrayList<>();
        if (sql == null || sql.isBlank()) {
            return findings;
        }

        String normalizedDbType = dbType == null
                ? "" : dbType.trim().toUpperCase(Locale.ROOT);
        if (MONGODB.equals(normalizedDbType)) {
            reviewMongoCommand(sql, findings);
            return findings;
        }
        if ("REDIS".equals(normalizedDbType)) {
            reviewRedisCommand(sql, findings);
            return findings;
        }

        /*
         * 优先使用 AST：表名中的 “somewhere”等文本不能再伪装成 WHERE，
         * 并且可以发现 WITH 子句中的数据修改语句。方言无法解析时使用保守的
         * 词法规则兜底，避免审查引擎不可用导致所有方言都无法执行。
         */
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sql);
            for (int index = 0; index < statements.size(); index++) {
                reviewParsedStatement(statements.get(index), findings, false);
            }
        } catch (JSQLParserException | RuntimeException exception) {
            findings.clear();
            reviewLexically(sql, findings);
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

    private void reviewParsedStatement(Statement statement,
            List<SqlReviewFinding> findings, boolean insideCte) {
        if (statement instanceof ParenthesedUpdate parenthesed) {
            reviewParsedStatement(parenthesed.getUpdate(), findings, insideCte);
            return;
        }
        if (statement instanceof ParenthesedDelete parenthesed) {
            reviewParsedStatement(parenthesed.getDelete(), findings, insideCte);
            return;
        }
        if (statement instanceof ParenthesedInsert parenthesed) {
            reviewParsedStatement(parenthesed.getInsert(), findings, insideCte);
            return;
        }
        if (statement instanceof ParenthesedSelect parenthesed) {
            reviewParsedStatement(parenthesed.getSelect(), findings, insideCte);
            return;
        }
        if (statement instanceof Update update) {
            reviewWithItems(update.getWithItemsList(), findings);
            if (insideCte) {
                addDataModifyingCteFinding(findings);
            }
            if (update.getWhere() == null) {
                addUpdateWithoutWhereFinding(findings);
            }
            return;
        }
        if (statement instanceof Delete delete) {
            reviewWithItems(delete.getWithItemsList(), findings);
            if (insideCte) {
                addDataModifyingCteFinding(findings);
            }
            if (delete.getWhere() == null) {
                addDeleteWithoutWhereFinding(findings);
            }
            return;
        }
        if (statement instanceof Insert insert) {
            reviewWithItems(insert.getWithItemsList(), findings);
            if (insideCte) {
                addDataModifyingCteFinding(findings);
            }
            return;
        }
        if (statement instanceof Select select) {
            reviewWithItems(select.getWithItemsList(), findings);
            if (!insideCte && !LIMIT_PATTERN.matcher(select.toString()).find()) {
                addSelectWithoutLimitFinding(findings);
            }
            return;
        }
        reviewStatementLexically(statement.toString(), findings);
    }

    private void reviewWithItems(List<WithItem<?>> withItems,
            List<SqlReviewFinding> findings) {
        if (withItems == null) {
            return;
        }
        for (WithItem<?> withItem : withItems) {
            if (withItem.getParenthesedStatement() != null) {
                reviewParsedStatement(withItem.getParenthesedStatement(), findings, true);
            }
        }
    }

    private void reviewLexically(String sql, List<SqlReviewFinding> findings) {
        String stripped = STRIP_PATTERN.matcher(sql).replaceAll(" ");
        for (String stmt : stripped.split(";")) {
            String trimmed = stmt.trim();
            if (!trimmed.isEmpty()) {
                reviewStatementLexically(trimmed, findings);
            }
        }
    }

    private void reviewStatementLexically(String stmt,
            List<SqlReviewFinding> findings) {
        String upper = stmt.toUpperCase(Locale.ROOT);
        String first = SqlParseUtil.firstWord(upper);
        boolean hasWhere = WHERE_PATTERN.matcher(upper).find();

        switch (first) {
            case "UPDATE" -> {
                if (!hasWhere) {
                    addUpdateWithoutWhereFinding(findings);
                }
            }
            case "DELETE" -> {
                if (!hasWhere) {
                    addDeleteWithoutWhereFinding(findings);
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
            case "WITH" -> {
                if (DATA_MODIFYING_CTE_PATTERN.matcher(upper).find()) {
                    addDataModifyingCteFinding(findings);
                }
                if (!LIMIT_PATTERN.matcher(upper).find()) {
                    addSelectWithoutLimitFinding(findings);
                }
            }
            case "SELECT" -> {
                if (!LIMIT_PATTERN.matcher(upper).find()) {
                    addSelectWithoutLimitFinding(findings);
                }
            }
            default -> {
                // 其他语句类型不在内置规则范围
            }
        }
    }

    private void reviewMongoCommand(String command,
            List<SqlReviewFinding> findings) {
        String trimmed = command.trim();
        // db.collection / db/collection 是只读查询，不属于 JSON 写 DSL。
        if (!trimmed.startsWith("{")) {
            return;
        }

        final JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(trimmed);
        } catch (Exception exception) {
            addInvalidMongoDslFinding(findings, "MongoDB 写命令不是合法 JSON");
            return;
        }
        if (root == null || !root.isObject()) {
            addInvalidMongoDslFinding(findings, "MongoDB 写命令必须是 JSON 对象");
            return;
        }

        JsonNode opNode = root.get("op");
        if (opNode == null || !opNode.isTextual() || opNode.asText().isBlank()) {
            addInvalidMongoDslFinding(findings, "MongoDB 写命令缺少有效 op");
            return;
        }
        String operation = opNode.asText().trim().toLowerCase(Locale.ROOT);
        String normalizedOperation =
                operation.replace("_", "").replace("-", "");
        if ("drop".equals(normalizedOperation)
                || "dropcollection".equals(normalizedOperation)
                || "dropdatabase".equals(normalizedOperation)) {
            findings.add(new SqlReviewFinding("R10_MONGODB_DROP", "HIGH",
                    "MongoDB drop 类操作会永久删除集合或数据库，已阻止直接执行"));
            return;
        }
        if ("update".equals(operation) || "delete".equals(operation)) {
            JsonNode filter = root.get("filter");
            if (filter == null || !filter.isObject() || filter.isEmpty()) {
                findings.add(new SqlReviewFinding(
                        "R9_MONGODB_EMPTY_FILTER", "HIGH",
                        "MongoDB " + operation
                                + " 缺少非空 filter，可能修改任意文档"));
            }
            return;
        }
        if (!"insert".equals(operation)) {
            addInvalidMongoDslFinding(
                    findings, "MongoDB 不支持的写操作: " + operation);
        }
    }

    private void addInvalidMongoDslFinding(
            List<SqlReviewFinding> findings, String message) {
        findings.add(new SqlReviewFinding(
                "R11_MONGODB_INVALID_DSL", "HIGH", message));
    }

    private void reviewRedisCommand(String command,
            List<SqlReviewFinding> findings) {
        String normalized = STRIP_PATTERN.matcher(command).replaceAll(" ").trim()
                .toUpperCase(Locale.ROOT);
        String first = SqlParseUtil.firstWord(normalized).replace(";", "");
        if ("FLUSHDB".equals(first) || "FLUSHALL".equals(first)) {
            findings.add(new SqlReviewFinding("R8_REDIS_FLUSH", "HIGH",
                    first + " 将清空 Redis 数据，操作不可逆"));
        }
    }

    private void addUpdateWithoutWhereFinding(List<SqlReviewFinding> findings) {
        findings.add(new SqlReviewFinding("R1_UPDATE_NO_WHERE", "HIGH",
                "UPDATE 语句缺少 WHERE 条件，将更新全表数据"));
    }

    private void addDeleteWithoutWhereFinding(List<SqlReviewFinding> findings) {
        findings.add(new SqlReviewFinding("R2_DELETE_NO_WHERE", "HIGH",
                "DELETE 语句缺少 WHERE 条件，将删除全表数据"));
    }

    private void addSelectWithoutLimitFinding(List<SqlReviewFinding> findings) {
        findings.add(new SqlReviewFinding("R6_SELECT_NO_LIMIT", "LOW",
                "SELECT 语句未指定 LIMIT，大表查询可能耗时较长（已由系统限行保护）"));
    }

    private void addDataModifyingCteFinding(List<SqlReviewFinding> findings) {
        findings.add(new SqlReviewFinding("R7_DATA_MODIFYING_CTE", "HIGH",
                "WITH 子句包含数据修改操作，需明确确认后执行"));
    }
}
