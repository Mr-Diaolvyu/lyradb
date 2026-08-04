import type { QueryResult } from '@/types/metadata'

export type AiFeatureName =
  | 'ASK_LYRA'
  | 'KNOWLEDGE_CORE'
  | 'GOVERNED_READ_AGENT'
  | 'TEAM_KNOWLEDGE_LOOP'
  | 'AI_QUALITY'
  | 'MAXCOMPUTE_AGENT'
  | 'AGENT_GATEWAY'
  | 'WRITE_AGENT'

export interface AiCapabilities {
  features: Record<AiFeatureName, boolean>
  writeAgentHardGate: boolean
  securityModel: string
}

export interface EvidenceRef {
  id: string
  type: string
  title: string
  sourceRef: string
  contentSha256: string
  observedAt: string
  trustLevel: string
}

export interface AiContextReceipt {
  requestId: string
  workspaceId: string
  purpose: string
  provider?: string | null
  model?: string | null
  createdAt: string
  evidence: EvidenceRef[]
  appliedPolicies: string[]
  omittedContext: string[]
  contextSha256: string
}

export interface AiChatResponse {
  explanation?: string
  sql?: string
  error?: string
  note?: string
  executed?: boolean
  needsApproval?: boolean
  result?: QueryResult
  evidence?: EvidenceRef[]
  contextReceipt?: AiContextReceipt
}

export interface AiReadAgentPlanRequest {
  grantedSourceName: string
  question: string
  sql: string
  defaultDatabase?: string
  requestedRows?: number
  estimatedCostMicros?: number
  maxComputePreflightSha256?: string
}

export interface AiReadAgentPlanView {
  runId: string
  planSha256: string
  grantedSourceName: string
  sql: string
  defaultDatabase?: string | null
  resources: string[]
  maxRows: number
  estimatedCostMicros: number
  riskLevel: string
  expiresAt: string
  steps: string[]
  confirmationRequired: boolean
}

export interface AiReadAgentExecutionView {
  runId: string
  status: string
  result: QueryResult
  contextReceipt: AiContextReceipt
}

export interface AiReadAgentCancelView {
  runId: string
  status: string
  databaseCancellationDispatched: boolean
}

export interface AiAgentToolTraceView {
  step: number
  callId?: string | null
  toolName: string
  decision: string
  detail: string
}

export interface AiAgentUsageView {
  promptTokens: number
  completionTokens: number
  totalTokens: number
}

export interface AiAgentOrchestrationRequest {
  grantedSourceName: string
  question: string
  metadataSnapshotId?: string
  defaultDatabase?: string
  requestedRows?: number
  estimatedCostMicros?: number
  maxComputePreflightSha256?: string
}

export interface AiAgentOrchestrationView {
  status: 'ANSWER_ONLY' | 'WAITING_FOR_CONFIRMATION' | string
  answer: string
  plan?: AiReadAgentPlanView | null
  evidence: EvidenceRef[]
  contextReceipt: AiContextReceipt
  toolTrace: AiAgentToolTraceView[]
  steps: number
  provider: string
  model: string
  usage: AiAgentUsageView
}

export type KnowledgeAssetType =
  | 'BUSINESS_TERM'
  | 'METRIC'
  | 'TABLE_NOTE'
  | 'COLUMN_NOTE'
  | 'POLICY_RULE'
  | 'VERIFIED_QUERY'

export type KnowledgeAssetStatus =
  | 'DRAFT'
  | 'IN_REVIEW'
  | 'VERIFIED'
  | 'REJECTED'
  | 'RETIRED'

export interface AiKnowledgeDraftRequest {
  type: KnowledgeAssetType
  title: string
  definition: string
  verifiedSql?: string
  dbType?: string
  grantedSourceName?: string
  defaultDatabase?: string
  keywords?: string[]
  sourceRef?: string
}

export interface AiKnowledgeAssetView extends AiKnowledgeDraftRequest {
  id: string
  status: KnowledgeAssetStatus
  contentSha256: string
  version: number
  createdBy: string
  reviewedBy?: string | null
  reviewComment?: string | null
  createdAt: string
  updatedAt: string
  reviewedAt?: string | null
}

