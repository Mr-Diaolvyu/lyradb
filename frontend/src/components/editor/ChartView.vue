<template>
  <!-- 结果集图表视图（迭代二 D2，SVG 自绘，无第三方依赖） -->
  <div class="chart-view">
    <div v-if="!numericColumns.length" class="chart-empty">
      <el-empty :description="t('chart.noNumeric')" :image-size="60" />
    </div>
    <template v-else>
      <div class="chart-toolbar">
        <el-radio-group v-model="chartType" size="small">
          <el-radio-button value="bar">{{ t('chart.bar') }}</el-radio-button>
          <el-radio-button value="line">{{ t('chart.line') }}</el-radio-button>
          <el-radio-button value="pie" :disabled="categories.length > 12">{{ t('chart.pie') }}</el-radio-button>
        </el-radio-group>
        <span class="chart-label">X</span>
        <el-select v-model="xColumn" size="small" class="chart-select">
          <el-option v-for="c in xCandidates" :key="c" :label="c" :value="c" />
        </el-select>
        <span class="chart-label">Y</span>
        <el-select v-model="yColumn" size="small" class="chart-select">
          <el-option v-for="c in numericColumns" :key="c" :label="c" :value="c" />
        </el-select>
        <span v-if="truncated" class="chart-truncated">{{ t('chart.truncated', { max: MAX_POINTS }) }}</span>
      </div>

      <div class="chart-body">
        <!-- 柱状 / 折线 -->
        <svg v-if="chartType !== 'pie'" :viewBox="`0 0 ${W} ${H}`" class="chart-svg" preserveAspectRatio="xMidYMid meet">
          <!-- Y 轴网格与刻度 -->
          <g v-for="(tick, i) in yTicks" :key="i">
            <line :x1="PAD_L" :y1="tick.y" :x2="W - PAD_R" :y2="tick.y" class="grid-line" />
            <text :x="PAD_L - 6" :y="tick.y + 4" text-anchor="end" class="axis-text">{{ tick.label }}</text>
          </g>
          <!-- X 轴 -->
          <line :x1="PAD_L" :y1="H - PAD_B" :x2="W - PAD_R" :y2="H - PAD_B" class="axis-line" />

          <!-- 柱状 -->
          <g v-if="chartType === 'bar'">
            <rect
              v-for="(p, i) in points"
              :key="i"
              :x="p.x - barWidth / 2"
              :y="p.y"
              :width="barWidth"
              :height="H - PAD_B - p.y"
              class="bar-rect"
            >
              <title>{{ p.label }}: {{ p.value }}</title>
            </rect>
          </g>

          <!-- 折线 -->
          <g v-else>
            <polyline :points="polyline" class="line-path" />
            <circle v-for="(p, i) in points" :key="i" :cx="p.x" :cy="p.y" r="3" class="line-dot">
              <title>{{ p.label }}: {{ p.value }}</title>
            </circle>
          </g>

          <!-- X 轴标签（抽样避免重叠） -->
          <g v-for="(p, i) in points" :key="'x' + i">
            <text
              v-if="i % labelStep === 0"
              :x="p.x"
              :y="H - PAD_B + 14"
              text-anchor="middle"
              class="axis-text"
            >{{ truncateLabel(p.label) }}</text>
          </g>
        </svg>

        <!-- 饼图 -->
        <div v-else class="pie-wrap">
          <svg :viewBox="`0 0 ${PIE_SIZE} ${PIE_SIZE}`" class="pie-svg" preserveAspectRatio="xMidYMid meet">
            <path v-for="(s, i) in pieSlices" :key="i" :d="s.d" :fill="s.color">
              <title>{{ s.label }}: {{ s.value }} ({{ s.percent }}%)</title>
            </path>
          </svg>
          <div class="pie-legend">
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
const H = 320
const PAD_L = 56
const PAD_R = 16
const PAD_T = 16
const PAD_B = 28
const PIE_SIZE = 280
const MAX_POINTS = 50

const PALETTE = ['#409eff', '#67c23a', '#e6a23c', '#f56c6c', '#909399', '#8e44ad', '#16a085', '#d35400', '#2c3e50', '#c0392b', '#27ae60', '#2980b9']

/** 判断某列是否数值列（非空值 80% 以上可转数字） */
function isNumericColumn(col: string): boolean {
  const values = props.rows.map(r => r[col]).filter(v => v !== null && v !== undefined && v !== '')
  if (!values.length) return false
  const numeric = values.filter(v => !isNaN(Number(v)))
  return numeric.length / values.length >= 0.8
}

const numericColumns = computed(() => props.columns.filter(isNumericColumn))
const xCandidates = computed(() => {
  const nonNumeric = props.columns.filter(c => !numericColumns.value.includes(c))
  return nonNumeric.length ? nonNumeric : props.columns
})

const xColumn = ref('')
const yColumn = ref('')
const chartType = ref<'bar' | 'line' | 'pie'>('bar')

/** 数据变化时重置轴选择并推荐图表类型 */
watch(
  () => [props.columns, props.rows],
  () => {
    if (!xCandidates.value.includes(xColumn.value)) xColumn.value = xCandidates.value[0] || ''
    if (!numericColumns.value.includes(yColumn.value)) yColumn.value = numericColumns.value[0] || ''
    chartType.value = recommendType()
  },
  { immediate: true, deep: false }
)

