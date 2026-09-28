/**
 * 企业治理 API（数据源/授权/查询/审批/审计/管理）
 */
import apiClient from './index'
import type { ColumnMetadata, ErDiagram, QueryResult, TableInspection, TablePartitionPage } from '@/types/metadata'
import type {
    AiAgentOrchestrationRequest,
    AiAgentOrchestrationView,
    AiCapabilities,
    AiChatResponse,
    AiGatewayTokenIssuedView,
    AiGatewayTokenView,
    AiKnowledgeAssetView,
    AiKnowledgeDraftRequest,
    AiKnowledgeIngestionView,
    AiOperationsView,
    AiProviderView,
    AiQualityDashboardView,
    AiQualityRunView,
    AiReadAgentCancelView,
    AiReadAgentExecutionView,
    AiReadAgentPlanRequest,
    AiReadAgentPlanView,
    AgentGatewayScope,
    MaxComputeDiagnosticView,
    MaxComputePreflightRequest,
    MaxComputePreflightView,
} from '@/types/ai'

export interface LogicalGrant {
    id: string
    grantedSourceName: string
    workspaceId?: string
    allowedSchemas?: string
    allowedTables?: string
    blockedTables?: string
    dbType?: string
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
    lastTestStatus?: 'NOT_TESTED' | 'CONNECTED' | 'FAILED' | 'DRIVER_UNAVAILABLE' | 'STALE'
    lastTestedAt?: string
    lastTestElapsedMs?: number
    lastTestErrorCode?: string
}

export interface AdminDataSourceTestResponse {
    success: boolean
    message: string
    elapsedMs?: number
    status?: string
    testedAt?: string
}

export interface DataSourceTestBatch {
    id: string
    state: 'RUNNING' | 'DONE'
    items: Array<{
        dataSourceId: string
        displayName: string
        state: 'PENDING' | 'RUNNING' | 'DONE' | 'ERROR'
        result?: AdminDataSourceTestResponse
    }>
}

export interface BatchGrantSource {
    dataSourceId: string
    grantedSourceName: string
    allowedSchemas: string
    allowedTables: string
    blockedTables: string
    sqlCapability: 'READ_ONLY' | 'DML_ALLOWED'
    maxRowsPerQuery: number
    expiresAt?: string
}

export interface BatchGrantRequest {
    userIds: string[]
    sources: BatchGrantSource[]
}

export interface BatchGrantPreview {
    count: number
    valid: boolean
    items: Array<BatchGrantSource & { userId: string; valid: boolean; error?: string }>
    errors: string[]
}

export interface AdminGrantScopeOptions {
    namespaces: Array<{ value: string; label: string; tablePrefix: string }>
    tables: string[]
    truncated: boolean
}

export interface AdminUser {
    id: string
    username: string
    displayName?: string
    email?: string
    enabled: boolean
    roles: string[]
    workspaceIds: string[]
}

export interface AdminUserScript {
    id: string
    title: string
    grantedSourceName: string
    updatedAt: string
}

export interface SavedSql {
    id: string
    title: string
    grantedSourceName: string
    sql: string
    createdAt: string
    updatedAt: string
}

export interface EnterpriseQueryHistory {
    id: string
    grantedSourceName: string
    sql: string
    succeeded: boolean
    elapsedMs: number
    createdAt: string
}

export interface TableEditChange {
    action: 'INSERT' | 'UPDATE' | 'DELETE'
    key?: Record<string, unknown>
    token?: string
    values?: Record<string, unknown>
}

export interface TableEditSnapshot {
    editable: boolean
    reason: string
    columns: ColumnMetadata[]
    primaryKeys: string[]
    lockedColumns: string[]
    rows: Array<{
        key: Record<string, unknown>
        token: string
        values: Record<string, unknown>
    }>
    truncated?: boolean
}

export interface AdminDataSourceCredentialResponse {
    field: string
    value: string
}

export interface AdminDataSourceSaveRequest {
    dbType?: string
    displayName: string
    description?: string
    params?: Record<string, any>
}

