<template>
  <div class="ask-page">
    <header class="ask-hero data-card">
      <div>
        <div class="section-kicker">TRUSTED AI DATA INTELLIGENCE</div>
        <h2>Ask Lyra</h2>
        <p>从已授权元数据与已审核知识出发，给出可追溯建议；任何数据读取都先展示计划，再由你确认。</p>
      </div>
      <div class="capability-row" aria-label="AI 能力状态">
        <span :class="['capability', { on: feature('KNOWLEDGE_CORE') }]">已审核知识</span>
        <span :class="['capability', { on: feature('GOVERNED_READ_AGENT') }]">受控只读 Agent</span>
        <span :class="['capability', { on: feature('MAXCOMPUTE_AGENT') }]">MaxCompute 专项</span>
        <span class="capability hard-gate">写入 Agent 已锁定</span>
      </div>
    </header>

    <section class="context-panel data-card" aria-labelledby="metadata-context-title">
      <div class="context-header">
        <div>
          <h3 id="metadata-context-title">本次事实范围</h3>
          <p>默认不读取样本数据。只有你主动采集并勾选后，下一条问题才会附加选定结构。</p>
        </div>
        <el-tag v-if="snapshot" type="success" size="small">
          已采集 · 约 {{ snapshot.approximateTokens }} Token
        </el-tag>
      </div>
      <div class="context-grid">
        <el-select v-model="source" placeholder="选择授权数据源" filterable aria-label="授权数据源" @change="resetSnapshot">
          <el-option v-for="grant in grants" :key="grant.id" :label="`${grant.grantedSourceName} · ${grant.dbType || 'SQL'}`" :value="grant.grantedSourceName" />
        </el-select>
        <el-input v-model="selection.database" clearable placeholder="数据库 / Project（可选）" aria-label="数据库或 Project" @input="resetSnapshot" />
        <el-select
          v-model="selection.schemas"
          multiple filterable allow-create default-first-option collapse-tags
          placeholder="Schema（可选）" aria-label="Schema 列表" @change="resetSnapshot"
        >
          <el-option v-for="name in schemaOptions" :key="name" :label="name" :value="name" />
        </el-select>
        <el-select
          v-model="selection.tables"
          multiple filterable allow-create default-first-option collapse-tags
          placeholder="完整限定表名（如 schema.table）" aria-label="表列表" @change="resetSnapshot"
        >
          <el-option v-for="name in tableOptions" :key="name" :label="name" :value="name" />
        </el-select>
      </div>
      <div class="context-actions">
        <el-button type="primary" :loading="capturing" :disabled="!source || !metadataScopeValid" @click="captureMetadata">采集元数据</el-button>
        <el-button v-if="capturing" @click="cancelCapture">取消采集</el-button>
        <el-button :disabled="!snapshot" @click="previewOpen = true">预览</el-button>
        <el-button
          v-if="feature('TEAM_KNOWLEDGE_LOOP')"
          :loading="ingestingKnowledge"
          :disabled="!snapshot"
          @click="ingestMetadataKnowledge"
        >
          沉淀结构草稿
        </el-button>
        <el-dropdown :disabled="!snapshot" @command="saveMetadata">
          <el-button :disabled="!snapshot">保存事实文档</el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="markdown">Markdown</el-dropdown-item>
              <el-dropdown-item command="json">JSON</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-checkbox v-model="attachOnce" :disabled="!snapshot">仅下一条附加</el-checkbox>
      </div>
      <el-alert v-if="captureError" :title="captureError" type="error" :closable="false" show-icon />
      <el-alert
        v-else-if="snapshot" type="info" :closable="false"
        :title="`范围：${snapshot.databaseCount} 库 / ${snapshot.schemaCount} Schema / ${snapshot.tableCount} 表 / ${snapshot.columnCount} 列。`"
      />
    </section>

    <div class="service-state">
      <el-tag v-if="providerReady === false" size="small" type="warning">当前工作空间未配置 AI Provider</el-tag>
      <el-tag v-if="capabilityError" size="small" type="danger">{{ capabilityError }}</el-tag>
    </div>

    <section class="chat-box data-card" aria-live="polite" :aria-busy="sending">
      <div v-if="!messages.length" class="chat-empty">
        <strong>从一个可核验的问题开始</strong>
        <span>例如：“按已审核口径统计最近 7 天订单量，并给出只读 SQL。”</span>
      </div>
      <article v-for="(message, index) in messages" :key="message.id" :class="['message', message.role]">
        <div class="message-rail">
          <span class="avatar">{{ message.role === 'user' ? '我' : 'L' }}</span>
          <span>{{ message.role === 'user' ? '你的问题' : 'Lyra' }}</span>
        </div>
        <div class="message-body">
          <div v-if="message.metadataAttached" class="metadata-badge">已附加一次性元数据快照</div>
          <p v-if="message.explanation" class="explanation">{{ message.explanation }}</p>
          <pre v-if="message.sql" class="sql-block">{{ message.sql }}</pre>
          <el-alert v-if="message.error" :title="message.error" type="error" :closable="false" show-icon />
          <el-alert v-if="message.note" :title="message.note" type="info" :closable="false" />

          <div v-if="message.role === 'assistant' && message.sql" class="message-actions">
            <el-button
              v-if="!message.plan && !message.needsApproval && feature('GOVERNED_READ_AGENT')"
              type="primary" plain :loading="message.planning"
              @click="preparePlan(index, message)"
            >
              生成受控读取计划
            </el-button>
            <el-button v-if="message.needsApproval" @click="goManualQuery(message)">转到人工查询与审批</el-button>
            <el-button v-if="feature('TEAM_KNOWLEDGE_LOOP')" @click="openKnowledgeDraft(index, message)">沉淀为知识草稿</el-button>
          </div>

          <section v-if="message.maxComputePreflight" class="preflight-card">
            <div class="card-heading">
              <strong>MaxCompute 专项预检</strong>
              <el-tag :type="message.maxComputePreflight.planEligible ? 'success' : 'danger'" size="small">
                {{ message.maxComputePreflight.planEligible ? '允许进入计划' : '未通过' }}
              </el-tag>
            </div>
            <div class="metric-line">
              <span>成本 {{ message.maxComputePreflight.estimatedCostMicros }} / {{ message.maxComputePreflight.costBudgetMicros }} 微单位</span>
              <span>{{ message.maxComputePreflight.costStatus }}</span>
            </div>
            <div class="metric-line">
              <span>证据模式 {{ evidenceModeLabel(message.maxComputePreflight.evidenceMode) }}</span>
              <span v-if="message.maxComputePreflight.liveEvidence">实时状态 {{ message.maxComputePreflight.liveEvidence.status }}</span>
            </div>
            <div v-if="message.maxComputePreflight.liveEvidence" class="live-evidence">
              <span>EXPLAIN 摘要 {{ shortHash(message.maxComputePreflight.liveEvidence.explainSha256) }}</span>
              <span>费用命令摘要 {{ shortHash(message.maxComputePreflight.liveEvidence.costCommandSha256) }}</span>
            </div>
            <div v-for="check in message.maxComputePreflight.partitionChecks" :key="check.table" class="partition-line">
              <span>{{ check.table }}</span>
              <span>{{ check.matchedColumns.join(', ') || '未命中' }} / {{ check.requiredColumns.join(', ') || '未声明' }}</span>
            </div>
            <ul v-if="message.maxComputePreflight.warnings.length" class="compact-list">
              <li v-for="warning in message.maxComputePreflight.warnings" :key="warning">{{ warning }}</li>
            </ul>
          </section>

          <section v-if="message.plan" class="plan-card">
            <div class="card-heading">
              <div>
                <strong>待确认只读计划</strong>
                <span class="hash">{{ shortHash(message.plan.planSha256) }}</span>
              </div>
              <el-tag type="warning" size="small">{{ message.plan.riskLevel }}</el-tag>
            </div>
            <div class="plan-grid">
              <span>资源</span><strong>{{ message.plan.resources.join(', ') }}</strong>
              <span>最大行数</span><strong>{{ message.plan.maxRows }}</strong>
              <span>有效期</span><strong>{{ formatTime(message.plan.expiresAt) }}</strong>
            </div>
            <ol class="compact-list ordered">
              <li v-for="step in message.plan.steps" :key="step">{{ step }}</li>
            </ol>
            <div class="message-actions">
              <el-button type="primary" :loading="message.executing" @click="executePlan(message)">核对摘要并执行</el-button>
              <el-button :disabled="message.cancelling" @click="cancelPlan(message)">取消计划 / 运行</el-button>
            </div>
          </section>

          <el-collapse v-if="message.result" class="result-collapse">
            <el-collapse-item :title="`受控结果 ${message.result.totalRows} 行 · ${message.result.elapsedMs}ms`">
              <DataTable :columns="message.result.columns" :rows="message.result.rows" />
            </el-collapse-item>
          </el-collapse>

          <el-collapse v-if="message.receipt" class="receipt-collapse">
            <el-collapse-item :title="`证据与 Context Receipt · ${message.receipt.evidence.length} 项`">
              <div class="receipt-summary">
                <span>用途 {{ message.receipt.purpose }}</span>
                <span>模型 {{ message.receipt.model || '未记录' }}</span>
                <span>摘要 {{ shortHash(message.receipt.contextSha256) }}</span>
              </div>
              <div v-if="message.receipt.evidence.length" class="evidence-list">
                <div v-for="item in message.receipt.evidence" :key="`${item.type}-${item.id}`" class="evidence-item">
                  <div><strong>{{ item.title }}</strong><el-tag size="small">{{ item.trustLevel }}</el-tag></div>
                  <span>{{ item.type }} · {{ item.sourceRef }}</span>
                </div>
              </div>
              <div class="policy-block">
                <strong>已应用策略</strong>
                <span v-for="policy in message.receipt.appliedPolicies" :key="policy">{{ policy }}</span>
              </div>
              <div v-if="message.receipt.omittedContext.length" class="policy-block omitted">
                <strong>未使用上下文</strong>
                <span v-for="item in message.receipt.omittedContext" :key="item">{{ item }}</span>
              </div>
            </el-collapse-item>
          </el-collapse>

          <el-collapse v-if="message.toolTrace?.length" class="receipt-collapse">
            <el-collapse-item :title="`有界工具编排 · ${message.orchestrationSteps || 0} 步 · ${message.usage?.totalTokens || 0} Token`">
              <div class="receipt-summary">
                <span>状态 {{ message.orchestrationStatus }}</span>
                <span>Provider {{ message.provider || '—' }}</span>
                <span>模型 {{ message.model || '—' }}</span>
              </div>
              <div class="trace-list">
                <div v-for="trace in message.toolTrace" :key="`${trace.step}-${trace.callId || trace.toolName}`" class="trace-item">
                  <div>
                    <strong>#{{ trace.step }} {{ trace.toolName }}</strong>
                    <el-tag size="small" effect="plain">{{ trace.decision }}</el-tag>
                  </div>
                  <span>{{ trace.detail }}</span>
                </div>
              </div>
            </el-collapse-item>
          </el-collapse>
        </div>
      </article>
    </section>

    <footer class="composer data-card">
      <el-input
        v-model="input" type="textarea" :rows="2"
        placeholder="描述问题、期望口径和时间范围；Ctrl + Enter 发送"
        aria-label="发送给 Ask Lyra 的问题" @keydown.enter.ctrl="send"
      />
      <el-button type="primary" :loading="sending" :disabled="!source || !input.trim()" @click="send">发送</el-button>
    </footer>

    <el-dialog v-model="previewOpen" title="元数据事实预览" width="760" destroy-on-close>
      <div v-if="snapshot" class="snapshot-summary">
        <span>{{ snapshot.grantedSourceName }}</span>
        <span>{{ snapshot.tableCount }} 表 / {{ snapshot.columnCount }} 列</span>
        <span>约 {{ snapshot.approximateTokens }} Token</span>
      </div>
      <pre class="metadata-preview">{{ metadataPreviewText || '暂无预览' }}</pre>
      <template #footer>
        <el-button @click="previewOpen = false">关闭</el-button>
        <el-button type="primary" :disabled="!snapshot" @click="attachFromPreview">下一条附加</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="knowledgeDialogOpen" title="沉淀为待审核知识" width="640" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="知识类型">
          <el-select v-model="knowledgeDraft.type" style="width: 100%">
            <el-option label="业务术语" value="BUSINESS_TERM" />
            <el-option label="指标口径" value="METRIC" />
            <el-option label="表说明" value="TABLE_NOTE" />
            <el-option label="列说明" value="COLUMN_NOTE" />
            <el-option label="治理规则" value="POLICY_RULE" />
            <el-option label="已验证查询" value="VERIFIED_QUERY" />
          </el-select>
        </el-form-item>
        <el-form-item label="标题"><el-input v-model="knowledgeDraft.title" maxlength="200" show-word-limit /></el-form-item>
        <el-form-item label="定义与核验说明"><el-input v-model="knowledgeDraft.definition" type="textarea" :rows="5" /></el-form-item>
        <el-form-item label="关键词（逗号分隔）"><el-input v-model="knowledgeKeywords" /></el-form-item>
        <el-checkbox v-model="submitAfterCreate">创建后提交人工审核</el-checkbox>
      </el-form>
      <template #footer>
        <el-button @click="knowledgeDialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="savingKnowledge" @click="saveKnowledgeDraft">保存草稿</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="maxComputeDialogOpen" title="MaxCompute 分区与成本预检" width="680" destroy-on-close>
      <el-alert type="warning" :closable="false" title="这里只接收你已核验的分区元数据与成本估算；LyraDB 不会把声明值当成真实观测。" />
      <el-form label-position="top" class="dialog-form">
        <el-form-item label="必需分区列">
          <el-input
            v-model="partitionDeclaration" type="textarea" :rows="4"
            placeholder="每行一张表，例如：&#10;sales.orders: ds, hour"
          />
        </el-form-item>
        <div class="two-column">
          <el-form-item label="预估成本（微单位）"><el-input-number v-model="estimatedCostMicros" :min="0" :precision="0" /></el-form-item>
          <el-form-item label="预估扫描字节（可选）"><el-input-number v-model="estimatedInputBytes" :min="0" :precision="0" /></el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="maxComputeDialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="preflighting" @click="runMaxComputePreflight">预检并生成计划</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import DataTable from '@/components/editor/DataTable.vue'