/** 类型推荐：X 像日期/时间 → 折线；类别 ≤8 → 饼图可读性好但默认柱状 */
function recommendType(): 'bar' | 'line' | 'pie' {
  const sample = props.rows[0]?.[xColumn.value]
  if (sample && /^\d{4}-\d{2}(-\d{2})?([ T]\d{2}:\d{2})?/.test(String(sample))) return 'line'
  return 'bar'
}

const truncated = computed(() => props.rows.length > MAX_POINTS)

const dataset = computed(() => {
  return props.rows.slice(0, MAX_POINTS).map((r, i) => ({
    label: xColumn.value ? String(r[xColumn.value] ?? '') : String(i + 1),
    value: Number(r[yColumn.value]) || 0,
  }))
})

const categories = computed(() => dataset.value.map(d => d.label))

const maxValue = computed(() => Math.max(...dataset.value.map(d => d.value), 0))
const minValue = computed(() => Math.min(...dataset.value.map(d => d.value), 0))

/** Y 轴刻度（5 档） */
const yTicks = computed(() => {
  const range = maxValue.value - minValue.value || 1
  const ticks = []
  for (let i = 0; i <= 4; i++) {
    const v = minValue.value + (range * i) / 4
    const y = H - PAD_B - ((v - minValue.value) / range) * (H - PAD_T - PAD_B)
    ticks.push({ y, label: formatNumber(v) })
  }
  return ticks
})

const points = computed(() => {
  const n = dataset.value.length
  if (!n) return []
  const plotW = W - PAD_L - PAD_R
  const range = maxValue.value - minValue.value || 1
  return dataset.value.map((d, i) => ({
    ...d,
    x: PAD_L + (plotW * (i + 0.5)) / n,
    y: H - PAD_B - ((d.value - minValue.value) / range) * (H - PAD_T - PAD_B),
  }))
})

const barWidth = computed(() => {
  const n = dataset.value.length || 1
  return Math.min(40, ((W - PAD_L - PAD_R) / n) * 0.6)
})

const polyline = computed(() => points.value.map(p => `${p.x},${p.y}`).join(' '))

const labelStep = computed(() => Math.max(1, Math.ceil(dataset.value.length / 12)))

/** 饼图扇区 */
const pieSlices = computed(() => {
  const total = dataset.value.reduce((s, d) => s + Math.max(d.value, 0), 0)
  if (total <= 0) return []
  const cx = PIE_SIZE / 2
  const cy = PIE_SIZE / 2
  const r = PIE_SIZE / 2 - 8
  let angle = -Math.PI / 2
  return dataset.value
    .filter(d => d.value > 0)
    .map((d, i) => {
      const sweep = (d.value / total) * Math.PI * 2
      const x1 = cx + r * Math.cos(angle)
      const y1 = cy + r * Math.sin(angle)
      angle += sweep
      const x2 = cx + r * Math.cos(angle)
      const y2 = cy + r * Math.sin(angle)
      const largeArc = sweep > Math.PI ? 1 : 0
      return {
        d: `M ${cx} ${cy} L ${x1} ${y1} A ${r} ${r} 0 ${largeArc} 1 ${x2} ${y2} Z`,
        color: PALETTE[i % PALETTE.length],
        label: d.label,
        value: d.value,
        percent: ((d.value / total) * 100).toFixed(1),
      }
    })
})

function formatNumber(v: number): string {
  if (Math.abs(v) >= 1_000_000) return (v / 1_000_000).toFixed(1) + 'M'
  if (Math.abs(v) >= 1_000) return (v / 1_000).toFixed(1) + 'K'
  return Number.isInteger(v) ? String(v) : v.toFixed(1)
}

function truncateLabel(s: string): string {
  return s.length > 8 ? s.slice(0, 8) + '…' : s
}
</script>

<style scoped>
.chart-view {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: var(--space-2) var(--space-3);
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
  gap: var(--space-2);
  flex-shrink: 0;
}

.chart-label {
  font-size: 12px;
  color: var(--color-text-muted);
}

.chart-select {
  width: 140px;
}

.chart-truncated {
  margin-left: auto;
  font-size: 12px;
  color: var(--color-text-muted);
}

.chart-body {
  flex: 1;
  overflow: hidden;
  margin-top: var(--space-2);
}

.chart-svg {
  width: 100%;
  height: 100%;
}

.grid-line {
  stroke: var(--color-border);
  stroke-dasharray: 3 3;
}

.axis-line {
  stroke: var(--color-border);
}

.axis-text {
  font-size: 10px;
  fill: var(--color-text-muted);
}

.bar-rect {
  fill: #409eff;
  opacity: 0.85;
}

.bar-rect:hover {
  opacity: 1;
}

.line-path {
  fill: none;
  stroke: #409eff;
  stroke-width: 2;
}

.line-dot {
  fill: #409eff;
}

.pie-wrap {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  height: 100%;
}

.pie-svg {
  height: 100%;
  max-height: 280px;
}

.pie-legend {
  overflow-y: auto;
  max-height: 100%;
  font-size: 12px;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: 2px 0;
}

.legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 2px;
  flex-shrink: 0;
}

.legend-label {
  max-width: 160px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.legend-value {
  color: var(--color-text-muted);
}
</style>