export interface AdminGrant extends LogicalGrant {
    dataSourceId: string
    userId?: string
    granteeUsername?: string
    granteeDisplayName?: string
    expiresAt?: string | null
}

export interface EnterpriseMetadataTable {
    schema: string
    namespace: string
    name: string
    qualifiedName: string
    type: string
    remarks?: string | null
    metadataSource?: string | null
    metadataStatus?: string | null
    metadataReason?: string | null
    remarksStatus?: string | null
    partitioned?: boolean | null
}

export interface EnterpriseMetadataCatalog {
    grantedSourceName: string
    dbType: string
    schemas: string[]
    tables: EnterpriseMetadataTable[]
    truncated: boolean
    refreshedAt: number
}

export interface EnterpriseNavigationNode {
    name: string
    type: string
    path: string
    hasChildren: boolean
    table?: EnterpriseMetadataTable
}

export interface EnterpriseNavigationPage {
    nodes: EnterpriseNavigationNode[]
    total: number
    offset: number
    limit: number
    hasMore: boolean
}

export interface EnterpriseTableSearchPage {
    tables: EnterpriseMetadataTable[]
    hasMore: boolean
}

export type EnterprisePartitionPage = TablePartitionPage

export interface AiTableRecommendation {
    path: string
    name?: string
    schema?: string
    type?: string
    remarks?: string | null
    reason: string
    confidence: number
}

export interface AiTableSearchResponse {
    mode: 'AI' | 'LOCAL_FALLBACK' | string
    message: string
    recommendations: AiTableRecommendation[]
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

export interface AiMaskingRuleDraft {
    dataSourceId: string
    tablePattern: string
    columnPattern: string
    maskType: 'FULL' | 'PARTIAL' | 'HASH'
    remark: string
    explanation: string
}

export type CredentialExportMode = 'OMIT' | 'PLAINTEXT' | 'PASSWORD_ENCRYPTED'
export type ImportConflictAction = 'SKIP' | 'RENAME' | 'OVERWRITE'

export interface ConnectionExportRequest {
    dataSourceIds: string[]
    credentialMode: CredentialExportMode
    plaintextRiskConfirmed: boolean
    reason?: string
}

export interface DataSourceExportDownloadRequest {
    password?: string
    plaintextRiskConfirmed: boolean
}

export interface ConnectionImportPreviewItem {
    entryKey: string
    displayName: string
    dbType: string
    conflict: boolean
    existingDisplayName?: string
    parameterKeys: string[]
    credentialKeys: string[]
    credentialsIncluded: boolean
}

export interface ConnectionImportPreview {
    previewToken: string
    credentialPolicy: CredentialExportMode
    riskCode?: string
    expiresAt?: string
    items: ConnectionImportPreviewItem[]
}

export interface ConnectionImportDecision {
    entryKey: string
    action: ImportConflictAction
    newDisplayName?: string
}

export interface ConnectionImportResult {
    created: number
    overwritten: number
    skipped: number
}

export interface MetadataSelection {
    grantedSourceName: string
    database?: string
    schemas?: string[]
    tables?: string[]
}

export interface MetadataTablePreview {
    database?: string
    schema?: string
    table: string
    type?: string
    columns: string[]
}

export interface MetadataSnapshotSummary {
    id: string
    grantedSourceName: string
    database?: string
    schemas?: string[]
    tables?: string[]
    databaseCount: number
    schemaCount: number
    tableCount: number
    columnCount: number
    approximateTokens: number
    preview: MetadataTablePreview[]
    expiresAt?: string
}

export interface AiChatRequest {
    grantedSourceName: string
    message: string
    history: Array<{ role: string; content: string }>
    attachMetadata?: boolean
    metadataSnapshotId?: string
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
    query(grantedSourceName: string, sql: string, defaultDatabase?: string, executionId?: string): Promise<QueryResult> {
        return apiClient.post('/ent/query', { grantedSourceName, sql, defaultDatabase, executionId })
    },
    prepareQuery(grantedSourceName: string): Promise<{ executionId: string }> {
        return apiClient.post('/ent/query/executions', { grantedSourceName })
    },
    cancelQuery(executionId: string): Promise<{ cancelRequested: boolean }> {
        return apiClient.post(`/ent/query/executions/${encodeURIComponent(executionId)}/cancel`)
    },
    savedSqlScripts(): Promise<SavedSql[]> {
        return apiClient.get('/ent/scripts')
    },
    saveSqlScript(body: { id?: string; title: string; grantedSourceName: string; sql: string }): Promise<SavedSql> {
        return apiClient.post('/ent/scripts', body)
    },
    deleteSqlScript(id: string): Promise<{ success: boolean }> {
        return apiClient.delete(`/ent/scripts/${encodeURIComponent(id)}`)
    },
    enterpriseQueryHistory(): Promise<EnterpriseQueryHistory[]> {
        return apiClient.get('/ent/history')
    },
    tableEditSnapshot(grantedSourceName: string, schema: string, table: string): Promise<TableEditSnapshot> {
        return apiClient.get('/ent/table-edits/snapshot', {
            params: { grantedSourceName, schema, table },
        })
    },
    metadataNavigation(grantedSourceName: string, parentPath?: string,
        offset = 0, limit = 100, query?: string): Promise<EnterpriseNavigationPage> {
        return apiClient.get('/ent/metadata/navigation', {
            params: { grantedSourceName, parentPath, offset, limit, query },
        })
    },
    metadataSearch(grantedSourceName: string, query: string): Promise<EnterpriseTableSearchPage> {
        return apiClient.get('/ent/metadata/search', {
            params: { grantedSourceName, query },
        })
    },
    requestTableEdit(body: { grantedSourceName: string; schema: string; table: string;
        changes: TableEditChange[]; reason?: string }): Promise<ApprovalRequest> {
        return apiClient.post('/ent/table-edits/requests', body)
    },
    executeTableEdit(id: string): Promise<{ success: boolean; count: number }> {
        return apiClient.post(`/ent/table-edits/${encodeURIComponent(id)}/execute`)
    },

