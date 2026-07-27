<template>
  <!-- 数据库：圆柱体 -->
  <svg v-if="type === 'DATABASE'" class="nav-svg-icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
    <ellipse cx="8" cy="4" rx="5" ry="2" />
    <path d="M3 4v8c0 1.1 2.24 2 5 2s5-.9 5-2V4" />
    <path d="M3 8c0 1.1 2.24 2 5 2s5-.9 5-2" />
  </svg>
  <!-- 模式：层叠 -->
  <svg v-else-if="type === 'SCHEMA'" class="nav-svg-icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
    <path d="M8 2.5 14 5.5 8 8.5 2 5.5 8 2.5z" />
    <path d="M2 9l6 3 6-3" />
  </svg>
  <!-- 表：网格 -->
  <svg v-else-if="type === 'TABLE'" class="nav-svg-icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
    <rect x="2.5" y="3" width="11" height="10" rx="1" />
    <path d="M2.5 6.5h11" />
    <path d="M7 6.5V13" />
  </svg>
  <!-- 视图：眼睛 -->
  <svg v-else-if="type === 'VIEW'" class="nav-svg-icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
    <path d="M1.8 8s2.4-3.8 6.2-3.8S14.2 8 14.2 8 11.8 11.8 8 11.8 1.8 8 1.8 8z" />
    <circle cx="8" cy="8" r="1.7" />
  </svg>
  <!-- 集合：层叠文档 -->
  <svg v-else-if="type === 'COLLECTION'" class="nav-svg-icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
    <rect x="4.5" y="2.5" width="9" height="9" rx="1.2" />
    <path d="M2.5 5.5v6.5a1.5 1.5 0 0 0 1.5 1.5h6.5" />
  </svg>
  <!-- 分区：分割方块 -->
  <svg v-else-if="type === 'PARTITION'" class="nav-svg-icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
    <rect x="2.5" y="2.5" width="11" height="11" rx="1.2" />
    <path d="M8 2.5v11" />
  </svg>
  <!-- 键分组 / 索引分组：文件夹 -->
  <svg v-else-if="type === 'KEY_GROUP' || type === 'INDEX_GROUP'" class="nav-svg-icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
    <path d="M2 4.8A1.3 1.3 0 0 1 3.3 3.5h3.2l1.4 1.8h4.8A1.3 1.3 0 0 1 14 6.6v4.6a1.3 1.3 0 0 1-1.3 1.3H3.3A1.3 1.3 0 0 1 2 11.2V4.8z" />
  </svg>
  <!-- 键：钥匙 -->
  <svg v-else-if="type === 'KEY'" class="nav-svg-icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
    <circle cx="5.5" cy="5.5" r="2.8" />
    <path d="M7.6 7.6 13 13" />
    <path d="M10.6 10.6 12.4 8.8" />
  </svg>
  <!-- 索引：排序线 -->
  <svg v-else-if="type === 'INDEX'" class="nav-svg-icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
    <path d="M3 4.5h10" />
    <path d="M3 8h7" />
    <path d="M3 11.5h4" />
  </svg>
  <!-- 默认：信息 -->
  <svg v-else class="nav-svg-icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
    <circle cx="8" cy="8" r="5.5" />
    <path d="M8 7.4V11" />
    <path d="M8 5.1v.1" />
  </svg>
</template>

<script setup lang="ts">
/**
 * 连接树节点图标（线性 SVG，1.4px 描边，颜色继承 currentColor）
 * 按节点类型渲染对应图形，替代原字母缩写徽章
 */
defineProps<{ type?: string }>()
</script>

<style scoped>
.nav-svg-icon {
  width: 13px;
  height: 13px;
  display: block;
}
</style>