import { entApi, type LogicalGrant, type MetadataSnapshotSummary } from '@/api/ent'
import type { QueryResult } from '@/types/metadata'
import type {
  AiAgentToolTraceView, AiAgentUsageView, AiCapabilities, AiContextReceipt,
  AiFeatureName, AiKnowledgeDraftRequest, AiReadAgentPlanView,
  KnowledgeAssetType, MaxComputePreflightView,
} from '@/types/ai'
import { saveBlob } from '@/utils/download'
import {
  formatMetadataPreview, hasMetadataScope, normalizeMetadataSelection, safeDownloadStem,
} from '@/utils/enterpriseTransfer'

type Role = 'user' | 'assistant'

interface Message {
  id: number
  role: Role
  question?: string
  sourceName?: string
  dbType?: string
  maxRows?: number
  defaultDatabase?: string
  explanation?: string
  sql?: string
  error?: string
  note?: string
  needsApproval?: boolean
  metadataAttached?: boolean
  receipt?: AiContextReceipt
  plan?: AiReadAgentPlanView
  result?: QueryResult
  maxComputePreflight?: MaxComputePreflightView
  toolTrace?: AiAgentToolTraceView[]
  usage?: AiAgentUsageView
  orchestrationStatus?: string
  orchestrationSteps?: number
  provider?: string
  model?: string
  planning?: boolean
  executing?: boolean
  cancelling?: boolean
}

