<template>
  <div class="chart-view">
    <div v-if="!numericColumns.length" class="chart-empty">
      <el-empty :description="t('chart.noNumeric')" :image-size="60" />
    </div>
    <template v-else>
      <div class="chart-toolbar glass-surface">
        <div class="chart-heading">
          <span class="section-kicker">Visual analysis</span>
          <span class="chart-title">结果可视化</span>
        </div>
        <el-radio-group v-model="chartType" size="small">
          <el-radio-button value="bar">{{ t('chart.bar') }}</el-radio-button>
          <el-radio-button value="line">{{ t('chart.line') }}</el-radio-button>
          <el-radio-button value="pie" :disabled="categories.length > 12">{{ t('chart.pie') }}</el-radio-button>
        </el-radio-group>
        <label class="axis-control">
          <span>X</span>
          <el-select v-model="xColumn" size="small" class="chart-select">
            <el-option v-for="c in xCandidates" :key="c" :label="c" :value="c" />
          </el-select>
        </label>
        <label class="axis-control">
          <span>Y</span>
          <el-select v-model="yColumn" size="small" class="chart-select">
            <el-option v-for="c in numericColumns" :key="c" :label="c" :value="c" />
          </el-select>
        </label>
        <span v-if="truncated" class="chart-truncated">{{ t('chart.truncated', { max: MAX_POINTS }) }}</span>
      </div>

      <div class="chart-body stellar-canvas">
        <svg v-if="chartType !== 'pie'" :viewBox="`0 0 ${W} ${H}`" class="chart-svg" preserveAspectRatio="xMidYMid meet">
          <defs>
            <linearGradient id="lyra-bar-fill" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stop-color="var(--color-chart-1)" stop-opacity="0.96" />
              <stop offset="100%" stop-color="var(--color-chart-1)" stop-opacity="0.58" />
            </linearGradient>
            <linearGradient id="lyra-line-area" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stop-color="var(--color-chart-1)" stop-opacity="0.26" />
              <stop offset="100%" stop-color="var(--color-chart-1)" stop-opacity="0.02" />
            </linearGradient>
          </defs>

          <g v-for="(tick, i) in yTicks" :key="i">
            <line :x1="PAD_L" :y1="tick.y" :x2="W - PAD_R" :y2="tick.y" class="grid-line" />
            <text :x="PAD_L - 9" :y="tick.y + 4" text-anchor="end" class="axis-text">{{ tick.label }}</text>
          </g>
          <line :x1="PAD_L" :y1="zeroY" :x2="W - PAD_R" :y2="zeroY" class="axis-line" />

          <g v-if="chartType === 'bar'">
            <rect
              v-for="(p, i) in points"
              :key="i"
              :x="p.x - barWidth / 2"
              :y="Math.min(p.y, zeroY)"
              :width="barWidth"
              :height="Math.max(1, Math.abs(zeroY - p.y))"
              :rx="Math.min(6, barWidth / 3)"
              class="bar-rect"
            >
              <title>{{ p.label }}: {{ p.value }}</title>
            </rect>
          </g>

          <g v-else>
            <path :d="areaPath" class="line-area" />
            <polyline :points="polyline" class="line-path" />
            <circle v-for="(p, i) in points" :key="i" :cx="p.x" :cy="p.y" r="3.2" class="line-dot">
              <title>{{ p.label }}: {{ p.value }}</title>
            </circle>
          </g>

          <g v-for="(p, i) in points" :key="'x' + i">
            <text
              v-if="i % labelStep === 0"
              :x="p.x"
              :y="H - PAD_B + 17"
              text-anchor="middle"
              class="axis-text"
            >{{ truncateLabel(p.label) }}</text>
          </g>
        </svg>

        <div v-else class="pie-wrap">
          <div class="donut-stage">
            <svg :viewBox="`0 0 ${PIE_SIZE} ${PIE_SIZE}`" class="pie-svg" preserveAspectRatio="xMidYMid meet">
              <path
                v-for="(s, i) in pieSlices"
                :key="i"
                :d="s.d"
                :fill="s.color"
                class="pie-slice"
              >
                <title>{{ s.label }}: {{ s.value }} ({{ s.percent }}%)</title>
              </path>
              <circle :cx="PIE_SIZE / 2" :cy="PIE_SIZE / 2" :r="PIE_SIZE * 0.27" class="donut-hole" />
              <text :x="PIE_SIZE / 2" :y="PIE_SIZE / 2 - 4" text-anchor="middle" class="donut-kicker">TOTAL</text>
              <text :x="PIE_SIZE / 2" :y="PIE_SIZE / 2 + 17" text-anchor="middle" class="donut-value">{{ formatNumber(pieTotal) }}</text>
            </svg>
          </div>
          <div class="pie-legend glass-surface">
            <div class="legend-heading">分类占比</div>
            <div v-for="(s, i) in pieSlices" :key="i" class="legend-item">
              <span class="legend-dot" :style="{ background: s.color }"></span>
              <span class="legend-label">{{ s.label }}</span>
              <span class="legend-value">{{ s.percent }}%</span>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps<{
  columns: string[]
  rows: Record<string, any>[]
}>()

