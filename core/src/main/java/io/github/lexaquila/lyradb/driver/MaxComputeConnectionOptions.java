package io.github.lexaquila.lyradb.driver;

import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * MaxCompute JDBC 连接参数映射与前置校验。
 */
final class MaxComputeConnectionOptions {

    private static final Set<String> PUBLIC_DATASET_PROJECTS = Set.of(
            "MAXCOMPUTE_PUBLIC_DATA",
            "BIGDATA_PUBLIC_DATASET");

    private MaxComputeConnectionOptions() {
    }

    static void apply(Properties properties, Map<String, Object> parameters) {
        String endpoint = text(parameters, "endpoint");
        String project = text(parameters, "project");
        requireExecutionProject(project);

        // 官方 JDBC 属性优先级高于 URL，显式双写可避免 URL 解析或 Schema
        // 模式自动判断时把 Project 错当成默认 Schema。
        properties.setProperty("end_point", endpoint);
        properties.setProperty("project_name", project);
        properties.setProperty("charset", "UTF-8");

        String schema = text(parameters, "schema");
        if (!schema.isBlank()) {
            if (PUBLIC_DATASET_PROJECTS.contains(
                    schema.toUpperCase(Locale.ROOT))) {
                throw new IllegalArgumentException(
                        "公共数据集 Project 不能填写为默认 Schema。"
                                + "默认 Schema 应填写执行 Project 内真实存在的 Schema，"
                                + "公共数据请用 BIGDATA_PUBLIC_DATASET.<Schema>.<表> 查询。");
            }
            properties.setProperty("schema", schema);
        }

        String schemaMode = text(parameters, "schemaMode")
                .toUpperCase(Locale.ROOT);
        if ("ENABLED".equals(schemaMode)) {
            properties.setProperty("odps_namespace_schema", "true");
        } else if ("DISABLED".equals(schemaMode)) {
            properties.setProperty("odps_namespace_schema", "false");
        }

        int timeoutSeconds = integer(
                parameters, "connectTimeoutSeconds", 15, 1, 120);
        properties.setProperty(
                "connect_timeout", String.valueOf(timeoutSeconds * 1_000));
        properties.setProperty(
                "read_timeout", String.valueOf(timeoutSeconds * 4_000));
    }

    static void requireExecutionProject(String project) {
        if (project == null || project.isBlank()) {
            throw new IllegalArgumentException("MaxCompute 执行 Project 不能为空");
        }
        if (PUBLIC_DATASET_PROJECTS.contains(
                project.trim().toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "公共数据集 Project 不能作为 JDBC 执行 Project。"
                            + "请填写账号已加入且具有 CREATE INSTANCE 权限的 Project，"
                            + "再通过完整名称访问 BIGDATA_PUBLIC_DATASET.<Schema>.<表>。");
        }
    }

    private static String text(
            Map<String, Object> parameters, String key) {
        Object value = parameters == null ? null : parameters.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private static int integer(
            Map<String, Object> parameters,
            String key,
            int fallback,
            int minimum,
            int maximum) {
        String value = text(parameters, key);
        if (value.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(minimum, Math.min(maximum,
                    Integer.parseInt(value)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
