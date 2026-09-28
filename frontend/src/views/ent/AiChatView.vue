<template>
  <div class="page">
    <header class="page-title">
      <div><h2>AI 数据助手</h2><p class="muted">选好数据源，直接描述你的问题。需要解释表或生成 SQL 时，先添加表结构。</p></div>
      <div class="quick-actions"><el-button @click="router.push({ name: 'ai-advanced' })">高级工具</el-button><el-button @click="tasksOpen = true">后台任务 <el-tag v-if="jobs.running" size="small">{{ jobs.running }} 进行中</el-tag></el-button></div>
    </header>
    <div class="assistant-toolbar">
      <label class="source-field">数据源
        <el-select v-model="source" filterable placeholder="选择已授权的数据源" aria-label="数据源" @change="changeSource">
          <el-option v-for="grant in grants" :key="grant.id" :value="grant.grantedSourceName" :label="grant.grantedSourceName" />
        </el-select>
      </label>
      <el-button :icon="Setting" :disabled="!source" @click="contextOpen = true">{{ snapshot ? `已选 ${snapshot.tableCount} 张表的结构` : '添加表结构' }}</el-button>
      <el-checkbox v-if="snapshot" v-model="attachOnce" :disabled="snapshotConsumed">{{ snapshotConsumed ? '该结构已发送，请重新采集' : '下一条问题附加此结构' }}</el-checkbox>
    </div>
    <el-alert v-if="providerReady === false" title="当前空间未配置 AI 服务，请联系管理员在“企业管理 → AI”中配置。" type="warning" :closable="false" />
    <el-alert v-if="jobs.error" :title="`任务状态刷新失败：${jobs.error}。已提交的任务仍可能在执行，请勿重复提交。`" type="warning" :closable="false"><el-button text @click="jobs.refresh">重新获取状态</el-button></el-alert>
    <div class="quick-actions" aria-label="常用 AI 能力">
      <el-button v-for="action in quickActions" :key="action.id" round size="small" :type="activeAction === action.id ? 'primary' : 'default'" @click="applyQuickAction(action)">{{ action.label }}</el-button>
    </div>
    <div ref="chatBox" class="chat-box" aria-label="对话与任务结果">
      <div v-if="!conversations.length" class="chat-empty">
        <strong>从一个具体问题开始</strong>
        <p>找表：选择“智能找表”，描述业务主题即可。</p>
        <p>解释表 / 写 SQL：添加表结构后提问，回答更有依据。</p>
        <span class="muted">AI 生成的 SQL 由你确认后手动执行。</span>
      </div>
      <article v-for="task in conversations" :key="task.id" class="exchange">
        <div class="msg user"><span class="msg-role">我</span><div class="expl">{{ task.message }}</div><small v-if="task.metadataAttached" class="muted">本条已请求附加表结构</small></div>
        <div class="msg assistant">
          <span class="msg-role">AI · {{ kindLabel(task.kind) }}</span>
          <div v-if="taskActive(task)" class="progress" role="status" aria-live="polite">
            <el-icon class="is-loading"><Loading /></el-icon>
            <div><strong>{{ task.state === 'QUEUED' ? '已提交，等待执行' : task.kind === 'FIND_TABLE' ? '正在检索授权目录并匹配相关表' : '正在准备回答，等待 AI 返回' }}</strong><p>已用 {{ elapsed(task) }} · 你可以离开页面，完成后回来查看。</p></div>
            <el-button v-if="foregroundTask === task.id" size="small" @click="background(task)">后台继续</el-button>
            <el-tag v-else size="small" type="info">后台执行中</el-tag>
          </div>
          <template v-else>
            <div v-if="answer(task)" class="expl">{{ answer(task) }}</div>
            <pre v-if="chatResult(task)?.sql" class="sql">{{ chatResult(task)?.sql }}</pre>
            <el-button v-if="chatResult(task)?.sql && !chatResult(task)?.needsApproval" size="small" @click="goApprove(task)">在查询页打开 SQL</el-button>
            <el-alert v-if="task.error" :title="task.error" type="error" :closable="false" />
            <el-button v-if="task.state === 'FAILED'" size="small" @click="input = task.message || ''">将问题放回输入框</el-button>
            <p v-if="chatResult(task)?.needsApproval" class="muted">该 SQL 需要审批。<el-button size="small" @click="goApprove(task)">前往申请</el-button></p>
            <small class="muted">{{ task.state === 'FAILED' ? '执行失败' : '已完成' }} · {{ elapsed(task) }}</small>
          </template>
        </div>
      </article>
    </div>
    <div class="input-bar">
      <el-input ref="inputRef" v-model="input" type="textarea" :rows="3" :placeholder="activeAction === 'FIND_TABLE' ? '描述业务主题，例如：项目、客户到访、车辆关系…' : '描述你的问题，或粘贴 SQL / 报错；Ctrl + Enter 发送'" aria-label="发送给 AI 的问题" @keydown.enter.ctrl.prevent="send" />
      <el-button type="primary" :loading="submitting" :disabled="!source || !input.trim() || submitting" @click="send">发送</el-button>
    </div>
    <p class="input-hint muted">{{ activeAction === 'FIND_TABLE' ? '智能找表会检索授权目录，无需先采集字段。' : attachOnce && snapshot ? '下一条将附加已选结构，不包含业务数据行。' : '当前未附加表结构，AI 无法确认实际字段。' }} 后台结果保留 1 小时，服务重启后清空。</p>

    <el-dialog v-model="contextOpen" title="添加表结构" width="min(760px, 94vw)">
      <p class="context-help">指定要了解的库或表，再采集结构。只读取表名、字段等元数据，不读取业务数据行。</p>
      <el-form label-position="top" class="context-grid">
        <el-form-item label="数据库 / Project"><el-input v-model="selection.database" clearable placeholder="MySQL 填数据库名，如 property_erp" @input="resetSnapshot" /></el-form-item>
        <el-form-item label="Schema（按需填写）"><el-select v-model="selection.schemas" multiple filterable allow-create default-first-option placeholder="PostgreSQL 如 public；MySQL 可留空" @change="resetSnapshot"><el-option v-for="name in schemaOptions" :key="name" :value="name" :label="name" /></el-select></el-form-item>
        <el-form-item label="指定表（可选，留空采集所选库 / Schema）" class="full-width"><el-select v-model="selection.tables" multiple filterable allow-create default-first-option placeholder="输入完整表名并回车，如 property_erp.orders" @change="resetSnapshot"><el-option v-for="name in tableOptions" :key="name" :value="name" :label="name" /></el-select></el-form-item>
      </el-form>
      <p class="muted">至少填写数据库、Schema 或完整表名之一。每次最多 200 张表、5000 个字段，范围较大时请指定表。</p>
      <div class="context-actions"><el-button type="primary" :loading="capturing" :disabled="!source || !metadataScopeValid || capturing" @click="captureMetadata">采集表结构</el-button><el-button v-if="capturing" @click="contextOpen = false; tasksOpen = true">后台继续</el-button><el-button :disabled="!snapshot" @click="previewOpen = true">查看结构</el-button></div>
      <div v-if="captureTask && taskActive(captureTask)" class="progress" role="status"><el-icon class="is-loading"><Loading /></el-icon><span>{{ captureTask.state === 'QUEUED' ? '等待采集' : '正在读取授权范围内的表与字段' }} · {{ elapsed(captureTask) }}，可以关闭此窗口。</span></div>
      <el-alert v-if="captureError" :title="captureError" type="error" :closable="false" show-icon />
      <el-alert v-else-if="snapshot" :title="`已采集 ${snapshot.tableCount} 张表、${snapshot.columnCount} 个字段。请预览后选择是否附加。`" type="success" :closable="false" />
    </el-dialog>
    <el-dialog v-model="previewOpen" title="表结构预览" width="min(760px, 94vw)">
      <p v-if="snapshot" class="muted">{{ snapshot.grantedSourceName }} · {{ snapshot.tableCount }} 表 / {{ snapshot.columnCount }} 字段 · 预览最多 20 张表</p>
      <pre class="metadata-preview">{{ metadataPreviewText || '暂无结构' }}</pre>
      <template #footer><el-button @click="saveMetadata('markdown')" :disabled="!snapshot">保存 Markdown</el-button><el-button @click="saveMetadata('json')" :disabled="!snapshot">保存 JSON</el-button><el-button type="primary" :disabled="!snapshot || snapshotConsumed" @click="attachFromPreview">用于下一条问题</el-button></template>
    </el-dialog>
    <el-drawer v-model="tasksOpen" title="后台任务" size="min(480px, 94vw)">
      <p class="muted">任务在服务端执行。切换页面或刷新后可继续查看；完成结果保留 1 小时，服务重启后清空。</p>
      <el-empty v-if="!jobs.tasks.length" description="暂无任务" />
      <article v-for="task in jobs.tasks" :key="task.id" class="task-card">
        <div class="task-title"><strong>{{ kindLabel(task.kind) }}</strong><el-tag :type="task.state === 'FAILED' ? 'danger' : task.state === 'SUCCEEDED' ? 'success' : 'info'">{{ stateLabel(task.state) }}</el-tag></div>
        <p>{{ task.grantedSourceName }}</p><p v-if="task.message" class="task-prompt">{{ task.message }}</p><p class="muted">{{ elapsed(task) }}</p>
        <el-alert v-if="task.error" :title="task.error" type="error" :closable="false" />
        <el-button v-if="task.kind === 'METADATA' && task.state === 'SUCCEEDED'" size="small" @click="useSnapshot(task)">查看并使用结构</el-button>
        <el-button v-else-if="task.kind !== 'METADATA'" size="small" @click="openTask(task)">查看对话</el-button>
      </article>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, reactive, ref, onMounted, onUnmounted, watch } from 'vue'