    inspectTable(
        grantedSourceName: string,
        schema: string,
        table: string,
        objectType = 'TABLE',
        limit = 200,
        options?: {
            includePreview?: boolean
            partitionSpec?: string | null
        },
    ): Promise<TableInspection> {
        return apiClient.post('/ent/table-inspection', {
            grantedSourceName, schema, table, objectType,
            limit: Math.min(200, Math.max(1, limit)),
            includePreview: options?.includePreview ?? false,
            partitionSpec: options?.partitionSpec || null,
        })
    },

    tablePartitions(
        grantedSourceName: string,
        schema: string,
        table: string,
        offset = 0,
        limit = 50,
        filter = '',
    ): Promise<EnterprisePartitionPage> {
        return apiClient.post('/ent/table-partitions', {
            grantedSourceName, schema, table,
            offset: Math.max(0, offset),
            limit: Math.min(100, Math.max(1, limit)),
            filter,
        })
    },

    metadataCatalog(
        grantedSourceName: string,
        refresh = false,
    ): Promise<EnterpriseMetadataCatalog> {
        return apiClient.get('/ent/metadata/catalog', {
            params: { grantedSourceName, refresh },
        })
    },

    metadataColumns(
        grantedSourceName: string,
        namespace: string,
        table: string,
    ): Promise<ColumnMetadata[]> {
        return apiClient.get('/ent/metadata/columns', {
            params: { grantedSourceName, namespace, table },
        })
    },

    erDiagram(
        grantedSourceName: string,
        schema: string,
        tables: string[],
    ): Promise<ErDiagram> {
        return apiClient.get('/ent/er', {
            params: { grantedSourceName, schema, tables: tables.join(',') },
        })
    },
    lineage(body: { grantedSourceName: string; schema: string; tables: string[];
        column?: string; direction: string; maxDepth?: number; maxNodes?: number }): Promise<ErDiagram> {
        return apiClient.post('/ent/lineage', body, { timeout: 180000 })
    },

    // 企业导出（需已批准 approvalRequestId，返回 blob）
    export(approvalRequestId: string, body: { sql: string; format: 'csv' | 'json'; defaultDatabase: string | null }): Promise<Blob> {
        return apiClient.post(`/ent/export?approvalRequestId=${encodeURIComponent(approvalRequestId)}`, body, { responseType: 'blob' })
    },

