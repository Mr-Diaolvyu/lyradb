package io.github.lexaquila.lyradb.ai.maxcompute;

import io.github.lexaquila.lyradb.service.SqlParseUtil;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于 AST 的 MaxCompute 分区谓词检查器。
 *
 * <p>声明由调用方提供并在回执中明确标注为“声明值”；检查器只判断 WHERE
 * 中是否引用了全部必需分区列，不推断业务含义，也不把字符串匹配当作证据。</p>
 */
public final class MaxComputePartitionInspector {

    private MaxComputePartitionInspector() {
    }

    public static Inspection inspect(
            String sql, Map<String, ? extends List<String>> declarations) {
        SqlParseUtil.Analysis analysis =
                SqlParseUtil.requireEnterpriseReadOnly(sql);
        Set<SqlParseUtil.SourceColumn> filters = collectFilters(sql);
        Map<String, Set<String>> normalized =
                normalizeDeclarations(declarations);
        List<PartitionCheck> checks = new ArrayList<>();
        for (String table : analysis.tables()) {
            Set<String> required = normalized.getOrDefault(
                    table, Set.of());
            Set<String> matched = new LinkedHashSet<>();
            for (String column : required) {
                if (filters.contains(new SqlParseUtil.SourceColumn(
                        table, column))) {
                    matched.add(column);
                }
            }
            checks.add(new PartitionCheck(
                    table, required, matched,
                    !required.isEmpty() && matched.containsAll(required)));
        }
        return new Inspection(analysis, checks, filters);
    }

    private static Set<SqlParseUtil.SourceColumn> collectFilters(String sql) {
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sql);
            if (statements == null || statements.size() != 1) {
                throw new IllegalArgumentException("仅允许执行一条 SQL");
            }
            Statement statement = statements.get(0);
            if (!(statement instanceof Select select)) {
                throw new IllegalArgumentException("仅允许只读 SELECT");
            }
            LinkedHashSet<SqlParseUtil.SourceColumn> result =
                    new LinkedHashSet<>();
            collectSelect(select, result);
            return Collections.unmodifiableSet(result);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "无法解析 MaxCompute 分区谓词", exception);
        }
    }

    private static void collectSelect(
            Select select, Set<SqlParseUtil.SourceColumn> output) {
        collectWithItems(select.getWithItemsList(), output);
        if (select instanceof ParenthesedSelect parenthesed) {
            if (parenthesed.getSelect() != null) {
                collectSelect(parenthesed.getSelect(), output);
            }
            return;
        }
        if (select instanceof SetOperationList operations) {
            for (Select nested : operations.getSelects()) {
                collectSelect(nested, output);
            }
            return;
        }
        if (!(select instanceof PlainSelect plain)) {
            return;
        }

        Map<String, String> aliases = new LinkedHashMap<>();
        LinkedHashSet<String> tables = new LinkedHashSet<>();
        registerFromItem(plain.getFromItem(), aliases, tables, output);
        if (plain.getJoins() != null) {
            for (Join join : plain.getJoins()) {
                registerFromItem(
                        join.getRightItem(), aliases, tables, output);
            }
        }
        collectExpression(plain.getWhere(), aliases, tables, output);
    }

    private static void collectWithItems(
            List<WithItem<?>> withItems,
            Set<SqlParseUtil.SourceColumn> output) {
        if (withItems == null) {
            return;
        }
        for (WithItem<?> withItem : withItems) {
            if (withItem.getParenthesedStatement()
                    instanceof ParenthesedSelect nested
                    && nested.getSelect() != null) {
                collectSelect(nested.getSelect(), output);
            }
        }
    }

    private static void registerFromItem(
            FromItem fromItem,
            Map<String, String> aliases,
            Set<String> tables,
            Set<SqlParseUtil.SourceColumn> output) {
        if (fromItem == null) {
            return;
        }
        if (fromItem instanceof Table table) {
            String physical = SqlParseUtil.normalizeQualifiedName(
                    table.getFullyQualifiedName());
            tables.add(physical);
            aliases.put(physical, physical);
            aliases.put(SqlParseUtil.normalizeQualifiedName(
                    table.getName()), physical);
            if (table.getAlias() != null) {
                aliases.put(SqlParseUtil.normalizeQualifiedName(
                        table.getAlias().getUnquotedName()), physical);
            }
            return;
        }
        if (fromItem instanceof ParenthesedSelect nested
                && nested.getSelect() != null) {
            collectSelect(nested.getSelect(), output);
            return;
        }
        if (fromItem instanceof ParenthesedFromItem nested) {
            registerFromItem(
                    nested.getFromItem(), aliases, tables, output);
            if (nested.getJoins() != null) {
                for (Join join : nested.getJoins()) {
                    registerFromItem(join.getRightItem(), aliases,
                            tables, output);
                }
            }
        }
    }

    private static void collectExpression(
            Expression expression,
            Map<String, String> aliases,
            Set<String> tables,
            Set<SqlParseUtil.SourceColumn> output) {
        if (expression == null) {
            return;
        }
        expression.accept(new ExpressionVisitorAdapter<Void>() {
            @Override
            public <S> Void visit(Column column, S context) {
                String name = SqlParseUtil.normalizeQualifiedName(
                        column.getUnquotedColumnName());
                String qualifier = SqlParseUtil.normalizeQualifiedName(
                        column.getUnquotedTableName());
                if (!qualifier.isEmpty()) {
                    String table = aliases.get(qualifier);
                    if (table != null) {
                        output.add(new SqlParseUtil.SourceColumn(
                                table, name));
                    }
                } else if (tables.size() == 1) {
                    output.add(new SqlParseUtil.SourceColumn(
                            tables.iterator().next(), name));
                }
                return super.visit(column, context);
            }

            @Override
            public <S> Void visit(
                    ParenthesedSelect nested, S context) {
                if (nested.getSelect() != null) {
                    collectSelect(nested.getSelect(), output);
                }
                return null;
            }
        }, null);
    }

    private static Map<String, Set<String>> normalizeDeclarations(
            Map<String, ? extends List<String>> declarations) {
        if (declarations == null) {
            return Map.of();
        }
        Map<String, Set<String>> result = new LinkedHashMap<>();
        declarations.forEach((rawTable, rawColumns) -> {
            String table = SqlParseUtil.normalizeQualifiedName(rawTable);
            if (table.isEmpty()) {
                throw new IllegalArgumentException(
                        "分区声明表名不能为空");
            }
            LinkedHashSet<String> columns = new LinkedHashSet<>();
            if (rawColumns != null) {
                for (String rawColumn : rawColumns) {
                    String column = SqlParseUtil.normalizeQualifiedName(
                            rawColumn);
                    if (column.isEmpty() || column.contains(".")) {
                        throw new IllegalArgumentException(
                                "分区列必须为非限定标识符");
                    }
                    columns.add(column);
                }
            }
            result.put(table, Collections.unmodifiableSet(columns));
        });
        return Collections.unmodifiableMap(result);
    }

    public record PartitionCheck(
            String table,
            Set<String> requiredColumns,
            Set<String> matchedColumns,
            boolean covered) {

        public PartitionCheck {
            requiredColumns = Set.copyOf(requiredColumns);
            matchedColumns = Set.copyOf(matchedColumns);
        }
    }

    public record Inspection(
            SqlParseUtil.Analysis analysis,
            List<PartitionCheck> partitionChecks,
            Set<SqlParseUtil.SourceColumn> filterColumns) {

        public Inspection {
            partitionChecks = List.copyOf(partitionChecks);
            filterColumns = Set.copyOf(filterColumns);
        }
    }
}