const router = useRouter()
const grants = ref<LogicalGrant[]>([])
const source = ref('')
const input = ref('')
const sending = ref(false)
const providerReady = ref<boolean | null>(null)
const capabilityError = ref('')
const capabilities = ref<AiCapabilities | null>(null)
const selection = reactive({ database: '', schemas: [] as string[], tables: [] as string[] })
const snapshot = ref<MetadataSnapshotSummary | null>(null)
const attachOnce = ref(false)
const previewOpen = ref(false)
const capturing = ref(false)
const captureError = ref('')
const messages = ref<Message[]>([])
const ingestingKnowledge = ref(false)
let sequence = 0
let captureController: AbortController | null = null

const knowledgeDialogOpen = ref(false)
const knowledgeTarget = ref<number | null>(null)
const knowledgeKeywords = ref('')
const submitAfterCreate = ref(false)
const savingKnowledge = ref(false)
const knowledgeDraft = reactive<AiKnowledgeDraftRequest>({
  type: 'TABLE_NOTE', title: '', definition: '',
})

const maxComputeDialogOpen = ref(false)
const maxComputeTarget = ref<number | null>(null)
const partitionDeclaration = ref('')
const estimatedCostMicros = ref(0)
const estimatedInputBytes = ref<number | undefined>()
const preflighting = ref(false)

