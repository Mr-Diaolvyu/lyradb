package io.github.lexaquila.lyradb.driver;

import io.github.lexaquila.lyradb.config.AppProperties;
import io.github.lexaquila.lyradb.model.entity.DriverInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 驱动工厂
 *
 * <p>
 * 根据dbType创建对应的DatabaseDriver实例。工厂内部通过MavenDriverManager
 * 下载/加载驱动JAR，然后实例化对应的驱动类。
 * </p>
 *
 * <p>
 * 9种数据库通过此工厂统一创建：
 * </p>
 * <ul>
 * <li>JDBC类型(7种)：MySQL/PG/Oracle/MSSQL/SQLite/ClickHouse/MaxCompute →
 * AbstractJdbcDriver子类</li>
 * <li>NoSQL类型(2种)：MongoDB/Redis → AbstractNoSqlDriver子类</li>
 * </ul>
 *
 * <p>
 * 这是"9种数据库全部一等公民"的实现入口——无论什么数据库，
 * 统一通过此工厂获取DatabaseDriver实例，接口完全一致。
 * </p>
 */
@Component
public class DriverFactory {

    private static final Logger log = LoggerFactory.getLogger(DriverFactory.class);

    private final DriverRegistry driverRegistry;
    private final MavenDriverManager mavenDriverManager;
    private final AppProperties appProperties;

    /** 按连接ID缓存的Driver实例 */
    private final Map<String, DatabaseDriver> driverCache = new ConcurrentHashMap<>();

    public DriverFactory(DriverRegistry driverRegistry, MavenDriverManager mavenDriverManager,
                        AppProperties appProperties) {
        this.driverRegistry = driverRegistry;
        this.mavenDriverManager = mavenDriverManager;
        this.appProperties = appProperties;
    }

    /**
     * 创建数据库驱动实例
     *
     * @param dbType 数据库类型（如MYSQL/POSTGRESQL/MAXCOMPUTE/MONGODB/REDIS）
     * @return DatabaseDriver实例
     */
    public DatabaseDriver createDriver(String dbType) {
        DriverInfo driverInfo = driverRegistry.getDriverInfo(dbType);

        try {
            // 通过Maven动态下载并加载驱动JAR
            ClassLoader classLoader = mavenDriverManager.getOrCreateClassLoader(driverInfo);

            // 根据driverType创建对应的驱动实例
            String driverType = driverInfo.getDriverType();
            DatabaseDriver driver;

            if ("jdbc".equals(driverType)) {
                // JDBC类型驱动
                if ("MAXCOMPUTE".equals(dbType.toUpperCase())) {
                    driver = new MaxComputeDriver(driverInfo, classLoader);
                } else if ("CLICKHOUSE".equals(dbType.toUpperCase())) {
                    driver = new ClickHouseDriver(driverInfo, classLoader);
                } else if ("SQLITE".equals(dbType.toUpperCase())) {
                    driver = new SQLiteDriver(driverInfo, classLoader);
                } else {
                    // MySQL/PostgreSQL/Oracle/MSSQL 使用通用JDBC驱动
                    driver = new GenericJdbcDriver(driverInfo, classLoader);
                }
            } else if ("nosql".equals(driverType)) {
                // NoSQL类型驱动
                if ("MONGODB".equals(dbType.toUpperCase())) {
                    driver = new MongoDBDriver(driverInfo, classLoader);
                } else if ("REDIS".equals(dbType.toUpperCase())) {
                    driver = new RedisDriver(driverInfo, classLoader);
                } else {
                    throw new IllegalArgumentException("不支持的NoSQL数据库类型: " + dbType);
                }
            } else {
                throw new IllegalArgumentException("未知的驱动类型: " + driverType);
            }

            log.info("创建驱动实例: {} ({})", driverInfo.getDisplayName(), dbType);

            // 注入 SQL 执行超时配置（仅 JDBC 驱动支持）
            if (driver instanceof AbstractJdbcDriver jdbcDriver) {
                jdbcDriver.setQueryTimeoutSeconds(appProperties.getQueryTimeoutSeconds());
            }

            return driver;

        } catch (Exception e) {
            log.error("创建驱动失败: {} - {}", dbType, e.getMessage(), e);
            throw new RuntimeException("无法创建数据库驱动: " + e.getMessage(), e);
        }
    }

    /**
     * 获取或创建驱动实例（带缓存）
     *
     * @param connectionId 连接ID（作为缓存key）
     * @param dbType       数据库类型
     * @return DatabaseDriver实例
     */
    public DatabaseDriver getOrCreateDriver(String connectionId, String dbType) {
        return driverCache.computeIfAbsent(connectionId, k -> createDriver(dbType));
    }

    /**
     * 移除缓存中的驱动实例
     */
    public void removeDriver(String connectionId) {
        driverCache.remove(connectionId);
    }

    /**
     * 检查驱动是否已下载
     */
    public boolean isDriverDownloaded(String dbType) {
        DriverInfo driverInfo = driverRegistry.getDriverInfo(dbType);
        return mavenDriverManager.isDriverDownloaded(driverInfo);
    }
}
