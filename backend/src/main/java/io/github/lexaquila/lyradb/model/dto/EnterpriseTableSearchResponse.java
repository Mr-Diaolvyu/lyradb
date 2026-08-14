package io.github.lexaquila.lyradb.model.dto;

import java.util.List;

/**
 * 企业版智能找表结果。
 *
 * <p>候选对象全部来自当前用户已经授权的轻量元数据目录。AI 只负责对候选
 * 重新排序并解释原因，服务端会丢弃模型返回的未知对象。</p>
 */
public record EnterpriseTableSearchResponse(
        String mode,
        String message,
        List<Recommendation> recommendations) {

    public record Recommendation(
            String path,
            String name,
            String schema,
            String type,
            String remarks,
            String reason,
            int confidence) {
    }
}
