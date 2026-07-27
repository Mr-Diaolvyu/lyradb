/**
 * 编辑器 Store
 * 管理工作区 Tab 页：SQL 编辑器 Tab 和 表详情 Tab
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { queryApi, metadataApi } from '@/api/metadata'
import type { QueryResult, ColumnMetadata, SqlReviewFinding } from '@/types/metadata'
import type { BackgroundTask } from '@/types/task'
import { useUiStore } from '@/stores/ui'
import { useTaskStore } from '@/stores/tasks'
import { useConnectionStore } from '@/stores/connection'

/** Tab 类型 */
export type TabType = 'sql' | 'table-detail'

/** Tab 基础接口 */
interface TabBase {
    id: string
    title: string
    connectionId: string
    type: TabType
}

/** SQL 编辑器 Tab */
export interface SqlTab extends TabBase {
    type: 'sql'
    sql: string
    result: QueryResult | null
    loading: boolean
    error: string | null
    /** 当前结果是否来自 EXPLAIN 执行计划 */
    isExplain?: boolean
}

/** 表详情 Tab */
export interface TableDetailTab extends TabBase {
    type: 'table-detail'
    tableName: string
    schema: string | null
    columns: ColumnMetadata[]
    ddl: string
    loading: boolean
}

/** 联合 Tab 类型 */
export type Tab = SqlTab | TableDetailTab