const metadataPreviewText = computed(() => formatMetadataPreview(snapshot.value?.preview || []))
const metadataScopeValid = computed(() => hasMetadataScope({
  grantedSourceName: source.value,
  database: selection.database,
  schemas: selection.schemas,
  tables: selection.tables,
}))
const selectedGrant = computed(() => grants.value.find(grant => grant.grantedSourceName === source.value))
const tableOptions = computed(() => grantTokens(selectedGrant.value?.allowedTables).filter(value => !value.includes('*')))
const schemaOptions = computed(() => {
  const direct = grantTokens(selectedGrant.value?.allowedSchemas)
  const fromTables = tableOptions.value.map(value => {
    const parts = value.split('.').filter(Boolean)
    return parts.length >= 2 ? parts[parts.length - 2] : ''
  })
  return Array.from(new Set([...direct, ...fromTables].filter(Boolean))).sort()
})

function feature(name: AiFeatureName) {
  return capabilities.value?.features?.[name] === true
}

function grantTokens(value?: string): string[] {
  if (!value) return []
  return Array.from(new Set(value.split(',').map(item => item.trim()).filter(Boolean))).sort()
}

async function load() {
  const [grantResult, providerResult, capabilityResult] = await Promise.allSettled([
    entApi.grantsMine(), entApi.aiProviders(), entApi.aiCapabilities(),
  ])
  if (grantResult.status === 'fulfilled') {
    grants.value = grantResult.value
    if (grants.value.length) source.value = grants.value[0].grantedSourceName
  } else {
    ElMessage.error(grantResult.reason?.message || '加载授权数据源失败')
  }
  providerReady.value = providerResult.status === 'fulfilled' && providerResult.value.length > 0
  if (capabilityResult.status === 'fulfilled') {
    capabilities.value = capabilityResult.value
  } else {
    capabilityError.value = capabilityResult.reason?.message || '无法读取 AI 能力状态'
  }
}
onMounted(load)

