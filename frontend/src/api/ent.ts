/**
 * 企业治理 API（数据源/授权/查询/审批/审计/管理）
 */
import apiClient from './index'
import type { QueryResult } from '@/types/metadata'

export interface LogicalGrant {
    id: string
    grantedSourceName: string
    workspaceId?: string
    allowedSchemas?: string
    allowedTables?: string
    blockedTables?: string
    sqlCapability: string
    maxRowsPerQuery: number
    exportApprovedOnly: boolean
}

export interface AdminDataSource {
    id: string
    workspaceId?: string
    dbType: string
    displayName: string
    description?: string
    params: Record<string, any>
    createdBy?: string
    createdAt?: string
}

export interface AdminGrant extends LogicalGrant {
    dataSourceId: string
    userId?: string
}

export interface ApprovalRequest {
    id: string
    applicantId?: string
    applicantName?: string
    operationType: string
    dataSourceId?: string
    grantedSourceName?: string
    payloadJson?: string
    reason?: string
    status: string
    approverId?: string
    approverComment?: string
    expiresAt?: string
    executedAt?: string
    executionResult?: string
    createdAt?: string
}

export interface AuditLog {
    id: string
    userId?: string
    username?: string
    role?: string
    grantedSourceName?: string
    dbType?: string
    operationType: string
    sqlText?: string
    resultRows?: number
    affectedRows?: number
    elapsedMs?: number
    success?: boolean
    errorMessage?: string
    createdAt?: string
}

export interface MaskingRule {
    id?: string
    dataSourceId?: string
    tablePattern?: string
    columnPattern: string
    maskType: string
    remark?: string
    enabled: boolean
    createdAt?: string
}

export interface Page<T> {
    content: T[]
    totalElements: number
    totalPages: number
    number: number
    size: number
}

export const entApi = {
    // 授权（用户侧，逻辑）
    grantsMine(): Promise<LogicalGrant[]> {
        return apiClient.get('/grants/mine')
    },

    // 企业查询
    query(grantedSourceName: string, sql: string, defaultDatabase?: string): Promise<QueryResult> {
        return apiClient.post('/ent/query', { grantedSourceName, sql, defaultDatabase })
    },

    // 企业导出（需已批准 approvalRequestId，返回 blob）
    export(approvalRequestId: string, body: { grantedSourceName: string; sql: string; format?: string; defaultDatabase?: string }): Promise<Blob> {
        return apiClient.post(`/ent/export?approvalRequestId=${encodeURIComponent(approvalRequestId)}`, body, { responseType: 'blob' })
    },

    // AI
    aiPresets(): Promise<Record<string, { displayName: string; baseUrl: string; model: string }>> {
        return apiClient.get('/ai/presets')
    },
    aiProviders(workspaceId?: string): Promise<any[]> {
        const params = workspaceId ? { params: { workspaceId } } : {}
        return apiClient.get('/ai/providers', params as any)
    },
    aiChat(grantedSourceName: string, message: string, history: any[]): Promise<any> {
        return apiClient.post('/ai/chat', { grantedSourceName, message, history })
    },
    // AI 管理
    adminAiProviders(workspaceId?: string): Promise<any[]> {
        const params = workspaceId ? { params: { workspaceId } } : {}
        return apiClient.get('/admin/ai/providers', params as any)
    },
    adminCreateAiProvider(body: any): Promise<{ id: string; success: boolean }> {
        return apiClient.post('/admin/ai/providers', body)
    },
    adminSetDefaultAiProvider(id: string): Promise<void> {
        return apiClient.post(`/admin/ai/providers/${id}/default`)
    },
    adminDeleteAiProvider(id: string): Promise<void> {
        return apiClient.delete(`/admin/ai/providers/${id}`)
    },

    // 审批
    approvals(mine = false, status?: string): Promise<ApprovalRequest[]> {
        const params: any = {}
        if (mine) params.mine = true
        if (status) params.status = status
        return apiClient.get('/approvals', { params })
    },
    approvalsPending(): Promise<ApprovalRequest[]> {
        return apiClient.get('/approvals/pending')
    },
    createApproval(body: any): Promise<ApprovalRequest> {
        return apiClient.post('/approvals', body)
    },
    approveApproval(id: string, comment?: string): Promise<ApprovalRequest> {
        return apiClient.post(`/approvals/${id}/approve`, { comment })
    },
    rejectApproval(id: string, comment?: string): Promise<ApprovalRequest> {
        return apiClient.post(`/approvals/${id}/reject`, { comment })
    },
    executeApproval(id: string, result: string, success = true): Promise<ApprovalRequest> {
        return apiClient.post(`/approvals/${id}/execute`, { result, success })
    },
    cancelApproval(id: string): Promise<ApprovalRequest> {
        return apiClient.delete(`/approvals/${id}`)
    },

    // 审计
    auditMine(page = 0, size = 50): Promise<Page<AuditLog>> {
        return apiClient.get('/audit/mine', { params: { page, size } })
    },

    // 管理员：数据源
    adminDataSources(workspaceId?: string): Promise<AdminDataSource[]> {
        const params = workspaceId ? { params: { workspaceId } } : {}
        return apiClient.get('/admin/datasources', params as any)
    },
    adminCreateDataSource(body: any): Promise<{ id: string; success: boolean }> {
        return apiClient.post('/admin/datasources', body)
    },
    adminDeleteDataSource(id: string): Promise<void> {
        return apiClient.delete(`/admin/datasources/${id}`)
    },
    adminTestDataSource(id: string): Promise<{ success: boolean; message: string }> {
        return apiClient.post(`/admin/datasources/${id}/test`)
    },

    // 管理员：授权
    adminGrants(workspaceId: string): Promise<AdminGrant[]> {
        return apiClient.get('/admin/grants', { params: { workspaceId } })
    },
    adminCreateGrant(body: any): Promise<{ id: string; success: boolean }> {
        return apiClient.post('/admin/grants', body)
    },
    adminDeleteGrant(id: string): Promise<void> {
        return apiClient.delete(`/admin/grants/${id}`)
    },

    // 管理员：用户
    adminUsers(): Promise<any[]> {
        return apiClient.get('/admin/users')
    },
    adminCreateUser(body: any): Promise<{ id: string; success: boolean }> {
        return apiClient.post('/admin/users', body)
    },

    // 管理员：脱敏规则
    adminMaskingRules(): Promise<MaskingRule[]> {
        return apiClient.get('/admin/masking')
    },
    adminSaveMaskingRule(body: Partial<MaskingRule>): Promise<MaskingRule> {
        return apiClient.post('/admin/masking', body)
    },
    adminDeleteMaskingRule(id: string): Promise<void> {
        return apiClient.delete(`/admin/masking/${id}`)
    },
}
