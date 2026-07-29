
/**
 * API 客户端 - Axios 实例
 */
import axios from 'axios'
import {
    DESKTOP_PROOF_HEADER,
    readDesktopSessionProof,
} from '@/utils/desktopAccess'
import { safeRequestPath, shouldExpireSession, stripLogControlCharacters } from '@/utils/requestControl'

const apiClient = axios.create({
    baseURL: '/api',
    timeout: 60000,
    withCredentials: true, // 携带会话 Cookie（企业版登录态）
    withXSRFToken: true,
    xsrfCookieName: 'XSRF-TOKEN',
    xsrfHeaderName: 'X-XSRF-TOKEN',
    headers: {
        'Content-Type': 'application/json',
    },
})

// 请求拦截器
apiClient.interceptors.request.use(
    (config) => {
        const desktopProof = readDesktopSessionProof()
        if (desktopProof) {
            config.headers.set(DESKTOP_PROOF_HEADER, desktopProof)
        }
        return config
    },
    (error) => {
        return Promise.reject(error)
    }
)

// 响应拦截器
apiClient.interceptors.response.use(
    (response) => {
        return response.data
    },
    (error) => {
        // 禁止记录完整 AxiosError：其中可能包含登录密码、连接密钥、SQL 与请求头。
        if (import.meta.env.DEV) {
            const rawRequestId = error.response?.headers?.['x-request-id']
            const requestId = typeof rawRequestId === 'string'
                ? stripLogControlCharacters(rawRequestId).slice(0, 128)
                : undefined
            console.warn('API 请求失败', {
                method: typeof error.config?.method === 'string'
                    ? error.config.method.toUpperCase().slice(0, 12)
                    : undefined,
                path: safeRequestPath(error.config?.url),
                status: typeof error.response?.status === 'number'
                    ? error.response.status
                    : undefined,
                requestId,
            })
        }
        // 会话过期/未登录 → 清理认证状态，由路由守卫统一跳转。
        if (shouldExpireSession(error.response?.status, error.config?.url)) {
            window.dispatchEvent(new CustomEvent('lyradb:session-expired'))
        }
        if (error.response) {
            const msg = typeof error.response.data?.message === 'string'
                ? error.response.data.message
                : error.response.statusText
            return Promise.reject(new Error(msg))
        }
        return Promise.reject(new Error(error.message || '网络请求失败'))
    }
)

export default apiClient
