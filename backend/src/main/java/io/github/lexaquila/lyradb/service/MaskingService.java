package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.model.entity.MaskingRule;
import io.github.lexaquila.lyradb.repository.MaskingRuleRepository;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据脱敏服务（企业版 PM3）
 *
 * <p>
 * 管理员按数据源配置脱敏规则（表/列通配 + 脱敏方式），
 * 企业查询链路在结果返回前调用 {@link #applyMasking} 对命中列做原地脱敏。
 * 三种脱敏方式：FULL 全遮盖、PARTIAL 保留首尾、HASH SHA-256 摘要前 16 位。
 * </p>
 */
@Service
public class MaskingService {

    private static final Pattern TABLE_PATTERN = Pattern.compile(
            "(?i)\\b(?:FROM|JOIN|INTO|UPDATE)\\s+([A-Za-z_][\\w.]*)");

    private final MaskingRuleRepository repository;

    public MaskingService(MaskingRuleRepository repository) {
        this.repository = repository;
    }

    // ==================== 规则 CRUD（管理员） ====================

    public List<MaskingRule> listAll() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    public MaskingRule save(MaskingRule rule) {
        if (rule.getColumnPattern() == null || rule.getColumnPattern().isBlank()) {
            throw new RuntimeException("columnPattern 必填");
        }
        String maskType = rule.getMaskType() == null ? "PARTIAL" : rule.getMaskType().toUpperCase();
        if (!Set.of("FULL", "PARTIAL", "HASH").contains(maskType)) {
            throw new RuntimeException("maskType 仅支持 FULL/PARTIAL/HASH");
        }
        rule.setMaskType(maskType);
        return repository.save(rule);
    }

    public void delete(String id) {
        repository.deleteById(id);
    }

    // ==================== 结果集脱敏 ====================

    /**
     * 对查询结果做原地脱敏：数据源级规则 + 全局规则（dataSourceId 为空）合并生效。
     * 表通配依据 SQL 中出现的表名判断，列通配依据结果集列名判断。
     */
    public void applyMasking(QueryResult result, String dataSourceId) {
        if (result == null || result.getColumns().isEmpty() || result.getRows().isEmpty()) {
            return;
        }
        List<MaskingRule> rules = new ArrayList<>(repository.findByDataSourceIdIsNullAndEnabledTrue());
        if (dataSourceId != null) {
            rules.addAll(repository.findByDataSourceIdAndEnabledTrue(dataSourceId));
        }
        if (rules.isEmpty()) {
            return;
        }

        Set<String> sqlTables = extractTables(result.getSql());
        for (MaskingRule rule : rules) {
            // 表通配：配置了 tablePattern 但 SQL 未命中该表则跳过
            if (rule.getTablePattern() != null && !rule.getTablePattern().isBlank()
                    && !sqlTables.isEmpty()
                    && sqlTables.stream().noneMatch(
                            t -> SqlParseUtil.matchAny(t, SqlParseUtil.splitCsv(rule.getTablePattern())))) {
                continue;
            }
            Set<String> columnPatterns = SqlParseUtil.splitCsv(rule.getColumnPattern());
            for (String column : result.getColumns()) {
                if (!SqlParseUtil.matchAny(column, columnPatterns)) {
                    continue;
                }
                for (Map<String, Object> row : result.getRows()) {
                    Object value = row.get(column);
                    if (value != null) {
                        row.put(column, mask(String.valueOf(value), rule.getMaskType()));
                    }
                }
            }
        }
    }

    private String mask(String value, String maskType) {
        return switch (maskType) {
            case "FULL" -> "******";
            case "HASH" -> sha256Prefix(value);
            // PARTIAL：保留首尾各 1 位，中间遮盖；过短则全遮盖
            default -> value.length() <= 2 ? "******"
                    : value.charAt(0) + "****" + value.charAt(value.length() - 1);
        };
    }

    private String sha256Prefix(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
                if (sb.length() >= 16) {
                    break;
                }
            }
            return sb.substring(0, 16);
        } catch (Exception e) {
            return "******";
        }
    }

    private Set<String> extractTables(String sql) {
        Set<String> tables = new HashSet<>();
        if (sql == null) {
            return tables;
        }
        Matcher m = TABLE_PATTERN.matcher(sql);
        while (m.find()) {
            tables.add(m.group(1));
        }
        return tables;
    }
}
