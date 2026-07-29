


package io.github.lexaquila.lyradb.service;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.JsonAggregateFunction;
import net.sf.jsqlparser.expression.JsonFunction;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.MySQLGroupConcat;
import net.sf.jsqlparser.expression.NextValExpression;
import net.sf.jsqlparser.expression.TranscodingFunction;
import net.sf.jsqlparser.expression.UserVariable;
import net.sf.jsqlparser.expression.VariableAssignment;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 基于 JSqlParser AST 的企业 SQL 安全解析工具。
 *
 * <p>多语句、解析失败、未知根语句、数据修改 CTE、SELECT INTO 和锁定读取均拒绝。
 * 授权匹配保留完整 Catalog/Schema/Table，禁止退化为末段表名比较。</p>
 */
public final class SqlParseUtil {

    private SqlParseUtil() {
    }

    /** 仅为个人版 SQL 审核兼容保留；企业授权不得用首词判断。 */
    public static final Set<String> DDL_PREFIX = Set.of(
            "CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME", "GRANT", "REVOKE", "COMMENT");
    public static final Set<String> DML_PREFIX = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "CALL", "REPLACE");
    public static final Set<String> READ_PREFIX = Set.of("SELECT", "WITH", "VALUES");

    /**
     * 企业只读查询允许的最小纯函数集合。未知、限定名和 UDF 默认拒绝；
     * 数据库账号仍必须撤销文件、网络、命令和例程权限。
     */
    private static final Set<String> SAFE_READ_FUNCTIONS = Set.of(
            "COUNT", "SUM", "AVG", "MIN", "MAX",
            "COALESCE", "NULLIF", "LOWER", "UPPER",
            "ABS", "ROUND", "CEIL", "CEILING", "FLOOR",
            "LENGTH", "CHAR_LENGTH", "SUBSTRING", "SUBSTR",
            "CONCAT", "REPLACE", "POWER", "SQRT", "MOD");
    private static final Set<String> SAFE_ANALYTIC_FUNCTIONS = Set.of(
            "COUNT", "SUM", "AVG", "MIN", "MAX",
            "ROW_NUMBER", "RANK", "DENSE_RANK");

    public enum StatementType {
        READ,
        DML
    }

    public record SourceColumn(String table, String column) {
    }

    public record Analysis(StatementType type,
                           Set<String> tables,
                           Map<String, Set<SourceColumn>> outputLineage,
                           boolean lineageComplete) {
    }

    /**
     * 解析且只接受一条 SELECT/VALUES 或受控 INSERT/UPDATE/DELETE。
     */
    public static Analysis analyze(String sql) {
        return analyzeInternal(sql, false);
    }

    public static Analysis analyzeEnterprise(String sql) {
        return analyzeInternal(sql, true);
    }

    private static Analysis analyzeInternal(String sql, boolean enterprisePolicy) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        final Statement statement;
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sql);
            if (statements == null || statements.size() != 1) {
                throw new IllegalArgumentException("仅允许执行一条 SQL");
            }
            statement = statements.get(0);
        } catch (JSQLParserException exception) {
            throw new IllegalArgumentException("SQL 无法安全解析，已拒绝执行", exception);
        }

        StatementType type;
        if (statement instanceof Select select) {
            validateReadOnlySelect(select, enterprisePolicy);
            type = StatementType.READ;
        } else if (statement instanceof Insert || statement instanceof Update || statement instanceof Delete) {
            if (enterprisePolicy) {
                validateEnterpriseDml(statement);
            }
            type = StatementType.DML;
        } else {
            throw new IllegalArgumentException("不支持的 SQL 语句类型: "
                    + statement.getClass().getSimpleName());
        }

        Set<String> tables;
        try {
            tables = new LinkedHashSet<>();
            for (String table : new TablesNamesFinder<Void>().getTables(statement)) {
                if (enterprisePolicy && containsQuotedResourceIdentifier(table)) {
                    throw new IllegalArgumentException(
                            "企业 SQL 不允许带引号的 Catalog/Schema/Table 标识符");
                }
                String normalized = normalizeQualifiedName(table);
                if (!normalized.isEmpty()) {
                    tables.add(normalized);
                }
            }
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("无法完整解析 SQL 资源范围，已拒绝执行", exception);
        }

        Lineage lineage = type == StatementType.READ
                ? buildOutputLineage((Select) statement, tables)
                : new Lineage(Map.of(), false);
        return new Analysis(
                type,
                Collections.unmodifiableSet(tables),
                immutableLineage(lineage.columns),
                lineage.complete);
    }

    public static Analysis requireReadOnly(String sql) {
        Analysis analysis = analyze(sql);
        if (analysis.type() != StatementType.READ) {
            throw new IllegalArgumentException("仅允许只读 SELECT");
        }
        return analysis;
    }

    public static Analysis requireEnterpriseReadOnly(String sql) {
        Analysis analysis = analyzeEnterprise(sql);
        if (analysis.type() != StatementType.READ) {
            throw new IllegalArgumentException("仅允许只读 SELECT");
        }
        return analysis;
    }

    public static boolean isReadOnly(String sql) {
        try {
            return analyze(sql).type() == StatementType.READ;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /** 严格匹配完整限定名，仅支持末尾星号前缀通配。 */
    public static boolean matchAny(String qualifiedName, Set<String> entries) {
        String target = normalizeQualifiedName(qualifiedName);
        for (String entry : entries) {
            String pattern = normalizeQualifiedName(entry);
            if (pattern.endsWith("*")) {
                if (target.startsWith(pattern.substring(0, pattern.length() - 1))) {
                    return true;
                }
            } else if (target.equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    public static Set<String> splitCsv(String csv) {
        Set<String> result = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) {
            return result;
        }
        for (String part : csv.split(",")) {
            String normalized = normalizeQualifiedName(part);
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return result;
    }

    public static String schemaOf(String qualifiedTable) {
        String normalized = normalizeQualifiedName(qualifiedTable);
        String[] parts = normalized.split("\\.");
        return parts.length >= 2 ? parts[parts.length - 2] : null;
    }

    private static boolean containsQuotedResourceIdentifier(String value) {
        return value != null && (value.indexOf('"') >= 0
                || value.indexOf('`') >= 0
                || value.indexOf('[') >= 0
                || value.indexOf(']') >= 0);
    }

    public static String normalizeQualifiedName(String value) {
        if (value == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String rawPart : value.trim().split("\\.")) {
            String part = unquote(rawPart.trim());
            if (!part.isEmpty()) {
                parts.add(part.toLowerCase(Locale.ROOT));
            }
        }
        return String.join(".", parts);
    }

    /** 个人版 SQL 审核兼容工具。 */
    public static String firstWord(String upper) {
        if (upper == null) {
            return "";
        }
        int index = 0;
        while (index < upper.length() && Character.isWhitespace(upper.charAt(index))) {
            index++;
        }
        int start = index;
        while (index < upper.length() && !Character.isWhitespace(upper.charAt(index))
                && upper.charAt(index) != '(') {
            index++;
        }
        return upper.substring(start, index);
    }

    private static void validateReadOnlySelect(Select select, boolean enterprisePolicy) {
        if (enterprisePolicy) {
            validateSelectEnvelope(select);
        }
        if (select.getForMode() != null || select.getForUpdateTable() != null
                || select.isSkipLocked() || select.getWait() != null) {
            throw new IllegalArgumentException("只读授权不允许锁定读取");
        }
        validateWithItems(select.getWithItemsList(), enterprisePolicy);
        if (select instanceof ParenthesedSelect parenthesed) {
            if (parenthesed.getSelect() == null) {
                throw new IllegalArgumentException("空子查询已拒绝");
            }
            validateReadOnlySelect(parenthesed.getSelect(), enterprisePolicy);
        } else if (select instanceof PlainSelect plain) {
            if (enterprisePolicy) {
                rejectUnsupportedPlainSelectClauses(plain);
            }
            if ((plain.getIntoTables() != null && !plain.getIntoTables().isEmpty())
                    || plain.getIntoTempTable() != null) {
                throw new IllegalArgumentException("只读授权不允许 SELECT INTO");
            }
            validateFromItem(plain.getFromItem(), enterprisePolicy);
            if (plain.getJoins() != null) {
                for (Join join : plain.getJoins()) {
                    validateFromItem(join.getRightItem(), enterprisePolicy);
                    validateExpressions(join.getOnExpressions(), enterprisePolicy);
                }
            }
            for (SelectItem<?> item : plain.getSelectItems()) {
                validateExpression(item.getExpression(), enterprisePolicy);
            }
            validateExpression(plain.getWhere(), enterprisePolicy);
            validateExpression(plain.getHaving(), enterprisePolicy);
            validateExpression(plain.getQualify(), enterprisePolicy);
            if (plain.getGroupBy() != null && plain.getGroupBy().getGroupByExpressionList() != null) {
                validateExpressions(plain.getGroupBy().getGroupByExpressionList(), enterprisePolicy);
            }
            if (plain.getOrderByElements() != null) {
                plain.getOrderByElements().forEach(
                        order -> validateExpression(order.getExpression(), enterprisePolicy));
            }
        } else if (select instanceof SetOperationList setOperation) {
            for (Select nested : setOperation.getSelects()) {
                validateReadOnlySelect(nested, enterprisePolicy);
            }
        } else if (select instanceof Values values) {
            if (values.getExpressions() != null) {
                for (Object expression : values.getExpressions()) {
                    if (expression instanceof Expression item) {
                        validateExpression(item, enterprisePolicy);
                    }
                }
            }
        } else {
            throw new IllegalArgumentException("不支持的 SELECT 结构: "
                    + select.getClass().getSimpleName());
        }
    }

    private static void validateSelectEnvelope(Select select) {
        if (select.getLimitBy() != null || select.getIsolation() != null
                || select.isOracleSiblings() || select.getForClause() != null
                || select.getPivot() != null || select.getUnPivot() != null) {
            throw new IllegalArgumentException(
                    "企业查询包含尚未安全支持的 SELECT 子句");
        }
        validatePagination(select.getLimit(), select.getOffset(), select.getFetch());
        if (select.getOrderByElements() != null) {
            select.getOrderByElements().forEach(
                    order -> validateExpression(order.getExpression(), true));
        }
    }

    private static void validatePagination(
            net.sf.jsqlparser.statement.select.Limit limit,
            net.sf.jsqlparser.statement.select.Offset offset,
            net.sf.jsqlparser.statement.select.Fetch fetch) {
        if (limit != null) {
            if (limit.getByExpressions() != null
                    && !limit.getByExpressions().isEmpty()) {
                throw new IllegalArgumentException("企业查询不支持 LIMIT BY");
            }
            requireSafePaginationValue(limit.getOffset());
            requireSafePaginationValue(limit.getRowCount());
        }
        if (offset != null) {
            requireSafePaginationValue(offset.getOffset());
        }
        if (fetch != null) {
            requireSafePaginationValue(fetch.getExpression());
        }
    }

    private static void requireSafePaginationValue(Expression expression) {
        if (expression != null
                && !(expression instanceof LongValue)
                && !(expression instanceof JdbcParameter)) {
            throw new IllegalArgumentException(
                    "企业查询分页子句只允许整数常量或 JDBC 参数");
        }
    }

    private static void rejectUnsupportedPlainSelectClauses(PlainSelect plain) {
        boolean distinctOn = plain.getDistinct() != null
                && plain.getDistinct().getOnSelectItems() != null
                && !plain.getDistinct().getOnSelectItems().isEmpty();
        if ((plain.getLateralViews() != null && !plain.getLateralViews().isEmpty())
                || plain.isUsingFinal() || plain.isUsingOnly()
                || plain.isUseWithNoLog() || plain.getSampleClause() != null
                || plain.getOptimizeFor() != null || plain.getTop() != null
                || plain.getSkip() != null || plain.getFirst() != null
                || distinctOn || plain.getBigQuerySelectQualifier() != null
                || plain.getOracleHierarchical() != null
                || plain.getPreferringClause() != null
                || plain.getOracleHint() != null || plain.getForXmlPath() != null
                || plain.getKsqlWindow() != null || plain.isEmitChanges()
                || (plain.getWindowDefinitions() != null
                    && !plain.getWindowDefinitions().isEmpty())
                || plain.getMySqlHintStraightJoin()
                || plain.getMySqlSqlCalcFoundRows()
                || plain.getMySqlSqlCacheFlag() != null) {
            throw new IllegalArgumentException(
                    "企业查询包含尚未安全支持的方言子句");
        }
    }

    private static void validateEnterpriseDml(Statement statement) {
        if (statement instanceof Update update) {
            if (update.getOutputClause() != null
                    || update.getReturningClause() != null
                    || update.getLimit() != null
                    || update.getPreferringClause() != null
                    || (update.getOrderByElements() != null
                        && !update.getOrderByElements().isEmpty())
                    || (update.getStartJoins() != null
                        && !update.getStartJoins().isEmpty())) {
                throw new IllegalArgumentException(
                        "企业 UPDATE 包含尚未安全支持的子句");
            }
            if (update.getWithItemsList() != null
                    && !update.getWithItemsList().isEmpty()) {
                throw new IllegalArgumentException("企业 DML 暂不允许 WITH 子句");
            }
            validateUpdateSets(update.getUpdateSets());
            validateExpression(update.getWhere(), true);
            validateFromItem(update.getFromItem(), true);
            if (update.getJoins() != null) {
                for (Join join : update.getJoins()) {
                    validateFromItem(join.getRightItem(), true);
                    validateExpressions(join.getOnExpressions(), true);
                }
            }
            return;
        }
        if (statement instanceof Insert insert) {
            if (insert.getOutputClause() != null
                    || insert.getReturningClause() != null
                    || insert.getConflictTarget() != null
                    || insert.getConflictAction() != null) {
                throw new IllegalArgumentException(
                        "企业 INSERT 包含尚未安全支持的子句");
            }
            if (insert.getWithItemsList() != null
                    && !insert.getWithItemsList().isEmpty()) {
                throw new IllegalArgumentException("企业 DML 暂不允许 WITH 子句");
            }
            if (insert.getSelect() != null) {
                validateReadOnlySelect(insert.getSelect(), true);
            }
            validateUpdateSets(insert.getSetUpdateSets());
            validateUpdateSets(insert.getDuplicateUpdateSets());
            return;
        }
        if (statement instanceof Delete delete) {
            if (delete.getOutputClause() != null
                    || delete.getReturningClause() != null
                    || delete.getLimit() != null
                    || delete.getPreferringClause() != null
                    || (delete.getOrderByElements() != null
                        && !delete.getOrderByElements().isEmpty())
                    || (delete.getUsingList() != null
                        && !delete.getUsingList().isEmpty())) {
                throw new IllegalArgumentException(
                        "企业 DELETE 包含尚未安全支持的子句");
            }
            if (delete.getWithItemsList() != null
                    && !delete.getWithItemsList().isEmpty()) {
                throw new IllegalArgumentException("企业 DML 暂不允许 WITH 子句");
            }
            validateExpression(delete.getWhere(), true);
            if (delete.getJoins() != null) {
                for (Join join : delete.getJoins()) {
                    validateFromItem(join.getRightItem(), true);
                    validateExpressions(join.getOnExpressions(), true);
                }
            }
        }
    }

    private static void validateUpdateSets(List<UpdateSet> updateSets) {
        if (updateSets == null) {
            return;
        }
        for (UpdateSet updateSet : updateSets) {
            if (updateSet.getValues() == null) {
                continue;
            }
            for (int index = 0; index < updateSet.getValues().size(); index++) {
                Expression value = updateSet.getValue(index);
                if (value instanceof Select select) {
                    validateReadOnlySelect(select, true);
                } else {
                    validateExpression(value, true);
                }
            }
        }
    }

    private static void validateWithItems(
            List<WithItem<?>> withItems, boolean enterprisePolicy) {
        if (withItems == null) {
            return;
        }
        for (WithItem<?> withItem : withItems) {
            if (!(withItem.getParenthesedStatement()
                    instanceof ParenthesedSelect parenthesed)
                    || parenthesed.getSelect() == null) {
                throw new IllegalArgumentException(
                        "只读授权不允许数据修改或未知 CTE");
            }
            validateReadOnlySelect(parenthesed.getSelect(), enterprisePolicy);
        }
    }

    private static void validateFromItem(
            FromItem fromItem, boolean enterprisePolicy) {
        if (fromItem == null || fromItem instanceof Table) {
            return;
        }
        if (fromItem instanceof Select nested) {
            validateReadOnlySelect(nested, enterprisePolicy);
            return;
        }
        if (fromItem instanceof ParenthesedFromItem parenthesed) {
            if (parenthesed.getFromItem() == null) {
                throw new IllegalArgumentException("空 FROM 结构已拒绝");
            }
            validateFromItem(parenthesed.getFromItem(), enterprisePolicy);
            if (parenthesed.getJoins() != null) {
                for (Join join : parenthesed.getJoins()) {
                    validateFromItem(join.getRightItem(), enterprisePolicy);
                    validateExpressions(join.getOnExpressions(), enterprisePolicy);
                }
            }
            return;
        }
        throw new IllegalArgumentException("不支持的 FROM 结构: "
                + fromItem.getClass().getSimpleName());
    }

    private static void validateExpressions(
            Collection<? extends Expression> expressions, boolean enterprisePolicy) {
        if (expressions != null) {
            expressions.forEach(expression ->
                    validateExpression(expression, enterprisePolicy));
        }
    }

    private static void validateExpression(
            Expression expression, boolean enterprisePolicy) {
        if (expression == null) {
            return;
        }
        expression.accept(new ExpressionVisitorAdapter<Void>() {
            @Override
            public <S> Void visit(ParenthesedSelect select, S context) {
                validateReadOnlySelect(select, enterprisePolicy);
                return null;
            }

            @Override
            public <S> Void visit(Function function, S context) {
                if (enterprisePolicy) {
                    validateSafeFunction(function);
                }
                return super.visit(function, context);
            }

            @Override
            public <S> Void visit(AnalyticExpression function, S context) {
                if (enterprisePolicy && !SAFE_ANALYTIC_FUNCTIONS.contains(
                        normalizeFunctionName(function.getName()))) {
                    throw new IllegalArgumentException(
                            "企业只读查询不允许未知分析函数: " + function.getName());
                }
                return super.visit(function, context);
            }

            @Override
            public <S> Void visit(NextValExpression expression, S context) {
                if (enterprisePolicy) {
                    throw new IllegalArgumentException("企业只读查询不允许序列取值");
                }
                return super.visit(expression, context);
            }

            @Override
            public <S> Void visit(UserVariable variable, S context) {
                if (enterprisePolicy) {
                    throw new IllegalArgumentException("企业只读查询不允许用户变量");
                }
                return super.visit(variable, context);
            }

            @Override
            public <S> Void visit(VariableAssignment assignment, S context) {
                if (enterprisePolicy) {
                    throw new IllegalArgumentException("企业只读查询不允许变量赋值");
                }
                return super.visit(assignment, context);
            }

            @Override
            public <S> Void visit(MySQLGroupConcat function, S context) {
                if (enterprisePolicy) {
                    throw new IllegalArgumentException("企业只读查询不允许未知聚合函数");
                }
                return super.visit(function, context);
            }

            @Override
            public <S> Void visit(JsonFunction function, S context) {
                if (enterprisePolicy) {
                    throw new IllegalArgumentException("企业只读查询不允许未知 JSON 函数");
                }
                return super.visit(function, context);
            }

            @Override
            public <S> Void visit(JsonAggregateFunction function, S context) {
                if (enterprisePolicy) {
                    throw new IllegalArgumentException("企业只读查询不允许未知 JSON 聚合函数");
                }
                return super.visit(function, context);
            }

            @Override
            public <S> Void visit(TranscodingFunction function, S context) {
                if (enterprisePolicy) {
                    throw new IllegalArgumentException("企业只读查询不允许未知转换函数");
                }
                return super.visit(function, context);
            }
        }, null);
    }

    private static void validateSafeFunction(Function function) {
        List<String> multipart = function.getMultipartName();
        if (multipart != null && multipart.size() != 1) {
            throw new IllegalArgumentException("企业只读查询不允许限定名或 UDF 函数");
        }
        String name = normalizeFunctionName(function.getName());
        if (!SAFE_READ_FUNCTIONS.contains(name)) {
            throw new IllegalArgumentException("企业只读查询不允许未知函数: " + function.getName());
        }
    }

    private static String normalizeFunctionName(String name) {
        return name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
    }

    private static Lineage buildOutputLineage(Select select, Set<String> tables) {
        if (!(select instanceof PlainSelect plain)) {
            return new Lineage(Map.of(), false);
        }
        Map<String, String> aliases = new HashMap<>();
        boolean simpleSources = registerTable(plain.getFromItem(), aliases);
        if (plain.getJoins() != null) {
            for (Join join : plain.getJoins()) {
                simpleSources &= registerTable(join.getRightItem(), aliases);
            }
        }

        Map<String, Set<SourceColumn>> output = new LinkedHashMap<>();
        boolean complete = simpleSources
                && (select.getWithItemsList() == null
                        || select.getWithItemsList().isEmpty())
                && !containsNestedSelect(plain);
        for (SelectItem<?> item : plain.getSelectItems()) {
            Expression expression = item.getExpression();
            if (expression instanceof AllColumns) {
                complete = false;
                continue;
            }
            Set<SourceColumn> sources = new LinkedHashSet<>();
            boolean[] expressionComplete = {true};
            expression.accept(new ExpressionVisitorAdapter<Void>() {
                @Override
                public <S> Void visit(Column column, S context) {
                    String columnName = normalizeQualifiedName(column.getUnquotedColumnName());
                    String qualifier = normalizeQualifiedName(column.getUnquotedTableName());
                    if (!qualifier.isEmpty()) {
                        sources.add(new SourceColumn(
                                aliases.getOrDefault(qualifier, qualifier), columnName));
                    } else if (tables.size() == 1) {
                        sources.add(new SourceColumn(tables.iterator().next(), columnName));
                    } else {
                        for (String table : tables) {
                            sources.add(new SourceColumn(table, columnName));
                        }
                    }
                    return null;
                }

                @Override
                public <S> Void visit(ParenthesedSelect nested, S context) {
                    expressionComplete[0] = false;
                    return null;
                }
            }, null);
            complete &= expressionComplete[0];

            String outputName = item.getUnquotedAliasName();
            if ((outputName == null || outputName.isBlank()) && expression instanceof Column column) {
                outputName = column.getUnquotedColumnName();
            }
            if (outputName == null || outputName.isBlank()) {
                complete = false;
            } else {
                output.put(normalizeQualifiedName(outputName), sources);
            }
        }
        return new Lineage(output, complete);
    }

    private static boolean containsNestedSelect(PlainSelect plain) {
        boolean[] found = {false};
        java.util.function.Consumer<Expression> inspect = expression -> {
            if (expression == null || found[0]) {
                return;
            }
            expression.accept(new ExpressionVisitorAdapter<Void>() {
                @Override
                public <S> Void visit(ParenthesedSelect nested, S context) {
                    found[0] = true;
                    return null;
                }
            }, null);
        };
        for (SelectItem<?> item : plain.getSelectItems()) {
            inspect.accept(item.getExpression());
        }
        inspect.accept(plain.getWhere());
        inspect.accept(plain.getHaving());
        inspect.accept(plain.getQualify());
        if (plain.getJoins() != null) {
            for (Join join : plain.getJoins()) {
                if (join.getOnExpressions() != null) {
                    join.getOnExpressions().forEach(inspect);
                }
            }
        }
        if (plain.getGroupBy() != null
                && plain.getGroupBy().getGroupByExpressionList() != null) {
            plain.getGroupBy().getGroupByExpressionList().forEach(inspect);
        }
        if (plain.getOrderByElements() != null) {
            plain.getOrderByElements().forEach(
                    order -> inspect.accept(order.getExpression()));
        }
        return found[0];
    }

    private static boolean registerTable(FromItem fromItem, Map<String, String> aliases) {
        if (fromItem == null) {
            return true;
        }
        if (!(fromItem instanceof Table table)) {
            return false;
        }
        String physical = normalizeQualifiedName(table.getFullyQualifiedName());
        aliases.put(physical, physical);
        aliases.put(normalizeQualifiedName(table.getName()), physical);
        if (table.getAlias() != null) {
            aliases.put(normalizeQualifiedName(table.getAlias().getUnquotedName()), physical);
        }
        return true;
    }

    private static Map<String, Set<SourceColumn>> immutableLineage(
            Map<String, Set<SourceColumn>> source) {
        Map<String, Set<SourceColumn>> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key,
                Collections.unmodifiableSet(new LinkedHashSet<>(value))));
        return Collections.unmodifiableMap(result);
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '`' && last == '`')
                    || (first == '[' && last == ']')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private record Lineage(Map<String, Set<SourceColumn>> columns, boolean complete) {
    }
}
