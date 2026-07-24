package io.github.lexaquila.lyradb.model.entity;

import lombok.Data;

/**
 * 数据库驱动能力描述模型
 *
 * <p>
 * 声明每种数据库支持的能力（事务/编辑/分区/NoSQL特性等），
 * 前端根据能力模型动态显示/隐藏功能按钮，后端根据能力模型控制操作权限。
 * </p>
 *
 * <p>
 * 这是"9种数据库全部一等公民"核心设计的关键——每种数据库的能力差异
 * 通过此模型声明，而非硬编码在代码逻辑中。
 * </p>
 */
@Data
public class DriverCapability {

    /** 是否支持事务 */
    private boolean supportsTransaction = false;

    /** 是否支持DML（INSERT/UPDATE/DELETE） */
    private boolean supportsDML = false;

    /** 是否支持DDL（CREATE/ALTER/DROP） */
    private boolean supportsDDL = false;

    /** 是否支持分区表 */
    private boolean supportsPartition = false;

    /** 是否支持视图 */
    private boolean supportsViews = false;

    /** 是否支持存储过程 */
    private boolean supportsProcedures = false;

    /** 是否支持函数 */
    private boolean supportsFunctions = false;

    /** 是否支持索引 */
    private boolean supportsIndexes = false;

    /** 是否支持触发器 */
    private boolean supportsTriggers = false;

    /** 是否支持SSL/TLS连接 */
    private boolean supportsSSL = false;

    /** 是否只读（OLAP数据库如MaxCompute通常为只读） */
    private boolean readOnly = false;

    // === NoSQL特有能力 ===

    /** MaxCompute分区表层级展开 */
    private boolean supportsMaxComputePartition = false;

    /** MongoDB文档树形展示 */
    private boolean supportsDocumentTree = false;

    /** Redis Key前缀分组 */
    private boolean supportsKeyPrefixGrouping = false;
}
