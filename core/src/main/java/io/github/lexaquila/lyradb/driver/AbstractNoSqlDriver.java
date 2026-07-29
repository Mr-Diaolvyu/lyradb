package io.github.lexaquila.lyradb.driver;

import io.github.lexaquila.lyradb.model.dto.ColumnMetadata;
import io.github.lexaquila.lyradb.model.dto.QueryResult;
import io.github.lexaquila.lyradb.model.dto.TreeNode;
import io.github.lexaquila.lyradb.model.entity.DriverCapability;
import io.github.lexaquila.lyradb.model.entity.DriverInfo;
import io.github.lexaquila.lyradb.model.entity.FormField;

import java.util.*;

/**
 * NoSQL通用驱动基类
 *
 * <p>
 * 为MongoDB和Redis两种NoSQL数据库提供通用实现框架。
 * 由于NoSQL数据库操作模式与JDBC完全不同，此类提供适配层将NoSQL操作
 * 统一为DatabaseDriver接口的标准方法。
 * </p>
 *
 * <p>
 * 子类（MongoDBDriver/RedisDriver）需要实现具体的连接、查询、元数据获取逻辑，
 * 但返回值统一为QueryResult/TreeNode等标准DTO。
 * </p>
 */
public abstract class AbstractNoSqlDriver implements DatabaseDriver {

    protected final DriverInfo driverInfo;
    protected final DriverCapability capabilities;
    protected final ClassLoader driverClassLoader;

    /**
     * 构造函数
     *
     * @param driverInfo        驱动配置信息
     * @param driverClassLoader 隔离的ClassLoader
     */
    protected AbstractNoSqlDriver(DriverInfo driverInfo, ClassLoader driverClassLoader) {
        this.driverInfo = driverInfo;
        this.capabilities = driverInfo.getCapabilities();
        this.driverClassLoader = driverClassLoader;
    }

    @Override
    public DriverInfo getDriverInfo() {
        return driverInfo;
    }

    @Override
    public DriverCapability getCapabilities() {
        return capabilities;
    }

    @Override
    public String buildConnectionUrl(Map<String, Object> params) {
        String template = driverInfo.getConnectionUrlTemplate();
        String url = template;

        for (FormField field : driverInfo.getConnectionFormFields()) {
            String placeholder = "{" + field.getName() + "}";
            Object value = params.get(field.getName());
            if (value != null) {
                url = url.replace(placeholder, value.toString());
            } else if (field.getDefaultValue() != null) {
                url = url.replace(placeholder, field.getDefaultValue().toString());
            }
        }

        return url;
    }

    /**
     * 安全获取字符串参数
     */
    protected String getStringParam(Map<String, Object> params, String key, String defaultValue) {
        Object value = params.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    /**
     * 安全获取整数参数
     */
    protected int getIntParam(Map<String, Object> params, String key, int defaultValue) {
        Object value = params.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * 安全获取布尔参数
     */
    protected boolean getBooleanParam(Map<String, Object> params, String key, boolean defaultValue) {
        Object value = params.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return defaultValue;
    }

    /**
     * 将一个JSON文档/Key-Value转换为行数据
     */
    protected Map<String, Object> convertToRow(Object document) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (document instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) document;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                row.put(entry.getKey(), entry.getValue());
            }
        } else if (document != null) {
            row.put("value", document.toString());
        }
        return row;
    }

    /**
     * 从行数据中提取列名
     */
    protected List<String> extractColumns(List<Map<String, Object>> rows) {
        Set<String> columns = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            columns.addAll(row.keySet());
        }
        return new ArrayList<>(columns);
    }
}
