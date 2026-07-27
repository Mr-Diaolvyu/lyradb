package io.github.lexaquila.lyradb.service;

import io.github.lexaquila.lyradb.model.entity.QueryHistory;
import io.github.lexaquila.lyradb.repository.QueryHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * SQL 查询历史服务
 *
 * <p>
 * 记录、检索、收藏用户执行过的 SQL（PRD F4「可搜索 SQL 历史」）。
 * </p>
 */
@Service
public class QueryHistoryService {

    private static final Logger log = LoggerFactory.getLogger(QueryHistoryService.class);

    /** 单次返回的历史条目上限，避免无限制拉取 */
    private static final int MAX_RESULTS = 500;

    private final QueryHistoryRepository repository;

    public QueryHistoryService(QueryHistoryRepository repository) {
        this.repository = repository;
    }

    /**
     * 记录一条执行历史
     */
    public QueryHistory record(String connectionId, String dbType, String sql,
            Long durationMs, Long rowCount, boolean success, String errorMessage) {
        try {
            QueryHistory h = new QueryHistory();
            h.setConnectionId(connectionId);
            h.setDbType(dbType);
            h.setSql(sql);
            h.setDurationMs(durationMs);
            h.setRowCount(rowCount);
            h.setSuccess(success);
            if (!success && errorMessage != null) {
                h.setErrorMessage(truncate(errorMessage, 2000));
            }
            return repository.save(h);
        } catch (Exception e) {
            // 历史记录失败不应影响主流程
            log.warn("记录查询历史失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 查询历史列表
     *
     * @param connectionId 可选，限定连接
     * @param favoriteOnly 是否仅收藏
     */
    public List<QueryHistory> list(String connectionId, boolean favoriteOnly) {
        List<QueryHistory> all;
        if (connectionId != null && !connectionId.isEmpty()) {
            all = favoriteOnly
                    ? repository.findByConnectionIdAndFavoriteTrueOrderByExecutedAtDesc(connectionId)
                    : repository.findByConnectionIdOrderByExecutedAtDesc(connectionId);
        } else {
            all = favoriteOnly
                    ? repository.findByFavoriteTrueOrderByExecutedAtDesc()
                    : repository.findAllByOrderByExecutedAtDesc();
        }
        return cap(all);
    }

    /**
     * 关键字全文搜索
     */
    public List<QueryHistory> search(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return list(null, false);
        }
        return cap(repository.searchByKeyword(keyword.trim()));
    }

    /**
     * 切换收藏
     */
    public QueryHistory toggleFavorite(String id) {
        QueryHistory h = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("历史记录不存在: " + id));
        h.setFavorite(!Boolean.TRUE.equals(h.getFavorite()));
        return repository.save(h);
    }

    /**
     * 更新标签（逗号分隔，空串/null 表示清空）
     */
    public QueryHistory updateTags(String id, String tags) {
        QueryHistory h = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("历史记录不存在: " + id));
        String normalized = tags == null ? null : tags.trim();
        if (normalized != null && normalized.isEmpty()) {
            normalized = null;
        }
        if (normalized != null) {
            normalized = truncate(normalized, 500);
        }
        h.setTags(normalized);
        return repository.save(h);
    }

    /**
     * 删除单条
     */
    public void delete(String id) {
        repository.deleteById(id);
    }

    /**
     * 清空历史（指定连接则只清该连接的）
     */
    public void clear(String connectionId) {
        if (connectionId != null && !connectionId.isEmpty()) {
            repository.deleteByConnectionId(connectionId);
        } else {
            repository.deleteAll();
        }
    }

    private List<QueryHistory> cap(List<QueryHistory> list) {
        if (list.size() > MAX_RESULTS) {
            return list.subList(0, MAX_RESULTS);
        }
        return list;
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
