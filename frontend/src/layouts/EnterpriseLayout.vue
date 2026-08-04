<template>
  <div class="ent-layout stellar-canvas">
    <div v-if="sideOpen" class="ent-overlay" @click="sideOpen = false"></div>

    <aside class="ent-side glass-surface" :class="{ open: sideOpen }">
      <div class="ent-brand">
        <svg class="brand-mark" viewBox="0 0 36 36" aria-hidden="true">
          <rect x="1" y="1" width="34" height="34" rx="10" class="brand-tile" />
          <path d="M10 12.5c0-2 3.6-3.6 8-3.6s8 1.6 8 3.6-3.6 3.6-8 3.6-8-1.6-8-3.6Z" class="brand-db" />
          <path d="M10 12.5v5.2c0 2 3.6 3.6 8 3.6s8-1.6 8-3.6v-5.2M10 17.7v5.2c0 2 3.6 3.6 8 3.6 1.6 0 3.1-.2 4.3-.7" class="brand-db" />
          <circle cx="27" cy="25" r="2.2" class="brand-star" />
          <path d="m24.4 22.7 1.2 1.1m3.8-1.2-1 1.2" class="brand-orbit" />
        </svg>
        <div>
          <div class="brand-name">LyraDB · 天琴智库</div>
          <div class="brand-edition">ENTERPRISE · TRUSTED AI HUB</div>
        </div>
      </div>

      <div class="nav-caption">工作空间</div>
      <nav class="ent-nav">
        <router-link
          v-for="m in menus"
          :key="m.name"
          :to="m.to"
          class="ent-nav-item"
          active-class="active"
          @click="sideOpen = false"
        >
          <span class="nav-icon"><el-icon><component :is="m.icon" /></el-icon></span>
          <span>{{ m.label }}</span>
          <span v-if="m.badge" class="nav-badge">{{ m.badge }}</span>
        </router-link>
      </nav>

      <div class="ws-switcher">
        <div class="ws-label">当前企业空间</div>
        <el-select
          v-if="auth.user?.workspaces?.length"
          :model-value="auth.user?.currentWorkspaceId || ''"
          size="small"
          style="width: 100%"
          @change="onWs"
        >
          <el-option v-for="w in auth.user?.workspaces" :key="w.id" :label="w.name" :value="w.id" />
        </el-select>
        <div class="security-note">
          <span class="security-dot"></span>
          连接凭据由平台安全托管
        </div>
      </div>
    </aside>

    <div class="ent-main">
      <header class="ent-header glass-surface">
        <div class="header-context">
          <el-button class="menu-toggle" text :icon="Menu" @click="sideOpen = true" aria-label="打开菜单" />
          <div>
            <div class="section-kicker">TRUSTED AI DATA INTELLIGENCE</div>
            <div class="page-name">{{ currentPageName }}</div>
          </div>
        </div>

        <div class="header-actions">
          <el-tooltip :content="themeStore.isDark ? '切换为浅色模式' : '切换为深色模式'" placement="bottom">
            <el-button
              class="round-action"
              :icon="themeStore.isDark ? Sunny : Moon"
              :aria-label="themeStore.isDark ? '切换为浅色模式' : '切换为深色模式'"
              :title="themeStore.isDark ? '切换为浅色模式' : '切换为深色模式'"
              circle
              @click="themeStore.toggleTheme()"
            />
          </el-tooltip>
          <div class="ent-user">
            <span class="user-avatar">{{ userInitial }}</span>
            <div class="user-copy">
              <span class="user-name">{{ auth.user?.displayName || auth.user?.username }}</span>
              <span class="user-role">{{ primaryRole }}</span>
            </div>
          </div>
          <el-button class="logout-button" text :icon="SwitchButton" @click="auth.logout()">退出</el-button>
        </div>
      </header>

      <main class="ent-content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import {
  Coin, DocumentCopy, Bell, List, Setting, SwitchButton, ChatLineRound, Collection, Menu, Moon, Sunny,
} from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import { entApi } from '@/api/ent'

const auth = useAuthStore()
const themeStore = useThemeStore()
const route = useRoute()
const sideOpen = ref(false)
const pendingCount = ref(0)

async function loadPending() {
  if (!auth.canApprove) {
    pendingCount.value = 0
    return
  }
  try {
    const list = await entApi.approvalsPending()
    pendingCount.value = list.length
  } catch {
    pendingCount.value = 0
  }
}
onMounted(loadPending)

const menus = computed(() => {
  const arr = [
    { name: 'my-sources', to: '/my-sources', label: '我的数据源', icon: Coin },
    { name: 'query', to: '/query', label: '受控查询', icon: DocumentCopy },
    { name: 'ai', to: '/ai', label: 'Ask Lyra', icon: ChatLineRound },
    { name: 'knowledge', to: '/knowledge', label: '智库运营', icon: Collection },
    { name: 'approvals', to: '/approvals', label: '审批中心', icon: Bell, badge: pendingCount.value || undefined },
  ]
  if (auth.canAudit) arr.push({ name: 'audit', to: '/audit', label: '操作审计', icon: List } as any)
  if (auth.isAdmin) arr.push({ name: 'admin', to: '/admin', label: '企业管理', icon: Setting } as any)
  return arr
})

const currentPageName = computed(() => {
  const current = menus.value.find(item => item.name === route.name)
  return current?.label || '团队智库控制台'
})

const userInitial = computed(() => {
  const name = auth.user?.displayName || auth.user?.username || 'L'
  return name.trim().slice(0, 1).toUpperCase()
})

const primaryRole = computed(() => roleLabel(auth.user?.roles?.[0] || '企业成员'))

