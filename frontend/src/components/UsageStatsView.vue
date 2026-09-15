<script setup>
import { ref, onMounted, computed } from 'vue'
import { api } from '../api'
import { fmtTok, fmtDuration } from '../ui'
import DatePicker from './DatePicker.vue'
import AppButton from './AppButton.vue'

function isoDaysAgo(n) {
  const d = new Date()
  d.setDate(d.getDate() - n)
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}

const keys = ref([])
const filterKey = ref('')
const startAt = ref(isoDaysAgo(0))
const endAt = ref(isoDaysAgo(0))
const result = ref(null)
const loading = ref(false)
const error = ref('')

const items = computed(() => result.value?.items || [])
const total = computed(() => result.value?.total || null)

async function loadKeys() {
  try {
    keys.value = await api.keys()
  } catch (e) {
    /* ignore */
  }
}

async function search() {
  loading.value = true
  error.value = ''
  try {
    result.value = await api.usageStats({
      keyId: filterKey.value,
      startAt: startAt.value,
      endAt: endAt.value
    })
  } catch (e) {
    error.value = e.message
  }
  loading.value = false
}

onMounted(() => {
  loadKeys()
  search()
})

function fmtInt(n) {
  return n == null ? '—' : Number(n).toLocaleString()
}
function fmtPct(v) {
  return v == null ? '—' : `${v}%`
}
function fmtSpeed(v) {
  return v == null ? '—' : `${v} tok/s`
}
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-3">
      <h2 class="text-base font-semibold"><i class="fa-solid fa-chart-column mr-1.5 text-gh-blue"></i>Key 用量统计</h2>
      <span class="text-xs text-gh-muted">按 Key 统计所选区间内的 token 消耗</span>
    </div>

    <!-- filters -->
    <div class="flex flex-wrap items-end gap-2.5 mb-3">
      <div>
        <label class="block text-[11px] text-gh-muted mb-0.5">Key</label>
        <select v-model="filterKey" class="usage-input min-w-36">
          <option value="">全部</option>
          <option v-for="k in keys" :key="k.id" :value="k.id">{{ k.name }}（{{ k.keyPrefix }}…）</option>
        </select>
      </div>
      <div>
        <label class="block text-[11px] text-gh-muted mb-0.5">开始日期</label>
        <DatePicker v-model="startAt" />
      </div>
      <div>
        <label class="block text-[11px] text-gh-muted mb-0.5">结束日期</label>
        <DatePicker v-model="endAt" />
      </div>
      <AppButton
        variant="primary-green"
        :loading="loading" @click="search"
      ><i v-if="!loading" class="fa-solid fa-search mr-1"></i>查询</AppButton>
    </div>

    <p v-if="error" class="text-gh-red text-sm mb-3">{{ error }}</p>

    <div v-if="result" class="panel-tech overflow-x-auto">
      <table class="w-full min-w-[880px] text-sm">
        <thead>
          <tr class="border-b border-gh-border bg-gh-tag/60 text-left text-xs text-gh-muted">
            <th class="px-3 py-2">Key 名称</th>
            <th class="px-3 py-2 text-right">请求数</th>
            <th class="px-3 py-2 text-right">总 tok</th>
            <th class="px-3 py-2 text-right">输入 tok</th>
            <th class="px-3 py-2 text-right">输出 tok</th>
            <th class="px-3 py-2 text-right">缓存 tok</th>
            <th class="px-3 py-2 text-right">命中率</th>
            <th class="px-3 py-2 text-right">平均速度</th>
            <th class="px-3 py-2 text-right">平均耗时</th>
            <th class="px-3 py-2 text-right">错误数</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="items.length === 0">
            <td colspan="10" class="px-4 py-10 text-center text-gh-muted">暂无数据</td>
          </tr>
          <tr v-for="i in items" :key="i.keyId" class="border-b border-gh-border/40 hover:bg-gh-cyan/5 transition-colors">
            <td class="px-3 py-1.5 text-xs">{{ i.name }}</td>
            <td class="px-3 py-1.5 text-right font-mono text-xs">{{ fmtInt(i.count) }}</td>
              <td class="px-3 py-1.5 text-right font-mono text-xs font-semibold">{{ fmtTok(i.totalTokens) }}</td>
              <td class="px-3 py-1.5 text-right font-mono text-xs">{{ fmtTok(i.promptTokens) }}</td>
              <td class="px-3 py-1.5 text-right font-mono text-xs">{{ fmtTok(i.completionTokens) }}</td>
            <td class="px-3 py-1.5 text-right font-mono text-xs">{{ fmtTok(i.cachedTokens) }}</td>
            <td class="px-3 py-1.5 text-right font-mono text-xs">{{ fmtPct(i.cacheHitRate) }}</td>
            <td class="px-3 py-1.5 text-right font-mono text-xs">{{ fmtSpeed(i.avgSpeed) }}</td>
            <td class="px-3 py-1.5 text-right font-mono text-xs">{{ fmtDuration(i.avgDuration) }}</td>
            <td class="px-3 py-1.5 text-right font-mono text-xs" :class="Number(i.errors) > 0 ? 'text-gh-red font-semibold' : 'text-gh-muted'">
              {{ fmtInt(i.errors) }}
            </td>
          </tr>
          <!-- 合计 -->
          <tr v-if="items.length > 0" class="bg-gh-cyan/[0.06] font-medium border-t border-gh-cyan/20">
            <td class="px-3 py-2 text-xs">合计</td>
            <td class="px-3 py-2 text-right font-mono text-xs">{{ fmtInt(total.count) }}</td>
              <td class="px-3 py-2 text-right font-mono text-xs font-semibold">{{ fmtTok(total.totalTokens) }}</td>
              <td class="px-3 py-2 text-right font-mono text-xs">{{ fmtTok(total.promptTokens) }}</td>
              <td class="px-3 py-2 text-right font-mono text-xs">{{ fmtTok(total.completionTokens) }}</td>
            <td class="px-3 py-2 text-right font-mono text-xs">{{ fmtTok(total.cachedTokens) }}</td>
            <td class="px-3 py-2 text-right font-mono text-xs">{{ fmtPct(total.cacheHitRate) }}</td>
            <td class="px-3 py-2 text-right font-mono text-xs">—</td>
            <td class="px-3 py-2 text-right font-mono text-xs">—</td>
            <td class="px-3 py-2 text-right font-mono text-xs">—</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.usage-input {
  height: 30px;
  padding: 0 8px;
  font-size: 12px;
  background: #0a111d;
  border: 1px solid var(--color-gh-border);
  border-radius: 6px;
  color: var(--color-gh-text);
  outline: none;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}
.usage-input:focus {
  border-color: #22d3ee;
  box-shadow: 0 0 0 3px rgba(34, 211, 238, 0.14);
}
</style>
