package io.github.lexaquila.lyradb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lexaquila.lyradb.model.entity.DataSource;
import io.github.lexaquila.lyradb.repository.DataSourceRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 使用工作空间默认 AI Provider 生成脱敏候选；不会保存规则或访问源数据库。 */
@Service
public class AiMaskingRuleDraftService {
    private static final Set<String> MASK_TYPES = Set.of("FULL", "PARTIAL", "HASH");
    private static final Pattern RESOURCE = Pattern.compile(
            "(?:[\\p{L}_][\\p{L}\\p{N}_$]*\\.)*(?:[\\p{L}_][\\p{L}\\p{N}_$]*\\*?|\\*)");
    private static final String SYSTEM_PROMPT = """
            你为 LyraDB 管理员生成展示端数据脱敏候选规则。只输出一个 JSON 对象，字段为
            tablePattern、columnPattern、maskType、remark、explanation。不要输出 Markdown。
            tablePattern 为空字符串表示所选数据源的所有表；否则只允许完整表名或末尾 * 前缀通配。
            columnPattern 为逗号分隔的列名或末尾 * 前缀通配。不要使用 *_phone 等前置通配。
            maskType 只能是 FULL（全遮盖）、PARTIAL（保留首尾）、HASH（不可逆摘要）。
            用户说“加密”或“哈希”时，使用 HASH，并在 explanation 说明这是展示端不可逆摘要，
            不是修改源数据库，也不是可解密的密文。不要提供 SQL、源库更新或执行步骤。
            根据用户描述给出保守候选；无法确认真实字段名时，在 explanation 明确提示管理员核对。
            """;

    private final DataSourceRepository dataSources;
    private final AiProviderService providers;
    private final ObjectMapper mapper;

    public AiMaskingRuleDraftService(DataSourceRepository dataSources,
                                     AiProviderService providers,
                                     ObjectMapper mapper) {
        this.dataSources = dataSources;
        this.providers = providers;
        this.mapper = mapper;
    }

    public Draft generate(String workspaceId, String dataSourceId,
                          String instruction) throws Exception {
        if (instruction == null || instruction.isBlank()
                || instruction.length() > 500) {
            throw new IllegalArgumentException("请用 1-500 字描述脱敏要求");
        }
        if (dataSourceId == null || dataSourceId.isBlank()) {
            throw new IllegalArgumentException("请先选择数据源");
        }
        DataSource source = dataSources.findById(dataSourceId)
                .orElseThrow(() -> new IllegalArgumentException("数据源不存在"));
        if (!workspaceId.equals(source.getWorkspaceId())) {
            throw new IllegalArgumentException("数据源不属于当前工作空间");
        }
        String description = mapper.writeValueAsString(Map.of(
                "selectedDataSource", source.getDisplayName(),
                "dbType", source.getDbType(),
                "instruction", instruction.trim()));
        String answer = providers.chat(providers.resolveDefault(workspaceId), List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", description)));
        JsonNode json = mapper.readTree(stripCodeFence(answer));
        if (json == null || !json.isObject()) {
            throw new IllegalStateException("AI 未返回可解析的规则，请重试或手动填写");
        }
        String tablePattern = optional(json, "tablePattern", 200);
        String columnPattern = required(json, "columnPattern", 500);
        String maskType = required(json, "maskType", 16).toUpperCase();
        String remark = optional(json, "remark", 200);
        String explanation = optional(json, "explanation", 500);
        if ("*".equals(tablePattern)) tablePattern = "";
        validatePatterns(tablePattern, true);
        validatePatterns(columnPattern, false);
        if (!MASK_TYPES.contains(maskType)) {
            throw new IllegalStateException("AI 返回了不支持的脱敏方式，请重试或手动填写");
        }
        return new Draft(dataSourceId, tablePattern, columnPattern, maskType,
                remark, explanation);
    }

    private static String stripCodeFence(String answer) {
        String value = answer == null ? "" : answer.trim();
        if (!value.startsWith("```")) return value;
        int firstLineEnd = value.indexOf('\n');
        int lastFence = value.lastIndexOf("```");
        if (firstLineEnd < 0 || lastFence <= firstLineEnd) return value;
        return value.substring(firstLineEnd + 1, lastFence).trim();
    }

    private static String optional(JsonNode json, String field, int maxLength) {
        JsonNode node = json.get(field);
        if (node == null || node.isNull()) return "";
        if (!node.isTextual() || node.asText().length() > maxLength) {
            throw new IllegalStateException("AI 返回的 " + field + " 格式无效");
        }
        return node.asText().trim();
    }

    private static String required(JsonNode json, String field, int maxLength) {
        String value = optional(json, field, maxLength);
        if (value.isBlank()) {
            throw new IllegalStateException("AI 未给出 " + field + "，请重试或手动填写");
        }
        return value;
    }

    private static void validatePatterns(String csv, boolean optional) {
        if (csv.isBlank()) {
            if (optional) return;
            throw new IllegalStateException("AI 未给出列匹配范围");
        }
        for (String value : csv.split(",", -1)) {
            if (!RESOURCE.matcher(value.trim()).matches()) {
                throw new IllegalStateException("AI 返回的资源通配符不受支持，请手动核对");
            }
        }
    }

    public record Draft(String dataSourceId, String tablePattern,
                        String columnPattern, String maskType,
                        String remark, String explanation) { }
}
