/**
 * 认证 API（企业版）
 */
import apiClient from './index'

export interface AuthUser {
    username: string
    displayName?: string
    roles: string[]
    workspaces: { id: string; name: string }[]
    currentWorkspaceId?: string
}

export const authApi = {
    login(username: string, password: string): Promise<AuthUser> {
        return apiClient.post('/auth/login', { username, password })
    },
    me(): Promise<AuthUser> {
        return apiClient.get('/auth/me')
    },
    logout(): Promise<void> {
        return apiClient.post('/auth/logout')
    },
    switchWorkspace(workspaceId: string): Promise<void> {
        return apiClient.post('/auth/workspace', { workspaceId })
    },
}
