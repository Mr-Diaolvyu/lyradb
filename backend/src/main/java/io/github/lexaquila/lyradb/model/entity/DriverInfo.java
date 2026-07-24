package io.github.lexaquila.lyradb.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.util.List;

/**
 * 数据库驱动信息（从drivers.json加载）
 *
 * <p>
 * 这是Maven动态驱动管理的核心配置模型。每种数据库对应一个DriverInfo，
 * 包含Maven坐标、JDBC驱动类名、连接URL模板、能力声明、表单字段定义等。
 * </p>
 *
 * <p>
 * 新增数据库支持只需在drivers.json中添加一条配置，无需修改代码——
 * 这是"Maven动态驱动"核心架构特色的直接体现。
 * </p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DriverInfo {

    /** 数据库类型标识（大写枚举式，如MYSQL/POSTGRESQL/MAXCOMPUTE） */
    private String dbType;

    /** 显示名称（如"MySQL"/"PostgreSQL"/"MaxCompute"） */
    private String displayName;

    /** 驱动类型：jdbc 或 nosql */
    private String driverType;

    /** JDBC驱动类名（nosql类型为客户端类名） */
    private String driverClass;

    /** Maven坐标信息 */
    private MavenCoordinates mavenCoordinates;

    /** 连接URL模板，使用{host}/{port}/{database}等占位符 */
    private String connectionUrlTemplate;

    /** 默认端口号 */
    private int defaultPort;

    /** 驱动能力声明 */
    private DriverCapability capabilities;

    /** 连接表单字段定义 */
    private List<FormField> connectionFormFields;
}
