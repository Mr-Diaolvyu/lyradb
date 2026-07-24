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

export interface QueryResult {
    columns: string[]
    rows: Record<string, any>[]
    elapsedMs: number
    totalRows: number
    truncated: boolean
    sql: string
}

export interface ExecuteQueryRequest {
    sql: string
    defaultDatabase?: string
}

export interface ExecuteUpdateResult {
    success: boolean
    affectedRows?: number
    message?: string
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
