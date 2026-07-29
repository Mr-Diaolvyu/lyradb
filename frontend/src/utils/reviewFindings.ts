import { h, type VNode } from 'vue'
import type { SqlReviewFinding } from '@/types/metadata'

const SEVERITY_LABEL: Record<SqlReviewFinding['severity'], string> = {
    HIGH: '高危',
    MEDIUM: '中危',
    LOW: '提醒',
}

/**
 * SQL 审核消息使用 VNode 文本节点渲染服务端内容。
 * finding.message 即使包含 HTML/脚本也只会作为文字展示。
 */
export function buildReviewFindingsMessage(findings: SqlReviewFinding[]): VNode {
    return h('div', [
        'SQL 审核命中以下规则：',
        h(
            'ul',
            { class: 'sql-review-findings' },
            findings.map(finding => h('li', [
                h('b', `[${SEVERITY_LABEL[finding.severity]}]`),
                ` ${finding.message}`,
            ])),
        ),
    ])
}
