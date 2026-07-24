/**
 * 认证与发行版 Store（企业版）
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { appApi, type AppInfo } from '@/api/app'
import { authApi, type AuthUser } from '@/api/auth'

export const useAuthStore = defineStore('auth', () => {
    const appInfo = ref<AppInfo>({ edition: 'personal', version: '', authRequired: false })
    const user = ref<AuthUser | null>(null)
    const ready = ref(false)

    const isEnterprise = computed(() => appInfo.value.edition === 'enterprise')
    const isAuthenticated = computed(() => !!user.value)
    const isAdmin = computed(() =>
        user.value?.roles?.some(r => r.includes('PLATFORM_ADMIN') || r.includes('DS_ADMIN')) ?? false
    )

    async function init() {
        if (ready.value) return
        try {
            appInfo.value = await appApi.info()
        } catch {
            appInfo.value = { edition: 'personal', version: '', authRequired: false }
        }
        if (appInfo.value.authRequired) {
            // 已有会话则恢复
            try {
                user.value = await authApi.me()
            } catch {
                user.value = null
            }
        }
        ready.value = true
    }

    async function login(username: string, password: string) {
        user.value = await authApi.login(username, password)
        return user.value
    }

    async function logout() {
        try { await authApi.logout() } catch {}
        user.value = null
        window.location.hash = '#/login'
    }

    async function switchWorkspace(workspaceId: string) {
        await authApi.switchWorkspace(workspaceId)
        user.value = await authApi.me()
    }

    return {
        appInfo, user, ready, isEnterprise, isAuthenticated, isAdmin,
        init, login, logout, switchWorkspace,
    }
})
