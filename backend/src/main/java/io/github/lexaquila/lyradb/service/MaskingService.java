

package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.model.entity.MaskingRule;
import io.github.lexaquila.lyradb.repository.DataSourceRepository;
import io.github.lexaquila.lyradb.repository.MaskingRuleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作空间隔离且基于 AST 源列血缘的结果脱敏服务。
 */
@Service
public class MaskingService {

    private final MaskingRuleRepository repository;
    private final DataSourceRepository dataSourceRepository;
    private final ApprovalSecurityContextService approvalSecurityContextService;

    public MaskingService(MaskingRuleRepository repository,
                          DataSourceRepository dataSourceRepository,
                          ApprovalSecurityContextService approvalSecurityContextService) {
        this.repository = repository;
        this.dataSourceRepository = dataSourceRepository;
        this.approvalSecurityContextService = approvalSecurityContextService;
    }

    public List<MaskingRule> listAll(String workspaceId) {
        return repository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
    }

    @Transactional
    public MaskingRule save(MaskingRule input, String workspaceId) {
        if (input.getColumnPattern() == null || input.getColumnPattern().isBlank()) {
            throw new IllegalArgumentException("columnPattern 必填");
        }
        String maskType = input.getMaskType() == null
                ? "PARTIAL" : input.getMaskType().trim().toUpperCase();
        if (!Set.of("FULL", "PARTIAL", "HASH").contains(maskType)) {
            throw new IllegalArgumentException("maskType 仅支持 FULL/PARTIAL/HASH");
        }
        if (input.getDataSourceId() != null && !input.getDataSourceId().isBlank()) {
            DataSource dataSource = dataSourceRepository.findById(input.getDataSourceId())
                    .orElseThrow(() -> new RuntimeException("数据源不存在"));
            if (!workspaceId.equals(dataSource.getWorkspaceId())) {
                throw new RuntimeException("脱敏规则不能绑定其他工作空间的数据源");
            }
        }

        MaskingRule rule = input.getId() == null ? new MaskingRule()
                : repository.findByIdAndWorkspaceId(input.getId(), workspaceId)
                        .orElseThrow(() -> new RuntimeException(
                                "脱敏规则不存在或不属于当前工作空间"));
        approvalSecurityContextService.invalidateForMasking(workspaceId);
        rule.setWorkspaceId(workspaceId);
        rule.setDataSourceId(blankToNull(input.getDataSourceId()));
        rule.setTablePattern(blankToNull(input.getTablePattern()));
        rule.setColumnPattern(input.getColumnPattern().trim());
        rule.setMaskType(maskType);
        rule.setRemark(input.getRemark());
        rule.setEnabled(input.isEnabled());
        return repository.save(rule);
    }

    @Transactional
    public void delete(String id, String workspaceId) {
        MaskingRule rule = repository.findByIdAndWorkspaceId(id, workspaceId)
                .orElseThrow(() -> new RuntimeException(
                        "脱敏规则不存在或不属于当前工作空间"));
        approvalSecurityContextService.invalidateForMasking(workspaceId);
        repository.delete(rule);
    }

    /**
     * 根据 AST 输出列到源列的映射进行脱敏。若复杂查询无法完整建立血缘，
     * 只要命中敏感表规则就对全部输出列使用该规则，避免别名/子查询绕过。
     */
    public void applyMasking(QueryResult result, String workspaceId, String dataSourceId,
                             SqlParseUtil.Analysis analysis) {
        if (result == null || result.getColumns().isEmpty() || result.getRows().isEmpty()) {
            return;
        }
        MaskingPlan plan = preparePlan(
                workspaceId, dataSourceId, analysis, result.getColumns());
        for (Map<String, Object> row : result.getRows()) {
            plan.apply(row);
        }
    }

