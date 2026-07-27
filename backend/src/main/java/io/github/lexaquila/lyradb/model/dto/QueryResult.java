package io.github.lexaquila.lyradb.model.dto;

import lombok.Data;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询结果DTO
 *
 * <p>
 * 统一的查询返回结果，适配所有数据库的查询输出。
 * 包含列信息和数据行，前端根据此结构渲染数据表格。
 * </p>
 *
 * <p>
 * 对于NoSQL查询（如MongoDB find / Redis scan），结果会被适配为相同结构。
 * </p>
 */
@Data
public class QueryResult {

    /** 列名列表 */
    private List<String> columns = new ArrayList<>();

    /** 数据行列表（每行是一个 列名→值 的Map） */
    private List<Map<String, Object>> rows = new ArrayList<>();

    /** 查询耗时（毫秒） */
    private long elapsedMs;

    /** 总行数（可能大于返回的rows大小，因为有limit） */
    private long totalRows;

    /** 是否被截断（达到limit上限） */
    private boolean truncated = false;

    /** 查询SQL */
    private String sql;

    /** 是否被 SQL 审核拦截（true 时 rows 为空，reviewFindings 为拦截原因） */
    private boolean reviewBlocked = false;

    /** SQL 审核命中规则（拦截原因或随结果附带的提醒） */
    private List<SqlReviewFinding> reviewFindings;

    /**
     * 添加列
     */
    public void addColumn(String columnName) {
        columns.add(columnName);
    }

    /**
     * 添加行
     */
    public void addRow(Map<String, Object> row) {
        rows.add(row);
    }

    /**
     * 创建一行数据
     */
    public Map<String, Object> newRow() {
        return new LinkedHashMap<>();
    }
}
