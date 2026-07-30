package io.github.lexaquila.lyradb.desktop.metadata;

import java.util.Locale;

/**
 * 将不同数据库导航树的表路径映射为快照归属与驱动元数据命名空间。
 */
record MetadataTableLocation(String databaseName,
                             String schemaName,
                             String metadataNamespace) {

    static MetadataTableLocation resolve(
            String dbType, String databaseFallback, String tablePath) {
        String type = normalize(dbType);
        String fallback = fallback(databaseFallback);
        String[] parts = split(tablePath);
        return switch (type) {
            case "MSSQL" -> sqlServer(parts, fallback);
            case "MYSQL", "CLICKHOUSE", "MONGODB" ->
                    catalogDatabase(parts, fallback);
            case "POSTGRESQL", "ORACLE" ->
                    schemaDatabase(parts, fallback);
            case "SQLITE", "MAXCOMPUTE" ->
                    new MetadataTableLocation(fallback, "", "");
            default -> generic(parts, fallback);
        };
    }

    private static MetadataTableLocation sqlServer(
            String[] parts, String fallback) {
        String database = parts.length >= 3 ? parts[0] : fallback;
        String schema = parts.length >= 3 ? parts[1]
                : parts.length >= 2 ? parts[0] : "";
        String namespace = database.isBlank() || schema.isBlank()
                ? schema : database + "/" + schema;
        return new MetadataTableLocation(database, schema, namespace);
    }

    private static MetadataTableLocation catalogDatabase(
            String[] parts, String fallback) {
        String database = parts.length >= 2 ? parts[0] : fallback;
        return new MetadataTableLocation(database, "", database);
    }

    private static MetadataTableLocation schemaDatabase(
            String[] parts, String fallback) {
        String schema = parts.length >= 2 ? parts[0] : "";
        return new MetadataTableLocation(fallback, schema, schema);
    }

    private static MetadataTableLocation generic(
            String[] parts, String fallback) {
        if (parts.length >= 3) {
            String database = parts[0];
            String schema = parts[parts.length - 2];
            return new MetadataTableLocation(
                    database, schema, database + "/" + schema);
        }
        if (parts.length == 2) {
            return new MetadataTableLocation(
                    fallback, parts[0], parts[0]);
        }
        return new MetadataTableLocation(fallback, "", "");
    }

    private static String[] split(String path) {
        if (path == null || path.isBlank()) {
            return new String[0];
        }
        return java.util.Arrays.stream(path.split("/"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toArray(String[]::new);
    }

    private static String fallback(String value) {
        return value == null || value.isBlank()
                ? "当前连接" : value.trim();
    }

    private static String normalize(String value) {
        return value == null ? ""
                : value.trim().toUpperCase(Locale.ROOT);
    }
}
