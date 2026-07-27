package io.github.lexaquila.lyradb.controller;

import io.github.lexaquila.lyradb.model.entity.QueryHistory;
import io.github.lexaquila.lyradb.service.QueryHistoryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * SQL 查询历史 REST 控制器
 *
 * <p>
 * 提供查询历史的检索、收藏、删除接口。
 * </p>
 */
@RestController
@RequestMapping("/history")
public class QueryHistoryController {

    private final QueryHistoryService queryHistoryService;

    public QueryHistoryController(QueryHistoryService queryHistoryService) {
        this.queryHistoryService = queryHistoryService;
    }

    /**
     * 查询历史列表
     *
     * <p>
     * 请求参数: connectionId（可选）, favorite=true/false（可选）
     * </p>
     */
    @GetMapping
    public List<QueryHistory> list(
            @RequestParam(value = "connectionId", required = false) String connectionId,
            @RequestParam(value = "favorite", required = false) Boolean favorite) {
        boolean fav = Boolean.TRUE.equals(favorite);
        return queryHistoryService.list(connectionId, fav);
    }

    /**
     * 关键字全文搜索
     */
    @GetMapping("/search")
    public List<QueryHistory> search(@RequestParam("keyword") String keyword) {
        return queryHistoryService.search(keyword);
    }

    /**
     * 切换收藏
     */
    @PostMapping("/{id}/favorite")
    public QueryHistory toggleFavorite(@PathVariable String id) {
        return queryHistoryService.toggleFavorite(id);
    }

    /**
     * 更新标签（逗号分隔，传空串或 null 表示清空）
     *
     * <p>
     * 请求体格式: { "tags": "报表,对账" }
     * </p>
     */
    @PutMapping("/{id}/tags")
    public QueryHistory updateTags(@PathVariable String id, @RequestBody Map<String, String> body) {
        return queryHistoryService.updateTags(id, body.get("tags"));
    }

    /**
     * 删除单条历史
     */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        queryHistoryService.delete(id);
    }

    /**
     * 清空历史
     *
     * <p>
     * 请求参数: connectionId（可选，指定则只清该连接的历史）
     * </p>
     */
    @DeleteMapping
    public void clear(@RequestParam(value = "connectionId", required = false) String connectionId) {
        queryHistoryService.clear(connectionId);
    }
}
