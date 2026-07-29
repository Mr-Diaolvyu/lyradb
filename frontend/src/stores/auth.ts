/**
 * 认证与发行版 Store（企业版）
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { appApi, type AppInfo } from '@/api/app'
import { authApi, type AuthUser } from '@/api/auth'
import { validateAppInfoProbe } from '@/utils/requestControl'

export const useAuthStore = defineStore('auth', () => {
    const appInfo = ref<AppInfo>({ edition: 'personal', version: '', authRequired: false })
    const user = ref<AuthUser | null>(null)
    const ready = ref(false)
    const initError = ref<string | null>(null)
    let initPromise: Promise<void> | null = null

    const isEnterprise = computed(() => appInfo.value.edition === 'enterprise')
    const isAuthenticated = computed(() => !!user.value)
    const hasRole = (role: string) => user.value?.roles?.includes(`ROLE_${role}`) ?? false
    const isAdmin = computed(() => hasRole('PLATFORM_ADMIN') || hasRole('DS_ADMIN'))
    // 审批策略和平台管理员覆盖均由服务端计算；缺失字段按 false 失败关闭。
    const canApprove = computed(() => user.value?.canApprove === true)
    const canAudit = computed(() => user.value?.canViewWorkspaceAudit === true)

    function expireSession() {
        user.value = null
        window.dispatchEvent(new CustomEvent('lyradb:session-cleared'))
        if (!window.location.hash.startsWith('#/login')) {
            window.location.hash = '#/login'
        }
    }

    window.addEventListener('lyradb:session-expired', expireSession)

    async function doInit() {
        initError.value = null
        if (ready.value) return
        try {
            const probe = await appApi.info()
            const validationError = validateAppInfoProbe(probe)
            if (validationError) {
                initError.value = validationError
                ready.value = true
                return
            }
            appInfo.value = probe
        } catch (e: any) {
            // 无法探测发行版时不能降级为免认证个人版。
            initError.value = e.message || '无法读取应用信息'
            ready.value = true
            return
        }
        if (appInfo.value.authRequired) {
            // 企业版写请求使用 Cookie CSRF；登录前先下发 XSRF-TOKEN。
            try {
                await authApi.csrf()
            } catch (e: any) {
                initError.value = e.message || '无法初始化安全令牌'
                ready.value = true
                return
            }
            // 已有会话则恢复
            try {
                user.value = await authApi.me()
            } catch {
                user.value = null
            }
        }
        ready.value = true
    }

    async function init() {
        if (ready.value) return
        if (!initPromise) {
            initPromise = doInit().finally(() => {
                initPromise = null
            })
        }
        await initPromise
    }

    async function retryInit() {
        ready.value = false
        await init()
    }

    async function login(username: string, password: string) {
        await authApi.csrf()
        user.value = await authApi.login(username, password)
        return user.value
    }

    async function logout() {
        try {
            await authApi.csrf()
            await authApi.logout()
        } catch (e: any) {
            // HttpOnly 会话只能由服务端可靠失效，失败时不能向用户假报“已退出”。
            if (!user.value) return true
            ElMessage.error(e.message || '退出失败，请检查网络后重试')
            return false
        }
        expireSession()
        return true
    }

    async function switchWorkspace(workspaceId: string) {
        await authApi.switchWorkspace(workspaceId)
        user.value = await authApi.me()
    }

    return {
        appInfo, user, ready, initError, isEnterprise, isAuthenticated,
        isAdmin, canApprove, canAudit, hasRole,
        init, retryInit, login, logout, switchWorkspace, expireSession,
    }
})