import { Setting, Loading } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { entApi, type LogicalGrant, type MetadataSnapshotSummary } from '@/api/ent'
import type { AiChatResponse } from '@/types/ai'
import { taskActive, type AiTask } from '@/api/aiTasks'
import { useAiTasksStore } from '@/stores/aiTasks'
import { useAuthStore } from '@/stores/auth'
import { saveBlob } from '@/utils/download'
import { formatMetadataPreview, hasMetadataScope, normalizeMetadataSelection, safeDownloadStem } from '@/utils/enterpriseTransfer'
const router = useRouter()
const route = useRoute()
const jobs = useAiTasksStore()
const auth = useAuthStore()
const grants = ref<LogicalGrant[]>([])
const source = ref('')
const input = ref('')
const submitting = ref(false)
const captureSubmitting = ref(false)
const contextOpen = ref(false)
const tasksOpen = ref(false)
const activeAction = ref('')
const inputRef = ref()
const chatBox = ref<HTMLElement>()
const providerReady = ref<boolean | null>(null)
const selection = reactive({ database: '', schemas: [] as string[], tables: [] as string[] })
const snapshot = ref<MetadataSnapshotSummary | null>(null)
const snapshotConsumed = ref(false)
const attachOnce = ref(false)
const previewOpen = ref(false)
const captureError = ref('')
const captureTaskId = ref('')
const foregroundTask = ref('')
const now = ref(Date.now())
const timer = setInterval(() => { now.value = Date.now() }, 1000)
onUnmounted(() => clearInterval(timer))
const metadataPreviewText = computed(() => formatMetadataPreview(snapshot.value?.preview || []))
const metadataScopeValid = computed(() => hasMetadataScope({ grantedSourceName: source.value, ...selection }))
const selectedGrant = computed(() => grants.value.find(grant => grant.grantedSourceName === source.value))
const tableOptions = computed(() => grantTokens(selectedGrant.value?.allowedTables))
const schemaOptions = computed(() => grantTokens(selectedGrant.value?.allowedSchemas))
const captureTask = computed(() => jobs.tasks.find(task => task.id === captureTaskId.value))
const capturing = computed(() => captureSubmitting.value || Boolean(captureTask.value && taskActive(captureTask.value)))
const conversations = computed(() => jobs.tasks.filter(task => task.kind !== 'METADATA' && task.grantedSourceName === source.value).slice().sort((a, b) => a.createdAt - b.createdAt))
interface QuickAction { id: string; label: string; prompt: string }
const quickActions: QuickAction[] = [
  { id: 'FIND_TABLE', label: '智能找表', prompt: '' },
  { id: 'EXPLAIN_TABLE', label: '解释表', prompt: '请解释所选表的用途、关键字段和需要确认的业务口径。' },
  { id: 'GENERATE_SQL', label: '生成 SQL', prompt: '请根据以下目标生成 SQL，并明确尚未核验的假设：' },
  { id: 'OPTIMIZE_SQL', label: '优化 SQL', prompt: '请优化以下 SQL，说明语义风险和验证方法：' },
  { id: 'DIAGNOSE', label: '诊断报错', prompt: '请分析以下数据库报错，给出修复建议：' },
]
function grantTokens(value?: string) { return [...new Set((value || '').split(',').map(v => v.trim()).filter(v => v && !v.includes('*')))] }
let loadVersion = 0
let selectionVersion = 0
async function load() {
  const version = ++loadVersion
  try {
    const [grantResult, providerResult] = await Promise.allSettled([entApi.grantsMine(), entApi.aiProviders()])
    if (version !== loadVersion) return
    if (grantResult.status === 'rejected') throw grantResult.reason
    const available = grantResult.value
    grants.value = available
    source.value = available.find(g => g.grantedSourceName === route.query.source)?.grantedSourceName || available[0]?.grantedSourceName || ''
    providerReady.value = providerResult.status === 'fulfilled' ? providerResult.value.length > 0 : null
    changeSource()
    if (typeof route.query.schema === 'string') selection.schemas = [route.query.schema]
    if (typeof route.query.table === 'string') { selection.tables = [route.query.table]; contextOpen.value = true }
  } catch (error: any) { if (version === loadVersion) ElMessage.error(error.message || '加载数据源失败') }
}
onMounted(load)
onUnmounted(() => { loadVersion++ })
watch(() => auth.user?.currentWorkspaceId, () => {
  source.value = ''; grants.value = []; input.value = ''; changeSource(); contextOpen.value = false; previewOpen.value = false
  void load()
})
function resetSnapshot() { selectionVersion++; snapshot.value = null; snapshotConsumed.value = false; attachOnce.value = false; captureError.value = ''; captureTaskId.value = '' }
function changeSource() { resetSnapshot(); selection.database = ''; selection.schemas = []; selection.tables = [] }
watch(() => captureTask.value?.state, state => {
  const task = captureTask.value
  if (!task || task.grantedSourceName !== source.value) return
  if (state === 'SUCCEEDED') useSnapshot(task)
  if (state === 'FAILED') captureError.value = task.error || '未采集到结构，请检查采集范围和授权'
})
async function captureMetadata() {
  if (capturing.value || !source.value || !metadataScopeValid.value) return
  captureSubmitting.value = true
  captureError.value = ''; snapshot.value = null; attachOnce.value = false
  const selected = source.value
  const version = selectionVersion
  try {
    const task = await jobs.submit({ requestId: crypto.randomUUID(), kind: 'METADATA', ...normalizeMetadataSelection({ grantedSourceName: selected, ...selection }) })
    if (selectionVersion === version && source.value === selected) { captureTaskId.value = task.id; if (task.state === 'SUCCEEDED') useSnapshot(task) }
  } catch (error: any) { captureError.value = error.message || '提交失败，请先查看后台任务确认状态'; await jobs.refresh() }
  finally { captureSubmitting.value = false }
}
function useSnapshot(task: AiTask) {
  const result = task.result as MetadataSnapshotSummary | undefined
  if (!result || result.tableCount <= 0) { captureError.value = '未采集到任何表，请检查范围和授权'; return }
  if (result.expiresAt && new Date(result.expiresAt).getTime() <= Date.now()) { ElMessage.warning('此结构快照已过期，请重新采集'); return }
  if (source.value !== task.grantedSourceName) { source.value = task.grantedSourceName; changeSource() }
  snapshot.value = result; snapshotConsumed.value = jobs.tasks.some(t => t.metadataSnapshotId === result.id); attachOnce.value = false; tasksOpen.value = false; previewOpen.value = true
}
function attachFromPreview() { if (!snapshot.value || snapshotConsumed.value) return; attachOnce.value = true; previewOpen.value = false; contextOpen.value = false }
function applyQuickAction(action: QuickAction) { activeAction.value = action.id; input.value = action.prompt; void nextTick(() => inputRef.value?.focus?.()) }
async function saveMetadata(format: 'json' | 'markdown') {
  if (!snapshot.value) return
  try { const blob = await entApi.downloadMetadataSnapshot(snapshot.value.id, format); await saveBlob(blob, `${safeDownloadStem(snapshot.value.grantedSourceName)}-metadata.${format === 'markdown' ? 'md' : 'json'}`) }
  catch (error: any) { ElMessage.error(error.message || '保存失败') }
}
async function send() {
  if (submitting.value || !source.value || !input.value.trim()) return
  submitting.value = true
  const text = input.value.trim()
  const version = selectionVersion
  const useMetadata = activeAction.value !== 'FIND_TABLE' && attachOnce.value && !!snapshot.value && !snapshotConsumed.value
  const history = conversations.value.filter(t => t.state === 'SUCCEEDED').slice(-10).flatMap(t => [{ role: 'user', content: t.message || '' }, { role: 'assistant', content: answer(t) }])
  try {
    const task = await jobs.submit({ requestId: crypto.randomUUID(), kind: activeAction.value === 'FIND_TABLE' ? 'FIND_TABLE' : 'CHAT', grantedSourceName: source.value, message: text, history, attachMetadata: useMetadata, metadataSnapshotId: useMetadata ? snapshot.value!.id : undefined })
    if (version !== selectionVersion) return
    foregroundTask.value = task.id
    if (input.value.trim() === text) input.value = ''
    if (useMetadata) { attachOnce.value = false; snapshotConsumed.value = true }
    await nextTick(); chatBox.value?.scrollTo({ top: chatBox.value.scrollHeight, behavior: 'smooth' })
  } catch (error: any) { ElMessage.error(error.message || '提交失败，请查看后台任务确认状态'); await jobs.refresh() }
  finally { submitting.value = false }
}
function chatResult(task: AiTask) { return task.kind === 'CHAT' ? task.result as AiChatResponse | undefined : undefined }
function answer(task: AiTask) {
  if (task.kind === 'CHAT') return chatResult(task)?.explanation || ''
  const result = task.result as { message?: string; recommendations?: Array<{ path: string; reason: string }> } | undefined
  return result ? [result.message, ...(result.recommendations || []).map((item, i) => `${i + 1}. ${item.path} — ${item.reason}`)].filter(Boolean).join('\n\n') : ''
}
function elapsed(task: AiTask) { const seconds = Math.max(0, Math.floor(((task.finishedAt || now.value) - task.createdAt) / 1000)); return seconds < 60 ? `${seconds} 秒` : `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒` }
function kindLabel(kind: AiTask['kind']) { return { CHAT: '问答', FIND_TABLE: '智能找表', METADATA: '采集表结构' }[kind] }
function stateLabel(state: AiTask['state']) { return { QUEUED: '排队中', RUNNING: '执行中', SUCCEEDED: '已完成', FAILED: '失败' }[state] }
function background(task: AiTask) { foregroundTask.value = ''; ElMessage.info(`${kindLabel(task.kind)}在后台继续，可切换到其他页面`) }
function openTask(task: AiTask) { if (source.value !== task.grantedSourceName) { source.value = task.grantedSourceName; changeSource() }; tasksOpen.value = false }
function goApprove(task: AiTask) { router.push({ name: 'query', query: { source: task.grantedSourceName }, state: { sql: chatResult(task)?.sql || '' } }) }
</script>

