<template>
  <div ref="editorContainer" class="sql-editor-container"></div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch, shallowRef } from 'vue'
import * as monaco from 'monaco-editor'
import editorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker'
import { useThemeStore } from '@/stores/theme'
import { useUiStore } from '@/stores/ui'
import { useConnectionStore } from '@/stores/connection'
import { metadataApi } from '@/api/metadata'
import { registerSqlDialects, getLanguageByDbType } from '@/utils/sqlLanguages'
import { formatSql } from '@/utils/sqlFormatter'

// 注册 Monaco Worker
self.MonacoEnvironment = {
  getWorker() {
    return new editorWorker()
  },
}

// === Props & Emits ===
const props = defineProps<{
  modelValue: string
  dbType?: string
  connectionId?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  execute: []
  explain: []
}>()

const themeStore = useThemeStore()

// === 状态 ===
const editorContainer = ref<HTMLElement>()
// Monaco editor 实例用 shallowRef 避免被 Vue 深度代理
const editor = shallowRef<monaco.editor.IStandaloneCodeEditor | null>(null)

// === 初始化编辑器 ===
onMounted(() => {
  if (!editorContainer.value) return

  // 注册自定义SQL方言
  registerSqlDialects()

  const language = getLanguageByDbType(props.dbType)

  editor.value = monaco.editor.create(editorContainer.value, {
    value: props.modelValue,
    language: language,
    theme: themeStore.isDark ? 'vs-dark' : 'vs',
    automaticLayout: true,
    fontSize: 14,
    fontFamily: "'JetBrains Mono', 'Fira Code', monospace",
    lineHeight: 20,
    minimap: { enabled: false },
    scrollBeyondLastLine: false,
    wordWrap: 'on',
    tabSize: 2,
    insertSpaces: true,
    lineNumbers: 'on',
    lineDecorationsWidth: 8,
    lineNumbersMinChars: 3,
    glyphMargin: false,
    folding: true,
    showFoldingControls: 'mouseover',
    renderWhitespace: 'selection',
    bracketPairColorization: { enabled: true },
    smoothScrolling: true,
    cursorBlinking: 'smooth',
    cursorSmoothCaretAnimation: 'on',
    scrollbar: {
      vertical: 'auto',
      horizontal: 'auto',
      verticalScrollbarSize: 10,
      horizontalScrollbarSize: 10,
    },
    padding: { top: 8, bottom: 8 },
  })

  // 内容变化时同步
  editor.value.onDidChangeModelContent(() => {
    const value = editor.value?.getValue() || ''
    if (value !== props.modelValue) {
      emit('update:modelValue', value)
    }
  })

  // Ctrl+Enter 执行SQL
  editor.value.addCommand(
    monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter,
    () => {
      emit('execute')
    }
  )

  // Ctrl+Shift+F 格式化 SQL
  editor.value.addCommand(
    monaco.KeyMod.CtrlCmd | monaco.KeyMod.Shift | monaco.KeyCode.KeyF,
    () => {
      formatCurrent()
    }
  )

  // Ctrl+Shift+E 执行计划
  editor.value.addCommand(
    monaco.KeyMod.CtrlCmd | monaco.KeyMod.Shift | monaco.KeyCode.KeyE,
    () => {
      emit('explain')
    }
  )

  // 注册SQL语言补全（关键字 + Schema 感知）
  registerSqlCompletion()
})

// === 外部值变化时同步到编辑器 ===
watch(() => props.modelValue, (newValue) => {
  if (editor.value && newValue !== editor.value.getValue()) {
    editor.value.setValue(newValue)
  }
})

// === 方言切换 ===
watch(() => props.dbType, (newDbType) => {
  if (editor.value) {
    const lang = getLanguageByDbType(newDbType)
    monaco.editor.setModelLanguage(editor.value.getModel()!, lang)
  }
})

// === 主题变化时切换 ===
watch(() => themeStore.isDark, (isDark) => {
  if (editor.value) {
    monaco.editor.setTheme(isDark ? 'vs-dark' : 'vs')
  }
})

// === 格式化当前 SQL ===
function formatCurrent() {
  if (!editor.value) return
  const raw = editor.value.getValue()
  if (!raw || !raw.trim()) return
  const formatted = formatSql(raw)
  const position = editor.value.getPosition()
  editor.value.setValue(formatted)
  if (position) editor.value.setPosition(position)
}

defineExpose({ format: formatCurrent })

