<template>
  <span
    class="database-icon"
    :class="`engine-${normalizedType.toLowerCase()}`"
    :style="{ width: iconSize + 'px', height: iconSize + 'px', '--engine-color': engineColor }"
    :title="engineLabel"
    role="img"
    :aria-label="engineLabel"
  >
    <svg viewBox="0 0 32 32" aria-hidden="true">
      <rect x="1" y="1" width="30" height="30" rx="9" class="tile" />

      <g v-if="normalizedType === 'MYSQL'" class="engine-mark">
        <ellipse cx="14.5" cy="10.8" rx="7.2" ry="3.1" />
        <path d="M7.3 10.8v8.8c0 1.8 3.2 3.2 7.2 3.2 2.2 0 4.1-.4 5.4-1.1M7.3 15.2c0 1.8 3.2 3.2 7.2 3.2 1.9 0 3.6-.3 4.9-.9" />
        <path d="m20.2 13.3 4.6 1.8-3.5 1.2 2.3 2.8" class="signature" />
      </g>

      <g v-else-if="normalizedType === 'POSTGRESQL'" class="engine-mark">
        <ellipse cx="16" cy="10.5" rx="7.4" ry="3.2" />
        <path d="M8.6 10.5v9.1c0 1.8 3.3 3.2 7.4 3.2s7.4-1.4 7.4-3.2v-9.1M8.6 15.1c0 1.8 3.3 3.2 7.4 3.2 1.7 0 3.2-.2 4.5-.7" />
        <path d="M18.5 13.8c2.7.3 4.6 1.7 4.6 3.3 0 1.3-1.1 2.2-2.8 2.6l1.3 2" class="signature" />
      </g>

      <g v-else-if="normalizedType === 'ORACLE'" class="engine-mark">
        <ellipse cx="16" cy="16" rx="9.3" ry="6.3" />
        <ellipse cx="16" cy="16" rx="5.2" ry="2.6" class="signature" />
      </g>

      <g v-else-if="normalizedType === 'MSSQL' || normalizedType === 'SQLSERVER'" class="engine-mark">
        <path d="m7.2 11.1 7.1-3.2 5.1 2.1-7.1 3.3-5.1-2.2Z" />
        <path d="m12.3 13.3 7.1-3.3 5.4 2.3-7.2 3.3-5.3-2.3Z" class="signature" />
        <path d="m7.2 17.2 7.1-3.2 5.1 2.1-7.1 3.3-5.1-2.2Zm5.1 2.2 7.1-3.3 5.4 2.3-7.2 3.3-5.3-2.3Z" />
      </g>

      <g v-else-if="normalizedType === 'SQLITE'" class="engine-mark">
        <ellipse cx="13.5" cy="11" rx="6.8" ry="3" />
        <path d="M6.7 11v8.4c0 1.7 3 3 6.8 3 1.4 0 2.7-.2 3.8-.5M6.7 15.2c0 1.7 3 3 6.8 3" />
        <path d="M16.5 20.8c1.3-5.7 3.9-9.3 8.1-11.2-1.1 4.9-3.5 8.6-7.1 11.3l-1 2.8" class="signature" />
      </g>

      <g v-else-if="normalizedType === 'CLICKHOUSE'" class="engine-mark signature">
        <path d="M8 23V9m4 14V12m4 11V7m4 16V10m4 13V14" />
      </g>

      <g v-else-if="normalizedType === 'MAXCOMPUTE'" class="engine-mark">
        <circle cx="16" cy="16" r="2.8" class="signature fill" />
        <circle cx="9" cy="10" r="1.7" class="fill" />
        <circle cx="23" cy="10" r="1.7" class="fill" />
        <circle cx="24" cy="22" r="1.7" class="fill" />
        <circle cx="8" cy="22" r="1.7" class="fill" />
        <path d="m10.3 11.2 3.6 3.1m4.2 0 3.6-3.1m-3.4 6.7 4.2 3m-8.8-3-4.4 3" />
      </g>

      <g v-else-if="normalizedType === 'MONGODB'" class="engine-mark">
        <path d="M16 6.8c5.1 4.5 7.4 8.3 6.8 11.5-.5 3-2.8 5.2-6.8 6.9-4-1.7-6.3-4-6.8-6.9-.6-3.2 1.7-7 6.8-11.5Z" />
        <path d="M16 10.3v15.9m0-5.7-2.8-3.1m2.8 1.1 2.9-3.3" class="signature" />
      </g>

      <g v-else-if="normalizedType === 'REDIS'" class="engine-mark">
        <path d="m7 11.2 9-4.1 9 4.1-9 4.1-9-4.1Z" />
        <path d="m7 16.1 9 4.1 9-4.1m-18 5 9 4.1 9-4.1" class="signature" />
      </g>

      <g v-else class="engine-mark">
        <ellipse cx="16" cy="10.2" rx="8" ry="3.3" />
        <path d="M8 10.2v10.7c0 1.8 3.6 3.3 8 3.3s8-1.5 8-3.3V10.2M8 15.5c0 1.8 3.6 3.3 8 3.3s8-1.5 8-3.3" />
      </g>
    </svg>
    <span v-if="connected !== undefined" class="status" :class="{ connected }"></span>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  dbType: string
  size?: number
  connected?: boolean
}>(), {
  size: 28,
})

