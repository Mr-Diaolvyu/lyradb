<template>
  <div class="page">
    <div class="page-title">
      <h2>企业查询</h2>
      <span class="page-sub">选择逻辑数据源 → 编写 SQL → 执行（连接信息由平台托管，你不可见）</span>
    </div>

    <div class="toolbar">
      <el-select v-model="source" placeholder="选择数据源" style="width: 280px" @change="onSourceChange">
        <el-option v-for="g in grants" :key="g.id" :label="g.grantedSourceName" :value="g.grantedSourceName" />
      </el-select>
      <el-tag v-if="currentGrant" size="small" :type="currentGrant.sqlCapability === 'DML_ALLOWED' ? 'success' : 'info'">
        {{ currentGrant.sqlCapability === 'DML_ALLOWED' ? '可写' : '只读' }} · 上限 {{ currentGrant.maxRowsPerQuery }} 行
      </el-tag>
      <div class="spacer"></div>
      <el-button :icon="VideoPlay" type="primary" :loading="executing" :disabled="!source || !sql.trim()" @click="execute">
        执行 (Ctrl+Enter)
      </el-button>
    </div>

    <div class="editor-wrap">
      <SqlEditor
        :model-value="sql"
        :db-type="undefined"
        @update:model-value="(v: string) => sql = v"
        @execute="execute"
      />
    </div>

    <div v-if="result" class="result-wrap">
      <div class="result-bar">
        <span>{{ result.totalRows }} 行 · {{ result.elapsedMs }}ms</span>
        <span v-if="result.truncated" class="warn">结果已截断</span>
      </div>
      <DataTable :columns="result.columns" :rows="result.rows" />
    </div>
    <el-empty v-else-if="!executing" description="执行查询后在此查看结果" :image-size="60" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { VideoPlay } from '@element-plus/icons-vue'
import SqlEditor from '@/components/editor/SqlEditor.vue'
import DataTable from '@/components/editor/DataTable.vue'
import { entApi, type LogicalGrant } from '@/api/ent'
import type { QueryResult } from '@/types/metadata'

const route = useRoute()
const grants = ref<LogicalGrant[]>([])
const source = ref('')
const sql = ref('')
const executing = ref(false)
const result = ref<QueryResult | null>(null)

const currentGrant = computed(() => grants.value.find(g => g.grantedSourceName === source.value))

async function loadGrants() {
  try { grants.value = await entApi.grantsMine() }
  catch { grants.value = [] }
  const q = route.query.source as string | undefined
  if (q) source.value = q
  else if (grants.value.length) source.value = grants.value[0].grantedSourceName
}
onMounted(loadGrants)

function onSourceChange() {
  result.value = null
}

async function execute() {
  if (!source.value || !sql.value.trim()) return
  executing.value = true
  result.value = null
  try {
    result.value = await entApi.query(source.value, sql.value)
  } catch (e: any) {
    // 后端把查询失败包成 200 + error 列；真正异常才进 catch
    result.value = { columns: ['error'], rows: [{ error: e.message || '执行失败' }], elapsedMs: 0, totalRows: 1, truncated: false, sql: sql.value }
  } finally {
    executing.value = false
  }
}
</script>

<style scoped>
.page { max-width: 1200px; margin: 0 auto; display: flex; flex-direction: column; height: 100%; }
.page-title { margin-bottom: 12px; }
.page-title h2 { font-size: 18px; margin: 0; }
.page-sub { font-size: 12px; color: var(--color-text-muted); }
.toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.spacer { flex: 1; }
.editor-wrap { flex: 1; min-height: 240px; border: 1px solid var(--color-border); border-radius: 6px; overflow: hidden; }
.result-wrap { margin-top: 12px; border: 1px solid var(--color-border); border-radius: 6px; overflow: hidden; }
.result-bar { padding: 6px 12px; font-size: 12px; color: var(--color-text-muted); background: var(--color-panel-header); display: flex; gap: 12px; }
.warn { color: var(--color-destructive); }
</style>
