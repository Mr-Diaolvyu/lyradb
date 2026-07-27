<template>
  <div class="page">
    <div class="page-title"><h2>审批中心</h2><span class="page-sub">导出/迁移/敏感操作需审批</span></div>

    <el-tabs v-model="tab" @tab-change="load">
      <el-tab-pane label="待我审批" name="pending">
        <el-table :data="pending" border size="small" empty-text="无待审批">
          <el-table-column prop="applicantName" label="申请人" width="100" />
          <el-table-column prop="operationType" label="操作" width="90" />
          <el-table-column prop="grantedSourceName" label="数据源" />
          <el-table-column prop="reason" label="理由" show-overflow-tooltip />
          <el-table-column prop="expiresAt" label="截止" width="160">
            <template #default="{ row }">{{ fmt(row.expiresAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="180">
            <template #default="{ row }">
              <el-button size="small" type="success" @click="approve(row.id)">批准</el-button>
              <el-button size="small" type="danger" @click="reject(row.id)">驳回</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="我的申请" name="mine">
        <div style="margin-bottom: 10px">
          <el-button type="primary" :icon="Plus" @click="showCreate = true">新建导出申请</el-button>
        </div>
        <el-table :data="mine" border size="small" empty-text="无申请">
          <el-table-column prop="operationType" label="操作" width="90" />
          <el-table-column prop="grantedSourceName" label="数据源" />
          <el-table-column prop="reason" label="理由" show-overflow-tooltip />
          <el-table-column prop="status" label="状态" width="110">
            <template #default="{ row }"><el-tag size="small" :type="statusType(row.status)">{{ row.status }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="createdAt" label="提交时间" width="160"><template #default="{ row }">{{ fmt(row.createdAt) }}</template></el-table-column>
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button v-if="row.status === 'PENDING' || row.status === 'DRAFT'" size="small" text type="danger" @click="cancel(row.id)">撤销</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="showCreate" title="新建导出申请" width="500">
      <el-form label-width="100px">
        <el-form-item label="数据源">
          <el-select v-model="form.grantedSourceName" placeholder="选择授权数据源" style="width:100%">
            <el-option v-for="g in grants" :key="g.id" :label="g.grantedSourceName" :value="g.grantedSourceName" />
          </el-select>
        </el-form-item>
        <el-form-item label="导出格式">
          <el-select v-model="form.format" style="width:100%"><el-option label="CSV" value="csv" /><el-option label="JSON" value="json" /><el-option label="Excel" value="excel" /></el-select>
        </el-form-item>
        <el-form-item label="理由"><el-input v-model="form.reason" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreate = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="create">提交申请</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { entApi, type ApprovalRequest, type LogicalGrant } from '@/api/ent'

const tab = ref('pending')
const pending = ref<ApprovalRequest[]>([])
const mine = ref<ApprovalRequest[]>([])
const grants = ref<LogicalGrant[]>([])
const showCreate = ref(false)
const creating = ref(false)
const form = ref({ grantedSourceName: '', format: 'csv', reason: '' })

async function load() {
  try {
    if (tab.value === 'pending') pending.value = await entApi.approvalsPending()
    else mine.value = await entApi.approvals(true)
  } catch {}
  grants.value = await entApi.grantsMine()
}
onMounted(load)

async function approve(id: string) {
  const { value } = await ElMessageBox.prompt('审批意见（可选）', '批准', { inputType: 'textarea' }).catch(() => ({ value: '' }))
  await entApi.approveApproval(id, value)
  ElMessage.success('已批准'); load()
}
async function reject(id: string) {
  const { value } = await ElMessageBox.prompt('驳回理由', '驳回', { inputType: 'textarea' }).catch(() => ({ value: '' }))
  await entApi.rejectApproval(id, value)
  ElMessage.success('已驳回'); load()
}
async function cancel(id: string) {
  await entApi.cancelApproval(id); ElMessage.success('已撤销'); load()
}
async function create() {
  if (!form.value.grantedSourceName) { ElMessage.warning('请选择数据源'); return }
  creating.value = true
  try {
    await entApi.createApproval({
      operationType: 'EXPORT', grantedSourceName: form.value.grantedSourceName,
      payloadJson: JSON.stringify({ format: form.value.format }),
      reason: form.value.reason,
    })
    ElMessage.success('申请已提交'); showCreate.value = false; load()
  } catch (e: any) { ElMessage.error(e.message || '提交失败') }
  finally { creating.value = false }
}

function statusType(s: string) {
  return ({ APPROVED: 'success', REJECTED: 'danger', DONE: 'success', FAILED: 'danger', EXPIRED: 'info', CANCELLED: 'info', PENDING: 'warning' } as any)[s] || ''
}
function fmt(d?: string) { return d ? new Date(d).toLocaleString() : '' }
</script>

<style scoped>
.page { max-width: 1100px; margin: 0 auto; }
.page-title { margin-bottom: 12px; }
.page-title h2 { font-size: 18px; margin: 0; }
.page-sub { font-size: 12px; color: var(--color-text-muted); }
</style>
