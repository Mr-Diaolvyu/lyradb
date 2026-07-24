/**
 * SQL 查询历史 API
 */
import apiClient from './index'
import type { QueryHistory, HistoryFilter } from '@/types/history'

export const historyApi = {
    /** 查询历史列表 */
    list(filter: HistoryFilter = {}): Promise<QueryHistory[]> {
        const params: any = {}
        if (filter.connectionId) params.connectionId = filter.connectionId
        if (filter.favorite) params.favorite = true
        return apiClient.get('/history', { params })
    },

    /** 关键字全文搜索 */
    search(keyword: string): Promise<QueryHistory[]> {
        return apiClient.get('/history/search', { params: { keyword } })
    },

    /** 切换收藏 */
    toggleFavorite(id: string): Promise<QueryHistory> {
        return apiClient.post(`/history/${id}/favorite`)
    },

    /** 删除单条 */
    remove(id: string): Promise<void> {
        return apiClient.delete(`/history/${id}`)
    },

    /** 清空历史（指定连接则只清该连接的） */
    clear(connectionId?: string): Promise<void> {
        const config = connectionId ? { params: { connectionId } } : undefined
        return apiClient.delete('/history', config as any)
    },
}
