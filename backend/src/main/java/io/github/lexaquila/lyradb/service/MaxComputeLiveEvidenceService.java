package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.ai.AiDigest;
import io.github.lexaquila.lyradb.model.dto.TreeNode;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通过当前已授权 MaxCompute JDBC 连接读取分区元数据，并执行 EXPLAIN/COST SQL。
 * 命令均为只读元命令，响应有行数和字符硬上限，且不会读取样本业务行。
 */
@Service
public class MaxComputeLiveEvidenceService {

    private static final Logger log = LoggerFactory.getLogger(
            MaxComputeLiveEvidenceService.class);
    private static final int MAX_COMMAND_ROWS = 256;
    private static final int MAX_COMMAND_CHARS = 256_000;
    private static final Pattern INPUT_BYTES = Pattern.compile(
            "(?i)(?:estimated[_\\s-]*)?(?:input|scan)[_\\s-]*bytes?\\s*[:=]\\s*([0-9][0-9,]*)");
    private static final Pattern COST_MICROS = Pattern.compile(
            "(?i)(?:estimated[_\\s-]*)?cost[_\\s-]*micros?\\s*[:=]\\s*([0-9][0-9,]*)");

    private final DataSourceService dataSourceService;

    public MaxComputeLiveEvidenceService(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    public LiveEvidence inspect(
            DataSource source, Set<String> authorizedTables, String sql) {
        if (source == null || authorizedTables == null
                || authorizedTables.isEmpty()) {
            throw new IllegalArgumentException("实时证据需要数据源和已授权表范围");
        }
        List<String> warnings = new ArrayList<>();
        Map<String, List<String>> partitions = new LinkedHashMap<>();
        String explainSha = null;
        String costSha = null;
        Long inputBytes = null;
        Long costMicros = null;
        boolean partitionMetadataComplete = true;
        boolean anyObserved = false;
        try {
            ConnectionService.ActiveConnection active =
                    dataSourceService.resolveActiveConnection(source.getId());
            if (!(active.connection instanceof Connection connection)) {
                return unavailable("MaxCompute 活跃连接不是 JDBC Connection");
            }
            try (ConnectionService.ActiveConnection.Lease ignored =
                         active.acquire()) {
                for (String table : authorizedTables) {
                    LinkedHashSet<String> columns = new LinkedHashSet<>();
                    try {
                        for (TreeNode node : active.driver.getTreeNodes(
                                active.connection, table)) {
                            if (node != null
                                    && "PARTITION".equalsIgnoreCase(node.getType())
                                    && node.getName() != null
                                    && !node.getName().isBlank()) {
                                columns.add(node.getName().trim());
                            }
                        }
                        anyObserved = true;
                    } catch (Exception exception) {
                        warnings.add("无法读取表分区元数据: " + table);
                    }
                    if (columns.isEmpty()) {
                        partitionMetadataComplete = false;
                        warnings.add("未观测到分区列: " + table);
                    }
                    partitions.put(table, List.copyOf(columns));
                }

                CommandResult explain = executeMetadataCommand(
                        connection, "EXPLAIN " + sql);
                if (explain.success()) {
                    anyObserved = true;
                    explainSha = explain.sha256();
                } else {
                    warnings.add("EXPLAIN 不可用: " + explain.error());
                }

                CommandResult cost = executeMetadataCommand(
                        connection, "COST SQL " + sql);
                if (cost.success()) {
                    anyObserved = true;
                    costSha = cost.sha256();
                    inputBytes = parseLong(INPUT_BYTES, cost.text());
                    costMicros = parseLong(COST_MICROS, cost.text());
                    if (inputBytes == null) {
                        warnings.add("COST SQL 未返回可识别的扫描字节标签");
                    }
                    if (costMicros == null) {
                        warnings.add("COST SQL 未返回明确的 cost_micros，未做币种或单位猜测");
                    }
                } else {
                    warnings.add("COST SQL 不可用: " + cost.error());
                }
            }
        } catch (RuntimeException exception) {
            log.warn("MaxCompute 实时证据读取失败: source={}, type={}",
                    source.getId(), exception.getClass().getSimpleName());
            return unavailable("实时连接或元命令不可用");
        }
        boolean complete = partitionMetadataComplete
                && costMicros != null && explainSha != null;
        String status = !anyObserved ? "UNAVAILABLE"
                : complete ? "OBSERVED" : "PARTIAL";
        return new LiveEvidence(status, partitions, inputBytes,
                costMicros, explainSha, costSha, warnings, complete);
    }

    public static LiveEvidence disabled() {
        return new LiveEvidence("DISABLED", Map.of(), null, null,
                null, null, List.of("实时证据功能未启用"), false);
    }

    private static LiveEvidence unavailable(String warning) {
        return new LiveEvidence("UNAVAILABLE", Map.of(), null, null,
                null, null, List.of(warning), false);
    }

    private static CommandResult executeMetadataCommand(
            Connection connection, String command) {
        StringBuilder text = new StringBuilder();
        try (Statement statement = connection.createStatement()) {
            try {
                statement.setMaxRows(MAX_COMMAND_ROWS);
                statement.setQueryTimeout(30);
            } catch (Exception ignored) {
                // ODPS JDBC 某些版本不实现提示能力；读取循环仍有硬上限。
            }
            try (ResultSet rows = statement.executeQuery(command)) {
                ResultSetMetaData metadata = rows.getMetaData();
                int columns = metadata.getColumnCount();
                int count = 0;
                while (rows.next() && count++ < MAX_COMMAND_ROWS
                        && text.length() < MAX_COMMAND_CHARS) {
                    for (int index = 1; index <= columns; index++) {
                        if (text.length() >= MAX_COMMAND_CHARS) {
                            break;
                        }
                        if (index > 1
                                && text.length() < MAX_COMMAND_CHARS) {
                            text.append('\t');
                        }
                        if (text.length() >= MAX_COMMAND_CHARS) {
                            break;
                        }
                        Object value = rows.getObject(index);
                        if (value != null) {
                            int remaining = MAX_COMMAND_CHARS - text.length();
                            String cell = value.toString();
                            text.append(cell, 0, Math.min(
                                    remaining, cell.length()));
                        }
                    }
                    if (text.length() < MAX_COMMAND_CHARS) {
                        text.append('\n');
                    }
                }
            }
            String value = text.toString();
            return new CommandResult(
                    true, value, AiDigest.sha256(value), null);
        } catch (Exception exception) {
            return new CommandResult(false, "", null,
                    exception.getClass().getSimpleName());
        }
    }

    private static Long parseLong(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Long.parseLong(matcher.group(1).replace(",", ""));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private record CommandResult(
            boolean success, String text, String sha256, String error) {
    }

    public record LiveEvidence(
            String status,
            Map<String, List<String>> partitionColumns,
            Long estimatedInputBytes,
            Long estimatedCostMicros,
            String explainSha256,
            String costCommandSha256,
            List<String> warnings,
            boolean completeForDecision) {

        public LiveEvidence {
            partitionColumns = Map.copyOf(
                    partitionColumns == null ? Map.of() : partitionColumns);
            warnings = List.copyOf(warnings == null ? List.of() : warnings);
        }

        public io.github.lexaquila.lyradb.model.dto.MaxComputeLiveEvidenceView view() {
            return new io.github.lexaquila.lyradb.model.dto.MaxComputeLiveEvidenceView(
                    status, partitionColumns, estimatedInputBytes,
                    estimatedCostMicros, explainSha256,
                    costCommandSha256, warnings);
        }

        public String digest() {
            return AiDigest.sha256(String.join("\n",
                    status, String.valueOf(partitionColumns),
                    String.valueOf(estimatedInputBytes),
                    String.valueOf(estimatedCostMicros),
                    String.valueOf(explainSha256),
                    String.valueOf(costCommandSha256)));
        }
    }
}
