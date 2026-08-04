<template>
  <div class="knowledge-page">
    <header class="knowledge-hero data-card">
      <div>
        <div class="section-kicker">DATA KNOWLEDGE CORE</div>
        <h2>智库运营</h2>
        <p>把个人经验沉淀为经过审核、可追溯、可被 Ask Lyra 安全引用的团队知识。</p>
      </div>
      <el-button
        v-if="feature('TEAM_KNOWLEDGE_LOOP')"
        type="primary"
        @click="openDraft"
      >
        新建知识草稿
      </el-button>
    </header>

    <section class="metric-grid" aria-label="智库运行概览">
      <article class="metric-card data-card">
        <span>已验证知识</span>
        <strong>{{ verifiedAssets.length }}</strong>
        <small>仅 VERIFIED 可进入 AI 上下文</small>
      </article>
      <article class="metric-card data-card">
        <span>我的贡献</span>
        <strong>{{ myAssets.length }}</strong>
        <small>{{ pendingMine }} 条正在等待审核</small>
      </article>
      <article class="metric-card data-card">
        <span>黄金集质量</span>
        <strong>{{ qualityRate }}</strong>
        <small>{{ qualityGateLabel }}</small>
      </article>
      <article class="metric-card data-card">
        <span>Gateway 身份</span>
        <strong>{{ activeTokens }}</strong>
        <small>短期、单空间、单授权身份</small>
      </article>
    </section>

    <el-alert
      v-if="capabilities && !feature('KNOWLEDGE_CORE')"
      class="feature-alert"
      title="Data Knowledge Core 当前未启用"
      description="后端能力已安装但默认失败关闭。由管理员显式启用 LYRADB_AI_KNOWLEDGE_ENABLED 后，已验证知识才会进入 Ask Lyra 上下文。"
      type="info"
      :closable="false"
      show-icon
    />

    <section class="workspace-card data-card">
      <el-tabs v-model="activeTab" class="knowledge-tabs" @tab-change="onTabChange">
        <el-tab-pane label="已验证知识" name="library">
          <div class="tab-toolbar">
            <el-input v-model="search" clearable placeholder="搜索标题、定义、关键词或数据源" />
            <el-button :loading="loading" @click="loadVerified">刷新</el-button>
          </div>
          <div v-if="filteredVerified.length" class="asset-grid">
            <article v-for="asset in filteredVerified" :key="asset.id" class="asset-card">
              <div class="asset-head">
                <div>
                  <el-tag size="small" type="success">{{ typeLabel(asset.type) }}</el-tag>
                  <h3>{{ asset.title }}</h3>
                </div>
                <span class="version">v{{ asset.version }}</span>
              </div>
              <p>{{ asset.definition }}</p>
              <pre v-if="asset.verifiedSql" class="sql-preview">{{ asset.verifiedSql }}</pre>
              <div class="asset-meta">
                <span v-if="asset.grantedSourceName">{{ asset.grantedSourceName }}</span>
                <span v-if="asset.dbType">{{ asset.dbType }}</span>
                <span>摘要 {{ shortDigest(asset.contentSha256) }}</span>
              </div>
              <div v-if="asset.keywords?.length" class="keyword-row">
                <el-tag v-for="keyword in asset.keywords" :key="keyword" size="small" effect="plain">{{ keyword }}</el-tag>
              </div>
              <div v-if="canModerate" class="asset-actions">
                <el-button size="small" type="danger" plain @click="reviewAsset(asset, 'RETIRE')">退役</el-button>
              </div>
            </article>
          </div>
          <el-empty v-else description="当前工作空间还没有可供 AI 引用的已验证知识" />
        </el-tab-pane>

        <el-tab-pane v-if="feature('TEAM_KNOWLEDGE_LOOP')" label="我的贡献" name="mine">
          <div class="tab-toolbar">
            <div class="workflow-hint">草稿 → 提交审核 → 数据管家核验 → 进入 Ask Lyra</div>
            <el-button :loading="loading" @click="loadMine">刷新</el-button>
          </div>
          <div v-if="myAssets.length" class="asset-list">
            <article v-for="asset in myAssets" :key="asset.id" class="review-row">
              <div class="review-copy">
                <div class="review-title">
                  <el-tag size="small" :type="statusTag(asset.status)">{{ statusLabel(asset.status) }}</el-tag>
                  <strong>{{ asset.title }}</strong>
                  <span>{{ typeLabel(asset.type) }}</span>
                </div>
                <p>{{ asset.definition }}</p>
                <small v-if="asset.reviewComment">审核意见：{{ asset.reviewComment }}</small>
              </div>
              <el-button
                v-if="asset.status === 'DRAFT'"
                type="primary"
                plain
                @click="submitAsset(asset)"
              >提交审核</el-button>
            </article>
          </div>
          <el-empty v-else description="你还没有贡献知识草稿" />
        </el-tab-pane>

        <el-tab-pane v-if="canModerate && feature('TEAM_KNOWLEDGE_LOOP')" label="审核队列" name="review">
          <div class="tab-toolbar">
            <div class="workflow-hint">只有人工核验后的资产才会被 AI 检索。</div>
            <el-button :loading="loading" @click="loadReview">刷新</el-button>
          </div>
          <div v-if="reviewAssets.length" class="asset-list">
            <article v-for="asset in reviewAssets" :key="asset.id" class="review-row">
              <div class="review-copy">
                <div class="review-title">
                  <el-tag size="small" :type="statusTag(asset.status)">{{ statusLabel(asset.status) }}</el-tag>
                  <strong>{{ asset.title }}</strong>
                  <span>{{ typeLabel(asset.type) }} · v{{ asset.version }}</span>
                </div>
                <p>{{ asset.definition }}</p>
                <pre v-if="asset.verifiedSql" class="sql-preview">{{ asset.verifiedSql }}</pre>
                <small>创建者 {{ asset.createdBy }} · 更新于 {{ formatTime(asset.updatedAt) }}</small>
              </div>
              <div class="review-actions">
                <el-button v-if="asset.status === 'DRAFT'" size="small" @click="submitAsset(asset)">代提交</el-button>
                <template v-if="asset.status === 'IN_REVIEW'">
                  <el-button size="small" type="success" @click="reviewAsset(asset, 'VERIFY')">核验通过</el-button>
                  <el-button size="small" type="danger" plain @click="reviewAsset(asset, 'REJECT')">驳回</el-button>
                </template>
                <el-button v-if="asset.status === 'VERIFIED'" size="small" type="danger" plain @click="reviewAsset(asset, 'RETIRE')">退役</el-button>
              </div>
            </article>
          </div>
          <el-empty v-else description="当前没有知识资产" />
        </el-tab-pane>

        <el-tab-pane label="AI 质量" name="quality">
          <div v-if="feature('AI_QUALITY') && quality" class="quality-panel">
            <div class="tab-toolbar">
              <div class="workflow-hint">自动回归会调用当前默认 Provider 跑完整黄金集，结果不可手工挑选。</div>
              <el-button v-if="canModerate" type="primary" :loading="qualityRunning" @click="runAutomaticQuality">
                运行自动回归
              </el-button>
            </div>
            <div class="quality-summary">
              <div>
                <span>黄金集</span>
                <strong>{{ quality.goldenSet.version }}</strong>
                <small>{{ quality.goldenSet.description }}</small>
              </div>
              <div>
                <span>用例</span>
                <strong>{{ quality.goldenSet.cases.length }}</strong>
                <small>必须完整提交，不接受挑选用例</small>
              </div>
              <div>
                <span>最近通过率</span>
                <strong>{{ quality.latestRun ? `${Math.round(quality.latestRun.passRate * 100)}%` : '待回归' }}</strong>
                <small>{{ qualityGateLabel }}</small>
              </div>
            </div>
            <div v-if="quality.latestRun" class="quality-run-meta">
              <el-tag size="small" :type="quality.latestRun.evaluationMode === 'AUTO' ? 'success' : 'info'">
                {{ quality.latestRun.evaluationMode === 'AUTO' ? '自动回归' : '人工观测' }}
              </el-tag>
              <span>{{ quality.latestRun.provider || '无 Provider' }} / {{ quality.latestRun.model || '无模型记录' }}</span>
              <span>耗时 {{ quality.latestRun.durationMs }}ms</span>
              <span>{{ quality.latestRun.totalTokens }} Token</span>
            </div>
            <el-table :data="quality.goldenSet.cases" stripe>
              <el-table-column prop="id" label="用例" min-width="150" />
              <el-table-column prop="category" label="类别" width="120" />
              <el-table-column prop="question" label="问题" min-width="260" />
              <el-table-column prop="maxRisk" label="风险上限" width="110" />
              <el-table-column label="最近结果" width="110">
                <template #default="scope">
                  <el-tag v-if="resultFor(scope.row.id)?.passed" type="success">通过</el-tag>
                  <el-tag v-else-if="resultFor(scope.row.id)" type="danger">未通过</el-tag>
                  <el-tag v-else type="info">未运行</el-tag>
                </template>
              </el-table-column>
            </el-table>
          </div>
          <el-alert
            v-else
            title="可信 AI 黄金集尚未启用"
            description="启用后，完整回归结果会按工作空间持久化；质量门禁失败不会被界面绕过。"
            type="info"
            :closable="false"
            show-icon
          />
        </el-tab-pane>

        <el-tab-pane v-if="canManageGateway" label="Agent Gateway" name="gateway">
          <div v-if="feature('AGENT_GATEWAY')" class="gateway-panel">
            <div class="tab-toolbar">
              <div class="workflow-hint">面向外部 Agent 的窄权限接口；不提供任意 Shell、任意 SQL 或写入工具。</div>
              <el-button type="primary" @click="openGatewayIssue">签发身份</el-button>
            </div>
            <el-table :data="gatewayTokens" stripe>
              <el-table-column prop="displayName" label="身份" min-width="150" />
              <el-table-column prop="tokenPrefix" label="令牌前缀" min-width="150" />
              <el-table-column prop="grantedSourceName" label="逻辑数据源" min-width="150" />
              <el-table-column label="权限" min-width="250">
                <template #default="scope">
                  <el-tag v-for="item in scope.row.scopes" :key="item" size="small" effect="plain">{{ item }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="100">
                <template #default="scope">
                  <el-tag :type="scope.row.revoked ? 'danger' : 'success'">{{ scope.row.revoked ? '已撤销' : '有效' }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="到期时间" min-width="170">
                <template #default="scope">{{ formatTime(scope.row.expiresAt) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="100" fixed="right">
                <template #default="scope">
                  <el-button v-if="!scope.row.revoked" text type="danger" @click="revokeToken(scope.row)">撤销</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
          <el-alert
            v-else
            title="Agent Gateway 默认关闭"
            description="先完成令牌轮换、撤销演练与外部客户端试点，再显式启用 LYRADB_AI_GATEWAY_ENABLED。"
            type="warning"
            :closable="false"
            show-icon
          />
        </el-tab-pane>

        <el-tab-pane v-if="canViewOperations" label="运行指标" name="operations">
          <div v-if="operations" class="operations-panel">
            <div class="tab-toolbar">
              <div class="workflow-hint">
                调用指标为当前进程范围；只读 Agent 状态来自当前工作空间的持久化记录。
              </div>
              <el-button :loading="operationsLoading" @click="loadOperations">刷新</el-button>
            </div>
            <div class="operations-meta">
              <el-tag size="small" type="info">{{ operations.processMetrics.scope }}</el-tag>
              <span>进程启动于 {{ formatTime(operations.processMetrics.startedAt) }}</span>
            </div>
            <el-table :data="operationRows" stripe>
              <el-table-column prop="name" label="操作" min-width="190" />
              <el-table-column prop="calls" label="调用" width="90" />
              <el-table-column prop="failures" label="失败" width="90" />
              <el-table-column prop="durationMs" label="总耗时(ms)" width="120" />
              <el-table-column label="平均耗时(ms)" width="140">
                <template #default="scope">{{ scope.row.averageDurationMs.toFixed(1) }}</template>
              </el-table-column>
            </el-table>
            <div class="durable-runs">
              <strong>持久化只读 Agent 运行</strong>
              <div>
                <el-tag v-for="item in durableRunRows" :key="item.status" size="small" effect="plain">
                  {{ item.status }} {{ item.count }}
                </el-tag>
              </div>
            </div>
          </div>
          <el-empty v-else description="尚未加载 AI 运行指标" />
        </el-tab-pane>

        <el-tab-pane label="运行诊断" name="diagnose">
          <div v-if="feature('MAXCOMPUTE_AGENT')" class="diagnose-grid">
            <el-form label-position="top" @submit.prevent>
              <el-form-item label="任务状态">
                <el-input v-model="diagnosticForm.taskStatus" placeholder="例如 FAILED / RUNNING" />
              </el-form-item>
              <el-form-item label="错误码">
                <el-input v-model="diagnosticForm.errorCode" placeholder="可选" />
              </el-form-item>
              <el-form-item label="脱敏后的错误摘要">
                <el-input v-model="diagnosticForm.errorMessage" type="textarea" :rows="5" placeholder="不要粘贴密钥或原始样本数据" />
              </el-form-item>
              <el-button type="primary" :loading="diagnosing" @click="diagnose">生成确定性诊断</el-button>
            </el-form>
            <article v-if="diagnostic" class="diagnostic-result">
              <div class="section-kicker">{{ diagnostic.category }}</div>
              <h3>{{ diagnostic.summary }}</h3>
              <ul>
                <li v-for="item in diagnostic.recommendations" :key="item">{{ item }}</li>
              </ul>
              <el-tag :type="diagnostic.automaticRetryAllowed ? 'warning' : 'info'">
                {{ diagnostic.automaticRetryAllowed ? '允许自动重试' : '不自动重试' }}
              </el-tag>
            </article>
          </div>
          <el-alert
            v-else
            title="MaxCompute Intelligence Agent 尚未启用"
            description="专项能力只做分区、成本声明和失败诊断，不自动重试，不绕过通用只读 Agent 的二次确认。"
            type="info"
            :closable="false"
            show-icon
          />
        </el-tab-pane>
      </el-tabs>
    </section>

    <el-dialog v-model="draftDialog" title="新建知识草稿" width="min(680px, 92vw)" destroy-on-close>
      <el-form label-position="top" @submit.prevent>
        <div class="form-grid">
          <el-form-item label="知识类型">
            <el-select v-model="draft.type">
              <el-option v-for="item in knowledgeTypes" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="标题">
            <el-input v-model="draft.title" maxlength="200" show-word-limit />
          </el-form-item>
        </div>
        <el-form-item label="定义 / 业务口径">
          <el-input v-model="draft.definition" type="textarea" :rows="5" maxlength="20000" show-word-limit />
        </el-form-item>
        <el-form-item label="关键词（逗号分隔）">
          <el-input v-model="draft.keywordText" placeholder="订单, GMV, 去重口径" />
        </el-form-item>
        <template v-if="draft.type === 'VERIFIED_QUERY'">
          <div class="form-grid">
            <el-form-item label="逻辑数据源">
              <el-select v-model="draft.grantedSourceName" filterable>
                <el-option v-for="grant in mineGrants" :key="grant.id" :label="grant.grantedSourceName" :value="grant.grantedSourceName" />
              </el-select>
            </el-form-item>
            <el-form-item label="数据库 / Project">
              <el-input v-model="draft.defaultDatabase" />
            </el-form-item>
          </div>
          <el-form-item label="已验证只读 SQL">
            <el-input v-model="draft.verifiedSql" type="textarea" :rows="6" placeholder="仅允许 SELECT / WITH / SHOW / DESCRIBE / EXPLAIN" />
          </el-form-item>
        </template>
        <el-form-item label="证据来源引用">
          <el-input v-model="draft.sourceRef" placeholder="需求单、口径文档或会议纪要引用（不粘贴敏感正文）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="draftDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingDraft" @click="saveDraft">保存草稿</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="gatewayDialog" title="签发 Agent Gateway 身份" width="min(620px, 92vw)">
      <el-alert
        title="令牌正文只展示一次"
        description="令牌绑定单一工作空间、用户与 READ_ONLY Grant；用户停用、密码轮换、成员关系或 Grant 失效都会使令牌立即失效。"
        type="warning"
        :closable="false"
        show-icon
      />
      <el-form label-position="top" class="dialog-form" @submit.prevent>
        <el-form-item label="身份名称">
          <el-input v-model="gatewayForm.displayName" placeholder="例如：数据质量巡检 Agent" />
        </el-form-item>
        <el-form-item label="绑定 READ_ONLY Grant">
          <el-select v-model="gatewayForm.grantId" filterable>
            <el-option
              v-for="grant in readOnlyGatewayGrants"
              :key="grant.id"
              :label="gatewayGrantLabel(grant)"
              :value="grant.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="最小权限范围">
          <el-checkbox-group v-model="gatewayForm.scopes" @change="normalizeGatewayScopes">
            <el-checkbox value="KNOWLEDGE_READ">知识检索</el-checkbox>
            <el-checkbox value="READ_PLAN">生成只读计划</el-checkbox>
            <el-checkbox value="READ_EXECUTE">确认并执行计划</el-checkbox>
            <el-checkbox value="MAXCOMPUTE_ANALYZE">MaxCompute 分析</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="到期时间（最长 90 天）">
          <el-date-picker v-model="gatewayForm.expiresAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="gatewayDialog = false">取消</el-button>
        <el-button type="primary" :loading="issuingToken" @click="issueToken">签发</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="plaintextDialog" title="立即保存 Gateway 令牌" width="min(620px, 92vw)" :close-on-click-modal="false">
      <el-alert :title="issuedWarning" type="warning" :closable="false" show-icon />
      <div class="plaintext-token">{{ issuedPlaintext }}</div>
      <template #footer>
        <el-button @click="copyToken">复制令牌</el-button>
        <el-button type="primary" @click="closePlaintext">我已保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { entApi, type AdminGrant, type LogicalGrant } from '@/api/ent'
import { useAuthStore } from '@/stores/auth'
import type {
  AgentGatewayScope,
  AiCapabilities,
  AiGatewayTokenView,
  AiKnowledgeAssetView,
  AiKnowledgeDraftRequest,
  AiOperationsView,
  AiQualityDashboardView,
  KnowledgeAssetStatus,
  KnowledgeAssetType,
  MaxComputeDiagnosticView,
} from '@/types/ai'

const auth = useAuthStore()
const capabilities = ref<AiCapabilities | null>(null)
const verifiedAssets = ref<AiKnowledgeAssetView[]>([])
const myAssets = ref<AiKnowledgeAssetView[]>([])
const reviewAssets = ref<AiKnowledgeAssetView[]>([])
const quality = ref<AiQualityDashboardView | null>(null)
const qualityRunning = ref(false)
const operations = ref<AiOperationsView | null>(null)
const operationsLoading = ref(false)
const gatewayTokens = ref<AiGatewayTokenView[]>([])
const mineGrants = ref<LogicalGrant[]>([])
const gatewayGrants = ref<Array<LogicalGrant | AdminGrant>>([])
const activeTab = ref('library')
const search = ref('')
const loading = ref(false)

const draftDialog = ref(false)
const savingDraft = ref(false)
const draft = reactive<AiKnowledgeDraftRequest & { keywordText: string }>({
  type: 'BUSINESS_TERM', title: '', definition: '', keywordText: '',
  verifiedSql: '', grantedSourceName: '', defaultDatabase: '', sourceRef: '',
})

const gatewayDialog = ref(false)
const issuingToken = ref(false)
const plaintextDialog = ref(false)
const issuedPlaintext = ref('')
const issuedWarning = ref('')
const gatewayForm = reactive<{
  displayName: string
  grantId: string
  scopes: AgentGatewayScope[]
  expiresAt: string
}>({ displayName: '', grantId: '', scopes: ['KNOWLEDGE_READ'], expiresAt: futureLocal(7) })

const diagnosing = ref(false)
const diagnostic = ref<MaxComputeDiagnosticView | null>(null)
const diagnosticForm = reactive({ taskStatus: '', errorCode: '', errorMessage: '' })

const knowledgeTypes: Array<{ value: KnowledgeAssetType; label: string }> = [
  { value: 'BUSINESS_TERM', label: '业务术语' },
  { value: 'METRIC', label: '指标口径' },
  { value: 'TABLE_NOTE', label: '表说明' },
  { value: 'COLUMN_NOTE', label: '字段说明' },
  { value: 'POLICY_RULE', label: '治理规则' },
  { value: 'VERIFIED_QUERY', label: '已验证查询' },
]

const canModerate = computed(() => auth.hasRole('STEWARD') || auth.hasRole('DS_ADMIN'))
const canManageGateway = computed(() => canModerate.value)
const canViewOperations = computed(() => canModerate.value || auth.hasRole('AUDITOR'))
const pendingMine = computed(() => myAssets.value.filter(item => item.status === 'IN_REVIEW').length)
const activeTokens = computed(() => gatewayTokens.value.filter(item => !item.revoked && new Date(item.expiresAt).getTime() > Date.now()).length)
const qualityRate = computed(() => quality.value?.latestRun ? `${Math.round(quality.value.latestRun.passRate * 100)}%` : '待回归')
const qualityGateLabel = computed(() => {
  if (!quality.value?.latestRun) return '尚无完整回归结果'
  return quality.value.latestRun.releaseGatePassed ? '达到当前质量门禁' : '未达到发布门禁'
})
const filteredVerified = computed(() => {
  const key = search.value.trim().toLowerCase()
  if (!key) return verifiedAssets.value
  return verifiedAssets.value.filter(asset => [
    asset.title, asset.definition, asset.grantedSourceName, asset.dbType,
    ...(asset.keywords || []),
  ].some(value => value?.toLowerCase().includes(key)))
})
const readOnlyGatewayGrants = computed(() => gatewayGrants.value.filter(grant => grant.sqlCapability === 'READ_ONLY'))
const operationRows = computed(() => Object.entries(operations.value?.processMetrics.operations || {})
  .map(([name, metric]) => ({ name, ...metric })))
const durableRunRows = computed(() => Object.entries(operations.value?.durableReadAgentRuns || {})
  .map(([status, count]) => ({ status, count })))

function feature(name: keyof AiCapabilities['features']) {
  return capabilities.value?.features?.[name] === true
}

onMounted(loadAll)

async function loadAll() {
  loading.value = true
  try {
    capabilities.value = await entApi.aiCapabilities()
    mineGrants.value = await entApi.grantsMine()
    gatewayGrants.value = mineGrants.value
    if (feature('KNOWLEDGE_CORE')) await loadVerified(false)
    if (feature('TEAM_KNOWLEDGE_LOOP')) await loadMine(false)
    if (feature('AI_QUALITY')) await loadQuality()
    if (feature('TEAM_KNOWLEDGE_LOOP') && canModerate.value) await loadReview(false)
    if (feature('AGENT_GATEWAY') && canManageGateway.value) {
      await loadGatewayContext()
    }
  } catch (error: any) {
    ElMessage.error(error.message || '无法加载智库运行状态')
  } finally {
    loading.value = false
  }
}

async function onTabChange(name: string | number) {
  if (name === 'review' && !reviewAssets.value.length) await loadReview()
  if (name === 'quality' && feature('AI_QUALITY')) await loadQuality()
  if (name === 'gateway' && feature('AGENT_GATEWAY')) await loadGatewayContext()
  if (name === 'operations' && canViewOperations.value) await loadOperations()
}

async function loadVerified(withSpinner = true) {
  if (!feature('KNOWLEDGE_CORE')) return
  if (withSpinner) loading.value = true
  try { verifiedAssets.value = await entApi.aiKnowledgeVerified() }
  finally { if (withSpinner) loading.value = false }
}

async function loadMine(withSpinner = true) {
  if (!feature('TEAM_KNOWLEDGE_LOOP')) return
  if (withSpinner) loading.value = true
  try { myAssets.value = await entApi.aiKnowledgeMine() }
  finally { if (withSpinner) loading.value = false }
}

async function loadReview(withSpinner = true) {
  if (!canModerate.value || !feature('TEAM_KNOWLEDGE_LOOP')) return
  if (withSpinner) loading.value = true
  try { reviewAssets.value = await entApi.aiKnowledgeReview() }
  finally { if (withSpinner) loading.value = false }
}

async function loadQuality() {
  if (feature('AI_QUALITY')) quality.value = await entApi.aiQualityDashboard()
}

async function runAutomaticQuality() {
  try {
    await ElMessageBox.confirm(
      '将调用当前工作空间默认 Provider 运行完整黄金集，可能产生模型费用。确认继续？',
      '运行自动 AI 回归',
      { confirmButtonText: '确认调用并计费', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  qualityRunning.value = true
  try {
    const run = await entApi.aiQualityEvaluateAutomatically()
    await loadQuality()
    ElMessage.success(run.releaseGatePassed
      ? `自动回归通过：${Math.round(run.passRate * 100)}%`
      : `自动回归完成但未通过门禁：${Math.round(run.passRate * 100)}%`)
  } catch (error: any) {
    ElMessage.error(error.message || '自动 AI 回归失败')
  } finally {
    qualityRunning.value = false
  }
}

async function loadOperations() {
  if (!canViewOperations.value || operationsLoading.value) return
  operationsLoading.value = true
  try {
    operations.value = await entApi.aiOperationsMetrics()
  } catch (error: any) {
    ElMessage.error(error.message || 'AI 运行指标加载失败')
  } finally {
    operationsLoading.value = false
  }
}

async function loadGatewayContext() {
  if (!feature('AGENT_GATEWAY') || !canManageGateway.value) return
  gatewayTokens.value = await entApi.aiGatewayTokens()
  if (auth.hasRole('DS_ADMIN')) {
    try {
      gatewayGrants.value = await entApi.adminGrants(auth.user?.currentWorkspaceId || '')
    } catch {
      gatewayGrants.value = mineGrants.value
    }
  }
}

function openDraft() {
  Object.assign(draft, {
    type: 'BUSINESS_TERM', title: '', definition: '', keywordText: '',
    verifiedSql: '', grantedSourceName: '', defaultDatabase: '', sourceRef: '',
  })
  draftDialog.value = true
}

async function saveDraft() {
  if (!draft.title.trim() || !draft.definition.trim()) {
    ElMessage.warning('请填写标题与知识定义')
    return
  }
  if (draft.type === 'VERIFIED_QUERY' && (!draft.grantedSourceName || !draft.verifiedSql?.trim())) {
    ElMessage.warning('已验证查询必须绑定逻辑数据源并提供只读 SQL')
    return
  }
  savingDraft.value = true
  try {
    const created = await entApi.aiKnowledgeCreateDraft({
      type: draft.type,
      title: draft.title.trim(),
      definition: draft.definition.trim(),
      verifiedSql: draft.type === 'VERIFIED_QUERY' ? draft.verifiedSql?.trim() : undefined,
      grantedSourceName: draft.type === 'VERIFIED_QUERY' ? draft.grantedSourceName : undefined,
      defaultDatabase: draft.type === 'VERIFIED_QUERY' ? draft.defaultDatabase?.trim() : undefined,
      dbType: draft.type === 'VERIFIED_QUERY'
        ? mineGrants.value.find(item => item.grantedSourceName === draft.grantedSourceName)?.dbType
        : undefined,
      keywords: draft.keywordText.split(/[,，]/).map(item => item.trim()).filter(Boolean),
      sourceRef: draft.sourceRef?.trim() || undefined,
    })
    draftDialog.value = false
    await loadMine(false)
    ElMessage.success(`知识草稿“${created.title}”已保存，尚不会被 AI 使用`)
    activeTab.value = 'mine'
  } catch (error: any) {
    ElMessage.error(error.message || '保存知识草稿失败')
  } finally {
    savingDraft.value = false
  }
}

async function submitAsset(asset: AiKnowledgeAssetView) {
  await ElMessageBox.confirm(
    `提交“${asset.title}”后将进入人工核验队列，确认继续？`,
    '提交知识审核',
    { type: 'warning', confirmButtonText: '提交', cancelButtonText: '取消' },
  )
  try {
    await entApi.aiKnowledgeSubmit(asset.id)
    await refreshKnowledgeLists()
    ElMessage.success('已提交审核')
  } catch (error: any) {
    ElMessage.error(error.message || '提交失败')
  }
}

async function reviewAsset(asset: AiKnowledgeAssetView, decision: 'VERIFY' | 'REJECT' | 'RETIRE') {
  let comment = ''
  if (decision === 'REJECT' || decision === 'RETIRE') {
    const result = await ElMessageBox.prompt(
      decision === 'REJECT' ? '请填写驳回原因' : '请填写退役原因',
      decision === 'REJECT' ? '驳回知识资产' : '退役知识资产',
      { inputValidator: value => !!value?.trim() || '原因不能为空' },
    )
    comment = result.value.trim()
  } else {
    await ElMessageBox.confirm(
      `确认已核对“${asset.title}”的口径、数据源与证据，并允许 Ask Lyra 引用？`,
      '核验知识资产',
      { type: 'warning', confirmButtonText: '核验通过', cancelButtonText: '取消' },
    )
  }
  try {
    await entApi.aiKnowledgeReviewDecision(asset.id, decision, comment)
    await refreshKnowledgeLists()
    ElMessage.success(decision === 'VERIFY' ? '已核验并进入知识库' : decision === 'REJECT' ? '已驳回' : '已退役')
  } catch (error: any) {
    ElMessage.error(error.message || '审核操作失败')
  }
}

async function refreshKnowledgeLists() {
  await Promise.all([
    loadMine(false),
    loadVerified(false),
    canModerate.value ? loadReview(false) : Promise.resolve(),
  ])
}

function openGatewayIssue() {
  Object.assign(gatewayForm, {
    displayName: '', grantId: '', scopes: ['KNOWLEDGE_READ'] as AgentGatewayScope[], expiresAt: futureLocal(7),
  })
  gatewayDialog.value = true
}

function normalizeGatewayScopes() {
  if (gatewayForm.scopes.includes('READ_EXECUTE') && !gatewayForm.scopes.includes('READ_PLAN')) {
    gatewayForm.scopes.push('READ_PLAN')
  }
}

async function issueToken() {
  normalizeGatewayScopes()
  if (!gatewayForm.displayName.trim() || !gatewayForm.grantId || !gatewayForm.scopes.length || !gatewayForm.expiresAt) {
    ElMessage.warning('请完整填写身份、Grant、权限与到期时间')
    return
  }
  issuingToken.value = true
  try {
    const issued = await entApi.aiGatewayIssue({
      displayName: gatewayForm.displayName.trim(),
      grantId: gatewayForm.grantId,
      scopes: gatewayForm.scopes,
      expiresAt: gatewayForm.expiresAt,
    })
    gatewayDialog.value = false
    issuedPlaintext.value = issued.plaintextToken
    issuedWarning.value = issued.warning
    plaintextDialog.value = true
    await loadGatewayContext()
  } catch (error: any) {
    ElMessage.error(error.message || 'Gateway 身份签发失败')
  } finally {
    issuingToken.value = false
  }
}

async function revokeToken(token: AiGatewayTokenView) {
  await ElMessageBox.confirm(
    `撤销“${token.displayName}”后外部 Agent 将立即失去访问权，确认继续？`,
    '撤销 Gateway 身份',
    { type: 'warning', confirmButtonText: '撤销', cancelButtonText: '取消' },
  )
  try {
    await entApi.aiGatewayRevoke(token.id)
    await loadGatewayContext()
    ElMessage.success('Gateway 身份已撤销')
  } catch (error: any) {
    ElMessage.error(error.message || '撤销失败')
  }
}

async function copyToken() {
  try {
    await navigator.clipboard.writeText(issuedPlaintext.value)
    ElMessage.success('令牌已复制')
  } catch {
    ElMessage.warning('无法自动复制，请手动选择令牌文本')
  }
}

function closePlaintext() {
  issuedPlaintext.value = ''
  issuedWarning.value = ''
  plaintextDialog.value = false
}

async function diagnose() {
  diagnosing.value = true
  try {
    diagnostic.value = await entApi.aiMaxComputeDiagnose({ ...diagnosticForm })
  } catch (error: any) {
    ElMessage.error(error.message || '诊断失败')
  } finally {
    diagnosing.value = false
  }
}

function resultFor(caseId: string) {
  return quality.value?.latestRun?.results.find(item => item.caseId === caseId)
}

function typeLabel(type: KnowledgeAssetType) {
  return knowledgeTypes.find(item => item.value === type)?.label || type
}

function statusLabel(status: KnowledgeAssetStatus) {
  return ({ DRAFT: '草稿', IN_REVIEW: '审核中', VERIFIED: '已验证', REJECTED: '已驳回', RETIRED: '已退役' } as Record<KnowledgeAssetStatus, string>)[status]
}

function statusTag(status: KnowledgeAssetStatus): '' | 'success' | 'warning' | 'danger' | 'info' {
  return ({ DRAFT: 'info', IN_REVIEW: 'warning', VERIFIED: 'success', REJECTED: 'danger', RETIRED: 'info' } as const)[status]
}

function gatewayGrantLabel(grant: LogicalGrant | AdminGrant) {
  const principal = 'userId' in grant && grant.userId ? grant.userId : '当前用户'
  return `${grant.grantedSourceName} · ${principal}`
}

function shortDigest(value: string) {
  return value ? `${value.slice(0, 10)}…` : '—'
}

function formatTime(value?: string | null) {
  if (!value) return '—'
  const time = new Date(value)
  return Number.isNaN(time.getTime()) ? value : time.toLocaleString('zh-CN', { hour12: false })
}

function futureLocal(days: number) {
  const value = new Date(Date.now() + days * 24 * 60 * 60 * 1000)
  const pad = (part: number) => String(part).padStart(2, '0')
  return `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())}T${pad(value.getHours())}:${pad(value.getMinutes())}:${pad(value.getSeconds())}`
}
</script>

<style scoped>
.knowledge-page { display: flex; flex-direction: column; gap: 14px; }
.knowledge-hero { display: flex; align-items: center; justify-content: space-between; gap: 24px; padding: 24px; }
.knowledge-hero h2 { margin: 5px 0 7px; font-size: clamp(25px, 3vw, 34px); letter-spacing: -.04em; }
.knowledge-hero p, .workflow-hint { margin: 0; color: var(--color-text-muted); line-height: 1.65; }
.metric-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.metric-card { display: flex; flex-direction: column; gap: 8px; min-height: 126px; padding: 18px; }
.metric-card span, .quality-summary span { color: var(--color-text-muted); font-size: 12px; }
.metric-card strong { font-size: 28px; letter-spacing: -.04em; }
.metric-card small, .quality-summary small { color: var(--color-text-muted); line-height: 1.5; }
.feature-alert { border-radius: 12px; }
.workspace-card { padding: 10px 18px 18px; overflow: hidden; }
.knowledge-tabs :deep(.el-tabs__header) { margin-bottom: 18px; }
.tab-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.tab-toolbar .el-input { max-width: 420px; }
.asset-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 12px; }
.asset-card, .review-row, .diagnostic-result { border: 1px solid var(--color-panel-border); border-radius: 13px; background: var(--color-panel-header); }
.asset-card { display: flex; flex-direction: column; gap: 12px; padding: 16px; }
.asset-head { display: flex; justify-content: space-between; gap: 12px; }
.asset-head h3 { margin: 8px 0 0; font-size: 16px; }
.version { color: var(--color-text-muted); font-family: var(--font-mono); font-size: 11px; }
.asset-card p, .review-copy p { margin: 0; line-height: 1.7; white-space: pre-wrap; }
.asset-meta, .keyword-row { display: flex; flex-wrap: wrap; gap: 6px 12px; color: var(--color-text-muted); font-size: 11px; }
.asset-actions { display: flex; justify-content: flex-end; margin-top: auto; }
.sql-preview { max-height: 180px; margin: 0; padding: 12px; overflow: auto; border-radius: 9px; background: var(--color-code-bg, var(--color-muted)); font-family: var(--font-mono); font-size: 11px; line-height: 1.6; white-space: pre-wrap; }
.asset-list { display: flex; flex-direction: column; gap: 10px; }
.review-row { display: flex; align-items: center; justify-content: space-between; gap: 18px; padding: 15px; }
.review-copy { min-width: 0; }
.review-copy small { display: block; margin-top: 7px; color: var(--color-text-muted); }
.review-title { display: flex; flex-wrap: wrap; align-items: center; gap: 9px; margin-bottom: 8px; }
.review-title span { color: var(--color-text-muted); font-size: 11px; }
.review-actions { display: flex; flex-shrink: 0; gap: 6px; }
.quality-panel { display: flex; flex-direction: column; gap: 18px; }
.quality-summary { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.quality-summary > div { display: flex; flex-direction: column; gap: 7px; padding: 15px; border: 1px solid var(--color-panel-border); border-radius: 11px; background: var(--color-panel-header); }
.quality-summary strong { font-size: 21px; }
.quality-run-meta, .operations-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 16px; color: var(--color-text-muted); font-size: 11px; }
.operations-panel { display: flex; flex-direction: column; gap: 16px; }
.durable-runs { display: flex; flex-direction: column; gap: 10px; }
.durable-runs > div { display: flex; flex-wrap: wrap; gap: 7px; }
.gateway-panel { display: flex; flex-direction: column; }
.gateway-panel .el-tag + .el-tag { margin-left: 4px; }
.diagnose-grid { display: grid; grid-template-columns: minmax(280px, .8fr) minmax(320px, 1.2fr); gap: 20px; }
.diagnostic-result { padding: 20px; }
.diagnostic-result h3 { margin: 7px 0 14px; }
.diagnostic-result li { margin-bottom: 8px; line-height: 1.6; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.dialog-form { margin-top: 18px; }
.plaintext-token { margin-top: 16px; padding: 16px; overflow-wrap: anywhere; border: 1px dashed var(--color-border-strong); border-radius: 10px; background: var(--color-muted); font-family: var(--font-mono); line-height: 1.7; user-select: all; }

@media (max-width: 980px) {
  .metric-grid { grid-template-columns: repeat(2, 1fr); }
  .diagnose-grid { grid-template-columns: 1fr; }
}

@media (max-width: 640px) {
  .knowledge-hero, .review-row, .tab-toolbar { align-items: stretch; flex-direction: column; }
  .metric-grid, .quality-summary, .form-grid { grid-template-columns: 1fr; }
  .review-actions { flex-wrap: wrap; }
}
</style>