    // AI
    aiPresets(): Promise<Record<string, { displayName: string; baseUrl: string; model: string }>> {
        return apiClient.get('/ai/presets')
    },
    aiProviders(workspaceId?: string): Promise<AiProviderView[]> {
        const params = workspaceId ? { params: { workspaceId } } : {}
        return apiClient.get('/ai/providers', params as any)
    },
    aiCapabilities(): Promise<AiCapabilities> {
        return apiClient.get('/ai/capabilities')
    },
    aiChat(body: AiChatRequest): Promise<AiChatResponse> {
        return apiClient.post('/ai/chat', body)
    },
    aiTableSearch(body: {
        grantedSourceName: string
        query: string
        limit?: number
    }): Promise<AiTableSearchResponse> {
        return apiClient.post('/ai/table-search', body)
    },
    aiAgentOrchestrate(body: AiAgentOrchestrationRequest): Promise<AiAgentOrchestrationView> {
        return apiClient.post('/ai/agent/orchestrate', body)
    },
    aiReadPlan(body: AiReadAgentPlanRequest): Promise<AiReadAgentPlanView> {
        return apiClient.post('/ai/agent/read/plans', body)
    },
    aiReadExecute(runId: string, planSha256: string): Promise<AiReadAgentExecutionView> {
        return apiClient.post(`/ai/agent/read/plans/${encodeURIComponent(runId)}/execute`, { planSha256 })
    },
    aiReadCancel(runId: string): Promise<AiReadAgentCancelView> {
        return apiClient.post(`/ai/agent/read/plans/${encodeURIComponent(runId)}/cancel`)
    },
    aiKnowledgeVerified(): Promise<AiKnowledgeAssetView[]> {
        return apiClient.get('/ai/knowledge/verified')
    },
    aiKnowledgeMine(): Promise<AiKnowledgeAssetView[]> {
        return apiClient.get('/ai/knowledge/mine')
    },
    aiKnowledgeReview(): Promise<AiKnowledgeAssetView[]> {
        return apiClient.get('/ai/knowledge/review')
    },
    aiKnowledgeCreateDraft(body: AiKnowledgeDraftRequest): Promise<AiKnowledgeAssetView> {
        return apiClient.post('/ai/knowledge/drafts', body)
    },
    aiKnowledgeIngestMetadata(snapshotId: string): Promise<AiKnowledgeIngestionView> {
        return apiClient.post(`/ai/knowledge/ingestions/metadata/${encodeURIComponent(snapshotId)}`)
    },
    aiKnowledgeSubmit(id: string): Promise<AiKnowledgeAssetView> {
        return apiClient.post(`/ai/knowledge/${encodeURIComponent(id)}/submit`)
    },
    aiKnowledgeReviewDecision(id: string, decision: 'VERIFY' | 'REJECT' | 'RETIRE', comment?: string): Promise<AiKnowledgeAssetView> {
        return apiClient.post(`/ai/knowledge/${encodeURIComponent(id)}/review`, { decision, comment })
    },
    aiQualityDashboard(): Promise<AiQualityDashboardView> {
        return apiClient.get('/ai/quality/dashboard')
    },
    aiQualityEvaluateAutomatically(): Promise<AiQualityRunView> {
        return apiClient.post('/ai/quality/evaluate/auto', { acknowledgeProviderUsage: true })
    },
    aiOperationsMetrics(): Promise<AiOperationsView> {
        return apiClient.get('/ai/operations/metrics')
    },
    aiMaxComputePreflight(body: MaxComputePreflightRequest): Promise<MaxComputePreflightView> {
        return apiClient.post('/ai/maxcompute/preflight', body)
    },
    aiMaxComputeDiagnose(body: { taskStatus?: string; errorCode?: string; errorMessage?: string }): Promise<MaxComputeDiagnosticView> {
        return apiClient.post('/ai/maxcompute/diagnose', body)
    },
    aiGatewayTokens(): Promise<AiGatewayTokenView[]> {
        return apiClient.get('/ai/gateway/tokens')
    },
    aiGatewayIssue(body: {
        displayName: string
        grantId: string
        scopes: AgentGatewayScope[]
        expiresAt: string
    }): Promise<AiGatewayTokenIssuedView> {
        return apiClient.post('/ai/gateway/tokens', body)
    },
    aiGatewayRevoke(id: string): Promise<AiGatewayTokenView> {
        return apiClient.post(`/ai/gateway/tokens/${encodeURIComponent(id)}/revoke`)
    },
    createMetadataSnapshot(selection: MetadataSelection, signal?: AbortSignal): Promise<MetadataSnapshotSummary> {
        return apiClient.post('/ai/metadata/snapshots', selection, { signal })
    },
    downloadMetadataSnapshot(id: string, format: 'json' | 'markdown'): Promise<Blob> {
        return apiClient.get(`/ai/metadata/snapshots/${encodeURIComponent(id)}/download`, {
            params: { format },
            responseType: 'blob',
        })
    },
    // AI 管理
    adminAiProviders(workspaceId?: string): Promise<AiProviderView[]> {
        const params = workspaceId ? { params: { workspaceId } } : {}
        return apiClient.get('/admin/ai/providers', params as any)
    },
    adminCreateAiProvider(body: Omit<AiProviderView, 'id' | 'apiKey'> & { apiKey?: string }): Promise<{ id: string; success: boolean }> {
        return apiClient.post('/admin/ai/providers', body)
    },
    adminSetDefaultAiProvider(id: string): Promise<void> {
        return apiClient.post(`/admin/ai/providers/${id}/default`)
    },
    adminTestAiProvider(id: string): Promise<{ success: boolean; message: string; elapsedMs?: number }> {
        return apiClient.post(`/admin/ai/providers/${encodeURIComponent(id)}/test`)
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
    approvalDetail(id: string): Promise<ApprovalRequest> {
        return apiClient.get(`/approvals/${encodeURIComponent(id)}`)
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
    adminDataSource(id: string): Promise<AdminDataSource> {
        return apiClient.get(`/admin/datasources/${id}`)
    },
    adminCreateDataSource(body: AdminDataSourceSaveRequest): Promise<{ id: string; success: boolean }> {
        return apiClient.post('/admin/datasources', body)
    },
    adminUpdateDataSource(id: string, body: AdminDataSourceSaveRequest): Promise<{ success: boolean }> {
        return apiClient.put(`/admin/datasources/${id}`, body)
    },
    adminRevealDataSourceCredential(id: string, field: string): Promise<AdminDataSourceCredentialResponse> {
        return apiClient.post(
            `/admin/datasources/${id}/credentials/reveal`,
            { field },
        )
    },
    adminDeleteDataSource(id: string): Promise<void> {
        return apiClient.delete(`/admin/datasources/${id}`)
    },
    adminTestDataSource(id: string): Promise<AdminDataSourceTestResponse> {
        return apiClient.post(
            `/admin/datasources/${id}/test`,
            undefined,
            { timeout: 120_000 },
        )
    },
    adminStartDataSourceTestBatch(dataSourceIds: string[]): Promise<DataSourceTestBatch> {
        return apiClient.post('/admin/datasources/test-batches', { dataSourceIds })
    },
    adminDataSourceTestBatch(id: string): Promise<DataSourceTestBatch> {
        return apiClient.get(`/admin/datasources/test-batches/${encodeURIComponent(id)}`)
    },
    adminRequestDataSourceExport(body: ConnectionExportRequest): Promise<ApprovalRequest> {
        return apiClient.post('/admin/datasources/export-requests', body)
    },
    adminDownloadDataSourceExport(approvalId: string, body: DataSourceExportDownloadRequest): Promise<Blob> {
        return apiClient.post(
            `/admin/datasources/exports/${encodeURIComponent(approvalId)}/download`,
            body,
            { responseType: 'blob' },
        )
    },
    adminDownloadDataSourceImportTemplate(): Promise<Blob> {
        return apiClient.get(
            '/admin/datasources/imports/template',
            { responseType: 'blob' },
        )
    },
    adminPreviewDataSourceImport(file: File, password?: string, signal?: AbortSignal): Promise<ConnectionImportPreview> {
        const body = new FormData()
        body.append('file', file)
        if (password) body.append('password', password)
        return apiClient.post('/admin/datasources/imports/preview', body, {
            headers: { 'Content-Type': 'multipart/form-data' },
            signal,
        })
    },
    adminApplyDataSourceImport(previewToken: string, decisions: ConnectionImportDecision[]): Promise<ConnectionImportResult> {
        return apiClient.post(
            `/admin/datasources/imports/${encodeURIComponent(previewToken)}/apply`,
            { decisions },
        )
    },

    // 管理员：授权
    adminGrants(workspaceId: string): Promise<AdminGrant[]> {
        return apiClient.get('/admin/grants', { params: { workspaceId } })
    },
    adminCreateGrant(body: any): Promise<{ id: string; success: boolean }> {
        return apiClient.post('/admin/grants', body)
    },
    adminGrantScope(dataSourceId: string): Promise<AdminGrantScopeOptions> {
        return apiClient.get(`/admin/grants/scope/${encodeURIComponent(dataSourceId)}`)
    },
    adminEligibleGrantUsers(): Promise<Array<{ id: string; username: string; displayName: string }>> {
        return apiClient.get('/admin/grants/eligible-users')
    },
    adminPreviewGrantBatch(body: BatchGrantRequest): Promise<BatchGrantPreview> {
        return apiClient.post('/admin/grants/batch/preview', body)
    },
    adminCreateGrantBatch(body: BatchGrantRequest): Promise<{ success: boolean; count: number; ids: string[] }> {
        return apiClient.post('/admin/grants/batch', body)
    },
    adminDeleteGrant(id: string): Promise<void> {
        return apiClient.delete(`/admin/grants/${id}`)
    },

    // 管理员：用户
    adminUsers(): Promise<AdminUser[]> {
        return apiClient.get('/admin/users')
    },
    adminCreateUser(body: any): Promise<{ id: string; success: boolean }> {
        return apiClient.post('/admin/users', body)
    },
    adminUpdateUserRoles(username: string, roles: string[]): Promise<{ success: boolean }> {
        return apiClient.put(`/admin/users/${encodeURIComponent(username)}/roles`, { roles })
    },
    adminFreezeUser(userId: string, frozen: boolean): Promise<{ success: boolean }> {
        return apiClient.post(`/admin/users/${encodeURIComponent(userId)}/${frozen ? 'freeze' : 'unfreeze'}`)
    },
    adminDeleteUser(userId: string): Promise<{ success: boolean }> {
        return apiClient.delete(`/admin/users/${encodeURIComponent(userId)}`)
    },
    adminUserScripts(userId: string): Promise<AdminUserScript[]> {
        return apiClient.get(`/admin/users/${encodeURIComponent(userId)}/scripts`)
    },
    adminTransferUserScripts(userId: string, targetUserId: string): Promise<{ success: boolean; count: number }> {
        return apiClient.post(`/admin/users/${encodeURIComponent(userId)}/scripts/transfer`, { targetUserId })
    },
    adminResetUserPassword(username: string, newPassword: string): Promise<{ success: boolean }> {
        return apiClient.post(`/admin/users/${encodeURIComponent(username)}/password`, { newPassword })
    },

    // 管理员：脱敏规则
    adminMaskingRules(): Promise<MaskingRule[]> {
        return apiClient.get('/admin/masking')
    },
    adminGenerateMaskingRule(dataSourceId: string, instruction: string): Promise<AiMaskingRuleDraft> {
        return apiClient.post('/admin/masking/generate', { dataSourceId, instruction })
    },
    adminSaveMaskingRule(body: Partial<MaskingRule>): Promise<MaskingRule> {
        return apiClient.post('/admin/masking', body)
    },
    adminDeleteMaskingRule(id: string): Promise<void> {
        return apiClient.delete(`/admin/masking/${id}`)
    },
}
