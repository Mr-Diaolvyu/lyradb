package io.github.lexaquila.lyradb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 通用数据库管理工具 - 后端主启动类
 *
 * <p>
 * Spring Boot 应用入口，扫描全部配置和组件。
 * 支持9种数据库（MySQL/PG/Oracle/MSSQL/SQLite + MaxCompute/ClickHouse +
 * MongoDB/Redis）的
 * 统一管理，通过Maven动态驱动加载机制实现运行时驱动下载。
 * </p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class LyraDbApplication {

    public static void main(String[] args) {
        SpringApplication.run(LyraDbApplication.class, args);
    }
}