function resetSnapshot() {
  captureController?.abort()
  snapshot.value = null
  attachOnce.value = false
  captureError.value = ''
}

async function captureMetadata() {
  if (!source.value || !metadataScopeValid.value) {
    ElMessage.warning('请至少指定数据库、Schema 或完整限定表名')
    return
  }
  captureController?.abort()
  const controller = new AbortController()
  captureController = controller
  capturing.value = true
  captureError.value = ''
  try {
    snapshot.value = await entApi.createMetadataSnapshot(normalizeMetadataSelection({
      grantedSourceName: source.value,
      database: selection.database,
      schemas: selection.schemas,
      tables: selection.tables,
    }), controller.signal)
    previewOpen.value = true
  } catch (error: any) {
    captureError.value = controller.signal.aborted ? '已取消元数据采集' : (error.message || '元数据采集失败')
  } finally {
    if (captureController === controller) {
      capturing.value = false
      captureController = null
    }
  }
}

function cancelCapture() {
  captureController?.abort()
}

function attachFromPreview() {
  attachOnce.value = true
  previewOpen.value = false
  ElMessage.success('下一条问题将附加此结构快照一次')
}

async function saveMetadata(format: 'json' | 'markdown') {
  if (!snapshot.value) return
  try {
    const blob = await entApi.downloadMetadataSnapshot(snapshot.value.id, format)
    const extension = format === 'markdown' ? 'md' : 'json'
    await saveBlob(blob, `${safeDownloadStem(snapshot.value.grantedSourceName)}-metadata.${extension}`)
    ElMessage.success('事实文档已保存')
  } catch (error: any) {
    ElMessage.error(error.message || '保存事实文档失败')
  }
}

async function ingestMetadataKnowledge() {
  if (!snapshot.value || ingestingKnowledge.value) return
  try {
    await ElMessageBox.confirm(
      '该一次性快照将被消费并转换为待审核草稿，之后不能再附加给 AI。确认继续？',
      '沉淀元数据知识',
      { confirmButtonText: '创建草稿', cancelButtonText: '返回', type: 'warning' },
    )
  } catch {
    return
  }
  ingestingKnowledge.value = true
  try {
    const result = await entApi.aiKnowledgeIngestMetadata(snapshot.value.id)
    snapshot.value = null
    attachOnce.value = false
    ElMessage.success(`已创建 ${result.createdDrafts} 条待审核草稿，省略 ${result.omittedTables} 张超限表`)
  } catch (error: any) {
    ElMessage.error(error.message || '元数据知识入库失败')
  } finally {
    ingestingKnowledge.value = false
  }
}

async function send() {
  if (!source.value || !input.value.trim() || sending.value) return
  const text = input.value.trim()
  input.value = ''
  const useMetadata = Boolean(attachOnce.value && snapshot.value)
  const snapshotId = useMetadata ? snapshot.value!.id : undefined
  attachOnce.value = false
  const sourceName = source.value
  const defaultDatabase = selection.database || undefined
  const grant = grants.value.find(item => item.grantedSourceName === sourceName)
  messages.value.push({ id: ++sequence, role: 'user', explanation: text, metadataAttached: useMetadata })
  sending.value = true
  try {
    const useOrchestrator = feature('GOVERNED_READ_AGENT')
      && feature('KNOWLEDGE_CORE')
      && grant?.dbType?.toUpperCase() !== 'MAXCOMPUTE'
    if (useOrchestrator) {
      const response = await entApi.aiAgentOrchestrate({
        grantedSourceName: sourceName,
        question: text,
        metadataSnapshotId: snapshotId,
        defaultDatabase,
        requestedRows: grant?.maxRowsPerQuery,
      })
      messages.value.push({
        id: ++sequence, role: 'assistant', question: text,
        sourceName, dbType: grant?.dbType, maxRows: grant?.maxRowsPerQuery,
        defaultDatabase, explanation: response.answer,
        sql: response.plan?.sql, plan: response.plan || undefined,
        receipt: response.contextReceipt, toolTrace: response.toolTrace,
        usage: response.usage, orchestrationStatus: response.status,
        orchestrationSteps: response.steps, provider: response.provider,
        model: response.model,
      })
    } else {
      const history = messages.value.slice(0, -1).map(message => ({
        role: message.role,
        content: message.explanation || message.sql || '',
      }))
      const response = await entApi.aiChat({
        grantedSourceName: sourceName,
        message: text,
        history,
        attachMetadata: useMetadata,
        metadataSnapshotId: snapshotId,
      })
      messages.value.push({
        id: ++sequence,
        role: 'assistant',
        question: text,
        sourceName,
        dbType: grant?.dbType,
        maxRows: grant?.maxRowsPerQuery,
        defaultDatabase,
        explanation: response.explanation,
        sql: response.sql,
        error: response.error,
        note: response.note,
        needsApproval: response.needsApproval,
        result: response.result,
        receipt: response.contextReceipt,
      })
    }
  } catch (error: any) {
    messages.value.push({ id: ++sequence, role: 'assistant', question: text, error: error.message || 'Ask Lyra 调用失败' })
  } finally {
    sending.value = false
  }
}

