<template>
  <div class="page">
    <div class="page-title"><h2>审批中心</h2><span class="page-sub">导出与高风险 SQL 按不可变申请内容审批</span></div>

    <el-tabs v-model="tab" @tab-change="load">
      <el-tab-pane v-if="canApprove" label="待我审批" name="pending">
        <el-table :data="pending" border size="small" empty-text="无待审批">
          <el-table-column prop="applicantName" label="申请人" width="100" />
          <el-table-column prop="operationType" label="操作" width="110" />
          <el-table-column prop="grantedSourceName" label="数据源" min-width="140" />
          <el-table-column label="申请内容" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">{{ payloadSummary(row) }}</template>
          </el-table-column>
          <el-table-column prop="reason" label="理由" min-width="140" show-overflow-tooltip />
          <el-table-column prop="expiresAt" label="截止" width="160">
            <template #default="{ row }">{{ fmt(row.expiresAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="180" fixed="right">
            <template #default="{ row }">
              <el-button size="small" type="success" @click="approve(row.id)">批准</el-button>
              <el-button size="small" type="danger" @click="reject(row.id)">驳回</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="我的申请" name="mine">
        <div class="mine-toolbar">
          <el-button type="primary" :icon="Plus" @click="openCreate">新建导出申请</el-button>
          <span class="hint">已批准的导出只能由申请人下载一次。</span>
        </div>
        <el-table :data="mine" border size="small" empty-text="无申请">
          <el-table-column prop="operationType" label="操作" width="110" />
          <el-table-column prop="grantedSourceName" label="数据源" min-width="140" />
          <el-table-column label="申请内容" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">{{ payloadSummary(row) }}</template>
          </el-table-column>
          <el-table-column prop="reason" label="理由" min-width="140" show-overflow-tooltip />
          <el-table-column prop="status" label="状态" width="110">
            <template #default="{ row }"><el-tag size="small" :type="statusType(row.status)">{{ row.status }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="createdAt" label="提交时间" width="160">
            <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="row.operationType === 'EXPORT' && row.status === 'APPROVED'"
                size="small"
                type="primary"
                :loading="downloadingId === row.id"
                @click="downloadApproved(row)"
              >下载</el-button>
              <el-button
                v-if="row.status === 'PENDING' || row.status === 'DRAFT'"
                size="small"
                text
                type="danger"
                @click="cancel(row.id)"
              >撤销</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="showCreate" title="新建导出申请" width="560">
      <el-form label-width="100px">
        <el-form-item label="数据源">
          <el-select v-model="form.grantedSourceName" placeholder="选择授权数据源" style="width:100%">
            <el-option v-for="g in grants" :key="g.id" :label="g.grantedSourceName" :value="g.grantedSourceName" />
          </el-select>
        </el-form-item>
        <el-form-item label="SQL">
          <el-input v-model="form.sql" type="textarea" :rows="7" placeholder="填写要导出的只读查询 SQL" />
        </el-form-item>
        <el-form-item label="导出格式">
          <el-select v-model="form.format" style="width:100%">
            <el-option label="CSV" value="csv" />
            <el-option label="JSON" value="json" />
          </el-select>
        </el-form-item>
        <el-form-item label="理由"><el-input v-model="form.reason" type="textarea" :rows="2" maxlength="500" show-word-limit /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreate = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="create">提交申请</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { entApi, type ApprovalRequest, type LogicalGrant } from '@/api/ent'
import { useAuthStore } from '@/stores/auth'
import { saveBlob } from '@/utils/download'
import { runPromptedAction } from '@/utils/requestControl'

interface ExportPayload {
  sql: string
  format: 'csv' | 'json'
  defaultDatabase: string | null
}

const route = useRoute()
const auth = useAuthStore()
const canApprove = computed(() => auth.canApprove)
const tab = ref(route.query.tab === 'mine' || !canApprove.value ? 'mine' : 'pending')
const pending = ref<ApprovalRequest[]>([])
const mine = ref<ApprovalRequest[]>([])
const grants = ref<LogicalGrant[]>([])
const showCreate = ref(false)
const creating = ref(false)
const downloadingId = ref<string | null>(null)
const form = ref({ grantedSourceName: '', sql: '', format: 'csv' as 'csv' | 'json', reason: '' })

function parseExportPayload(row: ApprovalRequest): ExportPayload | null {
  if (row.operationType !== 'EXPORT' || !row.payloadJson) return null
  try {
    const parsed = JSON.parse(row.payloadJson)
    if (typeof parsed?.sql !== 'string' || !parsed.sql.trim()) return null
    if (parsed.format !== 'csv' && parsed.format !== 'json') return null
    if (parsed.defaultDatabase !== null && typeof parsed.defaultDatabase !== 'string') return null
    return {
      sql: parsed.sql,
      format: parsed.format,
      defaultDatabase: parsed.defaultDatabase,
    }
  } catch {
    return null
  }
}

