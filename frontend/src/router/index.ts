/**
 * 路由（企业版）
 */
import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes = [
    {
        path: '/login',
        name: 'login',
        component: () => import('@/views/LoginView.vue'),
        meta: { public: true },
    },
    {
        path: '/',
        component: () => import('@/layouts/EnterpriseLayout.vue'),
        children: [
            { path: '', redirect: '/my-sources' },
            { path: 'my-sources', name: 'my-sources', component: () => import('@/views/ent/MyDataSourcesView.vue') },
            { path: 'query', name: 'query', component: () => import('@/views/ent/EnterpriseQueryView.vue') },
            { path: 'ai', name: 'ai', component: () => import('@/views/ent/AskLyraView.vue') },
            { path: 'knowledge', name: 'knowledge', component: () => import('@/views/ent/KnowledgeHubView.vue') },
            { path: 'approvals', name: 'approvals', component: () => import('@/views/ent/ApprovalsView.vue') },
            { path: 'audit', name: 'audit', component: () => import('@/views/ent/AuditView.vue') },
            { path: 'admin', name: 'admin', component: () => import('@/views/ent/AdminView.vue'), meta: { admin: true } },
        ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({
    history: createWebHashHistory(),
    routes,
})

router.beforeEach(async (to) => {
    const auth = useAuthStore()
    if (!auth.ready) {
        await auth.init()
    }
    // 个人版不走路由
    if (!auth.isEnterprise) {
        return true
    }
    // 企业版：未登录跳登录；已登录访问登录页跳首页
    if (to.meta?.public) {
        if (to.name === 'login' && auth.isAuthenticated) return { name: 'my-sources' }
        return true
    }
    if (!auth.isAuthenticated) {
        return { name: 'login' }
    }
    if (to.meta?.admin && !auth.isAdmin) {
        return { name: 'my-sources' }
    }
    return true
})

export default router