const iconSize = computed(() => Math.max(18, props.size || 28))
const normalizedType = computed(() => (props.dbType || 'DATABASE').toUpperCase().replace(/[^A-Z]/g, ''))

const engineColor = computed(() => {
  const colors: Record<string, string> = {
    MYSQL: 'var(--db-mysql)',
    POSTGRESQL: 'var(--db-postgresql)',
    ORACLE: 'var(--db-oracle)',
    MSSQL: 'var(--db-mssql)',
    SQLSERVER: 'var(--db-mssql)',
    SQLITE: 'var(--db-sqlite)',
    MAXCOMPUTE: 'var(--db-maxcompute)',
    CLICKHOUSE: '#D9A900',
    MONGODB: 'var(--db-mongodb)',
    REDIS: 'var(--db-redis)',
  }
  return colors[normalizedType.value] || 'var(--color-brand)'
})

const engineLabel = computed(() => {
  const labels: Record<string, string> = {
    MYSQL: 'MySQL',
    POSTGRESQL: 'PostgreSQL',
    ORACLE: 'Oracle',
    MSSQL: 'SQL Server',
    SQLSERVER: 'SQL Server',
    SQLITE: 'SQLite',
    MAXCOMPUTE: 'MaxCompute',
    CLICKHOUSE: 'ClickHouse',
    MONGODB: 'MongoDB',
    REDIS: 'Redis',
  }
  return labels[normalizedType.value] || props.dbType || '数据库'
})
</script>

<style scoped>
.database-icon {
  --engine-color: var(--color-brand);
  position: relative;
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  color: var(--engine-color);
}

.database-icon svg {
  display: block;
  width: 100%;
  height: 100%;
  overflow: visible;
}

.tile {
  fill: color-mix(in srgb, var(--engine-color) 9%, var(--color-panel-raised));
  stroke: color-mix(in srgb, var(--engine-color) 28%, var(--color-panel-border));
  stroke-width: 1;
}

.engine-mark {
  fill: none;
  stroke: currentColor;
  stroke-width: 1.55;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.engine-mark .signature {
  stroke-width: 1.9;
}

.engine-mark .fill {
  fill: currentColor;
  stroke: none;
}

.status {
  position: absolute;
  right: -1px;
  bottom: -1px;
  width: 7px;
  height: 7px;
  border: 1.5px solid var(--color-panel);
  border-radius: 50%;
  background: var(--color-disconnected);
}

.status.connected {
  background: var(--color-connected);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--color-connected) 14%, transparent);
}
</style>