<style scoped>
.page { max-width: 1100px; margin: 0 auto; display: flex; flex-direction: column; height: 100%; min-height: 0; gap: 12px; }
.page-title, .assistant-toolbar, .task-title { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.page-title h2 { font-size: 22px; margin: 0 0 6px; }
.muted, .context-help { font-size: 12px; color: var(--color-text-muted); line-height: 1.6; }
p { margin: 6px 0; }
.assistant-toolbar { justify-content: flex-start; flex-wrap: wrap; border: 1px solid var(--color-border); border-radius: 10px; padding: 12px; background: var(--color-panel); }
.source-field { display: flex; align-items: center; gap: 10px; font-size: 13px; }
.source-field .el-select { width: 250px; }
.quick-actions, .context-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.quick-actions :deep(.el-button + .el-button), .context-actions :deep(.el-button + .el-button) { margin-left: 0; }
.chat-box { flex: 1; min-height: 180px; overflow-y: auto; border: 1px solid var(--color-border); border-radius: 12px; padding: 20px; background: var(--color-background); }
.chat-empty { padding: 48px 16px; text-align: center; line-height: 1.8; }
.chat-empty strong { font-size: 20px; }
.exchange { margin-bottom: 24px; }
.msg { margin-bottom: 10px; padding: 12px 16px; }
.msg.assistant { background: var(--color-panel); border: 1px solid var(--color-border); border-radius: 10px; }
.msg-role { display: block; font-size: 11px; color: var(--color-text-muted); margin-bottom: 8px; }
.expl { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 14px; line-height: 1.7; }
.progress { display: flex; align-items: center; gap: 12px; padding: 14px 0; font-size: 13px; }
.progress .el-icon { flex-shrink: 0; color: var(--color-primary); font-size: 22px; }
.progress p { color: var(--color-text-muted); font-size: 12px; }
.progress strong { font-weight: 500; }
.sql, .metadata-preview { background: var(--color-muted); color: var(--color-foreground); padding: 12px; border-radius: 6px; font-size: 12px; overflow: auto; white-space: pre-wrap; }
.metadata-preview { max-height: 55vh; }
.input-bar { display: flex; gap: 10px; align-items: flex-end; }
.input-hint { margin: -4px 0 0; }
.context-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 14px; margin-top: 18px; }
.full-width { grid-column: 1 / -1; }
.context-actions { margin: 14px 0; }
.task-card { border: 1px solid var(--color-border); border-radius: 10px; padding: 14px; margin: 12px 0; }
.task-prompt { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 100px; overflow: auto; font-size: 13px; }
@media (prefers-reduced-motion: reduce) { .is-loading { animation: none !important; } }
@media (max-width: 768px) { .page-title, .input-bar { align-items: stretch; flex-direction: column; } .context-grid { grid-template-columns: 1fr; } .source-field { width: 100%; } .source-field .el-select { flex: 1; } .chat-box { padding: 10px; } .progress { flex-wrap: wrap; } }
</style>
