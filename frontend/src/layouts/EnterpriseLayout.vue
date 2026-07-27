<template>
  <div class="ent-layout">
    <!-- 窄屏遮罩（点击关闭抽屉） -->
    <div v-if="sideOpen" class="ent-overlay" @click="sideOpen = false"></div>

    <!-- 侧栏（窄屏时为抽屉） -->
    <aside class="ent-side" :class="{ open: sideOpen }">
      <div class="ent-logo">LyraDB</div>
      <nav class="ent-nav">
        <router-link v-for="m in menus" :key="m.name" :to="m.to" class="ent-nav-item" active-class="active" @click="sideOpen = false">
          <el-icon><component :is="m.icon" /></el-icon>
          <span>{{ m.label }}</span>
          <span v-if="m.badge" class="nav-badge">{{ m.badge }}</span>
        </router-link>
      </nav>
      <div class="ws-switcher">
        <el-select v-if="auth.user?.workspaces?.length"
          :model-value="auth.user?.currentWorkspaceId || ''" size="small" style="width:100%"
          @change="onWs">
          <el-option v-for="w in auth.user?.workspaces" :key="w.id" :label="w.name" :value="w.id" />
        </el-select>
      </div>
    </aside>

    <!-- 主区 -->
    <div class="ent-main">
      <header class="ent-header">
        <el-button class="menu-toggle" text :icon="Menu" @click="sideOpen = true" aria-label="打开菜单" />
        <div class="ent-user">
          <el-icon><UserFilled /></el-icon>
          <span class="user-name">{{ auth.user?.displayName || auth.user?.username }}</span>
          <el-tag v-for="r in (auth.user?.roles || [])" :key="r" size="small" type="info" effect="plain" class="role-tag">
            {{ roleLabel(r) }}
          </el-tag>
        </div>
        <el-button text :icon="SwitchButton" @click="auth.logout()">登出</el-button>
      </header>
      <main class="ent-content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import {
  Coin, DocumentCopy, Bell, List, Setting, UserFilled, SwitchButton, ChatLineRound, Menu,
} from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { entApi } from '@/api/ent'

const auth = useAuthStore()

// 窄屏侧栏抽屉开关
const sideOpen = ref(false)

const pendingCount = ref(0)
async function loadPending() {
  try {
    const list = await entApi.approvalsPending()
    pendingCount.value = list.length
  } catch { pendingCount.value = 0 }
}
onMounted(loadPending)

const menus = computed(() => {
  const arr = [
    { name: 'my-sources', to: '/my-sources', label: '我的数据源', icon: Coin },
    { name: 'query', to: '/query', label: '企业查询', icon: DocumentCopy },
    { name: 'ai', to: '/ai', label: 'AI 助手', icon: ChatLineRound },
    { name: 'approvals', to: '/approvals', label: '审批中心', icon: Bell, badge: pendingCount.value || undefined },
    { name: 'audit', to: '/audit', label: '操作审计', icon: List },
  ]
  if (auth.isAdmin) {
    arr.push({ name: 'admin', to: '/admin', label: '管理', icon: Setting } as any)
  }
  return arr
})

async function onWs(id: string) {
  try { await auth.switchWorkspace(id) } catch {}
}

function roleLabel(r: string) {
  return r.replace('ROLE_', '')
}
</script>

<style scoped>
.ent-layout {
  display: flex;
  height: 100vh;
  background: var(--color-background);
}
.ent-side {
  width: 220px;
  flex-shrink: 0;
  background: var(--color-panel);
  border-right: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
}
.ent-logo {
  padding: 18px 20px;
  font-size: 16px;
  font-weight: 700;
  color: var(--color-primary);
  border-bottom: 1px solid var(--color-border);
}
.ent-nav {
  flex: 1;
  padding: 8px;
  overflow-y: auto;
}
.ent-nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 6px;
  color: var(--color-foreground);
  font-size: 13px;
  text-decoration: none;
  cursor: pointer;
  margin-bottom: 2px;
}
.ent-nav-item:hover { background: var(--color-hover); }
.ent-nav-item.active {
  background: var(--color-active);
  color: var(--color-secondary);
  font-weight: 600;
}
.nav-badge {
  margin-left: auto;
  background: var(--color-destructive);
  color: #fff;
  font-size: 10px;
  border-radius: 9px;
  padding: 0 6px;
  min-width: 16px;
  text-align: center;
}
.ent-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.ent-header {
  height: 52px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  background: var(--color-panel);
  border-bottom: 1px solid var(--color-border);
}
.ent-user {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}
.role-tag { margin-left: 4px; }
.ent-content {
  flex: 1;
  overflow: auto;
  padding: 20px;
}
/* 汉堡菜单按钮：宽屏隐藏，窄屏显示 */
.menu-toggle { display: none; }
.ent-overlay { display: none; }

/* === 窄屏（移动 WebView / 小屏浏览器） === */
@media (max-width: 768px) {
  .ent-layout { height: 100dvh; }
  /* 侧栏收为左侧抽屉 */
  .ent-side {
    position: fixed;
    top: 0;
    left: 0;
    bottom: 0;
    z-index: 1000;
    width: 260px;
    transform: translateX(-100%);
    transition: transform 0.25s ease;
    box-shadow: none;
  }
  .ent-side.open {
    transform: translateX(0);
    box-shadow: 4px 0 24px rgba(0, 0, 0, 0.18);
  }
  /* 遮罩 */
  .ent-overlay {
    display: block;
    position: fixed;
    inset: 0;
    z-index: 999;
    background: rgba(0, 0, 0, 0.4);
  }
  /* 显示汉堡按钮 */
  .menu-toggle { display: inline-flex; }
  .ent-header { padding: 0 12px; gap: 8px; }
  /* 窄屏隐藏角色标签与用户名，节省空间 */
  .role-tag { display: none; }
  .user-name { display: none; }
  .ent-content { padding: 12px; }
}
</style>