export interface AiKnowledgeIngestionView {
  snapshotId: string
  grantedSourceName: string
  createdDrafts: number
  omittedTables: number
  drafts: Array<{
    id: string
    title: string
    sourceRef: string
    status: KnowledgeAssetStatus
  }>
  reviewRequired: boolean
}

export interface AiEvaluationCase {
  id: string
  category: string
  question: string
  expectedSqlType?: string | null
  requiredEvidence: string[]
  forbiddenPatterns: string[]
  maxRisk: string
}

export interface AiEvaluationResult {
  caseId: string
  passed: boolean
  score: number
  failures: string[]
}

export interface AiQualityRunView {
  id: string
  goldenSetVersion: string
  evaluationMode: 'MANUAL' | 'AUTO' | string
  provider?: string | null
  model?: string | null
  durationMs: number
  totalTokens: number
  caseCount: number
  passedCount: number
  passRate: number
  averageScore: number
  releaseGatePassed: boolean
  results: AiEvaluationResult[]
  createdBy: string
  createdAt: string
}

export interface AiQualityDashboardView {
  goldenSet: {
    version: string
    description: string
    cases: AiEvaluationCase[]
  }
  latestRun?: AiQualityRunView | null
}

export interface MaxComputePreflightRequest {
  grantedSourceName: string
  sql: string
  defaultDatabase?: string
  requiredPartitionColumns: Record<string, string[]>
  estimatedInputBytes?: number
  estimatedCostMicros: number
}

export interface MaxComputePreflightView {
  planEligible: boolean
  decision: string
  preflightSha256?: string | null
  expiresAt?: string | null
  resources: string[]
  evidenceMode: 'DECLARED_ONLY' | 'LIVE_PARTIAL' | 'LIVE_COMPLETE' | string
  liveEvidence?: {
    status: string
    partitionColumns: Record<string, string[]>
    estimatedInputBytes?: number | null
    estimatedCostMicros?: number | null
    explainSha256?: string | null
    costCommandSha256?: string | null
    warnings: string[]
  } | null
  partitionChecks: Array<{
    table: string
    requiredColumns: string[]
    matchedColumns: string[]
    covered: boolean
  }>
  estimatedInputBytes?: number | null
  estimatedCostMicros: number
  costBudgetMicros: number
  costStatus: string
  warnings: string[]
  contextReceipt: AiContextReceipt
}

export interface MaxComputeDiagnosticView {
  normalizedStatus: string
  category: string
  summary: string
  recommendations: string[]
  automaticRetryAllowed: boolean
  contextReceipt: AiContextReceipt
}

export type AgentGatewayScope =
  | 'KNOWLEDGE_READ'
  | 'READ_PLAN'
  | 'READ_EXECUTE'
  | 'MAXCOMPUTE_ANALYZE'

export interface AiGatewayTokenView {
  id: string
  displayName: string
  tokenPrefix: string
  principalUserId: string
  grantId: string
  grantedSourceName: string
  scopes: AgentGatewayScope[]
  revoked: boolean
  expiresAt: string
  lastUsedAt?: string | null
  createdAt: string
}

export interface AiGatewayTokenIssuedView {
  token: AiGatewayTokenView
  plaintextToken: string
  warning: string
}

export interface AiOperationMetric {
  calls: number
  failures: number
  durationMs: number
  averageDurationMs: number
}

export interface AiOperationsView {
  processMetrics: {
    scope: 'PROCESS_LOCAL' | string
    startedAt: string
    operations: Record<string, AiOperationMetric>
  }
  durableReadAgentRuns: Record<string, number>
}

export type AiProviderDeploymentMode = 'PUBLIC' | 'PRIVATE'

export interface AiProviderView {
  id: string
  providerKey: string
  displayName: string
  baseUrl: string
  model: string
  apiKey?: string | null
  isDefault: boolean
  deploymentMode: AiProviderDeploymentMode
  temperature?: number
  maxTokens?: number
}