const { t } = useI18n()
const W = 800
const H = 340
const PAD_L = 62
const PAD_R = 22
const PAD_T = 24
const PAD_B = 38
const PIE_SIZE = 300
const MAX_POINTS = 50
const PALETTE = [
  'var(--color-chart-1)', 'var(--color-chart-2)', 'var(--color-chart-3)', 'var(--color-chart-4)',
  'var(--color-chart-5)', 'var(--color-chart-6)', 'var(--color-chart-7)', 'var(--color-chart-8)',
]

function isNumericColumn(col: string): boolean {
  const values = props.rows.map(r => r[col]).filter(v => v !== null && v !== undefined && v !== '')
  if (!values.length) return false
  return values.filter(v => !isNaN(Number(v))).length / values.length >= 0.8
}

const numericColumns = computed(() => props.columns.filter(isNumericColumn))
const xCandidates = computed(() => {
  const nonNumeric = props.columns.filter(c => !numericColumns.value.includes(c))
  return nonNumeric.length ? nonNumeric : props.columns
})

const xColumn = ref('')
const yColumn = ref('')
const chartType = ref<'bar' | 'line' | 'pie'>('bar')

watch(
  () => [props.columns, props.rows],
  () => {
    if (!xCandidates.value.includes(xColumn.value)) xColumn.value = xCandidates.value[0] || ''
    if (!numericColumns.value.includes(yColumn.value)) yColumn.value = numericColumns.value[0] || ''
    chartType.value = recommendType()
  },
  { immediate: true, deep: false },
)

function recommendType(): 'bar' | 'line' | 'pie' {
  const sample = props.rows[0]?.[xColumn.value]
  return sample && /^\d{4}-\d{2}(-\d{2})?([ T]\d{2}:\d{2})?/.test(String(sample)) ? 'line' : 'bar'
}

const truncated = computed(() => props.rows.length > MAX_POINTS)
const dataset = computed(() => props.rows.slice(0, MAX_POINTS).map((row, index) => ({
  label: xColumn.value ? String(row[xColumn.value] ?? '') : String(index + 1),
  value: Number(row[yColumn.value]) || 0,
})))
const categories = computed(() => dataset.value.map(item => item.label))
const maxValue = computed(() => Math.max(...dataset.value.map(item => item.value), 0))
const minValue = computed(() => Math.min(...dataset.value.map(item => item.value), 0))
const valueRange = computed(() => maxValue.value - minValue.value || 1)

function valueToY(value: number) {
  return H - PAD_B - ((value - minValue.value) / valueRange.value) * (H - PAD_T - PAD_B)
}

const zeroY = computed(() => valueToY(0))
const yTicks = computed(() => {
  const ticks = []
  for (let i = 0; i <= 4; i++) {
    const value = minValue.value + (valueRange.value * i) / 4
    ticks.push({ y: valueToY(value), label: formatNumber(value) })
  }
  return ticks
})

const points = computed(() => {
  const count = dataset.value.length
  if (!count) return []
  const plotWidth = W - PAD_L - PAD_R
  return dataset.value.map((item, index) => ({
    ...item,
    x: PAD_L + (plotWidth * (index + 0.5)) / count,
    y: valueToY(item.value),
  }))
})

const barWidth = computed(() => Math.min(42, ((W - PAD_L - PAD_R) / (dataset.value.length || 1)) * 0.58))
const polyline = computed(() => points.value.map(point => `${point.x},${point.y}`).join(' '))
const areaPath = computed(() => {
  if (!points.value.length) return ''
  const first = points.value[0]
  const last = points.value[points.value.length - 1]
  return `M ${first.x} ${zeroY.value} L ${points.value.map(point => `${point.x} ${point.y}`).join(' L ')} L ${last.x} ${zeroY.value} Z`
})
const labelStep = computed(() => Math.max(1, Math.ceil(dataset.value.length / 12)))
const pieTotal = computed(() => dataset.value.reduce((sum, item) => sum + Math.max(item.value, 0), 0))

