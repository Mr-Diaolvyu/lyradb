<template>
  <div class="page">
    <div class="page-title">
      <h2>AI 数据助手</h2>
      <span class="page-sub">生成 SQL、解释结构并辅助分析；执行与审批仍遵循数据源授权。</span>
    </div>

    <section class="context-panel" aria-labelledby="metadata-context-title">
      <div class="context-header">
        <div>
          <h3 id="metadata-context-title">元数据上下文</h3>
          <p>仅在点击“采集元数据”后读取结构，不读取业务数据行；请至少指定数据库、Schema 或表范围。</p>
        </div>
        <el-tag v-if="snapshot" type="success" size="small">已采集 · 约 {{ snapshot.approximateTokens }} Token</el-tag>
      </div>
      <div class="context-grid">
        <el-select v-model="source" placeholder="选择授权数据源" filterable aria-label="授权数据源" @change="resetSnapshot">
          <el-option v-for="g in grants" :key="g.id" :label="g.grantedSourceName" :value="g.grantedSourceName" />
        </el-select>
        <el-input v-model="selection.database" clearable placeholder="数据库（可选）" aria-label="数据库" @input="resetSnapshot" />
        <el-select
          v-model="selection.schemas"
          multiple
          filterable
          allow-create
          default-first-option
          collapse-tags
          placeholder="选择或输入 Schema（可选）"
          aria-label="Schema 列表"
          @change="resetSnapshot"
        >
          <el-option v-for="name in schemaOptions" :key="name" :label="name" :value="name" />
        </el-select>
        <el-select
          v-model="selection.tables"
          multiple
          filterable
          allow-create
          default-first-option
          collapse-tags
          placeholder="完整限定表名（如 schema.table）"
          aria-label="表列表"
          @change="resetSnapshot"
        >
          <el-option v-for="name in tableOptions" :key="name" :label="name" :value="name" />
        </el-select>
      </div>
      <div class="context-actions">
        <el-button type="primary" :loading="capturing" :disabled="!source || !metadataScopeValid" @click="captureMetadata">采集元数据</el-button>
        <el-button v-if="capturing" @click="cancelCapture">取消采集</el-button>
        <el-button :disabled="!snapshot" @click="previewOpen = true">预览</el-button>
        <el-dropdown :disabled="!snapshot" @command="saveMetadata">
          <el-button :disabled="!snapshot">保存元数据文档</el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="markdown">Markdown</el-dropdown-item>
              <el-dropdown-item command="json">JSON</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-checkbox v-model="attachOnce" :disabled="!snapshot">仅下一条消息附加此元数据</el-checkbox>
      </div>
      <el-alert v-if="captureError" :title="captureError" type="error" :closable="false" show-icon />
      <el-alert
        v-else-if="snapshot"
        type="info"
        :closable="false"
        :title="`已采集 ${snapshot.databaseCount} 库 / ${snapshot.schemaCount} Schema / ${snapshot.tableCount} 表 / ${snapshot.columnCount} 列。勾选后只附加给下一条消息。`"
      />
    </section>

    <div class="bar">
      <el-tag v-if="providerReady === false" size="small" type="warning">未配置 AI Provider，请联系管理员或前往“管理 - AI”配置。</el-tag>
    </div>

    <div class="chat-box" aria-live="polite" :aria-busy="sending">
      <div v-if="!messages.length" class="chat-empty">输入数据库问题，或先采集选定结构后再提问。</div>
      <div v-for="(m, i) in messages" :key="i" :class="['msg', m.role]">
        <div class="msg-role">{{ m.role === 'user' ? '我' : 'AI' }}</div>
        <div class="msg-content">
          <div v-if="m.metadataAttached" class="metadata-badge">本条消息已附加元数据快照</div>
          <div v-if="m.explanation" class="expl">{{ m.explanation }}</div>
          <pre v-if="m.sql" class="sql">{{ m.sql }}</pre>
          <div v-if="m.error" class="err">{{ m.error }}</div>
          <div v-if="m.needsApproval" class="warn">AI 生成的 DML 需要审批：
            <el-button size="small" @click="goApprove(m.sql || '')">前往申请</el-button>
          </div>
          <template v-if="m.result">
            <el-collapse class="result-collapse">
              <el-collapse-item :title="`结果 ${m.result.totalRows} 行 · ${m.result.elapsedMs}ms`">
                <DataTable :columns="m.result.columns" :rows="m.result.rows" />
              </el-collapse-item>
            </el-collapse>
          </template>
        </div>
      </div>
    </div>

    <div class="input-bar">
      <el-input
        v-model="input"
        type="textarea"
        :rows="2"
        placeholder="用自然语言描述你的数据库问题…"
        aria-label="发送给 AI 的问题"
        @keydown.enter.ctrl="send"
      />
      <el-button type="primary" :loading="sending" :disabled="!source || !input.trim()" @click="send">发送</el-button>
    </div>

    <el-dialog v-model="previewOpen" title="元数据预览" width="760" destroy-on-close>
      <div v-if="snapshot" class="snapshot-summary">
        <span>{{ snapshot.grantedSourceName }}</span>
        <span>{{ snapshot.tableCount }} 表 / {{ snapshot.columnCount }} 列</span>
        <span>约 {{ snapshot.approximateTokens }} Token</span>
      </div>
      <pre class="metadata-preview">{{ metadataPreviewText || '暂无预览' }}</pre>
      <template #footer>
        <el-button @click="previewOpen = false">关闭</el-button>
        <el-button type="primary" :disabled="!snapshot" @click="attachFromPreview">下一条消息附加</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import DataTable from '@/components/editor/DataTable.vue'
