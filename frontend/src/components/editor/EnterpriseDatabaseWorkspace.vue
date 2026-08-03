<template>
  <section class="enterprise-db-workspace">
    <header class="workspace-header">
      <div class="source-identity">
        <span class="source-icon" aria-hidden="true"><el-icon><Coin /></el-icon></span>
        <div>
          <div class="workspace-kicker">AUTHORIZED DATABASE WORKSPACE</div>
          <div class="source-name">{{ catalog?.grantedSourceName || '逻辑数据源' }}</div>
          <div class="source-meta">
            {{ catalog?.dbType || 'DATABASE' }} ·
            {{ catalog?.schemas.length || 0 }} 个 Schema ·
            {{ catalog?.tables.length || 0 }} 张表/视图
          </div>
        </div>
      </div>
      <div class="header-actions">
        <el-button
          :icon="Share"
          :disabled="!selectedSchema"
          @click="selectedSchema && $emit('open-er', selectedSchema)"
        >
          ER 图
        </el-button>
        <el-button :icon="Refresh" :loading="loading" @click="$emit('refresh')">
          刷新目录
        </el-button>
      </div>
    </header>

    <el-alert
      v-if="catalog?.truncated"
      type="warning"
      :closable="false"
      title="授权对象超过目录上限，当前仅展示前 2500 个对象"
    />
    <el-alert
      v-if="error"
      type="error"
      :closable="false"
      :title="error"
    />

    <div class="workspace-body" v-loading="loading">
      <aside class="schema-rail">
        <button
          type="button"
          :class="['schema-item', { active: schemaFilter === '' }]"
          @click="schemaFilter = ''"
        >
          <span>全部 Schema</span>
          <b>{{ catalog?.tables.length || 0 }}</b>
        </button>
        <button
          v-for="schema in catalog?.schemas || []"
          :key="schema"
          type="button"
          :class="['schema-item', { active: schemaFilter === schema }]"
          @click="schemaFilter = schema"
          @dblclick="$emit('open-er', schema)"
        >
          <span :title="schema">{{ schema }}</span>
          <b>{{ schemaCounts[schema] || 0 }}</b>
        </button>
      </aside>

      <main class="table-browser">
        <div class="browser-toolbar">
          <el-input
            v-model="search"
            :prefix-icon="Search"
            clearable
            placeholder="搜索已授权的 Schema、表名或注释"
          />
          <span class="result-count">
            {{ filteredTables.length }} 个结果
            <template v-if="filteredTables.length > visibleTables.length">
              · 显示前 {{ visibleTables.length }} 个
            </template>
          </span>
        </div>

        <div v-if="visibleTables.length" class="table-list">
          <button
            v-for="table in visibleTables"
            :key="table.qualifiedName"
            type="button"
            :class="['table-row', {
              selected: selected?.qualifiedName === table.qualifiedName,
            }]"
            @click="selected = table"
            @dblclick="$emit('open-table', table)"
          >
            <span class="type-glyph" :class="table.type.toLowerCase()">
              <el-icon><Grid /></el-icon>
            </span>
            <span class="table-copy">
              <strong>{{ table.name }}</strong>
              <small>{{ table.schema }}</small>
            </span>
            <span v-if="table.remarks" class="table-remarks">
              {{ table.remarks }}
            </span>
            <span class="table-type">{{ table.type }}</span>
          </button>
        </div>
        <el-empty
          v-else-if="!loading"
          description="当前筛选条件下没有授权对象"
          :image-size="72"
        />
      </main>

      <aside class="object-panel">
        <template v-if="selected">
          <div class="panel-kicker">SELECTED OBJECT</div>
          <h3>{{ selected.name }}</h3>
          <dl>
            <dt>逻辑数据源</dt>
            <dd>{{ catalog?.grantedSourceName }}</dd>
            <dt>Schema</dt>
            <dd>{{ selected.schema }}</dd>
            <dt>类型</dt>
            <dd>{{ selected.type }}</dd>
            <dt>完整名称</dt>
            <dd><code>{{ selected.qualifiedName }}</code></dd>
            <dt v-if="selected.remarks">注释</dt>
            <dd v-if="selected.remarks">{{ selected.remarks }}</dd>
          </dl>
          <el-button type="primary" @click="$emit('open-table', selected)">
            打开表工作台
          </el-button>
          <el-button @click="$emit('open-sql', selected)">
            在 SQL 中查询
          </el-button>
        </template>
        <el-empty v-else description="单击对象查看信息，双击打开" :image-size="58" />
      </aside>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Coin, Grid, Refresh, Search, Share } from '@element-plus/icons-vue'
import type {
  EnterpriseMetadataCatalog,
  EnterpriseMetadataTable,
} from '@/api/ent'

const props = defineProps<{
  catalog: EnterpriseMetadataCatalog | null
  loading?: boolean
  error?: string | null
}>()

defineEmits<{
  refresh: []
  'open-table': [table: EnterpriseMetadataTable]
  'open-sql': [table: EnterpriseMetadataTable]
  'open-er': [schema: string]
}>()

const search = ref('')
const schemaFilter = ref('')
const selected = ref<EnterpriseMetadataTable | null>(null)

const schemaCounts = computed<Record<string, number>>(() => {
  const counts: Record<string, number> = {}
  for (const table of props.catalog?.tables || []) {
    counts[table.schema] = (counts[table.schema] || 0) + 1
  }
  return counts
})

