package io.github.lexaquila.lyradb.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SQL 审核结论条目
 *
 * <p>
 * 由 {@link io.github.lexaquila.lyradb.service.SqlReviewService} 产出，
 * 描述单条内置规则的命中情况。severity 三级对应三级处置：
 * </p>
 * <ul>
 * <li>HIGH - 拦截（个人版可"仍要执行"逃生，企业版走审批）</li>
 * <li>MEDIUM - 拦截（个人版可逃生；企业版 DDL 本身被授权层禁止）</li>
 * <li>LOW - 提醒（不阻断执行，随结果返回）</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SqlReviewFinding {

    /** 规则编号，如 R1_UPDATE_NO_WHERE */
    private String ruleId;

    /** 危险级别：HIGH / MEDIUM / LOW */
    private String severity;

    /** 面向用户的说明文案 */
    private String message;
}
