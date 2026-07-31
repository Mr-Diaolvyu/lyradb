/**
 * 编辑器 Store
 * 管理工作区 Tab 页：SQL 编辑器 Tab 和 表详情 Tab
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { queryApi, metadataApi } from '@/api/metadata'
import type { QueryResult, ColumnMetadata, SqlReviewFinding, TableInspection } from '@/types/metadata'
import type { BackgroundTask } from '@/types/task'
import { useUiStore } from '@/stores/ui'
import { useTaskStore } from '@/stores/tasks'
import { useConnectionStore } from '@/stores/connection'
import { LatestRequestGate } from '@/utils/requestControl'
import { buildReviewFindingsMessage } from '@/utils/reviewFindings'

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
    objectType: string
    columns: ColumnMetadata[]
    ddl: string
    inspection: TableInspection | null
    loading: boolean
    error: string | null
}

/** 联合 Tab 类型 */
export type Tab = SqlTab | TableDetailTab

export const useEditorStore = defineStore('editor', () => {
    // === State ===
    const tabs = ref<Tab[]>([])
    const activeTabId = ref<string | null>(null)
    /** 每个标签页的请求代次；取消或新请求会使旧响应失效。 */
    const requestGate = new LatestRequestGate()

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
        schema: string | null,
        objectType = 'TABLE',
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
            objectType,
            columns: [],
            ddl: '',
            inspection: null,
            loading: true,
            error: null,
        }
        tabs.value.push(tab)
        activeTabId.value = id

        await refreshTableDetailTab(id)
        return id
    }

    /** 刷新表工作台；请求代次保证关闭或重复刷新后旧响应不会回写。 */
    async function refreshTableDetailTab(id: string): Promise<void> {
        const tab = tabs.value.find(t => t.id === id)
        if (!tab || tab.type !== 'table-detail') return
        const version = requestGate.begin(id)
        tab.loading = true
        tab.error = null
        try {
            const inspection = await metadataApi.inspectTable(
                tab.connectionId,
                tab.schema,
                tab.tableName,
                tab.objectType,
                200,
            )
            if (!requestGate.isCurrent(id, version)) return
            const targetTab = tabs.value.find(t => t.id === id) as TableDetailTab | undefined
            if (targetTab) {
                targetTab.inspection = inspection
                targetTab.columns = inspection.columns
                targetTab.ddl = inspection.ddl
                targetTab.loading = false
            }
        } catch (e: any) {
            if (!requestGate.isCurrent(id, version)) return
            const targetTab = tabs.value.find(t => t.id === id) as TableDetailTab | undefined
            if (targetTab) {
                targetTab.loading = false
                targetTab.error = e.message || '表工作台加载失败'
            }
        }
    }

    /** 关闭 Tab */
    function closeTab(id: string) {
        requestGate.invalidate(id)
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
        try {
            await ElMessageBox.confirm(
                buildReviewFindingsMessage(findings),
                'SQL 审核拦截',
                {
                    confirmButtonText: '仍要执行',
                    cancelButtonText: '取消',
                    type: 'warning',
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
        if (!tab || tab.type !== 'sql' || !tab.sql.trim() || tab.loading) return

        const requestVersion = requestGate.begin(tabId)
        tab.loading = true
        tab.error = null
        tab.isExplain = false
        try {
            const uiStore = useUiStore()
            const result = await queryApi.executeQuery(tab.connectionId, tab.sql, uiStore.currentDatabase || undefined, force)
            if (!requestGate.isCurrent(tabId, requestVersion)) return
            if (result.reviewBlocked && result.reviewFindings?.length) {
                tab.loading = false
                const confirmed = await confirmReviewFindings(result.reviewFindings)
                if (confirmed && requestGate.isCurrent(tabId, requestVersion)) {
                    return executeSql(tabId, true)
                }
                if (requestGate.isCurrent(tabId, requestVersion)) tab.result = null
                return
            }
            tab.result = result
        } catch (e: any) {
            if (!requestGate.isCurrent(tabId, requestVersion)) return
            tab.error = e.message || '执行失败'
            tab.result = null
        } finally {
            if (requestGate.isCurrent(tabId, requestVersion)) tab.loading = false
        }
    }

    /** 取消正在执行的查询（仅 SQL Tab） */
    async function cancelQuery(tabId: string) {
        const tab = tabs.value.find(t => t.id === tabId)
        if (!tab || tab.type !== 'sql' || !tab.loading) return
        // 先使在途响应失效，避免取消与完成同时发生时旧结果回写。
        requestGate.invalidate(tabId)
        tab.loading = false
        try {
            await queryApi.cancelQuery(tab.connectionId)
        } catch {
            ElMessage.warning('取消请求未被服务端确认')
        }
    }

    /** 执行 EXPLAIN（执行计划），仅 SQL Tab */
    async function explainSql(tabId: string) {
        const tab = tabs.value.find(t => t.id === tabId)
        if (!tab || tab.type !== 'sql' || !tab.sql.trim() || tab.loading) return

        const requestVersion = requestGate.begin(tabId)
        const baseSql = tab.sql.trim().replace(/;$/, '')
        // 已是 EXPLAIN 则不重复前缀
        const explainSqlText = /^EXPLAIN\b/i.test(baseSql) ? baseSql : `EXPLAIN ${baseSql}`

        tab.loading = true
        tab.error = null
        try {
            const uiStore = useUiStore()
            const result = await queryApi.executeQuery(tab.connectionId, explainSqlText, uiStore.currentDatabase || undefined)
            if (!requestGate.isCurrent(tabId, requestVersion)) return
            tab.result = result
            tab.isExplain = true
            uiStore.setBottomPanelTab('results')
        } catch (e: any) {
            if (!requestGate.isCurrent(tabId, requestVersion)) return
            tab.error = e.message || '执行计划失败'
            tab.result = null
        } finally {
            if (requestGate.isCurrent(tabId, requestVersion)) tab.loading = false
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
        refreshTableDetailTab,
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
