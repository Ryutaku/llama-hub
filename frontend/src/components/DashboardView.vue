<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { api } from '../api'
import { fmtTok } from '../ui'
import AppButton from './AppButton.vue'
import EChart from './EChart.vue'

const props = defineProps({
  upstream: { type: Object, default: () => ({ up: false, latencyMs: null }) },
  refresh: { type: Boolean, default: false }
})

const loading = ref(true)
const error = ref('')
const data = ref(null)

function useCountUp(duration = 900) {
  const display = ref(0)
  let raf = null
  function to(target) {
    const from = display.value
    const t = Number(target) || 0
    if (from === t) {
      display.value = t
      return
    }
    const start = performance.now()
    cancelAnimationFrame(raf)
    const step = now => {
      const p = Math.min(1, (now - start) / duration)
      const e = 1 - Math.pow(1 - p, 3)
      display.value = from + (t - from) * e
      if (p < 1) raf = requestAnimationFrame(step)
    }
    raf = requestAnimationFrame(step)
  }
  function cancel() {
    cancelAnimationFrame(raf)
  }
  return { display, to, cancel }
}

const cuReq = useCountUp()
const cuTok = useCountUp()
const cuRate = useCountUp()
const cuKey = useCountUp()

watch(data, d => {
  if (!d) return
  cuReq.to(d.todayRequests)
  cuTok.to(d.todayTokens)
  cuRate.to(d.todayCacheHitRate)
  cuKey.to(d.activeKeys)
})

onBeforeUnmount(() => {
  ;[cuReq, cuTok, cuRate, cuKey].forEach(c => c.cancel())
})

const topMax = computed(() => {
  const keys = data.value?.topKeys || []
  return Math.max(1, ...keys.map(k => Number(k.count) || 0))
})

const barsIn = ref(false)

async function load() {
  try {
    data.value = await api.dashboard()
    error.value = ''
    barsIn.value = false
    await nextTick()
    barsIn.value = true
  } catch (e) {
    error.value = e.message
  }
  loading.value = false
}

onMounted(load)

function fmtInt(n) {
  return (n == null)
    ? '—'
    : Number(n).toLocaleString()
}

function barWidth(v) {
  if (v == null || v <= 0) return 0
  return Math.min(100, Math.round(v * 100))
}

const AXES = {
  xAxis: {
    type: 'category',
    axisLine: { lineStyle: { color: 'rgba(126,144,169,0.25)' } },
    axisTick: { show: false },
    axisLabel: { color: '#7e90a9', fontSize: 10, fontFamily: 'monospace' }
  },
  yAxis: {
    type: 'value',
    splitLine: { lineStyle: { color: 'rgba(126,144,169,0.14)', type: 'dashed' } },
    axisLine: { show: false },
    axisTick: { show: false },
    axisLabel: { color: '#7e90a9', fontSize: 10, fontFamily: 'monospace' }
  },
  grid: { left: 4, right: 8, top: 14, bottom: 4, containLabel: true }
}

function baseTooltip(unit, fmt) {
  return {
    trigger: 'axis',
    backgroundColor: 'rgba(10,17,29,0.95)',
    borderColor: 'rgba(34,211,238,0.35)',
    borderWidth: 1,
    padding: [6, 10],
    textStyle: { color: '#d9e4f2', fontSize: 12 },
    valueFormatter: v => `${fmt(Number(v) || 0)}${unit ? ' ' + unit : ''}`
  }
}

const hasTrend = computed(() => (data.value?.trend || []).length > 0)

const reqChartOption = computed(() => {
  const trend = data.value?.trend || []
  return {
    ...AXES,
    animationDuration: 1000,
    animationEasing: 'cubicOut',
    xAxis: { ...AXES.xAxis, data: trend.map(d => d.date) },
    tooltip: baseTooltip('', fmtInt),
    series: [{
      name: '请求数',
      type: 'line',
      data: trend.map(d => d.count),
      animationDuration: 1200,
      smooth: true,
      symbol: 'circle',
      symbolSize: 5,
      lineStyle: { color: '#22d3ee', width: 2 },
      itemStyle: { color: '#22d3ee' },
      emphasis: { disabled: true },
      areaStyle: {
        color: {
          type: 'linear', x1: 0, y1: 0, x2: 0, y2: 1,
          colorStops: [
            { offset: 0, color: 'rgba(34,211,238,0.28)' },
            { offset: 1, color: 'rgba(34,211,238,0)' }
          ]
        }
      }
    }]
  }
})

