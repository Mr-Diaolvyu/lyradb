/**
 * 元数据和查询 API
 */
import apiClient from './index'
import type { TreeNode, ColumnMetadata, QueryResult, ExecuteUpdateResult, ExecuteQueryRequest, ErDiagram } from '@/types/metadata'
import type { DriverCapability } from '@/types/driver'

export const metadataApi = {
    /** 获取导航树节点 */
    getTreeNodes(connectionId: string, path?: string): Promise<TreeNode[]> {
        const params = path ? { path } : {}
        return apiClient.get(`/metadata/${connectionId}/tree`, { params })
    },

    /** 获取表列信息 */
    getTableColumns(connectionId: string, schema: string | null, table: string): Promise<ColumnMetadata[]> {
        const params: any = { table }
        if (schema) params.schema = schema
        return apiClient.get(`/metadata/${connectionId}/columns`, { params })
    },

    /** 获取表DDL */
    getTableDDL(connectionId: string, schema: string | null, table: string): Promise<string> {
        const params: any = { table }
        if (schema) params.schema = schema
        return apiClient.get(`/metadata/${connectionId}/ddl`, { params })
    },

    /** 获取数据库列表 */
    getDatabases(connectionId: string): Promise<string[]> {
        return apiClient.get(`/metadata/${connectionId}/databases`)
    },

    /** 获取驱动能力声明 */
    getCapabilities(connectionId: string): Promise<DriverCapability> {
        return apiClient.get(`/metadata/${connectionId}/capabilities`)
    },

    /** 获取 ER 图数据（表 + 外键关系）；大库元数据扫描较慢，单独放宽超时 */
    getErDiagram(connectionId: string, schema?: string): Promise<ErDiagram> {
        const params = schema ? { schema } : {}
        return apiClient.get(`/metadata/${connectionId}/er`, { params, timeout: 180000 })
    },

    /** 搜索导航树节点 */
    searchNodes(connectionId: string, keyword: string, type?: string): Promise<TreeNode[]> {
        const params: any = { keyword }
        if (type) params.type = type
        return apiClient.get(`/metadata/${connectionId}/search`, { params })
    },
}

export const queryApi = {
    /** 执行查询SQL */
    executeQuery(connectionId: string, sql: string, defaultDatabase?: string): Promise<QueryResult> {
        const body: ExecuteQueryRequest = { sql }
        if (defaultDatabase) body.defaultDatabase = defaultDatabase
        return apiClient.post(`/query/${connectionId}/execute`, body)
    },

    /** 执行更新/DDL */
    executeUpdate(connectionId: string, sql: string, defaultDatabase?: string): Promise<ExecuteUpdateResult> {
        const body: any = { sql }
        if (defaultDatabase) body.defaultDatabase = defaultDatabase
        return apiClient.post(`/query/${connectionId}/update`, body)
    },

    /** 取消正在执行的查询（仅 JDBC 类驱动有效） */
    cancelQuery(connectionId: string): Promise<{ cancelled: boolean; message?: string }> {
        return apiClient.post(`/query/${connectionId}/cancel`)
    },

    /** 导出查询结果 (CSV/JSON/Excel/SQL INSERT) */
    export(connectionId: string, params: {
        sql: string
        format: 'csv' | 'json' | 'excel' | 'sql'
        defaultDatabase?: string
        tableName?: string
        limit?: number
    }): Promise<Blob> {
        return apiClient.post(`/query/${connectionId}/export`, {
            ...params,
            limit: params.limit ?? 100000,
        }, { responseType: 'blob' })
    },

    /** 批量导入数据 (CSV/JSON 解析为行) */
    importRows(connectionId: string, schema: string | null, table: string, rows: Record<string, any>[]): Promise<{ success: boolean; inserted: number; total: number; errors: string[]; message?: string }> {
        return apiClient.post(`/query/${connectionId}/import`, { schema, table, rows })
    },

    /** 文件上传导入（multipart，CSV 首行表头 / JSON 数组） */
    importFile(connectionId: string, schema: string | null, table: string, file: File, format?: 'csv' | 'json'): Promise<{ success: boolean; inserted: number; total: number; errors: string[]; message?: string }> {
        const form = new FormData()
        form.append('file', file)
        form.append('table', table)
        if (schema) form.append('schema', schema)
        if (format) form.append('format', format)
        return apiClient.post(`/query/${connectionId}/import/file`, form, {
            headers: { 'Content-Type': 'multipart/form-data' },
        })
    },
}
