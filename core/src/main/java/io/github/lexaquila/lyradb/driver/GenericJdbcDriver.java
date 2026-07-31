package io.github.lexaquila.lyradb.driver;

import io.github.lexaquila.lyradb.model.entity.DriverInfo;

import java.util.Map;

/**
 * 通用JDBC驱动实现
 *
 * <p>
 * 为MySQL/PostgreSQL/Oracle/MSSQL等标准JDBC数据库提供默认实现。
 * 这些数据库的JDBC API行为一致，无需特殊处理——使用AbstractJdbcDriver的默认方法即可。
 * </p>
 *
 * <p>
 * "每种数据库都是一等公民"——即使是通用实现，也是为每种数据库单独创建实例，
 * 确保4种数据库各有独立的ClassLoader和Driver实例。
 * </p>
 */
public class GenericJdbcDriver extends AbstractJdbcDriver {

    private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
    private static final int MAX_CONNECT_TIMEOUT_SECONDS = 120;

    public GenericJdbcDriver(DriverInfo driverInfo, ClassLoader driverClassLoader) {
        super(driverInfo, driverClassLoader);
    }

    @Override
    protected void setExtraConnectionProperties(java.util.Properties props, Map<String, Object> params) {
        int timeoutSeconds = Math.max(1, Math.min(
                getIntParam(params, "connectTimeoutSeconds",
                        DEFAULT_CONNECT_TIMEOUT_SECONDS),
                MAX_CONNECT_TIMEOUT_SECONDS));
        String dbType = driverInfo.getDbType();
        switch (dbType) {
            case "MYSQL" -> {
                props.setProperty("connectTimeout",
                        String.valueOf(timeoutSeconds * 1_000));
                props.setProperty("tcpKeepAlive", "true");
                String sslMode = getStringParam(
                        params, "sslMode", "PREFERRED");
                props.setProperty("sslMode", sslMode);
                props.setProperty("allowPublicKeyRetrieval", String.valueOf(
                        getBooleanParam(params,
                                "allowPublicKeyRetrieval", false)));
                props.setProperty("rewriteBatchedStatements", "true");
                if (!"DISABLED".equalsIgnoreCase(sslMode)) {
                    props.setProperty(
                            "enabledTLSProtocols", "TLSv1.2,TLSv1.3");
                }
            }
            case "POSTGRESQL" -> {
                props.setProperty("connectTimeout", String.valueOf(timeoutSeconds));
                props.setProperty("ssl", String.valueOf(
                        getBooleanParam(params, "ssl", false)));
            }
            case "MSSQL" -> {
                props.setProperty("loginTimeout", String.valueOf(timeoutSeconds));
                props.setProperty("trustServerCertificate", String.valueOf(
                        getBooleanParam(params, "trustServerCertificate", false)));
            }
            case "ORACLE" -> props.setProperty(
                    "oracle.net.CONNECT_TIMEOUT",
                    String.valueOf(timeoutSeconds * 1_000));
            default -> {
            }
        }
    }
}
