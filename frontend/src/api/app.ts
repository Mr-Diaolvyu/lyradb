/**
 * 应用信息 API
 */
import apiClient from './index'

export interface AppInfo {
    edition: 'personal' | 'enterprise'
    version: string
    authRequired: boolean
}

export const appApi = {
    info(): Promise<AppInfo> {
        return apiClient.get('/app/info')
    },
}