const tokChartOption = computed(() => {
  const trend = data.value?.trend || []
  const num = v => Math.max(0, Number(v) || 0)
  return {
    ...AXES,
    animationDuration: 800,
    animationEasing: 'cubicOut',
    grid: { left: 4, right: 8, top: 26, bottom: 4, containLabel: true },
    xAxis: { ...AXES.xAxis, data: trend.map(d => d.date) },
    yAxis: {
      ...AXES.yAxis,
      axisLabel: { ...AXES.yAxis.axisLabel, formatter: v => fmtTok(v) }
    },
    tooltip: baseTooltip('', v => fmtTok(v)),
    legend: {
      top: 0,
      right: 0,
      itemWidth: 10,
      itemHeight: 8,
      itemGap: 12,
      textStyle: { color: '#7e90a9', fontSize: 10 }
    },
    series: [
      {
        name: '缓存',
        type: 'bar',
        stack: 'tok',
        data: trend.map(d => num(d.cachedTokens)),
        barMaxWidth: 26,
        animationDelay: i => i * 40,
        itemStyle: { color: 'rgba(25,181,132,0.4)' }
      },
      {
        name: '输入',
        type: 'bar',
        stack: 'tok',
        data: trend.map(d => num(d.promptTokens) - num(d.cachedTokens)),
        barMaxWidth: 26,
        animationDelay: i => 100 + i * 40,
        itemStyle: { color: '#19b584' }
      },
      {
        name: '输出',
        type: 'bar',
        stack: 'tok',
        data: trend.map(d => num(d.completionTokens)),
        barMaxWidth: 26,
        animationDelay: i => 200 + i * 40,
        itemStyle: { color: '#22d3ee', borderRadius: [4, 4, 0, 0] }
      }
    ]
  }
})
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-3">
      <h2 class="text-base font-semibold"><i class="fa-solid fa-gauge-high mr-1.5 text-gh-cyan"></i>用量仪表盘</h2>
      <AppButton variant="secondary" :loading="loading" @click="loading = true; load()"
      ><i class="fa-solid fa-rotate-right mr-1" :class="{ 'fa-spin': loading }"></i>刷新</AppButton>
    </div>

    <p v-if="error" class="text-gh-red text-sm mb-3">{{ error }}</p>

    <!-- stat cards -->
    <div class="grid grid-cols-2 lg:grid-cols-4 gap-3 mb-4">
      <div class="panel-tech p-3.5 dash-card">
        <div class="text-xs text-gh-muted mb-2 flex items-center justify-between">
          今日请求数
          <span class="chip chip-cyan"><i class="fa-solid fa-left-right"></i></span>
        </div>
        <div class="text-2xl font-mono font-bold num-glow">{{ loading ? '—' : fmtInt(Math.round(cuReq.display.value)) }}</div>
      </div>
      <div class="panel-tech p-3.5 dash-card" style="animation-delay: 60ms">
        <div class="text-xs text-gh-muted mb-2 flex items-center justify-between">
          今日 Tokens 消耗
          <span class="chip chip-green"><i class="fa-solid fa-coins"></i></span>
        </div>
        <div class="text-2xl font-mono font-bold num-glow">{{ loading ? '—' : fmtTok(Math.round(cuTok.display.value)) }}</div>
      </div>
      <div class="panel-tech p-3.5 dash-card" style="animation-delay: 120ms">
        <div class="text-xs text-gh-muted mb-2 flex items-center justify-between">
          今日缓存命中率
          <span class="chip chip-amber"><i class="fa-solid fa-bolt-lightning"></i></span>
        </div>
        <div class="text-2xl font-mono font-bold num-glow">{{ loading ? '—' : `${Math.round(cuRate.display.value * 10) / 10}%` }}</div>
      </div>
      <div class="panel-tech p-3.5 dash-card" style="animation-delay: 180ms">
        <div class="text-xs text-gh-muted mb-2 flex items-center justify-between">
          活跃 Key 数
          <span class="chip chip-slate"><i class="fa-solid fa-key"></i></span>
        </div>
        <div class="text-2xl font-mono font-bold num-glow">{{ loading ? '—' : fmtInt(Math.round(cuKey.display.value)) }}</div>
      </div>
    </div>

    <!-- trend charts -->
    <div class="grid grid-cols-1 lg:grid-cols-2 gap-3 mb-4">
      <div class="panel-tech p-3.5 dash-card" style="animation-delay: 220ms">
        <div class="flex items-center gap-2 text-sm text-gh-muted mb-2">
          <i class="fa-solid fa-chart-line mr-1.5 text-gh-cyan"></i>
          近 7 天请求量
        </div>
        <EChart v-if="hasTrend" :option="reqChartOption" height="200px" />
        <div v-else class="h-[200px] flex items-center justify-center text-gh-muted text-sm">
          近 7 天暂无数据
        </div>
      </div>
      <div class="panel-tech p-3.5 dash-card" style="animation-delay: 280ms">
        <div class="flex items-center gap-2 text-sm text-gh-muted mb-2">
          <i class="fa-solid fa-coins mr-1.5 text-gh-green"></i>
          近 7 天 Tokens 消耗
        </div>
        <EChart v-if="hasTrend" :option="tokChartOption" height="200px" />
        <div v-else class="h-[200px] flex items-center justify-center text-gh-muted text-sm">
          近 7 天暂无数据
        </div>
      </div>
    </div>

    <div class="grid grid-cols-1 lg:grid-cols-2 gap-3">
      <!-- top keys -->
      <div class="panel-tech p-3.5 dash-card" style="animation-delay: 340ms">
        <div class="text-sm text-gh-muted mb-3"><i class="fa-solid fa-ranking-star mr-1.5 text-gh-cyan"></i>今日 Top 5 活跃 Key</div>
        <div v-if="!data?.topKeys || data.topKeys.length === 0" class="text-gh-muted text-sm py-6 text-center">
          今日暂无调用
        </div>
        <div v-else class="space-y-3">
          <div v-for="(k, i) in data.topKeys" :key="k.keyId">
            <div class="flex justify-between text-sm mb-1.5">
              <div class="flex items-center gap-2">
                <span class="font-mono text-xs w-4 text-gh-cyan">{{ String(i + 1).padStart(2, '0') }}</span>
                <span class="truncate">{{ k.name }}</span>
              </div>
              <span class="text-xs text-gh-muted font-mono whitespace-nowrap">
                {{ k.count }} 次 · {{ fmtTok(k.tokens) }} tok
              </span>
            </div>
            <div class="h-1.5 bg-gh-tag rounded-full overflow-hidden">
              <div
                class="topbar-fill h-full rounded-full bg-gradient-to-r from-gh-cyan to-gh-green"
                :style="{ width: (barsIn ? barWidth(k.count / topMax) : 0) + '%', boxShadow: '0 0 8px rgba(34,211,238,0.5)' }"
              />
            </div>
          </div>
        </div>
      </div>

      <!-- upstream status -->
      <div class="panel-tech p-3.5 dash-card" style="animation-delay: 400ms">
        <div class="text-sm text-gh-muted mb-3"><i class="fa-solid fa-server mr-1.5 text-gh-green"></i>上游状态（llama-server）</div>
        <div class="flex items-center gap-3.5">
          <span
            class="w-3.5 h-3.5 rounded-full"
            :class="props.upstream?.up ? 'dot-live bg-gh-green' : 'bg-gh-red'"
            :style="{ color: props.upstream?.up ? '#19b584' : '#f0455c' }"
          />
          <div>
            <div class="text-sm font-semibold" :class="props.upstream?.up ? 'text-gh-green' : 'text-gh-red'">
              {{ props.upstream?.up ? '正常' : '不可达' }}
            </div>
            <div class="text-xs text-gh-muted font-mono mt-0.5">
              {{ props.upstream?.latencyMs != null && props.upstream.latencyMs >= 0
                ? `延迟 ${props.upstream.latencyMs} ms`
                : '最近探测失败' }}
            </div>
          </div>
          <div v-if="data?.upstream?.lastCheckedAt" class="ml-auto text-xs text-gh-muted font-mono">
            每 30s 自动探测
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
@keyframes dash-in {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.dash-card {
  animation: dash-in 0.5s cubic-bezier(0.22, 1, 0.36, 1) both;
}

.topbar-fill {
  transition: width 0.9s cubic-bezier(0.22, 1, 0.36, 1);
}

@media (prefers-reduced-motion: reduce) {
  .dash-card {
    animation: none;
  }
  .topbar-fill {
    transition: none;
  }
}
</style>
