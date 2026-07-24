<template>
  <div class="page">
    <div class="page-title"><h2>操作审计</h2><span class="page-sub">我的操作记录（合规留痕）</span></div>

    <el-table :data="rows" border size="small" empty-text="暂无记录">
      <el-table-column prop="createdAt" label="时间" width="160"><template #default="{ row }">{{ fmt(row.createdAt) }}</template></el-table-column>
      <el-table-column prop="operationType" label="操作" width="90" />
      <el-table-column prop="grantedSourceName" label="数据源" width="140" />
      <el-table-column prop="username" label="用户" width="100" />
      <el-table-column label="结果" width="80"><template #default="{ row }"><el-tag size="small" :type="row.success ? 'success' : 'danger'">{{ row.success ? '成功' : '失败' }}</el-tag></template></el-table-column>
      <el-table-column prop="resultRows" label="行数" width="70" />
      <el-table-column prop="elapsedMs" label="耗时" width="80"><template #default="{ row }">{{ row.elapsedMs }}ms</template></el-table-column>
      <el-table-column prop="sqlText" label="SQL / 错误" show-overflow-tooltip>
        <template #default="{ row }">{{ row.errorMessage || row.sqlText }}</template>
      </el-table-column>
    </el-table>

    <el-pagination
      style="margin-top: 12px; justify-content: flex-end; display: flex"
      :total="total" :current-page="page + 1" :page-size="size"
      layout="prev, pager, next, total"
      @current-change="onPage" />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { entApi, type AuditLog } from '@/api/ent'

const rows = ref<AuditLog[]>([])
const total = ref(0)
const page = ref(0)
const size = ref(20)

async function load() {
  const res = await entApi.auditMine(page.value, size.value)
  rows.value = res.content
  total.value = res.totalElements
}
onMounted(load)
function onPage(p: number) { page.value = p - 1; load() }
function fmt(d?: string) { return d ? new Date(d).toLocaleString() : '' }
</script>

<style scoped>
.page { max-width: 1200px; margin: 0 auto; }
.page-title { margin-bottom: 12px; }
.page-title h2 { font-size: 18px; margin: 0; }
.page-sub { font-size: 12px; color: var(--color-text-muted, #999); }
</style>
