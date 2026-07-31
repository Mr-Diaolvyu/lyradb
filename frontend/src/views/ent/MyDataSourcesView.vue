<template>
  <div class="page">
    <header class="page-hero">
      <div>
        <div class="section-kicker">Governed access</div>
        <h1>我的数据源</h1>
        <p>浏览当前企业空间授权的数据资产。连接地址与凭据由平台托管，不会暴露给使用者。</p>
      </div>
      <div class="hero-metrics glass-surface">
        <div class="metric">
          <span class="metric-value">{{ grants.length }}</span>
          <span class="metric-label">已授权数据源</span>
        </div>
        <span class="metric-divider"></span>
        <div class="metric">
          <span class="metric-value">{{ writableCount }}</span>
          <span class="metric-label">具备写权限</span>
        </div>
      </div>
    </header>

    <div v-if="loading" class="loading-state glass-surface">
      <span class="loading-orbit"><span></span></span>
      <div>
        <strong>正在读取数据源授权</strong>
        <p>同步企业空间的数据访问策略…</p>
      </div>
    </div>
    <el-empty v-else-if="!grants.length" description="暂无授权的数据源，请联系管理员分配" />

    <div v-else class="grant-grid">
      <article v-for="grant in grants" :key="grant.id" class="grant-card data-card">
        <div class="grant-head">
          <DatabaseIcon db-type="DATABASE" :size="38" />
          <div class="grant-title">
            <span class="grant-name">{{ grant.grantedSourceName }}</span>
            <span class="grant-caption">LOGICAL DATA SOURCE</span>
          </div>
          <span class="permission-chip" :class="{ writable: grant.sqlCapability === 'DML_ALLOWED' }">
            {{ grant.sqlCapability === 'DML_ALLOWED' ? '读写' : '只读' }}
          </span>
        </div>

        <dl class="grant-meta">
          <div>
            <dt>访问范围</dt>
            <dd>{{ formatTables(grant.allowedTables) }}</dd>
          </div>
          <div v-if="grant.blockedTables">
            <dt>限制范围</dt>
            <dd>{{ formatTables(grant.blockedTables) }}</dd>
          </div>
          <div>
            <dt>单次查询上限</dt>
            <dd class="numeric">{{ grant.maxRowsPerQuery.toLocaleString() }} 行</dd>
          </div>
          <div>
            <dt>数据导出</dt>
            <dd>{{ grant.exportApprovedOnly ? '审批后可导出' : '按策略直接导出' }}</dd>
          </div>
        </dl>

        <footer class="grant-actions">
          <el-button size="small" type="primary" @click="goQuery(grant.grantedSourceName)">进入查询</el-button>
          <el-button size="small" text @click="goApprove(grant.grantedSourceName)">申请导出</el-button>
        </footer>
      </article>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { entApi, type LogicalGrant } from '@/api/ent'
import DatabaseIcon from '@/components/common/DatabaseIcon.vue'

const router = useRouter()
const grants = ref<LogicalGrant[]>([])
const loading = ref(true)
const writableCount = computed(() => grants.value.filter(item => item.sqlCapability === 'DML_ALLOWED').length)

async function load() {
  loading.value = true
  try {
    grants.value = await entApi.grantsMine()
  } catch {
    grants.value = []
  } finally {
    loading.value = false
  }
}
onMounted(load)

function formatTables(value?: string) {
  if (!value?.trim()) return '未授权任何表'
  const items = value.split(',').map(item => item.trim()).filter(Boolean)
  if (items.length <= 3) return items.join(' · ')
  return `${items.slice(0, 3).join(' · ')} 等 ${items.length} 项`
}

function goQuery(name: string) {
  router.push({ name: 'query', query: { source: name } })
}

function goApprove(name: string) {
  router.push({ name: 'query', query: { source: name } })
}
</script>

<style scoped>
.page {
  max-width: 1180px;
  margin: 0 auto;
}

.page-hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 22px;
}

.page-hero h1 {
  margin: 4px 0 6px;
  font-size: 24px;
  font-weight: 720;
  letter-spacing: -0.03em;
}

.page-hero p {
  max-width: 660px;
  margin: 0;
  color: var(--color-text-muted);
  font-size: 12px;
}

.hero-metrics {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 11px 16px;
  border-radius: 12px;
}

.metric { display: flex; flex-direction: column; }
.metric-value { font-size: 18px; font-weight: 720; font-variant-numeric: tabular-nums; }
.metric-label { margin-top: 1px; color: var(--color-text-muted); font-size: 9px; }
.metric-divider { width: 1px; height: 28px; background: var(--color-panel-border); }

.grant-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 14px;
}

.grant-card {
  display: flex;
  flex-direction: column;
  min-height: 260px;
  padding: 15px;
}

.grant-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-bottom: 13px;
  border-bottom: 1px solid var(--color-panel-border);
}

.grant-title {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
}

.grant-name {
  overflow: hidden;
  font-size: 14px;
  font-weight: 680;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.grant-caption {
  margin-top: 3px;
  color: var(--color-text-muted);
  font-size: 8px;
  font-weight: 700;
  letter-spacing: 0.09em;
}

.permission-chip {
  padding: 3px 7px;
  border: 1px solid var(--color-panel-border);
  border-radius: 6px;
  background: var(--color-muted);
  color: var(--color-text-muted);
  font-size: 9px;
  font-weight: 650;
}

.permission-chip.writable {
  border-color: color-mix(in srgb, var(--color-success) 32%, var(--color-panel-border));
  background: color-mix(in srgb, var(--color-success) 10%, var(--color-panel));
  color: var(--color-success);
}

.grant-meta {
  display: grid;
  gap: 9px;
  margin: 14px 0;
}

.grant-meta > div {
  display: grid;
  grid-template-columns: 94px minmax(0, 1fr);
  gap: 8px;
}

.grant-meta dt { color: var(--color-text-muted); font-size: 10px; }
.grant-meta dd { margin: 0; overflow: hidden; color: var(--color-foreground); font-size: 11px; white-space: nowrap; text-overflow: ellipsis; }
.grant-meta dd.numeric { font-variant-numeric: tabular-nums; }

.grant-actions {
  display: flex;
  gap: 7px;
  margin-top: auto;
  padding-top: 11px;
  border-top: 1px solid var(--color-panel-border);
}

.loading-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  min-height: 220px;
  border-radius: 14px;
}

.loading-state strong { font-size: 12px; }
.loading-state p { margin: 3px 0 0; color: var(--color-text-muted); font-size: 10px; }
.loading-orbit { position: relative; width: 36px; height: 36px; border: 1px solid var(--color-border-strong); border-radius: 50%; animation: spin 1.2s linear infinite; }
.loading-orbit::before, .loading-orbit span { position: absolute; border-radius: 50%; content: ''; }
.loading-orbit::before { top: 3px; left: 14px; width: 6px; height: 6px; background: var(--color-brand); }
.loading-orbit span { inset: 13px; background: var(--color-accent); }
@keyframes spin { to { transform: rotate(360deg); } }

@media (max-width: 768px) {
  .page-hero { align-items: flex-start; flex-direction: column; }
  .hero-metrics { width: 100%; justify-content: space-around; }
  .grant-grid { grid-template-columns: 1fr; }
}
</style>
