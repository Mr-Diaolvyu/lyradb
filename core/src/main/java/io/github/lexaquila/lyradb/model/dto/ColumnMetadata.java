package io.github.lexaquila.lyradb.model.dto;

import lombok.Data;

/**
 * 列元数据DTO
 *
 * <p>
 * 描述表结构的列信息，统一适配RDBMS/OLAP/NoSQL的列结构。
 * 对于MongoDB文档，描述字段名和类型；对于Redis Key，描述Key的类型和TTL。
 * </p>
 */
@Data
public class ColumnMetadata {

    /** 列名 */
    private String name;

    /** 数据类型 */
    private String dataType;

    /** 类型名称（如 VARCHAR, INT, TIMESTAMP） */
    private String typeName;

    /** 列大小 */
    private int columnSize;

    /** 小数位数 */
    private int decimalDigits;

    /** 是否允许NULL */
    private boolean nullable = true;

    /** 默认值 */
    private String defaultValue;

    /** 是否主键 */
    private boolean primaryKey = false;

    /** 是否自增 */
    private boolean autoIncrement = false;

    /** 注释/备注 */
    private String remarks;

    /** 表名 */
    private String tableName;

    /** Schema名 */
    private String schemaName;
}
