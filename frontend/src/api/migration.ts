/**
 * 跨库数据迁移 API
 */
import apiClient from './index'

export interface MigrationRequest {
    sourceConnectionId: string
    targetConnectionId: string
    sourceSchema?: string
    sourceTable: string
    targetSchema?: string
    targetTable: string
    mode?: 'create' | 'append'
    batchSize?: number
    maxRows?: number
}

export interface MigrationResult {
    success: boolean
    rowsRead: number
    rowsWritten: number
    errors?: string[]
    elapsedMs?: number
    message?: string
}

export const migrationApi = {
    migrate(req: MigrationRequest): Promise<MigrationResult> {
        return apiClient.post('/migration', req)
    },
}