async function onWs(id: string) {
  try {
    await auth.switchWorkspace(id)
    await loadPending()
  } catch {
    pendingCount.value = 0
  }
}

function roleLabel(role: string) {
  const labels: Record<string, string> = {
    PLATFORM_ADMIN: '平台管理员',
    DS_ADMIN: '数据源管理员',
    STEWARD: '数据管家',
    ANALYST: '数据分析师',
    AUDITOR: '审计员',
    USER: '企业成员',
  }
  const normalized = role.replace('ROLE_', '')
  return labels[normalized] || normalized
}
</script>

<style scoped>
.ent-layout {
  display: flex;
  height: 100vh;
  padding: 12px;
  gap: 12px;
  color: var(--color-foreground);
}

.ent-side {
  width: 244px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  border-radius: 16px;
  overflow: hidden;
}

.ent-brand {
  display: flex;
  align-items: center;
  gap: 11px;
  min-height: 78px;
  padding: 16px;
  border-bottom: 1px solid var(--color-panel-border);
}

.brand-mark {
  width: 38px;
  height: 38px;
  flex-shrink: 0;
}

.brand-tile { fill: var(--color-active); stroke: var(--color-border-strong); }
.brand-db { fill: none; stroke: var(--color-brand); stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
.brand-star { fill: var(--color-accent); }
.brand-orbit { fill: none; stroke: var(--color-accent); stroke-width: 1.3; stroke-linecap: round; }

.brand-name {
  font-size: 17px;
  font-weight: 720;
  letter-spacing: -0.02em;
}

.brand-edition {
  margin-top: 2px;
  color: var(--color-text-muted);
  font-size: 8px;
  font-weight: 650;
  letter-spacing: 0.09em;
}

.nav-caption {
  padding: 18px 18px 7px;
  color: var(--color-text-muted);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.1em;
}

.ent-nav {
  flex: 1;
  padding: 0 10px;
  overflow-y: auto;
}

.ent-nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 42px;
  margin-bottom: 4px;
  padding: 0 10px;
  border: 1px solid transparent;
  border-radius: 10px;
  color: var(--color-text-muted);
  font-size: 13px;
  font-weight: 520;
  text-decoration: none;
  transition: color var(--transition-normal), background var(--transition-normal), border-color var(--transition-normal);
}

.ent-nav-item:hover {
  color: var(--color-foreground);
  background: var(--color-hover);
}

.ent-nav-item.active {
  color: var(--color-foreground);
  border-color: var(--color-panel-border);
  background: var(--color-active);
}

.ent-nav-item.active::before {
  position: absolute;
  left: -1px;
  width: 3px;
  height: 18px;
  border-radius: 0 3px 3px 0;
  background: var(--color-brand);
  content: '';
}

.nav-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: var(--color-muted);
  color: var(--color-brand);
}

.ent-nav-item.active .nav-icon {
  background: var(--color-panel-translucent);
}

.nav-badge {
  min-width: 18px;
  margin-left: auto;
  padding: 1px 6px;
  border-radius: 10px;
  background: var(--color-destructive);
  color: #fff;
  font-size: 10px;
  text-align: center;
}

.ws-switcher {
  margin: 10px;
  padding: 12px;
  border: 1px solid var(--color-panel-border);
  border-radius: 12px;
  background: var(--color-panel-header);
}

.ws-label {
  margin-bottom: 7px;
  color: var(--color-text-muted);
  font-size: 10px;
  font-weight: 650;
}

.security-note {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
  color: var(--color-text-muted);
  font-size: 10px;
}

.security-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-connected);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--color-connected) 14%, transparent);
}

.ent-main {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.ent-header {
  min-height: 66px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px 0 20px;
  border-radius: 14px;
}

.header-context,
.header-actions,
.ent-user {
  display: flex;
  align-items: center;
}

.header-context { gap: 10px; }
.header-actions { gap: 9px; }
.ent-user { gap: 8px; padding: 5px 8px 5px 6px; border: 1px solid var(--color-panel-border); border-radius: 10px; background: var(--color-panel-header); }

.page-name {
  margin-top: 1px;
  font-size: 15px;
  font-weight: 650;
}

.user-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 9px;
  background: var(--color-active);
  color: var(--color-brand);
  font-size: 12px;
  font-weight: 750;
}

.user-copy {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
}

.user-name { font-size: 12px; font-weight: 650; }
.user-role { margin-top: 2px; color: var(--color-text-muted); font-size: 9px; }
.round-action { border-color: var(--color-panel-border); background: var(--color-panel-header); }
.logout-button { color: var(--color-text-muted); }

.ent-content {
  min-height: 0;
  flex: 1;
  overflow: auto;
  padding: 20px 8px 8px;
}

.menu-toggle,
.ent-overlay {
  display: none;
}

@media (max-width: 768px) {
  .ent-layout { height: 100dvh; padding: 0; gap: 0; }
  .ent-side {
    position: fixed;
    inset: 8px auto 8px 8px;
    z-index: 1000;
    width: 268px;
    transform: translateX(calc(-100% - 16px));
    transition: transform 0.25s ease;
  }
  .ent-side.open { transform: translateX(0); }
  .ent-overlay {
    position: fixed;
    inset: 0;
    z-index: 999;
    display: block;
    background: rgba(4, 6, 12, 0.56);
    backdrop-filter: blur(4px);
  }
  .ent-header { min-height: 60px; border-width: 0 0 1px; border-radius: 0; }
  .menu-toggle { display: inline-flex; }
  .user-copy,
  .logout-button { display: none; }
  .ent-content { padding: 14px 12px; }
}
</style>
