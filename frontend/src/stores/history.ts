/**
 * 查询历史 Store
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { historyApi } from '@/api/history'
import type { QueryHistory } from '@/types/history'
import { useEditorStore } from '@/stores/editor'

export const useHistoryStore = defineStore('history', () => {
    const items = ref<QueryHistory[]>([])
    const loading = ref(false)
    const keyword = ref('')
    const favoriteOnly = ref(false)
    /** 是否处于搜索模式 */
    const searching = computed(() => keyword.value.trim().length > 0)

    /** 当前激活连接 ID（用于过滤展示） */
    const scopeConnectionId = ref<string | null>(null)

    const filtered = computed<QueryHistory[]>(() => items.value)

    /** 加载历史列表（按当前 scope 与收藏过滤） */
    async function load() {
        loading.value = true
        try {
            if (keyword.value.trim()) {
                items.value = await historyApi.search(keyword.value.trim())
            } else {
                items.value = await historyApi.list({
                    connectionId: scopeConnectionId.value || undefined,
                    favorite: favoriteOnly.value,
                })
            }
        } catch (e) {
            items.value = []
        } finally {
            loading.value = false
        }
    }

    /** 设置作用域连接（null 表示全部） */
    function setScope(connectionId: string | null) {
        scopeConnectionId.value = connectionId
        if (!keyword.value) {
            load()
        }
    }

    /** 设置关键字并搜索 */
    async function setKeyword(kw: string) {
        keyword.value = kw
        await load()
    }

    /** 切换仅看收藏 */
    async function toggleFavoriteOnly() {
        favoriteOnly.value = !favoriteOnly.value
        if (!keyword.value) {
            await load()
        }
    }

    /** 切换某条收藏状态 */
    async function toggleFavorite(id: string) {
        try {
            await historyApi.toggleFavorite(id)
            const it = items.value.find(i => i.id === id)
            if (it) it.favorite = !it.favorite
        } catch (e) {
        }
    }

    /** 删除单条 */
    async function remove(id: string) {
        await historyApi.remove(id)
        items.value = items.value.filter(i => i.id !== id)
    }

    /** 更新某条标签 */
    async function updateTags(id: string, tags: string) {
        const updated = await historyApi.updateTags(id, tags)
        const it = items.value.find(i => i.id === id)
        if (it) it.tags = updated.tags ?? null
    }

    /** 清空（当前 scope） */
    async function clear() {
        await historyApi.clear(scopeConnectionId.value || undefined)
        await load()
    }

    /** 将历史 SQL 插入到当前激活 SQL Tab */
    function insertToActiveTab(sql: string) {
        const editorStore = useEditorStore()
        const tab = editorStore.activeSqlTab
        if (!tab) {
            // 没有激活的 SQL Tab，新建一个
            const connId = scopeConnectionId.value
            if (!connId) return false
            const newId = editorStore.createTab(connId)
            editorStore.updateSql(newId, sql)
            return true
        }
        // 在当前光标处插入（简单实现：追加）
        const newSql = tab.sql ? tab.sql + '\n' + sql : sql
        editorStore.updateSql(tab.id, newSql)
        return true
    }

    return {
        items,
        filtered,
        loading,
        keyword,
        favoriteOnly,
        searching,
        scopeConnectionId,
        load,
        setScope,
        setKeyword,
        toggleFavoriteOnly,
        toggleFavorite,
        remove,
        updateTags,
        clear,
        insertToActiveTab,
    }
})
