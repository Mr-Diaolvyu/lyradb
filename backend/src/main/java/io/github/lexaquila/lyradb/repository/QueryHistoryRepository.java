package io.github.lexaquila.lyradb.repository;

import io.github.lexaquila.lyradb.model.entity.QueryHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * SQL 查询历史仓储
 */
public interface QueryHistoryRepository extends JpaRepository<QueryHistory, String> {

    /**
     * 按连接查询历史（最近优先）
     */
    List<QueryHistory> findByConnectionIdOrderByExecutedAtDesc(String connectionId);

    /**
     * 按连接 + 收藏查询
     */
    List<QueryHistory> findByConnectionIdAndFavoriteTrueOrderByExecutedAtDesc(String connectionId);

    /**
     * 全部收藏的历史
     */
    List<QueryHistory> findByFavoriteTrueOrderByExecutedAtDesc();

    /**
     * 全部历史（最近优先）
     */
    List<QueryHistory> findAllByOrderByExecutedAtDesc();

    /**
     * 关键字全文搜索（SQL 文本、标题或标签，子串匹配），最近优先
     *
     * <p>
     * 注意：不使用 LOWER() —— Hibernate 6 的 lower() 不接受 @Lob 字段参数。
     * H2 默认 LIKE 对 VARCHAR 大小写不敏感，其他库可按需在应用层处理大小写。
     * </p>
     */
    @Query("SELECT h FROM QueryHistory h " +
            "WHERE h.sql LIKE CONCAT('%', :keyword, '%') " +
            "OR COALESCE(h.title, '') LIKE CONCAT('%', :keyword, '%') " +
            "OR COALESCE(h.tags, '') LIKE CONCAT('%', :keyword, '%') " +
            "ORDER BY h.executedAt DESC")
    List<QueryHistory> searchByKeyword(@Param("keyword") String keyword);

    /**
     * 删除指定连接的全部历史
     */
    void deleteByConnectionId(String connectionId);

    /**
     * 删除全部历史
     */
    void deleteAllBy();
}