async function preparePlan(index: number, message: Message) {
  if (!message.sql || !message.sourceName) return
  if (message.dbType?.toUpperCase() === 'MAXCOMPUTE' && feature('MAXCOMPUTE_AGENT')
    && !message.maxComputePreflight?.preflightSha256) {
    maxComputeTarget.value = index
    partitionDeclaration.value = ''
    estimatedCostMicros.value = 0
    estimatedInputBytes.value = undefined
    maxComputeDialogOpen.value = true
    return
  }
  await createPlan(message)
}

async function createPlan(message: Message) {
  if (!message.sql || !message.sourceName || !message.question) return
  message.planning = true
  try {
    message.plan = await entApi.aiReadPlan({
      grantedSourceName: message.sourceName,
      question: message.question,
      sql: message.sql,
      defaultDatabase: message.defaultDatabase,
      requestedRows: message.maxRows,
      estimatedCostMicros: message.maxComputePreflight?.estimatedCostMicros || 0,
      maxComputePreflightSha256: message.maxComputePreflight?.preflightSha256 || undefined,
    })
    ElMessage.success('只读计划已生成，请核对资源、行数与摘要')
  } catch (error: any) {
    ElMessage.error(error.message || '生成只读计划失败')
  } finally {
    message.planning = false
  }
}

async function executePlan(message: Message) {
  if (!message.plan || message.executing) return
  try {
    await ElMessageBox.confirm(
      `将读取 ${message.plan.resources.join(', ')}，最多 ${message.plan.maxRows} 行。计划摘要 ${shortHash(message.plan.planSha256)}。`,
      '确认受控读取计划',
      { confirmButtonText: '确认执行', cancelButtonText: '返回核对', type: 'warning' },
    )
  } catch {
    return
  }
  message.executing = true
  try {
    const execution = await entApi.aiReadExecute(message.plan.runId, message.plan.planSha256)
    message.result = execution.result
    message.receipt = execution.contextReceipt
    ElMessage.success('受控读取已完成，并生成执行回执')
  } catch (error: any) {
    ElMessage.error(error.message || '受控读取失败')
  } finally {
    message.executing = false
  }
}

async function cancelPlan(message: Message) {
  if (!message.plan || message.cancelling) return
  message.cancelling = true
  try {
    const result = await entApi.aiReadCancel(message.plan.runId)
    ElMessage.info(`计划状态：${result.status}`)
    if (result.status === 'CANCELLED') message.plan = undefined
  } catch (error: any) {
    ElMessage.error(error.message || '取消失败')
  } finally {
    message.cancelling = false
  }
}

function openKnowledgeDraft(index: number, message: Message) {
  knowledgeTarget.value = index
  knowledgeDraft.type = (message.sql ? 'VERIFIED_QUERY' : 'TABLE_NOTE') as KnowledgeAssetType
  knowledgeDraft.title = (message.question || 'Ask Lyra 建议').slice(0, 200)
  knowledgeDraft.definition = (message.explanation || '').slice(0, 20_000)
  knowledgeKeywords.value = ''
  submitAfterCreate.value = false
  knowledgeDialogOpen.value = true
}

