/**
 * SQL 查询历史相关类型
 */

export interface QueryHistory {
    id: string
    connectionId: string
    dbType?: string
    sql: string
    title?: string | null
    favorite?: boolean
    tags?: string | null
    executedAt?: string
    durationMs?: number
    rowCount?: number
    success?: boolean
    errorMessage?: string | null
}

export interface HistoryFilter {
    connectionId?: string
    favorite?: boolean
}