const pieSlices = computed(() => {
  const total = pieTotal.value
  if (total <= 0) return []
  const center = PIE_SIZE / 2
  const radius = PIE_SIZE / 2 - 10
  let angle = -Math.PI / 2
  return dataset.value
    .filter(item => item.value > 0)
    .map((item, index) => {
      const sweep = (item.value / total) * Math.PI * 2
      const x1 = center + radius * Math.cos(angle)
      const y1 = center + radius * Math.sin(angle)
      angle += sweep
      const x2 = center + radius * Math.cos(angle)
      const y2 = center + radius * Math.sin(angle)
      return {
        d: `M ${center} ${center} L ${x1} ${y1} A ${radius} ${radius} 0 ${sweep > Math.PI ? 1 : 0} 1 ${x2} ${y2} Z`,
        color: PALETTE[index % PALETTE.length],
        label: item.label,
        value: item.value,
        percent: ((item.value / total) * 100).toFixed(1),
      }
    })
})

function formatNumber(value: number): string {
  if (Math.abs(value) >= 1_000_000) return (value / 1_000_000).toFixed(1) + 'M'
  if (Math.abs(value) >= 1_000) return (value / 1_000).toFixed(1) + 'K'
  return Number.isInteger(value) ? String(value) : value.toFixed(1)
}

function truncateLabel(value: string): string {
  return value.length > 10 ? value.slice(0, 10) + '…' : value
}
</script>

<style scoped>
.chart-view {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 10px 12px 12px;
}

.chart-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

.chart-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 52px;
  padding: 7px 10px;
  border-radius: 12px;
  flex-shrink: 0;
}

.chart-heading {
  display: flex;
  flex-direction: column;
  min-width: 108px;
  line-height: 1.2;
}

.chart-title {
  margin-top: 2px;
  font-size: 12px;
  font-weight: 650;
}

.axis-control {
  display: flex;
  align-items: center;
  gap: 5px;
  color: var(--color-text-muted);
  font-size: 10px;
  font-weight: 700;
}

.chart-select { width: 140px; }
.chart-truncated { margin-left: auto; color: var(--color-warning); font-size: 11px; }

.chart-body {
  min-height: 0;
  flex: 1;
  margin-top: 10px;
  overflow: hidden;
  border: 1px solid var(--color-panel-border);
  border-radius: 14px;
}

.chart-svg { width: 100%; height: 100%; min-height: 280px; }
.grid-line { stroke: var(--color-chart-grid); stroke-dasharray: 2 6; }
.axis-line { stroke: var(--color-border-strong); stroke-width: 1; }
.axis-text { fill: var(--color-text-muted); font-size: 10px; font-family: var(--font-data); }
.bar-rect { fill: url(#lyra-bar-fill); transition: opacity var(--transition-normal); }
.bar-rect:hover { opacity: 0.78; }
.line-area { fill: url(#lyra-line-area); }
.line-path { fill: none; stroke: var(--color-chart-1); stroke-width: 2.2; stroke-linecap: round; stroke-linejoin: round; }
.line-dot { fill: var(--color-panel); stroke: var(--color-chart-1); stroke-width: 2; }

.pie-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 28px;
  height: 100%;
  padding: 18px;
}

.donut-stage { min-width: 0; height: 100%; }
.pie-svg { width: 100%; height: 100%; max-width: 360px; max-height: 320px; }
.pie-slice { stroke: var(--color-canvas); stroke-width: 1.5; transition: opacity var(--transition-normal); }
.pie-slice:hover { opacity: 0.78; }
.donut-hole { fill: var(--color-panel-translucent); stroke: var(--color-panel-border); }
.donut-kicker { fill: var(--color-text-muted); font-size: 9px; font-weight: 700; letter-spacing: 0.14em; }
.donut-value { fill: var(--color-foreground); font-size: 18px; font-weight: 700; }

.pie-legend {
  min-width: 220px;
  max-height: 84%;
  padding: 12px;
  overflow-y: auto;
  border-radius: 12px;
}

.legend-heading { margin-bottom: 8px; font-size: 11px; font-weight: 650; }
.legend-item { display: flex; align-items: center; gap: 7px; min-height: 25px; font-size: 11px; }
.legend-dot { width: 8px; height: 8px; border-radius: 3px; flex-shrink: 0; }
.legend-label { max-width: 160px; flex: 1; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.legend-value { color: var(--color-text-muted); font-variant-numeric: tabular-nums; }

@media (max-width: 768px) {
  .chart-heading { display: none; }
  .chart-toolbar { flex-wrap: wrap; }
  .chart-select { width: 120px; }
  .pie-wrap { flex-direction: column; gap: 10px; }
  .pie-legend { width: 100%; max-height: 150px; }
}
</style>
