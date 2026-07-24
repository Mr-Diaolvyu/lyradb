/**
 * API 客户端 - Axios 实例
 */
import axios from 'axios'

const apiClient = axios.create({
    baseURL: '/api',
    timeout: 60000,
    withCredentials: true, // 携带会话 Cookie（企业版登录态）
    headers: {
        'Content-Type': 'application/json',
    },
})

// 请求拦截器
apiClient.interceptors.request.use(
    (config) => {
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
        console.error('API Error:', error)
        // 会话过期/未登录 → 跳登录（企业版）
        if (error.response && error.response.status === 401) {
            const url = error.config?.url || ''
            if (!url.includes('/auth/login') && !window.location.hash.startsWith('#/login')) {
                window.location.hash = '#/login'
            }
        }
        if (error.response) {
            const msg = error.response.data?.message || error.response.statusText
            return Promise.reject(new Error(msg))
        }
        return Promise.reject(error)
    }
)

export default apiClient
