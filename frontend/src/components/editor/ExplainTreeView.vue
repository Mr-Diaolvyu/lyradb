<template>
  <div class="explain-tree">
    <div class="explain-toolbar">
      <span class="explain-title">执行计划</span>
      <el-radio-group v-model="viewMode" size="small">
        <el-radio-button value="tree">树形</el-radio-button>
        <el-radio-button value="table">原始表格</el-radio-button>
      </el-radio-group>
    </div>

    <div v-if="viewMode === 'tree'" class="tree-body">
      <el-tree
        v-if="treeData.length > 0"
        :data="treeData"
        :props="{ label: 'label', children: 'children' }"
        default-expand-all
        :expand-on-click-node="false"
      >
        <template #default="{ data }">
          <div class="tree-node">
            <span class="node-label">{{ data.label }}</span>
            <span v-if="data.detail" class="node-detail">{{ data.detail }}</span>
          </div>
        </template>
      </el-tree>
      <el-empty v-else description="无法解析执行计划，请切换到原始表格视图" :image-size="60" />
    </div>

    <div v-else class="table-body">
      <DataTable :columns="result.columns" :rows="result.rows" />
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 执行计划可视化树
 *
 * 将 EXPLAIN 结果解析为树形展示，支持两类输出格式：
 * 1. 文本型计划（PostgreSQL/ClickHouse 等）：单列多行，按缩进与 "->" 前缀解析层级
 * 2. 表格型计划（MySQL 等）：多列行集，按 id 列分层（id 越大层级越深）
 */
import { ref, computed } from 'vue'
import type { QueryResult } from '@/types/metadata'
import DataTable from '@/components/editor/DataTable.vue'

const props = defineProps<{
  result: QueryResult
}>()

const viewMode = ref<'tree' | 'table'>('tree')

interface ExplainNode {
  label: string
  detail?: string
  children: ExplainNode[]
}

/** 文本型计划解析：按缩进深度构造树 */
function parseTextPlan(lines: string[]): ExplainNode[] {
  const roots: ExplainNode[] = []
  // 栈内元素：[缩进深度, 节点]
  const stack: Array<{ depth: number; node: ExplainNode }> = []

  for (const raw of lines) {
    if (!raw || !raw.trim()) continue
    const indentMatch = raw.match(/^(\s*)/)
    let depth = indentMatch ? indentMatch[1].length : 0
    let text = raw.trim()
    // PG 风格 "->  Hash Join ..." 前缀
    if (text.startsWith('->')) {
      text = text.replace(/^->\s*/, '')
      depth += 2
    }
    // 纯附加信息行（如 "Hash Cond: ..."、"Filter: ..."）挂到最近节点的 detail
    const isAttr = /^[A-Za-z ]+:\s/.test(text) && stack.length > 0
    if (isAttr) {
      const top = stack[stack.length - 1].node
      top.detail = top.detail ? `${top.detail}; ${text}` : text
      continue
    }

    const node: ExplainNode = { label: text, children: [] }
    while (stack.length > 0 && stack[stack.length - 1].depth >= depth) {
      stack.pop()
    }
    if (stack.length === 0) {
      roots.push(node)
    } else {
      stack[stack.length - 1].node.children.push(node)
    }
    stack.push({ depth, node })
  }
  return roots
}

/** 表格型计划解析（MySQL EXPLAIN）：按 id 列分层 */
function parseTabularPlan(columns: string[], rows: Record<string, any>[]): ExplainNode[] {
  const idCol = columns.find(c => c.toLowerCase() === 'id')
  const labelCols = ['table', 'select_type', 'type', 'operation'].filter(c =>
    columns.some(col => col.toLowerCase() === c)
  )
  if (!idCol || labelCols.length === 0) return []

  const findCol = (name: string) => columns.find(c => c.toLowerCase() === name)

  const roots: ExplainNode[] = []
  const levelLast = new Map<number, ExplainNode>()

  for (const row of rows) {
    const id = Number(row[idCol] ?? 1) || 1
    const table = row[findCol('table') || ''] ?? ''
    const selType = row[findCol('select_type') || ''] ?? ''
    const accessType = row[findCol('type') || ''] ?? ''
    const rowsEst = row[findCol('rows') || ''] ?? ''
    const key = row[findCol('key') || ''] ?? ''
    const extra = row[findCol('extra') || 'Extra'] ?? row['Extra'] ?? ''

    const label = [selType, table && `on ${table}`, accessType && `(${accessType})`]
      .filter(Boolean).join(' ')
    const detailParts: string[] = []
    if (key) detailParts.push(`key=${key}`)
    if (rowsEst) detailParts.push(`rows≈${rowsEst}`)
    if (extra) detailParts.push(String(extra))

    const node: ExplainNode = {
      label: label || `id=${id}`,
      detail: detailParts.join(' · ') || undefined,
      children: [],
    }

    if (id <= 1 || !levelLast.has(id - 1)) {
      roots.push(node)
    } else {
      levelLast.get(id - 1)!.children.push(node)
    }
    levelLast.set(id, node)
  }
  return roots
}

const treeData = computed<ExplainNode[]>(() => {
  const { columns, rows } = props.result
  if (!columns || !rows || rows.length === 0) return []

  // 单列输出 → 文本型计划
  if (columns.length === 1) {
    const col = columns[0]
    const lines = rows.map(r => String(r[col] ?? ''))
    return parseTextPlan(lines)
  }
  // 多列输出 → 表格型计划
  return parseTabularPlan(columns, rows)
})
</script>

<style scoped>
.explain-tree {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.explain-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-2) var(--space-3);
  border-bottom: 1px solid var(--color-border);
  flex-shrink: 0;
}

.explain-title {
  font-size: var(--text-label);
  font-weight: 600;
  color: var(--color-text-muted);
}

.tree-body {
  flex: 1;
  overflow: auto;
  padding: var(--space-2) var(--space-3);
}

.table-body {
  flex: 1;
  overflow: auto;
}

.tree-node {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
  overflow: hidden;
}

.node-label {
  font-family: var(--font-mono);
  font-size: var(--text-code);
  color: var(--color-foreground);
  white-space: nowrap;
}

.node-detail {
  font-size: 11px;
  color: var(--color-text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
