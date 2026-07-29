/**
 * UI 状态管理 Store
 * 管理界面交互状态：选中的树节点、面板可见性等
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { TreeNode } from '@/types/metadata'
import type { ColumnMetadata } from '@/types/metadata'
import type { DriverCapability } from '@/types/driver'
import type { ConnectionDTO } from '@/types/connection'
import { metadataApi } from '@/api/metadata'

export const useUiStore = defineStore('ui', () => {
    // === State ===

    /** 当前选中的导航树节点 */
    const selectedNode = ref<TreeNode | null>(null)

    /** 属性面板是否可见 */
    const sidePropsVisible = ref(true)

    /** 底部面板是否可见 */
    const bottomPanelVisible = ref(true)

    /** 底部面板当前激活的 tab */
    const bottomPanelTab = ref<'results' | 'chart' | 'messages' | 'history'>('results')

    /** 连接对话框是否可见 */
    const connectionDialogVisible = ref(false)

    /** 当前正在编辑的连接（新建时为 null） */
    const editingConnection = ref<ConnectionDTO | null>(null)

    /** 当前活跃连接的能力描述（驱动 UI 显隐） */
    const capabilities = ref<DriverCapability | null>(null)

    /** 当前加载的列信息 */
    const columns = ref<ColumnMetadata[]>([])
    const columnsLoading = ref(false)

    /** 当前加载的 DDL */
    const ddl = ref<string>('')
    const ddlLoading = ref(false)

    /** 当前选中的数据库（用于查询时的默认数据库上下文） */
    const currentDatabase = ref<string | null>(null)

    /** 当前连接可用的数据库列表 */
    const databases = ref<string[]>([])

    // === Actions ===

    /** 设置选中的树节点 */
    function setSelectedNode(node: TreeNode | null) {
        selectedNode.value = node
    }

    /** 切换属性面板可见性 */
    function toggleSideProps() {
        sidePropsVisible.value = !sidePropsVisible.value
    }

    /** 切换底部面板可见性 */
    function toggleBottomPanel() {
        bottomPanelVisible.value = !bottomPanelVisible.value
    }

    /** 打开连接对话框（传入连接对象则进入编辑模式，否则新建） */
    function openConnectionDialog(connection: ConnectionDTO | null = null) {
        editingConnection.value = connection
        connectionDialogVisible.value = true
    }

    /** 关闭连接对话框 */
    function closeConnectionDialog() {
        connectionDialogVisible.value = false
        editingConnection.value = null
    }

    /** 拉取并设置当前连接的能力描述 */
    async function loadCapabilities(connectionId: string) {
        try {
            capabilities.value = await metadataApi.getCapabilities(connectionId)
        } catch (e: any) {
            capabilities.value = null
        }
    }

    /** 清空能力描述（断开连接时调用） */
    function clearCapabilities() {
        capabilities.value = null
    }

    /** 设置底部面板激活的 tab */
    function setBottomPanelTab(tab: 'results' | 'chart' | 'messages' | 'history') {
        bottomPanelTab.value = tab
    }

    /** 打开历史面板 */
    function openHistoryTab() {
        bottomPanelVisible.value = true
        bottomPanelTab.value = 'history'
    }

    /** 设置列信息 */
    function setColumns(cols: ColumnMetadata[]) {
        columns.value = cols
    }

    /** 设置 DDL */
    function setDdl(ddlText: string) {
        ddl.value = ddlText
    }

    /** 加载数据库列表 */
    async function loadDatabases(connectionId: string) {
        try {
            const list = await metadataApi.getDatabases(connectionId)
            databases.value = list
            // 如果当前没有选中的数据库，且列表不为空，选择第一个
            if (!currentDatabase.value && list.length > 0) {
                currentDatabase.value = list[0]
            }
            // 如果当前选中的数据库不在列表中，重置
            if (currentDatabase.value && !list.includes(currentDatabase.value)) {
                currentDatabase.value = list.length > 0 ? list[0] : null
            }
        } catch (e: any) {
            databases.value = []
        }
    }

    /** 设置当前数据库 */
    function setCurrentDatabase(db: string | null) {
        currentDatabase.value = db
    }

    /** 清空数据库列表（断开连接时调用） */
    function clearDatabases() {
        databases.value = []
        currentDatabase.value = null
    }

    return {
        selectedNode,
        sidePropsVisible,
        bottomPanelVisible,
        bottomPanelTab,
        connectionDialogVisible,
        editingConnection,
        capabilities,
        columns,
        columnsLoading,
        ddl,
        ddlLoading,
        currentDatabase,
        databases,
        setSelectedNode,
        toggleSideProps,
        toggleBottomPanel,
        setBottomPanelTab,
        openHistoryTab,
        openConnectionDialog,
        closeConnectionDialog,
        loadCapabilities,
        clearCapabilities,
        setColumns,
        setDdl,
        loadDatabases,
        setCurrentDatabase,
        clearDatabases,
    }
})
