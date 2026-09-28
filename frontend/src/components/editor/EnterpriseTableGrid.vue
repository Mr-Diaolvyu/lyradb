<template>
  <section class="enterprise-grid" v-loading="loading">
    <div class="grid-toolbar">
      <span>表格增改删 · 每批最多 100 项</span>
      <el-button :disabled="!snapshot?.editable" @click="openInsert">新增记录</el-button>
      <el-button :disabled="loading" @click="reload">刷新数据</el-button>
    </div>
    <el-alert v-if="error" type="error" :title="error" :closable="false" />
    <el-alert v-else-if="snapshot && !snapshot.editable" type="info" :title="snapshot.reason" :closable="false" />
    <el-alert v-else-if="snapshot?.truncated" type="warning" title="仅显示前 100 行；可通过受控 SQL 定位其他记录。" :closable="false" />
    <el-table v-if="snapshot?.editable" :data="snapshot.rows" border height="360" size="small" empty-text="表中暂无可显示记录">
      <el-table-column v-for="column in snapshot.columns" :key="column.name"
        :prop="`values.${column.name}`" :label="column.name" min-width="145" show-overflow-tooltip>
        <template #default="{ row }">{{ displayValue(row.values[column.name]) }}</template>
      </el-table-column>
      <el-table-column label="操作" fixed="right" width="155">
        <template #default="{ row }">
          <el-button size="small" @click="openUpdate(row)">修改</el-button>
          <el-button size="small" type="danger" plain @click="stageDelete(row)">暂存删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div v-if="snapshot?.editable" class="staged-panel">
      <div class="grid-toolbar">
        <strong>待提交变更 {{ changes.length }} 项</strong>
        <el-button v-if="changes.length" text @click="changes = []">清空暂存</el-button>
      </div>
      <div v-for="(change, index) in changes" :key="index" class="staged-item">
        <el-tag :type="change.action === 'DELETE' ? 'danger' : change.action === 'INSERT' ? 'success' : 'warning'" size="small">{{ actionLabel(change.action) }}</el-tag>
        <span>{{ change.action === 'INSERT' ? '新记录' : JSON.stringify(change.key) }}</span>
        <span>{{ Object.keys(change.values || {}).join('、') }}</span>
        <el-button size="small" text @click="changes.splice(index, 1)">移除</el-button>
      </div>
      <el-input v-model="reason" type="textarea" :rows="2" maxlength="500" show-word-limit placeholder="申请理由（可选）" />
      <el-button type="primary" :loading="submitting" :disabled="!changes.length" @click="submit">提交整批审批</el-button>
      <el-alert v-if="approvalId" type="success" :closable="false"
        :title="`审批申请 ${approvalId} 已提交。批准后由申请人在审批中心手动执行。`" />
    </div>

    <el-dialog v-model="editor.visible" :title="editor.mode === 'INSERT' ? '新增记录' : '修改记录'" width="650" append-to-body>
      <el-form label-width="140px">
        <el-form-item v-for="column in editableColumns" :key="column.name" :label="column.name">
          <div class="field-editor">
            <el-checkbox v-model="editor.selected[column.name]">写入</el-checkbox>
            <el-input v-model="editor.values[column.name]" :disabled="!editor.selected[column.name] || editor.nulls[column.name]" :placeholder="column.typeName" />
            <el-checkbox v-if="column.nullable" v-model="editor.nulls[column.name]" :disabled="!editor.selected[column.name]">NULL</el-checkbox>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editor.visible = false">取消</el-button>
        <el-button type="primary" @click="stageEditor">暂存变更</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { entApi, type TableEditChange, type TableEditSnapshot } from '@/api/ent'
import type { ColumnMetadata } from '@/types/metadata'

const props = defineProps<{ source: string; schema: string; table: string }>()
type SnapshotRow = TableEditSnapshot['rows'][number]
const snapshot = ref<TableEditSnapshot | null>(null)
const loading = ref(false)
const submitting = ref(false)
const error = ref('')
const changes = ref<TableEditChange[]>([])
const reason = ref('')
const approvalId = ref('')
const editor = reactive({
  visible: false, mode: 'INSERT' as 'INSERT' | 'UPDATE', row: null as SnapshotRow | null,
  selected: {} as Record<string, boolean>, values: {} as Record<string, string>,
  nulls: {} as Record<string, boolean>,
})

const editableColumns = computed(() => (snapshot.value?.columns || []).filter(column =>
  !snapshot.value?.lockedColumns.includes(column.name)
  && (editor.mode === 'INSERT' || !snapshot.value?.primaryKeys.includes(column.name)),
))