import { entApi, type LogicalGrant, type MetadataSnapshotSummary } from '@/api/ent'
import type { QueryResult } from '@/types/metadata'
import { saveBlob } from '@/utils/download'
import { formatMetadataPreview, hasMetadataScope, normalizeMetadataSelection, safeDownloadStem } from '@/utils/enterpriseTransfer'

const router = useRouter()
const grants = ref<LogicalGrant[]>([])
const source = ref('')
const input = ref('')
const sending = ref(false)
const providerReady = ref<boolean | null>(null)
const selection = reactive({ database: '', schemas: [] as string[], tables: [] as string[] })
const snapshot = ref<MetadataSnapshotSummary | null>(null)
const attachOnce = ref(false)
const previewOpen = ref(false)
const capturing = ref(false)
const captureError = ref('')
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
let captureController: AbortController | null = null

interface Msg {
  role: string
  explanation?: string
  sql?: string
  error?: string
  result?: QueryResult
  needsApproval?: boolean
  metadataAttached?: boolean
}
const messages = ref<Msg[]>([])

function grantTokens(value?: string): string[] {
  if (!value) return []
  return Array.from(new Set(value.split(',').map(item => item.trim()).filter(Boolean))).sort()
}
async function load() {
  try {
    grants.value = await entApi.grantsMine()
    if (grants.value.length) source.value = grants.value[0].grantedSourceName
  } catch (e: any) {
    ElMessage.error(e.message || '加载授权数据源失败')
  }
  try {
    const ps = await entApi.aiProviders()
    providerReady.value = ps.length > 0
  } catch {
    providerReady.value = false
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
  } catch (e: any) {
    if (controller.signal.aborted) {
      captureError.value = '已取消元数据采集'
    } else {
      captureError.value = e.message || '元数据采集失败'
    }
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
  ElMessage.success('元数据将在下一条消息中附加一次')
}

async function saveMetadata(format: 'json' | 'markdown') {
  if (!snapshot.value) return
  try {
    const blob = await entApi.downloadMetadataSnapshot(snapshot.value.id, format)
    const extension = format === 'markdown' ? 'md' : 'json'
    await saveBlob(blob, `${safeDownloadStem(snapshot.value.grantedSourceName)}-metadata.${extension}`)
    ElMessage.success('元数据文档已保存')
  } catch (e: any) {
    ElMessage.error(e.message || '保存元数据文档失败')
  }
}

async function send() {
  if (!source.value || !input.value.trim()) return
  const text = input.value.trim()
  input.value = ''
  const useMetadata = Boolean(attachOnce.value && snapshot.value)
  const snapshotId = useMetadata ? snapshot.value!.id : undefined
  attachOnce.value = false
  messages.value.push({ role: 'user', explanation: text, metadataAttached: useMetadata })
  sending.value = true
  try {
    const history = messages.value.slice(0, -1).map(m => ({ role: m.role, content: m.explanation || m.sql || '' }))
    const res = await entApi.aiChat({
      grantedSourceName: source.value,
      message: text,
      history,
      attachMetadata: useMetadata,
      metadataSnapshotId: snapshotId,
    })
    messages.value.push({
      role: 'assistant',
      explanation: res.explanation,
      sql: res.sql,
      error: res.error,
      result: res.result,
      needsApproval: res.needsApproval,
    })
  } catch (e: any) {
    messages.value.push({ role: 'assistant', error: e.message || 'AI 调用失败' })
  } finally {
    sending.value = false
  }
}

function goApprove(sql: string) {
  router.push({ name: 'query', query: { source: source.value }, state: { sql } })
}
</script>

<style scoped>
.page { max-width: 1100px; margin: 0 auto; display: flex; flex-direction: column; height: 100%; min-height: 0; }
.page-title { margin-bottom: 10px; }
.page-title h2 { font-size: 18px; margin: 0; }
.page-sub { font-size: 12px; color: var(--color-text-muted); }
.context-panel { border: 1px solid var(--color-border); border-radius: 8px; padding: 12px; background: var(--color-panel); margin-bottom: 10px; }
.context-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; margin-bottom: 10px; }
.context-header h3 { font-size: 14px; margin: 0; }
.context-header p { font-size: 12px; color: var(--color-text-muted); margin: 3px 0 0; }
.context-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.context-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-top: 10px; }
.context-actions :deep(.el-button + .el-button) { margin-left: 0; }
.bar { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; min-height: 24px; }
.chat-box { flex: 1; min-height: 180px; overflow-y: auto; border: 1px solid var(--color-border); border-radius: 8px; padding: 12px; background: var(--color-background); }
.chat-empty { color: var(--color-text-muted); text-align: center; padding: 30px; font-size: 13px; }
.msg { margin-bottom: 12px; }
.msg-role { font-size: 11px; color: var(--color-text-muted); margin-bottom: 2px; }
.msg.user .msg-content { color: var(--color-foreground); }
.msg.assistant .msg-content { background: var(--color-panel); border: 1px solid var(--color-border); border-radius: 6px; padding: 8px 10px; }
.expl { font-size: 13px; margin-bottom: 4px; }
.sql, .metadata-preview { background: var(--color-muted); color: #e2e8f0; padding: 10px; border-radius: 4px; font-size: 12px; overflow: auto; white-space: pre-wrap; }
.sql { margin: 4px 0; }
.metadata-preview { max-height: 55vh; margin: 10px 0 0; }
.err { color: var(--color-destructive); font-size: 12px; }
.warn { color: var(--color-warning); font-size: 12px; margin-top: 4px; }
.metadata-badge { color: var(--color-primary); font-size: 11px; margin-bottom: 4px; }
.result-collapse { margin-top: 6px; }
.input-bar { display: flex; gap: 8px; align-items: flex-end; margin-top: 8px; }
.snapshot-summary { display: flex; flex-wrap: wrap; gap: 14px; color: var(--color-text-muted); font-size: 12px; }
@media (max-width: 768px) {
  .context-grid { grid-template-columns: 1fr; }
  .context-header, .input-bar { align-items: stretch; flex-direction: column; }
}
</style>
