package io.github.lexaquila.lyradb.driver;

import io.github.lexaquila.lyradb.model.entity.DriverInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 驱动注册表
 *
 * <p>
 * 从drivers.json加载所有9种数据库驱动配置，并提供按dbType查找的接口。
 * 这是"Maven动态驱动"架构的配置中心——新增数据库只需在drivers.json添加一条配置。
 * </p>
 *
 * <p>
 * drivers.json结构：每种数据库一条记录，包含：
 * dbType, displayName, driverType(jdbc/nosql), driverClass, mavenCoordinates,
 * connectionUrlTemplate, defaultPort, capabilities, connectionFormFields
 * </p>
 */
@Component
public class DriverRegistry {

    private static final Logger log = LoggerFactory.getLogger(DriverRegistry.class);

    /** drivers.json 路径 */
    private static final String DRIVERS_JSON_PATH = "drivers.json";

    /** 按dbType索引的驱动信息 */
    private final Map<String, DriverInfo> driverInfoMap = new ConcurrentHashMap<>();

    /** 按displayName索引的驱动信息 */
    private final Map<String, DriverInfo> driverInfoByNameMap = new ConcurrentHashMap<>();

    /**
     * 初始化：加载drivers.json
     */
    @PostConstruct
    public void init() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ClassPathResource resource = new ClassPathResource(DRIVERS_JSON_PATH);
            try (InputStream is = resource.getInputStream()) {
                List<DriverInfo> drivers = mapper.readValue(is, new TypeReference<List<DriverInfo>>() {
                });

                for (DriverInfo driver : drivers) {
                    driverInfoMap.put(driver.getDbType().toUpperCase(), driver);
                    driverInfoByNameMap.put(driver.getDisplayName(), driver);
                    log.info("已注册驱动: {} ({})", driver.getDisplayName(), driver.getDbType());
                }

                log.info("驱动注册表加载完成，共 {} 个驱动", driverInfoMap.size());
            }
        } catch (Exception e) {
            log.error("加载drivers.json失败", e);
            throw new RuntimeException("无法加载驱动配置文件: " + e.getMessage(), e);
        }
    }

    /**
     * 按dbType获取驱动信息
     */
    public DriverInfo getDriverInfo(String dbType) {
        String normalized = normalizeDbType(dbType);
        DriverInfo info = driverInfoMap.get(normalized);
        if (info == null) {
            throw new IllegalArgumentException("不支持的数据库类型: " + dbType);
        }
        return info;
    }

    /**
     * 获取所有已注册的驱动信息
     */
    public List<DriverInfo> getAllDriverInfos() {
        return new ArrayList<>(driverInfoMap.values());
    }

    /**
     * 检查是否支持某种数据库
     */
    public boolean isSupported(String dbType) {
        return driverInfoMap.containsKey(normalizeDbType(dbType));
    }

    /**
     * 获取所有支持的数据库类型列表
     */
    public Set<String> getSupportedTypes() {
        return Collections.unmodifiableSet(driverInfoMap.keySet());
    }

    public static String normalizeDbType(String dbType) {
        String normalized = Objects.requireNonNullElse(dbType, "")
                .trim()
                .toUpperCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "");
        return "SQLSERVER".equals(normalized) ? "MSSQL" : normalized;
    }
}
