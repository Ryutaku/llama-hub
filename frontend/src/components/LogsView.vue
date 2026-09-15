<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api'
import { toast, fmtTok, fmtDuration } from '../ui'
import DatePicker from './DatePicker.vue'
import AppButton from './AppButton.vue'
import Pagination from './Pagination.vue'

function today() {
  const d = new Date()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}

const keys = ref([])
const logs = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(15)
const loading = ref(false)
const error = ref('')
const expanded = ref(null)
const exporting = ref(false)

const filterKey = ref('')
const startAt = ref(today())
const endAt = ref(today())

async function load() {
  loading.value = true
  try {
    const r = await api.logs({
      page: page.value,
      size: size.value,
      keyId: filterKey.value,
      startAt: startAt.value,
      endAt: endAt.value
    })
    logs.value = r.content
    total.value = r.total
    error.value = ''
  } catch (e) {
    error.value = e.message
  }
  loading.value = false
}

async function loadKeys() {
  try {
    keys.value = await api.keys()
  } catch (e) {
    /* ignore */
  }
}

onMounted(() => {
  load()
  loadKeys()
})

function search() {
  page.value = 1
  load()
}

async function doExport() {
  exporting.value = true
  try {
    await api.exportLogs({
      keyId: filterKey.value,
      startAt: startAt.value,
      endAt: endAt.value
    })
    toast('导出成功', 'success')
  } catch (e) {
    toast(e.message, 'error')
  }
  exporting.value = false
}

function fmtTime(t) {
  if (!t) return '—'
  return String(t).replace('T', ' ').slice(0, 16)
}
function fmtRate(v) {
  return v == null ? '—' : `${v} tok/s`
}
function fmtHit(v) {
  return v == null ? '—' : `${(v * 100).toFixed(2)}%`
}

function statusColor(code) {
  if (code == null) return 'text-gh-muted'
  if (code < 300) return 'text-gh-green'
  if (code < 500) return 'text-gh-orange'
  return 'text-gh-red'
}

function toggle(row) {
  expanded.value = expanded.value === row.id ? null : row.id
}
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-3">
      <h2 class="text-base font-semibold"><i class="fa-solid fa-table-list mr-1.5 text-gh-cyan"></i>调用日志</h2>
      <AppButton
        variant="secondary"
        :loading="exporting" :disabled="exporting" @click="doExport"
      ><i v-if="!exporting" class="fa-solid fa-download mr-1"></i>{{ exporting ? '导出中…' : '导出 CSV' }}</AppButton>
    </div>

    <!-- filters -->
    <div class="flex flex-wrap items-end gap-2.5 mb-3">
      <div>
        <label class="block text-[11px] text-gh-muted mb-0.5">Key</label>
        <select v-model="filterKey" class="input min-w-36">
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

    <div class="panel-tech overflow-x-auto">
      <table class="w-full min-w-[1000px] text-sm">
        <thead>
          <tr class="border-b border-gh-border bg-gh-tag/60 text-left text-xs text-gh-muted">
            <th class="px-3 py-2">时间</th>
            <th class="px-3 py-2">Key</th>
            <th class="px-3 py-2">端点</th>
            <th class="px-3 py-2 text-right">总 tok</th>
            <th class="px-3 py-2 text-right">入 tok</th>
            <th class="px-3 py-2 text-right">出 tok</th>
            <th class="px-3 py-2 text-right">缓存 tok</th>
            <th class="px-3 py-2 text-right">命中率</th>
            <th class="px-3 py-2 text-right">速度</th>
            <th class="px-3 py-2 text-right">耗时</th>
            <th class="px-3 py-2 text-right">状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!loading && logs.length === 0">
            <td colspan="11" class="px-4 py-10 text-center text-gh-muted">暂无日志</td>
          </tr>
          <template v-for="l in logs" :key="l.id">
            <tr class="border-b border-gh-border/40 hover:bg-gh-cyan/5 active:bg-gh-cyan/10 cursor-pointer transition-colors" @click="toggle(l)">
              <td class="px-3 py-1.5 text-xs text-gh-muted font-mono whitespace-nowrap">{{ fmtTime(l.startedAt) }}</td>
              <td class="px-3 py-1.5 text-xs">{{ l.keyName }}</td>
              <td class="px-3 py-1.5 text-xs font-mono text-gh-muted">{{ l.endpoint }}</td>
              <td class="px-3 py-1.5 text-right font-mono text-xs">{{ fmtTok(l.totalTokens) }}</td>
              <td class="px-3 py-1.5 text-right font-mono text-xs">{{ fmtTok(l.promptTokens) }}</td>
              <td class="px-3 py-1.5 text-right font-mono text-xs">{{ fmtTok(l.completionTokens) }}</td>
              <td class="px-3 py-1.5 text-right font-mono text-xs">{{ fmtTok(l.cachedTokens) }}</td>
              <td class="px-3 py-1.5 text-right font-mono text-xs">{{ fmtHit(l.cacheHitRate) }}</td>
              <td class="px-3 py-1.5 text-right font-mono text-xs">{{ fmtRate(l.tokensPerSec) }}</td>
              <td class="px-3 py-1.5 text-right font-mono text-xs">{{ fmtDuration(l.durationMs) }}</td>
              <td class="px-3 py-1.5 text-right font-mono text-xs font-semibold" :class="statusColor(l.statusCode)">
                {{ l.statusCode ?? '—' }}
              </td>
            </tr>
            <tr v-if="expanded === l.id" class="border-b border-gh-border bg-gh-cyan/[0.04]">
              <td colspan="11" class="px-3 py-3">
                <div class="text-xs text-gh-muted mb-1">模型：<span class="text-gh-text font-mono">{{ l.model || '—' }}</span></div>
                <div v-if="l.errorMsg" class="text-xs mb-2">错误信息：<span class="text-gh-red font-mono break-all">{{ l.errorMsg }}</span></div>
                <div v-if="l.requestBody" class="mb-2">
                  <div class="text-xs text-gh-muted mb-1">请求体</div>
                  <pre class="text-xs bg-[#0a111d] border border-gh-border rounded-md p-2.5 overflow-x-auto max-h-56 font-mono">{{ l.requestBody }}</pre>
                </div>
                <div v-if="l.responseBody">
                  <div class="text-xs text-gh-muted mb-1">响应体</div>
                  <pre class="text-xs bg-[#0a111d] border border-gh-border rounded-md p-2.5 overflow-x-auto max-h-56 font-mono">{{ l.responseBody }}</pre>
                </div>
                <div v-if="!l.requestBody && !l.responseBody && !l.errorMsg" class="text-xs text-gh-muted">
                  未记录请求体/响应体（可在 application.yml 开启 gateway.log.body-enabled）
                </div>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>

    <Pagination v-model:page="page" v-model:size="size" :total="total" @change="load" />
  </div>
</template>

<style scoped>
.input {
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
.input:focus {
  border-color: #22d3ee;
  box-shadow: 0 0 0 3px rgba(34, 211, 238, 0.14);
}
</style>