const filteredTables = computed(() => {
  const keyword = search.value.trim().toLocaleLowerCase()
  return (props.catalog?.tables || []).filter(table => {
    if (schemaFilter.value && table.schema !== schemaFilter.value) return false
    if (!keyword) return true
    return table.name.toLocaleLowerCase().includes(keyword)
      || table.schema.toLocaleLowerCase().includes(keyword)
      || table.qualifiedName.toLocaleLowerCase().includes(keyword)
      || (table.remarks || '').toLocaleLowerCase().includes(keyword)
  })
})

const visibleTables = computed(() => filteredTables.value.slice(0, 500))
const selectedSchema = computed(() =>
  selected.value?.schema || schemaFilter.value || props.catalog?.schemas[0] || '',
)

watch(() => props.catalog, () => {
  search.value = ''
  schemaFilter.value = ''
  selected.value = props.catalog?.tables[0] || null
}, {
  immediate: true,
})
</script>

<style scoped>
.enterprise-db-workspace {
  display: flex;
  height: min(78vh, 850px);
  min-height: 520px;
  flex-direction: column;
  overflow: hidden;
  color: var(--color-foreground);
}

.workspace-header {
  display: flex;
  min-height: 78px;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-panel-header);
}

.source-identity,
.header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.source-icon {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  border: 1px solid color-mix(in srgb, var(--color-brand) 38%, var(--color-border));
  border-radius: 12px;
  background: color-mix(in srgb, var(--color-brand) 12%, transparent);
  color: var(--color-brand);
  font-size: 20px;
}

.workspace-kicker,
.panel-kicker {
  color: var(--color-brand);
  font-size: 9px;
  font-weight: 760;
  letter-spacing: .12em;
}

.source-name { margin-top: 2px; font-size: 17px; font-weight: 700; }
.source-meta { margin-top: 3px; color: var(--color-text-muted); font-size: 11px; }

.workspace-body {
  display: grid;
  min-height: 0;
  flex: 1;
  grid-template-columns: 220px minmax(360px, 1fr) 280px;
}

.schema-rail,
.object-panel {
  min-width: 0;
  overflow: auto;
  padding: 10px;
  background: color-mix(in srgb, var(--color-panel-header) 74%, transparent);
}

.schema-rail { border-right: 1px solid var(--color-border); }
.object-panel { border-left: 1px solid var(--color-border); }

.schema-item,
.table-row {
  width: 100%;
  border: 0;
  color: inherit;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.schema-item {
  display: flex;
  min-height: 34px;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 9px;
  border-radius: 7px;
  font-size: 11px;
}

.schema-item span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.schema-item b { color: var(--color-text-muted); font: 10px var(--font-mono); }
.schema-item:hover { background: var(--color-hover); }
.schema-item.active {
  background: var(--color-active);
  color: var(--color-brand);
}

.table-browser { display: flex; min-width: 0; flex-direction: column; overflow: hidden; }
.browser-toolbar {
  display: flex;
  min-height: 50px;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--color-border);
}
.browser-toolbar .el-input { max-width: 520px; }
.result-count { margin-left: auto; color: var(--color-text-muted); font-size: 10px; white-space: nowrap; }
.table-list { min-height: 0; flex: 1; overflow: auto; padding: 7px; }
.table-row {
  display: grid;
  min-height: 45px;
  grid-template-columns: 28px minmax(150px, 1fr) minmax(80px, .8fr) auto;
  align-items: center;
  gap: 9px;
  padding: 5px 9px;
  border-bottom: 1px solid color-mix(in srgb, var(--color-border) 65%, transparent);
}
.table-row:hover { background: var(--color-hover); }
.table-row.selected {
  border-radius: 7px;
  background: var(--color-active);
  box-shadow: inset 2px 0 var(--color-brand);
}
.type-glyph { color: var(--color-brand); font-size: 16px; }
.table-copy { min-width: 0; display: flex; flex-direction: column; }
.table-copy strong,
.table-copy small,
.table-remarks { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.table-copy strong { font: 600 12px var(--font-mono); }
.table-copy small,
.table-remarks,
.table-type { color: var(--color-text-muted); font-size: 9px; }
.table-type { padding: 2px 5px; border: 1px solid var(--color-border); border-radius: 4px; }

.object-panel h3 { overflow-wrap: anywhere; font: 650 15px var(--font-mono); }
.object-panel dl { display: grid; gap: 5px; margin: 16px 0; }
.object-panel dt { color: var(--color-text-muted); font-size: 9px; }
.object-panel dd { margin: 0 0 7px; overflow-wrap: anywhere; font-size: 11px; }
.object-panel code { font: 10px var(--font-mono); }
.object-panel .el-button { width: 100%; margin: 0 0 8px; }

@media (max-width: 980px) {
  .workspace-body { grid-template-columns: 180px 1fr; }
  .object-panel { display: none; }
}

@media (max-width: 680px) {
  .workspace-header { align-items: flex-start; flex-direction: column; }
  .workspace-body { grid-template-columns: 1fr; }
  .schema-rail { display: flex; max-height: 52px; overflow-x: auto; border-right: 0; border-bottom: 1px solid var(--color-border); }
  .schema-item { min-width: max-content; }
  .table-row { grid-template-columns: 26px 1fr auto; }
  .table-remarks { display: none; }
}
</style>