async function saveKnowledgeDraft() {
  const index = knowledgeTarget.value
  if (index === null) return
  const message = messages.value[index]
  if (!message || !knowledgeDraft.title.trim() || !knowledgeDraft.definition.trim()) {
    ElMessage.warning('请填写知识标题与定义')
    return
  }
  savingKnowledge.value = true
  try {
    const verified = knowledgeDraft.type === 'VERIFIED_QUERY'
    const created = await entApi.aiKnowledgeCreateDraft({
      type: knowledgeDraft.type,
      title: knowledgeDraft.title.trim(),
      definition: knowledgeDraft.definition.trim(),
      verifiedSql: verified ? message.sql : undefined,
      dbType: message.dbType,
      grantedSourceName: message.sourceName,
      defaultDatabase: message.defaultDatabase,
      keywords: knowledgeKeywords.value.split(',').map(item => item.trim()).filter(Boolean),
      sourceRef: message.receipt ? `ai-receipt:${message.receipt.requestId}` : 'ask-lyra',
    })
    if (submitAfterCreate.value) await entApi.aiKnowledgeSubmit(created.id)
    knowledgeDialogOpen.value = false
    ElMessage.success(submitAfterCreate.value ? '知识草稿已提交人工审核' : '知识草稿已保存')
  } catch (error: any) {
    ElMessage.error(error.message || '保存知识草稿失败')
  } finally {
    savingKnowledge.value = false
  }
}

async function runMaxComputePreflight() {
  const index = maxComputeTarget.value
  if (index === null) return
  const message = messages.value[index]
  if (!message?.sql || !message.sourceName) return
  let partitions: Record<string, string[]>
  try {
    partitions = parsePartitionDeclarations(partitionDeclaration.value)
  } catch (error: any) {
    ElMessage.warning(error.message)
    return
  }
  preflighting.value = true
  try {
    message.maxComputePreflight = await entApi.aiMaxComputePreflight({
      grantedSourceName: message.sourceName,
      sql: message.sql,
      defaultDatabase: message.defaultDatabase,
      requiredPartitionColumns: partitions,
      estimatedInputBytes: estimatedInputBytes.value,
      estimatedCostMicros: estimatedCostMicros.value,
    })
    maxComputeDialogOpen.value = false
    if (!message.maxComputePreflight.planEligible) {
      ElMessage.error('MaxCompute 专项预检未通过，未生成执行计划')
      return
    }
    await createPlan(message)
  } catch (error: any) {
    ElMessage.error(error.message || 'MaxCompute 专项预检失败')
  } finally {
    preflighting.value = false
  }
}

function parsePartitionDeclarations(value: string): Record<string, string[]> {
  const result: Record<string, string[]> = {}
  for (const rawLine of value.split(/\r?\n/)) {
    const line = rawLine.trim()
    if (!line) continue
    const separator = line.indexOf(':')
    if (separator <= 0) throw new Error(`分区声明格式错误：${line}`)
    const table = line.slice(0, separator).trim()
    const columns = line.slice(separator + 1).split(',').map(item => item.trim()).filter(Boolean)
    if (!table.includes('.') || !columns.length) throw new Error(`必须提供完整表名和至少一个分区列：${line}`)
    result[table] = Array.from(new Set(columns))
  }
  if (!Object.keys(result).length) throw new Error('请提供已核验的必需分区列')
  return result
}

function goManualQuery(message: Message) {
  router.push({ name: 'query', query: { source: message.sourceName }, state: { sql: message.sql || '' } })
}

function shortHash(value?: string | null) {
  if (!value) return '—'
  return `${value.slice(0, 10)}…${value.slice(-6)}`
}

