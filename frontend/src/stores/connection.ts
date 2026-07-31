/**
 * 连接管理 Store
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { connectionApi } from '@/api/connection'
import { driverApi } from '@/api/driver'
import type { ConnectionDTO, TestConnectionRequest, ImportResult } from '@/types/connection'
import type { DriverInfo, DatabaseType } from '@/types/driver'
import { useUiStore } from '@/stores/ui'

export const useConnectionStore = defineStore('connection', () => {
    // === State ===
    const connections = ref<ConnectionDTO[]>([])
    const drivers = ref<DriverInfo[]>([])
    const dbTypes = ref<DatabaseType[]>([])
    const activeConnectionId = ref<string | null>(null)
    const loading = ref(false)
    const lastConnectionMessage = ref('')

    // === Getters ===
    const activeConnection = computed(() =>
        connections.value.find(c => c.id === activeConnectionId.value)
    )

    const connectedConnections = computed(() =>
        connections.value.filter(c => c.status === 'CONNECTED')
    )

    // === Actions ===

    /** 加载连接列表 */
    async function loadConnections() {
        loading.value = true
        try {
            connections.value = await connectionApi.list()
        } catch (e) {
        } finally {
            loading.value = false
        }
    }

    /** 加载驱动配置 */
    async function loadDrivers() {
        try {
            drivers.value = await driverApi.getAllDrivers()
            dbTypes.value = await driverApi.getSupportedTypes()
        } catch (e) {
        }
    }

    /** 获取指定数据库的驱动配置 */
    function getDriverByType(dbType: string): DriverInfo | undefined {
        return drivers.value.find(d => d.dbType === dbType)
    }

    /** 创建连接 */
    async function createConnection(dto: Partial<ConnectionDTO>): Promise<ConnectionDTO> {
        const created = await connectionApi.create(dto)
        connections.value.push(created)
        return created
    }

    /** 更新连接 */
    async function updateConnection(id: string, dto: Partial<ConnectionDTO>): Promise<void> {
        const updated = await connectionApi.update(id, dto)
        const idx = connections.value.findIndex(c => c.id === id)
        if (idx >= 0) {
            connections.value[idx] = updated
        }
    }

    /** 删除连接 */
    async function deleteConnection(id: string): Promise<void> {
        await connectionApi.remove(id)
        connections.value = connections.value.filter(c => c.id !== id)
        if (activeConnectionId.value === id) {
            activeConnectionId.value = null
        }
    }

    /** 测试连接 */
    async function testConnection(request: TestConnectionRequest) {
        return await connectionApi.test(request)
    }

    /** 建立连接 */
    async function connect(id: string): Promise<boolean> {
        lastConnectionMessage.value = ''
        try {
            const result = await connectionApi.connect(id)
            lastConnectionMessage.value = result.message || (result.success ? '连接成功' : '数据库连接失败')
            if (result.success) {
                const conn = connections.value.find(c => c.id === id)
                if (conn) conn.status = 'CONNECTED'
                activeConnectionId.value = id
                // 拉取能力描述，用于驱动 UI 显隐（编辑入口/只读提示等）
                const uiStore = useUiStore()
                await uiStore.loadCapabilities(id)
            }
            return result.success
        } catch (e: any) {
            lastConnectionMessage.value =
                e?.response?.data?.message ||
                e?.response?.data?.error ||
                e?.message ||
                '数据库连接失败'
            return false
        }
    }

    /** 断开连接 */
    async function disconnect(id: string): Promise<void> {
        await connectionApi.disconnect(id)
        const conn = connections.value.find(c => c.id === id)
        if (conn) conn.status = 'DISCONNECTED'
        if (activeConnectionId.value === id) {
            activeConnectionId.value = null
            const uiStore = useUiStore()
            uiStore.clearCapabilities()
        }
    }

    /** 切换收藏 */
    async function toggleFavorite(id: string): Promise<void> {
        const updated = await connectionApi.toggleFavorite(id)
        const idx = connections.value.findIndex(c => c.id === id)
        if (idx >= 0) {
            connections.value[idx] = updated
        }
        // Re-sort: favorites first
        connections.value.sort((a, b) => {
            const aFav = a.favorite === true
            const bFav = b.favorite === true
            if (aFav !== bFav) return aFav ? -1 : 1
            return 0
        })
    }

    /** 复制连接 */
    async function duplicateConnection(id: string): Promise<void> {
        const created = await connectionApi.duplicate(id)
        connections.value.push(created)
    }

    /** 导出连接配置 */
    async function exportConnections(): Promise<ConnectionDTO[]> {
        return await connectionApi.export()
    }

    /** 导入连接配置 */
    async function importConnections(dtos: ConnectionDTO[]): Promise<ImportResult> {
        const result = await connectionApi.import(dtos)
        await loadConnections() // Refresh list
        return result
    }

    return {
        connections,
        drivers,
        dbTypes,
        activeConnectionId,
        loading,
        lastConnectionMessage,
        activeConnection,
        connectedConnections,
        loadConnections,
        loadDrivers,
        getDriverByType,
        createConnection,
        updateConnection,
        deleteConnection,
        testConnection,
        connect,
        disconnect,
        toggleFavorite,
        duplicateConnection,
        exportConnections,
        importConnections,
    }
})
