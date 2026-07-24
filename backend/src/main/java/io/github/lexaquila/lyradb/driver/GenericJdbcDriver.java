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

    public GenericJdbcDriver(DriverInfo driverInfo, ClassLoader driverClassLoader) {
        super(driverInfo, driverClassLoader);
    }

    @Override
    protected void setExtraConnectionProperties(java.util.Properties props, Map<String, Object> params) {
        // MySQL特殊处理：设置时区和字符编码
        if ("MYSQL".equals(driverInfo.getDbType())) {
            // URL模板中已包含参数，这里不需要额外设置
        }
        // PostgreSQL SSL特殊处理
        if ("POSTGRESQL".equals(driverInfo.getDbType())) {
            boolean ssl = getBooleanParam(params, "ssl", false);
            props.setProperty("ssl", String.valueOf(ssl));
        }
    }
}