    /**
     * 预编译逐行脱敏计划。流式导出在读取 ResultSet 元数据后仅加载一次规则，
     * 后续每一行直接应用计划，避免完整结果集驻留内存或逐行访问数据库。
     */
    public MaskingPlan preparePlan(String workspaceId, String dataSourceId,
                                   SqlParseUtil.Analysis analysis,
                                   List<String> outputColumns) {
        if (analysis == null || outputColumns == null || outputColumns.isEmpty()) {
            return MaskingPlan.empty();
        }
        List<MaskingRule> rules = new ArrayList<>(
                repository.findByWorkspaceIdAndDataSourceIdIsNullAndEnabledTrue(workspaceId));
        rules.addAll(repository.findByWorkspaceIdAndDataSourceIdAndEnabledTrue(
                workspaceId, dataSourceId));

        Map<String, List<String>> masksByColumn = new LinkedHashMap<>();
        for (MaskingRule rule : rules) {
            Set<String> tablePatterns = SqlParseUtil.splitCsv(rule.getTablePattern());
            boolean relevantTable = tablePatterns.isEmpty()
                    || analysis.tables().stream().anyMatch(
                            table -> maskingTableMatches(table, tablePatterns));
            if (!relevantTable) {
                continue;
            }

            Set<String> columnPatterns = SqlParseUtil.splitCsv(rule.getColumnPattern());
            for (String outputColumn : outputColumns) {
                String normalizedOutput =
                        SqlParseUtil.normalizeQualifiedName(outputColumn);
                boolean shouldMask;
                if (!analysis.lineageComplete()) {
                    shouldMask = true;
                } else {
                    Set<SqlParseUtil.SourceColumn> sources =
                            analysis.outputLineage().getOrDefault(
                                    normalizedOutput, Set.of());
                    shouldMask = sources.stream().anyMatch(source -> {
                        boolean tableMatch = tablePatterns.isEmpty()
                                || maskingTableMatches(source.table(), tablePatterns);
                        return tableMatch
                                && SqlParseUtil.matchAny(
                                        source.column(), columnPatterns);
                    });
                    if (sources.isEmpty() && tablePatterns.isEmpty()) {
                        shouldMask = SqlParseUtil.matchAny(
                                normalizedOutput, columnPatterns);
                    }
                }
                if (shouldMask) {
                    masksByColumn
                            .computeIfAbsent(outputColumn,
                                    ignored -> new ArrayList<>())
                            .add(rule.getMaskType());
                }
            }
        }
        return new MaskingPlan(masksByColumn);
    }

    /** 已加载规则并按输出列编译的不可变脱敏计划。 */
    public static final class MaskingPlan {
        private final Map<String, List<String>> masksByColumn;

        private MaskingPlan(Map<String, List<String>> masksByColumn) {
            Map<String, List<String>> copy = new LinkedHashMap<>();
            masksByColumn.forEach((column, types) ->
                    copy.put(column, List.copyOf(types)));
            this.masksByColumn = Map.copyOf(copy);
        }

        private static MaskingPlan empty() {
            return new MaskingPlan(Map.of());
        }

        public void apply(Map<String, Object> row) {
            if (row == null || row.isEmpty()) {
                return;
            }
            for (Map.Entry<String, List<String>> entry :
                    masksByColumn.entrySet()) {
                Object value = row.get(entry.getKey());
                if (value == null) {
                    continue;
                }
                String masked = String.valueOf(value);
                for (String maskType : entry.getValue()) {
                    masked = mask(masked, maskType);
                }
                row.put(entry.getKey(), masked);
            }
        }
    }

    /** 脱敏裸表模式安全地覆盖所有 Schema 的同名表；只会扩大脱敏，不扩大授权。 */
    private static boolean maskingTableMatches(
            String physicalTable, Set<String> patterns) {
        String normalized = SqlParseUtil.normalizeQualifiedName(physicalTable);
        int separator = normalized.lastIndexOf('.');
        String tableOnly = separator >= 0
                ? normalized.substring(separator + 1) : normalized;
        for (String pattern : patterns) {
            if (SqlParseUtil.matchAny(normalized, Set.of(pattern))) {
                return true;
            }
            if (!pattern.contains(".")
                    && SqlParseUtil.matchAny(tableOnly, Set.of(pattern))) {
                return true;
            }
        }
        return false;
    }

    private static String mask(String value, String maskType) {
        return switch (maskType) {
            case "FULL" -> "******";
            case "HASH" -> sha256Prefix(value);
            default -> value.length() <= 2 ? "******"
                    : value.charAt(0) + "****"
                            + value.charAt(value.length() - 1);
        };
    }

    private static String sha256Prefix(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : hash) {
                result.append(String.format("%02x", item));
                if (result.length() >= 16) {
                    break;
                }
            }
            return result.substring(0, 16);
        } catch (Exception exception) {
            return "******";
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
