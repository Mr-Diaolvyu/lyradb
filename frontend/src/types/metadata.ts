/**
 * 元数据和查询相关类型定义
 */

export interface TreeNode {
    id: string
    name: string
    type: 'CONNECTION' | 'DATABASE' | 'SCHEMA' | 'TABLE' | 'VIEW' | 'COLLECTION' | 'PARTITION' | 'KEY_GROUP' | 'KEY' | 'INFO' | 'INDEX_GROUP' | 'INDEX'
    iconType?: string
    hasChildren: boolean
    path: string
    properties?: Record<string, any>
}

export interface ColumnMetadata {
    name: string
    dataType: string
    typeName: string
    columnSize: number
    decimalDigits: number
    nullable: boolean
    defaultValue: string | null
    primaryKey: boolean
    autoIncrement: boolean
    remarks: string | null
    tableName: string
    schemaName: string | null
}

/** SQL 审核命中条目（迭代二 E2） */
export interface SqlReviewFinding {
    ruleId: string
    severity: 'HIGH' | 'MEDIUM' | 'LOW'
    message: string
}

export interface QueryResult {
    columns: string[]
    rows: Record<string, any>[]
    elapsedMs: number
    totalRows: number
    truncated: boolean
    sql: string
    /** 是否被 SQL 审核拦截（true 时 rows 为空） */
    reviewBlocked?: boolean
    /** SQL 审核命中规则（拦截原因或随结果附带的提醒） */
    reviewFindings?: SqlReviewFinding[]
}

export interface ExecuteQueryRequest {
    sql: string
    defaultDatabase?: string
    /** 确认"仍要执行"后跳过审核拦截 */
    force?: boolean
}

export interface ExecuteUpdateResult {
    success: boolean
    affectedRows?: number
    message?: string
    reviewBlocked?: boolean
    reviewFindings?: SqlReviewFinding[]
}

export interface ErTable {
    name: string
    columns: string[]
    schema?: string | null
}

export interface ErEdge {
    source: string
    target: string
    sourceColumn: string
    targetColumn: string
}

export interface ErDiagram {
    tables: ErTable[]
    edges: ErEdge[]
}
