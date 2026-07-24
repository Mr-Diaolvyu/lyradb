/**
 * 连接管理 API
 */
import apiClient from './index'
import type { ConnectionDTO, TestConnectionRequest, TestConnectionResult, ConnectResult, ImportResult } from '@/types/connection'

export const connectionApi = {
    /** 列出所有连接 */
    list(): Promise<ConnectionDTO[]> {
        return apiClient.get('/connections')
    },

    /** 获取单个连接 */
    get(id: string): Promise<ConnectionDTO> {
        return apiClient.get(`/connections/${id}`)
    },

    /** 创建连接 */
    create(dto: Partial<ConnectionDTO>): Promise<ConnectionDTO> {
        return apiClient.post('/connections', dto)
    },

    /** 更新连接 */
    update(id: string, dto: Partial<ConnectionDTO>): Promise<ConnectionDTO> {
        return apiClient.put(`/connections/${id}`, dto)
    },

    /** 删除连接 */
    remove(id: string): Promise<void> {
        return apiClient.delete(`/connections/${id}`)
    },

    /** 测试连接 */
    test(request: TestConnectionRequest): Promise<TestConnectionResult> {
        return apiClient.post('/connections/test', request)
    },

    /** 建立连接 */
    connect(id: string): Promise<ConnectResult> {
        return apiClient.post(`/connections/${id}/connect`)
    },

    /** 断开连接 */
    disconnect(id: string): Promise<void> {
        return apiClient.post(`/connections/${id}/disconnect`)
    },

    /** 检查连接状态 */
    getStatus(id: string): Promise<{ connected: boolean }> {
        return apiClient.get(`/connections/${id}/status`)
    },

    /** 切换收藏状态 */
    toggleFavorite(id: string): Promise<ConnectionDTO> {
        return apiClient.post(`/connections/${id}/favorite`)
    },

    /** 复制连接配置 */
    duplicate(id: string): Promise<ConnectionDTO> {
        return apiClient.post(`/connections/${id}/duplicate`)
    },

    /** 导出所有连接配置 */
    export(): Promise<ConnectionDTO[]> {
        return apiClient.post('/connections/export')
    },

    /** 导入连接配置 */
    import(dtos: ConnectionDTO[]): Promise<ImportResult> {
        return apiClient.post('/connections/import', dtos)
    },
}