function formatTime(value?: string) {
  if (!value) return '—'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function evidenceModeLabel(mode?: string) {
  return ({
    DECLARED_ONLY: '仅声明',
    LIVE_PARTIAL: '部分实时证据',
    LIVE_COMPLETE: '完整实时证据',
  } as Record<string, string>)[mode || ''] || mode || '未知'
}
</script>

<style scoped>
.ask-page { width: min(1180px, 100%); min-height: 100%; margin: 0 auto; display: flex; flex-direction: column; gap: 12px; }
.ask-hero { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; padding: 18px 20px; }
.ask-hero h2 { margin: 3px 0 4px; font-size: 25px; letter-spacing: -0.035em; }
.ask-hero p, .context-header p { margin: 0; color: var(--color-text-muted); font-size: 12px; }
.capability-row { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 7px; }
.capability { padding: 4px 8px; border: 1px solid var(--color-panel-border); border-radius: 999px; color: var(--color-text-muted); background: var(--color-panel-header); font-size: 10px; }
.capability.on { color: var(--color-success); border-color: color-mix(in srgb, var(--color-success) 42%, var(--color-panel-border)); }
.capability.hard-gate { color: var(--color-warning); }
.context-panel { padding: 14px; }
.context-header, .card-heading, .metric-line, .partition-line { display: flex; justify-content: space-between; gap: 12px; }
.context-header { align-items: flex-start; margin-bottom: 10px; }
.context-header h3 { margin: 0 0 3px; font-size: 14px; }
.context-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.context-actions, .message-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-top: 10px; }
.context-actions :deep(.el-button + .el-button), .message-actions :deep(.el-button + .el-button) { margin-left: 0; }
.service-state { display: flex; min-height: 22px; gap: 8px; }
.chat-box { flex: 1; min-height: 280px; padding: 14px; overflow: auto; }
.chat-empty { min-height: 180px; display: grid; place-content: center; gap: 6px; color: var(--color-text-muted); text-align: center; }
.chat-empty strong { color: var(--color-foreground); font-size: 15px; }
.message { display: grid; grid-template-columns: 92px minmax(0, 1fr); gap: 12px; padding: 14px 2px; border-bottom: 1px solid var(--color-panel-border); }
.message:last-child { border-bottom: 0; }
.message-rail { display: flex; align-items: center; gap: 7px; align-self: start; color: var(--color-text-muted); font-size: 11px; }
.avatar { width: 27px; height: 27px; display: inline-grid; place-items: center; border-radius: 8px; background: var(--color-muted); color: var(--color-brand); font-weight: 750; }
.message.assistant .message-body { padding: 12px; border: 1px solid var(--color-panel-border); border-radius: 12px; background: var(--color-panel-translucent); }
.explanation { margin: 0 0 8px; white-space: pre-wrap; line-height: 1.65; }
.metadata-badge { margin-bottom: 6px; color: var(--color-success); font-size: 11px; }
.sql-block, .metadata-preview { margin: 8px 0 0; padding: 12px; overflow: auto; border: 1px solid var(--color-panel-border); border-radius: 8px; background: var(--color-panel-header); color: var(--color-foreground); font: 12px/1.6 var(--font-mono); white-space: pre-wrap; }
.metadata-preview { max-height: 55vh; }
.plan-card, .preflight-card { margin-top: 10px; padding: 12px; border: 1px solid var(--color-panel-border); border-radius: 10px; background: var(--color-panel-header); }
.card-heading { align-items: center; }
.card-heading > div { display: flex; align-items: center; gap: 8px; }
.hash { color: var(--color-text-muted); font: 10px var(--font-mono); }
.plan-grid { display: grid; grid-template-columns: max-content 1fr; gap: 5px 12px; margin-top: 10px; font-size: 11px; }
.plan-grid > span { color: var(--color-text-muted); }
.compact-list { margin: 8px 0 0 18px; color: var(--color-text-muted); font-size: 11px; }
.ordered { margin-left: 20px; }
.metric-line, .partition-line { margin-top: 7px; font-size: 11px; }
.partition-line span:first-child { font-family: var(--font-mono); }
.partition-line span:last-child { color: var(--color-text-muted); }
.live-evidence { display: flex; flex-wrap: wrap; gap: 6px 16px; margin-top: 8px; color: var(--color-text-muted); font: 10px var(--font-mono); }
.result-collapse, .receipt-collapse { margin-top: 10px; }
.receipt-summary { display: flex; flex-wrap: wrap; gap: 8px 16px; color: var(--color-text-muted); font-size: 11px; }
.evidence-list { display: grid; gap: 7px; margin-top: 10px; }
.evidence-item { padding: 8px; border: 1px solid var(--color-panel-border); border-radius: 8px; }
.evidence-item > div { display: flex; justify-content: space-between; gap: 8px; }
.evidence-item > span { display: block; margin-top: 3px; color: var(--color-text-muted); font-size: 10px; }
.trace-list { display: flex; flex-direction: column; gap: 8px; margin-top: 10px; }
.trace-item { padding: 10px; border: 1px solid var(--color-panel-border); border-radius: 9px; background: var(--color-panel-header); }
.trace-item > div { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.trace-item > span { display: block; margin-top: 5px; color: var(--color-text-muted); font-size: 11px; line-height: 1.5; }
.policy-block { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.policy-block strong { width: 100%; font-size: 11px; }
.policy-block span { padding: 2px 6px; border-radius: 6px; background: var(--color-muted); color: var(--color-text-muted); font-size: 10px; }
.policy-block.omitted span { color: var(--color-warning); }
.composer { position: sticky; bottom: 0; display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: end; gap: 10px; padding: 12px; }
.snapshot-summary { display: flex; flex-wrap: wrap; gap: 14px; color: var(--color-text-muted); font-size: 12px; }
.dialog-form { margin-top: 14px; }
.two-column { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
@media (max-width: 780px) {
  .ask-hero { align-items: flex-start; flex-direction: column; }
  .capability-row { justify-content: flex-start; }
  .context-grid, .two-column { grid-template-columns: 1fr; }
  .message { grid-template-columns: 1fr; }
  .message-rail { margin-bottom: -4px; }
  .composer { grid-template-columns: 1fr; }
}
</style>