function displayValue(value: unknown) {
  if (value === null || value === undefined) return 'NULL'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

function actionLabel(action: TableEditChange['action']) {
  return ({ INSERT: '新增', UPDATE: '修改', DELETE: '删除' })[action]
}

async function reload() {
  if (changes.value.length) {
    try { await ElMessageBox.confirm('刷新会清空尚未提交的暂存变更，继续吗？', '刷新表格') }
    catch { return }
  }
  changes.value = []
  loading.value = true
  error.value = ''
  try {
    snapshot.value = await entApi.tableEditSnapshot(props.source, props.schema, props.table)
  } catch (failure: any) {
    snapshot.value = null
    error.value = failure.message || '无法读取表格编辑快照'
  } finally { loading.value = false }
}

onMounted(reload)
watch(() => [props.source, props.schema, props.table], () => {
  changes.value = []
  approvalId.value = ''
  void reload()
})

function openInsert() {
  editor.mode = 'INSERT'
  editor.row = null
  editor.selected = {}
  editor.values = {}
  editor.nulls = {}
  editor.visible = true
}

function openUpdate(row: SnapshotRow) {
  editor.mode = 'UPDATE'
  editor.row = row
  editor.selected = {}
  editor.values = Object.fromEntries(
    Object.entries(row.values).map(([key, value]) => [key, value == null ? '' : String(value)]),
  )
  editor.nulls = Object.fromEntries(
    Object.entries(row.values).map(([key, value]) => [key, value == null]),
  )
  editor.visible = true
}

function convertedValue(column: ColumnMetadata): unknown {
  if (editor.nulls[column.name]) return null
  const raw = editor.values[column.name] ?? ''
  const type = (column.typeName || '').toUpperCase()
  if (/^(BOOLEAN|BOOL|BIT)$/.test(type)) {
    if (!['true', 'false', '1', '0'].includes(raw.toLowerCase())) throw new Error(`${column.name} 须为 true/false`)
    return raw === '1' || raw.toLowerCase() === 'true'
  }
  if (/^(SMALLINT|TINYINT|INTEGER|INT|FLOAT|REAL|DOUBLE)/.test(type)) {
    const value = Number(raw)
    if (!raw.trim() || !Number.isFinite(value)) throw new Error(`${column.name} 须为有效数字`)
    return value
  }
  return raw
}

function stageEditor() {
  if (!snapshot.value) return
  try {
    const values: Record<string, unknown> = {}
    for (const column of editableColumns.value) {
      if (editor.selected[column.name]) values[column.name] = convertedValue(column)
    }
    if (!Object.keys(values).length) throw new Error('请至少选择一个要写入的字段')
    const change: TableEditChange = editor.mode === 'INSERT'
      ? { action: 'INSERT', values }
      : { action: 'UPDATE', key: editor.row!.key, token: editor.row!.token, values }
    if (change.action === 'UPDATE') {
      changes.value = changes.value.filter(item => item.token !== change.token)
    }
    if (changes.value.length >= 100) throw new Error('每批最多暂存 100 项变更')
    changes.value.push(change)
    editor.visible = false
    approvalId.value = ''
  } catch (failure: any) { ElMessage.warning(failure.message || '暂存失败') }
}

function stageDelete(row: SnapshotRow) {
  changes.value = changes.value.filter(item => item.token !== row.token)
  if (changes.value.length >= 100) { ElMessage.warning('每批最多暂存 100 项变更'); return }
  changes.value.push({ action: 'DELETE', key: row.key, token: row.token })
  approvalId.value = ''
}

async function submit() {
  if (!changes.value.length) return
  try {
    await ElMessageBox.confirm(`确认提交 ${changes.value.length} 项增改删审批？批准后仍需申请人手动执行。`, '提交审批')
  } catch { return }
  submitting.value = true
  try {
    const approval = await entApi.requestTableEdit({
      grantedSourceName: props.source, schema: props.schema, table: props.table,
      changes: changes.value, reason: reason.value.trim() || undefined,
    })
    approvalId.value = approval.id
    changes.value = []
    ElMessage.success('表格变更审批已提交')
  } catch (failure: any) {
    ElMessage.error(failure.message || '审批申请失败')
  } finally { submitting.value = false }
}
</script>

<style scoped>
.enterprise-grid { display: flex; flex-direction: column; gap: 10px; min-height: 0; height: 100%; overflow: auto; }
.grid-toolbar { display: flex; gap: 10px; align-items: center; padding: 8px 0; }
.grid-toolbar span { flex: 1; color: var(--color-text-muted); }
.staged-panel { display: flex; flex-direction: column; gap: 8px; border-top: 1px solid var(--color-panel-border); padding-top: 8px; }
.staged-item { display: flex; align-items: center; gap: 10px; font-size: 12px; }
.staged-item span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.field-editor { display: flex; width: 100%; align-items: center; gap: 8px; }
.field-editor :deep(.el-input) { flex: 1; }
</style>
