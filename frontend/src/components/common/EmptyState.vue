<template>
  <div class="empty-state-brand">
    <!-- 线性 SVG 图标（1.4px 描边，currentColor 着色，禁用 emoji） -->
    <svg class="empty-icon" viewBox="0 0 48 48" fill="none" aria-hidden="true">
      <template v-if="icon === 'search'">
        <circle cx="21" cy="21" r="11" stroke="currentColor" stroke-width="1.4" />
        <path d="M29.5 29.5 L38 38" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
        <path d="M16 21 h10 M21 16 v10" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" opacity="0.45" />
      </template>
      <template v-else>
        <ellipse cx="24" cy="12" rx="14" ry="5" stroke="currentColor" stroke-width="1.4" />
        <path d="M10 12 v24 c0 2.8 6.3 5 14 5 s14 -2.2 14 -5 V12" stroke="currentColor" stroke-width="1.4" />
        <path d="M10 24 c0 2.8 6.3 5 14 5 s14 -2.2 14 -5" stroke="currentColor" stroke-width="1.4" opacity="0.45" />
      </template>
    </svg>
    <div class="empty-title">{{ title }}</div>
    <div class="empty-hint" v-if="hint">{{ hint }}</div>
    <div class="empty-extra" v-if="$slots.default">
      <slot />
    </div>
  </div>
</template>

<script setup lang="ts">
withDefaults(defineProps<{
  title: string
  hint?: string
  icon?: 'db' | 'search'
}>(), {
  icon: 'search',
})
</script>

<style scoped>
.empty-state-brand {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  height: 100%;
  padding: var(--space-4);
  color: var(--color-text-muted);
}

.empty-icon {
  width: 48px;
  height: 48px;
  opacity: 0.55;
}

.empty-title {
  font-size: var(--text-body);
  font-weight: 500;
  color: var(--color-foreground);
  opacity: 0.75;
}

.empty-hint {
  font-size: var(--text-caption);
  color: var(--color-text-muted);
  text-align: center;
  max-width: 360px;
}

.empty-extra {
  margin-top: var(--space-1);
}
</style>