export const useEditorStore = defineStore('editor', () => {
    // === State ===
    const tabs = ref<Tab[]>([])
    const activeTabId = ref<string | null>(null)

    // === Getters ===
    const activeTab = computed(() =>
        tabs.value.find(t => t.id === activeTabId.value)
    )

    const activeSqlTab = computed(() => {
        const tab = tabs.value.find(t => t.id === activeTabId.value)
        return tab?.type === 'sql' ? tab : null
    })

    const tabCount = computed(() => tabs.value.length)

    // === Actions ===

    /** 新建 SQL 编辑器 Tab */
    function createTab(connectionId: string, title: string = 'New Query'): string {
        const id = `tab-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`
        const tab: SqlTab = {
            id,
            title,
            sql: '',
            connectionId,
            type: 'sql',
            result: null,
            loading: false,
            error: null,
        }
        tabs.value.push(tab)
        activeTabId.value = id
        return id
    }

    /** 新建表详情 Tab */
    async function createTableDetailTab(
        connectionId: string,
        tableName: string,
        schema: string | null
    ): Promise<string> {
        // 检查是否已存在同表 Tab
        const existing = tabs.value.find(t =>
            t.type === 'table-detail' &&
            t.connectionId === connectionId &&
            t.tableName === tableName &&
            t.schema === schema
        )
        if (existing) {
            activeTabId.value = existing.id
            return existing.id
        }

        const id = `tab-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`
        const fullTitle = schema ? `${schema}.${tableName}` : tableName
        const tab: TableDetailTab = {
            id,
            title: fullTitle,
            connectionId,
            type: 'table-detail',
            tableName,
            schema,
            columns: [],
            ddl: '',
            loading: true,
        }
        tabs.value.push(tab)
        activeTabId.value = id

        // 异步加载列信息和 DDL
        try {
            const [columns, ddl] = await Promise.all([
                metadataApi.getTableColumns(connectionId, schema, tableName),
                metadataApi.getTableDDL(connectionId, schema, tableName).catch(() => ''),
            ])
            const targetTab = tabs.value.find(t => t.id === id) as TableDetailTab | undefined
            if (targetTab) {
                targetTab.columns = columns
                targetTab.ddl = ddl
                targetTab.loading = false
            }
        } catch (e: any) {
            const targetTab = tabs.value.find(t => t.id === id) as TableDetailTab | undefined
            if (targetTab) {
                targetTab.loading = false
            }
            console.error('加载表详情失败:', e)
        }

        return id
    }

    /** 关闭 Tab */
    function closeTab(id: string) {
        const idx = tabs.value.findIndex(t => t.id === id)
        if (idx >= 0) {
            tabs.value.splice(idx, 1)
            if (activeTabId.value === id) {
                activeTabId.value = tabs.value.length > 0
                    ? (tabs.value[Math.max(0, idx - 1)]?.id ?? null)
                    : null
            }
        }
    }

    /** 更新 Tab SQL 内容（仅 SQL Tab） */
    function updateSql(tabId: string, sql: string) {
        const tab = tabs.value.find(t => t.id === tabId)
        if (tab && tab.type === 'sql') {
            tab.sql = sql
        }
    }

    /** 弹出 SQL 审核拦截确认框，确认"仍要执行"返回 true */
    async function confirmReviewFindings(findings: SqlReviewFinding[]): Promise<boolean> {
        const severityLabel: Record<string, string> = { HIGH: '高危', MEDIUM: '中危', LOW: '提醒' }
        const lines = findings
            .map(f => `<li><b>[${severityLabel[f.severity] || f.severity}]</b> ${f.message}</li>`)
            .join('')
        try {
            await ElMessageBox.confirm(
                `<div>SQL 审核命中以下规则：<ul style="margin:8px 0 0 18px;padding:0">${lines}</ul></div>`,
                'SQL 审核拦截',
                {
                    confirmButtonText: '仍要执行',
                    cancelButtonText: '取消',
                    type: 'warning',
                    dangerouslyUseHTMLString: true,
                    confirmButtonClass: 'el-button--danger',
                }
            )
            return true
        } catch {
            return false
        }
    }

    /** 执行 SQL（仅 SQL Tab）；命中审核拦截时弹确认框，确认后携 force 重发 */
    async function executeSql(tabId: string, force = false) {
        const tab = tabs.value.find(t => t.id === tabId)
        if (!tab || tab.type !== 'sql' || !tab.sql.trim()) return

        tab.loading = true
        tab.error = null
        tab.isExplain = false
        try {
            const uiStore = useUiStore()
            const result = await queryApi.executeQuery(tab.connectionId, tab.sql, uiStore.currentDatabase || undefined, force)
            if (result.reviewBlocked && result.reviewFindings?.length) {
                tab.loading = false
                if (await confirmReviewFindings(result.reviewFindings)) {
                    return executeSql(tabId, true)
                }
                tab.result = null
                return
            }
            tab.result = result
        } catch (e: any) {
            tab.error = e.message || '执行失败'
            tab.result = null
        } finally {
            tab.loading = false
        }
    }

    /** 取消正在执行的查询（仅 SQL Tab） */
    async function cancelQuery(tabId: string) {
        const tab = tabs.value.find(t => t.id === tabId)
        if (!tab || tab.type !== 'sql' || !tab.loading) return
        try {
            await queryApi.cancelQuery(tab.connectionId)
        } catch (e: any) {
            console.warn('取消查询失败:', e)
        }
    }

    /** 执行 EXPLAIN（执行计划），仅 SQL Tab */
    async function explainSql(tabId: string) {
        const tab = tabs.value.find(t => t.id === tabId)
        if (!tab || tab.type !== 'sql' || !tab.sql.trim()) return

        const baseSql = tab.sql.trim().replace(/;$/, '')
        // 已是 EXPLAIN 则不重复前缀
        const explainSqlText = /^EXPLAIN\b/i.test(baseSql) ? baseSql : `EXPLAIN ${baseSql}`

        tab.loading = true
        tab.error = null
        try {
            const uiStore = useUiStore()
            tab.result = await queryApi.executeQuery(tab.connectionId, explainSqlText, uiStore.currentDatabase || undefined)
            tab.isExplain = true
            uiStore.setBottomPanelTab('results')
        } catch (e: any) {
            tab.error = e.message || '执行计划失败'
            tab.result = null
        } finally {
            tab.loading = false
        }
    }

    /** 将当前 SQL 转入后台执行（迭代二 E1）；同样受 SQL 审核拦截约束 */
    async function runInBackground(tabId: string, force = false) {
        const tab = tabs.value.find(t => t.id === tabId)
        if (!tab || tab.type !== 'sql' || !tab.sql.trim()) return

        // 审核拦截由后端异步判定：命中时任务置为 ERROR 并提示回到编辑器确认后重新提交
        const taskStore = useTaskStore()
        const connectionStore = useConnectionStore()
        const uiStore = useUiStore()
        const connectionName = connectionStore.connections.find(c => c.id === tab.connectionId)?.name
        try {
            await taskStore.submit({
                connectionId: tab.connectionId,
                connectionName,
                sql: tab.sql,
                defaultDatabase: uiStore.currentDatabase || undefined,
                force,
            })
            ElMessage.success('已转入后台执行，完成后将通知')
        } catch (e: any) {
            ElMessage.error(e.message || '提交后台任务失败')
        }
    }

    /** 回看后台任务结果：新建 Tab 展示（迭代二 E1） */
    async function openTaskResult(task: BackgroundTask) {
        const taskStore = useTaskStore()
        try {
            const result = await taskStore.loadResult(task.id)
            const id = createTab(task.connectionId, `后台结果-${task.connectionName}`)
            const tab = tabs.value.find(t => t.id === id)
            if (tab && tab.type === 'sql') {
                tab.sql = task.sql
                tab.result = result
            }
            const uiStore = useUiStore()
            uiStore.setBottomPanelTab('results')
        } catch (e: any) {
            ElMessage.error(e.message || '结果已失效')
        }
    }

    /** 设置活跃 Tab */
    function setActiveTab(id: string) {
        activeTabId.value = id
    }

    return {
        tabs,
        activeTabId,
        activeTab,
        activeSqlTab,
        tabCount,
        createTab,
        createTableDetailTab,
        closeTab,
        updateSql,
        executeSql,
        cancelQuery,
        explainSql,
        runInBackground,
        openTaskResult,
        setActiveTab,
    }
})
