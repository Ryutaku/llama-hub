<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({
  total: { type: Number, default: 0 },
  page: { type: Number, default: 1 },
  size: { type: Number, default: 20 }
})

const emit = defineEmits(['update:page', 'update:size', 'change'])

const jump = ref('')
const SIZES = [10, 20, 50, 100]

const totalPages = computed(() => Math.max(1, Math.ceil(props.total / props.size)))
const cur = computed(() => Math.min(Math.max(props.page, 1), totalPages.value))
const start = computed(() => (props.total === 0 ? 0 : (cur.value - 1) * props.size + 1))
const end = computed(() => (props.total === 0 ? 0 : Math.min(cur.value * props.size, props.total)))

watch([() => props.total, () => props.size], () => {
  if (props.page > totalPages.value) {
    emit('update:page', totalPages.value)
    emit('change')
  }
})

const pageItems = computed(() => {
  const c = cur.value
  const t = totalPages.value
  if (t <= 5) return Array.from({ length: t }, (_, i) => i + 1)
  const items = [1]
  if (c > 3) items.push('gap-start')
  for (let p = Math.max(2, c - 1); p <= Math.min(t - 1, c + 1); p++) items.push(p)
  if (c < t - 2) items.push('gap-end')
  items.push(t)
  return items
})

function fmt(n) {
  return Number(n).toLocaleString()
}

function go(p) {
  const n = Math.min(Math.max(p, 1), totalPages.value)
  if (n === props.page) return
  emit('update:page', n)
  emit('change')
}

function setSize(s) {
  if (s === props.size) return
  emit('update:size', s)
  emit('update:page', 1)
  emit('change')
}

function doJump() {
  const n = parseInt(jump.value, 10)
  if (Number.isNaN(n) || n < 1) return
  go(n)
  jump.value = ''
}
</script>

<template>
  <div v-if="total === 0" class="mt-3 text-xs text-gh-muted">共 0 条</div>
  <div v-else class="pagination-bar">
    <span class="pagination-stats">共 {{ fmt(total) }} 条</span>

    <div class="pagination-controls">
      <label class="flex items-center gap-1.5">
        <select class="page-size-select" :value="size" @change="setSize(Number($event.target.value))">
          <option v-for="s in SIZES" :key="s" :value="s">{{ s }}</option>
        </select>
        <span class="text-xs text-gh-muted whitespace-nowrap">条 / 页</span>
      </label>

      <div v-if="totalPages > 1" class="flex items-center gap-1">
        <button type="button" class="page-btn" title="首页" :disabled="cur <= 1" @click="go(1)">
          <i class="fa-solid fa-angles-left text-[10px]"></i>
        </button>
        <button type="button" class="page-btn" title="上一页" :disabled="cur <= 1" @click="go(cur - 1)">
          <i class="fa-solid fa-chevron-left text-xs"></i>
        </button>
        <template v-for="it in pageItems" :key="String(it)">
          <span v-if="typeof it === 'string'" class="page-ellipsis">…</span>
          <button
            v-else
            type="button"
            class="page-btn"
            :class="{ 'page-active': it === cur }"
            @click="go(it)"
          >{{ it }}</button>
        </template>
        <button type="button" class="page-btn" title="下一页" :disabled="cur >= totalPages" @click="go(cur + 1)">
          <i class="fa-solid fa-chevron-right text-xs"></i>
        </button>
        <button type="button" class="page-btn" title="末页" :disabled="cur >= totalPages" @click="go(totalPages)">
          <i class="fa-solid fa-angles-right text-[10px]"></i>
        </button>

        <div class="flex items-center gap-1.5 ml-2">
          <input
            v-model="jump"
            type="number"
            class="jump-input"
            :min="1"
            :max="totalPages"
            @keyup.enter="doJump"
          />
          <span class="text-xs text-gh-muted whitespace-nowrap">/ {{ totalPages }} 页</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.pagination-bar {
  margin-top: 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.pagination-stats {
  font-size: 12px;
  color: var(--color-gh-muted);
  font-variant-numeric: tabular-nums;
}

.pagination-controls {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.page-size-select {
  height: 30px;
  padding: 0 26px 0 10px;
  border: 1px solid var(--color-gh-border);
  border-radius: 8px;
  background-color: #0a111d;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='12' viewBox='0 0 12 12' fill='none'%3E%3Cpath d='M2.5 4.5L6 8L9.5 4.5' stroke='%237e90a9' stroke-width='1.5' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E");
  background-repeat: no-repeat;
  background-position: right 8px center;
  background-size: 12px;
  color: var(--color-gh-text);
  font-size: 12px;
  cursor: pointer;
  appearance: none;
  -webkit-appearance: none;
  transition: border-color 0.15s ease, box-shadow 0.15s ease, background-color 0.15s ease;
}
.page-size-select:hover {
  border-color: rgba(34, 211, 238, 0.45);
}
.page-size-select:focus {
  border-color: var(--color-gh-cyan);
  outline: none;
  box-shadow: 0 0 0 3px rgba(34, 211, 238, 0.16);
}

.page-btn {
  width: 30px;
  height: 30px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--color-gh-border);
  border-radius: 8px;
  background-color: #0a111d;
  color: var(--color-gh-text);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  user-select: none;
  flex: 0 0 auto;
  transition: background-color 0.12s ease, color 0.12s ease, border-color 0.12s ease,
    transform 0.08s ease, box-shadow 0.12s ease;
}
.page-btn:hover:not(:disabled):not(.page-active) {
  border-color: rgba(34, 211, 238, 0.45);
  background-color: #101c2e;
}
.page-btn:active:not(:disabled):not(.page-active) {
  transform: scale(0.94);
  background-color: #16233a;
}
.page-btn:disabled:not(.page-active) {
  color: #4d5f78;
  background-color: #0a111d;
  cursor: not-allowed;
}
.page-btn.page-active {
  border-color: transparent;
  background: var(--color-gh-green);
  color: #04121a;
  font-weight: 700;
  box-shadow: 0 0 14px -3px rgba(34, 211, 238, 0.7);
}

.page-ellipsis {
  width: 22px;
  height: 30px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--color-gh-muted);
  font-size: 12px;
  user-select: none;
  flex: 0 0 auto;
}

.jump-input {
  width: 52px;
  height: 30px;
  padding: 0 6px;
  text-align: center;
  border: 1px solid var(--color-gh-border);
  border-radius: 8px;
  background-color: #0a111d;
  font-size: 12px;
  font-family: var(--font-mono);
  color: var(--color-gh-text);
  outline: none;
  font-variant-numeric: tabular-nums;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}
.jump-input:focus {
  border-color: var(--color-gh-cyan);
  box-shadow: 0 0 0 3px rgba(34, 211, 238, 0.16);
}
.jump-input::-webkit-outer-spin-button,
.jump-input::-webkit-inner-spin-button {
  -webkit-appearance: none;
  margin: 0;
}
</style>