// === SQL 关键字 ===
const SQL_KEYWORDS = [
  'SELECT', 'FROM', 'WHERE', 'AND', 'OR', 'NOT', 'IN', 'LIKE', 'BETWEEN',
  'IS', 'NULL', 'AS', 'ORDER BY', 'GROUP BY', 'HAVING', 'LIMIT', 'OFFSET',
  'INSERT', 'INTO', 'VALUES', 'UPDATE', 'SET', 'DELETE', 'CREATE', 'TABLE',
  'ALTER', 'DROP', 'INDEX', 'VIEW', 'DATABASE', 'JOIN', 'INNER', 'LEFT',
  'RIGHT', 'FULL', 'ON', 'UNION', 'ALL', 'DISTINCT', 'CASE', 'WHEN', 'THEN',
  'ELSE', 'END', 'IF', 'EXISTS', 'COUNT', 'SUM', 'AVG', 'MIN', 'MAX',
  'SHOW', 'TABLES', 'COLUMNS', 'DESCRIBE', 'EXPLAIN', 'WITH', 'RECURSIVE',
  // MaxCompute 方言
  'INSERT OVERWRITE', 'INSERT OVERWRITE TABLE', 'PARTITION', 'OVERWRITE',
  'LIFECYCLE',
]

// === Schema 感知补全缓存 ===
interface TableCache {
  tables: string[]
  schema: string | null
  fetchedAt: number
}
const tableCache = new Map<string, TableCache>()
const CACHE_TTL = 60_000 // 1 分钟

let completionRegistered = false

function registerSqlCompletion() {
  if (completionRegistered) return
  completionRegistered = true

  monaco.languages.registerCompletionItemProvider('sql', {
    triggerCharacters: [' ', '.', '.'],
    provideCompletionItems: async (model, position) => {
      const word = model.getWordUntilPosition(position)
      const range = {
        startLineNumber: position.lineNumber,
        endLineNumber: position.lineNumber,
        startColumn: word.startColumn,
        endColumn: word.endColumn,
      }

      // 1. 关键字补全（始终提供）
      const suggestions: monaco.languages.CompletionItem[] = SQL_KEYWORDS.map(keyword => ({
        label: keyword,
        kind: monaco.languages.CompletionItemKind.Keyword,
        insertText: keyword,
        detail: 'SQL 关键字',
        range,
      }))

      // 2. Schema 感知补全（需要激活连接）
      const uiStore = useUiStore()
      const connectionStore = useConnectionStore()
      const connId = props.connectionId || connectionStore.activeConnectionId
      if (!connId) {
        return { suggestions }
      }

      // 判断是否为 "." 触发的列补全
      const lineUpToCursor = model.getValueInRange({
        startLineNumber: position.lineNumber,
        startColumn: 1,
        endLineNumber: position.lineNumber,
        endColumn: position.column,
      })
      const dotMatch = lineUpToCursor.match(/([A-Za-z_][\w]*)\s*\.\s*$/)

      if (dotMatch) {
        // 列补全：根据表名加载列
        const tableName = dotMatch[1]
        const schema = uiStore.currentDatabase || null
        try {
          const cols = await metadataApi.getTableColumns(connId, schema, tableName)
          for (const col of cols) {
            suggestions.push({
              label: col.name,
              kind: monaco.languages.CompletionItemKind.Field,
              insertText: col.name,
              detail: `${col.typeName || 'column'} · ${col.tableName || ''}`,
              range,
            } as monaco.languages.CompletionItem)
          }
        } catch {
          // 忽略（表名可能无效）
        }
        return { suggestions }
      }

      // 表名补全：加载当前数据库下的表
      const schema = uiStore.currentDatabase || undefined
      const cacheKey = `${connId}:${schema || ''}`
      let cache = tableCache.get(cacheKey)
      if (!cache || Date.now() - cache.fetchedAt > CACHE_TTL) {
        try {
          const nodes = await metadataApi.getTreeNodes(connId, schema)
          const tables = nodes
            .filter(n => n.type === 'TABLE' || n.type === 'VIEW' || n.type === 'COLLECTION')
            .map(n => n.name)
          cache = { tables, schema: schema || null, fetchedAt: Date.now() }
          tableCache.set(cacheKey, cache)
        } catch {
          cache = { tables: [], schema: schema || null, fetchedAt: Date.now() }
        }
      }

      for (const t of cache.tables) {
        suggestions.push({
          label: t,
          kind: monaco.languages.CompletionItemKind.Class,
          insertText: t,
          detail: '表',
          range,
        } as monaco.languages.CompletionItem)
      }

      return { suggestions }
    },
  })
}

// === 销毁 ===
onUnmounted(() => {
  editor.value?.dispose()
})
</script>

<style scoped>
.sql-editor-container {
  width: 100%;
  height: 100%;
  overflow: hidden;
}

:deep(.monaco-editor) {
  --vs-editor-background: var(--color-panel);
}
</style>
