<template>
  <div class="page">
    <div class="page-title">
      <h2>我的数据源</h2>
      <span class="page-sub">你被授权的逻辑数据源（连接信息不可见）</span>
    </div>

    <div v-if="loading" class="loading">加载中...</div>
    <el-empty v-else-if="!grants.length" description="暂无授权的数据源（请联系管理员分配）" />

    <div v-else class="grant-grid">
      <div v-for="g in grants" :key="g.id" class="grant-card">
        <div class="grant-head">
          <el-icon color="var(--color-secondary)"><Coin /></el-icon>
          <span class="grant-name">{{ g.grantedSourceName }}</span>
          <el-tag size="small" :type="g.sqlCapability === 'DML_ALLOWED' ? 'success' : 'info'" effect="plain">
            {{ g.sqlCapability === 'DML_ALLOWED' ? '可写' : '只读' }}
          </el-tag>
        </div>
        <div class="grant-meta">
          <div><b>可访问表:</b> {{ g.allowedTables?.trim() || '未授权任何表' }}</div>
          <div v-if="g.blockedTables"><b>黑名单:</b> {{ g.blockedTables }}</div>
          <div><b>行数上限:</b> {{ g.maxRowsPerQuery }}</div>
          <div><b>导出需审批:</b> {{ g.exportApprovedOnly ? '是' : '否' }}</div>
        </div>
        <div class="grant-actions">
          <el-button size="small" type="primary" @click="goQuery(g.grantedSourceName)">去查询</el-button>
          <el-button size="small" @click="goApprove(g.grantedSourceName)">申请导出</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Coin } from '@element-plus/icons-vue'
import { entApi, type LogicalGrant } from '@/api/ent'

const router = useRouter()
const grants = ref<LogicalGrant[]>([])
const loading = ref(true)

async function load() {
  loading.value = true
  try { grants.value = await entApi.grantsMine() }
  catch { grants.value = [] }
  finally { loading.value = false }
}
onMounted(load)

function goQuery(name: string) {
  router.push({ name: 'query', query: { source: name } })
}
function goApprove(name: string) {
  // 导出审批必须绑定具体 SQL，先进入查询页填写并确认查询。
  router.push({ name: 'query', query: { source: name } })
}
</script>

<style scoped>
.page { max-width: 1100px; margin: 0 auto; }
.page-title { margin-bottom: 16px; }
.page-title h2 { font-size: 18px; margin: 0; }
.page-sub { font-size: 12px; color: var(--color-text-muted); }
.loading { color: var(--color-text-muted); padding: 20px; }
.grant-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 14px;
}
.grant-card {
  background: var(--color-panel);
  border: 1px solid var(--color-border);
  border-radius: 8px;
  padding: 14px;
}
.grant-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 10px;
}
.grant-name { font-weight: 600; font-size: 14px; flex: 1; }
.grant-meta { font-size: 12px; color: var(--color-text-muted); line-height: 1.8; }
.grant-actions { margin-top: 12px; display: flex; gap: 8px; }
</style>
