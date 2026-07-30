package io.github.lexaquila.lyradb.transfer.connection;

/**
 * 连接配置包的资源消耗限制。
 */
public record ConnectionPackageLimits(
        int maxFileBytes,
        int maxConnections,
        int maxParametersPerConnection,
        int maxCollectionElements,
        int maxNestingDepth,
        int maxStringLength) {

    public static final int DEFAULT_MAX_FILE_BYTES = 10 * 1024 * 1024;
    public static final int DEFAULT_MAX_CONNECTIONS = 1_000;
    public static final int DEFAULT_MAX_PARAMETERS_PER_CONNECTION = 256;
    public static final int DEFAULT_MAX_COLLECTION_ELEMENTS = 10_000;
    public static final int DEFAULT_MAX_NESTING_DEPTH = 16;
    public static final int DEFAULT_MAX_STRING_LENGTH = 65_536;

    public ConnectionPackageLimits {
        requirePositive(maxFileBytes, "maxFileBytes");
        requirePositive(maxConnections, "maxConnections");
        requirePositive(maxParametersPerConnection, "maxParametersPerConnection");
        requirePositive(maxCollectionElements, "maxCollectionElements");
        requirePositive(maxNestingDepth, "maxNestingDepth");
        requirePositive(maxStringLength, "maxStringLength");
    }

    public static ConnectionPackageLimits defaults() {
        return new ConnectionPackageLimits(
                DEFAULT_MAX_FILE_BYTES,
                DEFAULT_MAX_CONNECTIONS,
                DEFAULT_MAX_PARAMETERS_PER_CONNECTION,
                DEFAULT_MAX_COLLECTION_ELEMENTS,
                DEFAULT_MAX_NESTING_DEPTH,
                DEFAULT_MAX_STRING_LENGTH);
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " 必须大于 0");
        }
    }
}