function payloadSummary(row: ApprovalRequest): string {
  const payload = parseExportPayload(row)
  if (!payload) return row.payloadJson || '—'
  const compactSql = payload.sql.replace(/\s+/g, ' ').trim()
  return (payload.format.toUpperCase() + ' · ' + compactSql).slice(0, 240)
}

async function load() {
  try {
    if (tab.value === 'pending' && canApprove.value) {
      pending.value = await entApi.approvalsPending()
    } else {
      mine.value = await entApi.approvals(true)
    }
  } catch (e: any) {
    ElMessage.error(e.message || '加载审批列表失败')
  }
  try {
    grants.value = await entApi.grantsMine()
  } catch {
    grants.value = []
  }
}
onMounted(load)

function openCreate() {
  form.value = {
    grantedSourceName: grants.value[0]?.grantedSourceName || '',
    sql: '',
    format: 'csv',
    reason: '',
  }
  showCreate.value = true
}

async function approve(id: string) {
  try {
    const completed = await runPromptedAction(
      () => ElMessageBox.prompt('审批意见（可选）', '批准', { inputType: 'textarea' }),
      comment => entApi.approveApproval(id, comment),
    )
    if (!completed) return
    ElMessage.success('已批准')
    await load()
  } catch (e: any) {
    ElMessage.error(e.message || '批准失败')
  }
}

async function reject(id: string) {
  try {
    const completed = await runPromptedAction(
      () => ElMessageBox.prompt('驳回理由', '驳回', {
        inputType: 'textarea',
        inputValidator: value => !!value?.trim() || '请填写驳回理由',
      }),
      comment => entApi.rejectApproval(id, comment),
    )
    if (!completed) return
    ElMessage.success('已驳回')
    await load()
  } catch (e: any) {
    ElMessage.error(e.message || '驳回失败')
  }
}

async function cancel(id: string) {
  try {
    await ElMessageBox.confirm('确定撤销此申请？', '撤销申请', { type: 'warning' })
  } catch {
    return
  }
  try {
    await entApi.cancelApproval(id)
    ElMessage.success('已撤销')
    await load()
  } catch (e: any) {
    ElMessage.error(e.message || '撤销失败')
  }
}

async function create() {
  if (!form.value.grantedSourceName) {
    ElMessage.warning('请选择数据源')
    return
  }
  if (!form.value.sql.trim()) {
    ElMessage.warning('请填写导出 SQL')
    return
  }
  creating.value = true
  try {
    await entApi.createApproval({
      operationType: 'EXPORT',
      grantedSourceName: form.value.grantedSourceName,
      payloadJson: JSON.stringify({
        sql: form.value.sql.trim(),
        format: form.value.format,
        defaultDatabase: null,
      }),
      reason: form.value.reason,
    })
    ElMessage.success('申请已提交')
    showCreate.value = false
    tab.value = 'mine'
    await load()
  } catch (e: any) {
    ElMessage.error(e.message || '提交失败')
  } finally {
    creating.value = false
  }
}

async function downloadApproved(row: ApprovalRequest) {
  const payload = parseExportPayload(row)
  if (!payload) {
    ElMessage.error('审批内容不完整，无法安全下载')
    return
  }
  downloadingId.value = row.id
  try {
    const blob = await entApi.export(row.id, {
      sql: payload.sql,
      format: payload.format,
      defaultDatabase: payload.defaultDatabase,
    })
    await saveBlob(blob, 'lyradb-export-' + row.id + '.' + payload.format)
    ElMessage.success('导出已生成')
    await load()
  } catch (e: any) {
    ElMessage.error(e.message || '导出失败')
  } finally {
    downloadingId.value = null
  }
}

function statusType(status: string) {
  return ({
    APPROVED: 'success',
    REJECTED: 'danger',
    DONE: 'success',
    FAILED: 'danger',
    EXPIRED: 'info',
    CANCELLED: 'info',
    EXECUTING: 'warning',
    PENDING: 'warning',
  } as Record<string, string>)[status] || ''
}
function fmt(date?: string) { return date ? new Date(date).toLocaleString() : '' }
</script>

<style scoped>
.page { max-width: 1200px; margin: 0 auto; }
.page-title { margin-bottom: 12px; }
.page-title h2 { font-size: 18px; margin: 0; }
.page-sub, .hint { font-size: 12px; color: var(--color-text-muted); }
.mine-toolbar { margin-bottom: 10px; display: flex; align-items: center; gap: 12px; }

@media (max-width: 768px) {
  .mine-toolbar { align-items: flex-start; flex-direction: column; }
}
</style>
