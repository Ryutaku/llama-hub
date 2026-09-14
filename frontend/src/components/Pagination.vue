<script setup>
import { computed, ref } from 'vue'

const props = defineProps({
  total: { type: Number, default: 0 },
  page: { type: Number, default: 1 },
  size: { type: Number, default: 50 }
})

const emit = defineEmits(['update:page', 'update:size', 'change'])

const jump = ref('')
const SIZES = [10, 20, 50, 100]

const totalPages = computed(() => Math.max(1, Math.ceil(props.total / props.size)))
const cur = computed(() => Math.min(Math.max(props.page, 1), totalPages.value))
const start = computed(() => (props.total === 0 ? 0 : (cur.value - 1) * props.size + 1))
const end = computed(() => (props.total === 0 ? 0 : Math.min(cur.value * props.size, props.total)))

const pageItems = computed(() => {
  const c = cur.value
  const t = totalPages.value
  if (t <= 7) return Array.from({ length: t }, (_, i) => i + 1)
  if (c <= 4) return [1, 2, 3, 4, 5, 'gap-end', t]
  if (c >= t - 3) return [1, 'gap-start', t - 4, t - 3, t - 2, t - 1, t]
  return [1, 'gap-start', c - 1, c, c + 1, 'gap-end', t]
})

function fmt(n) {
  return Number(n).toLocaleString()
}

function go(p) {
  const n = Math.min(Math.max(p, 1), totalPages.value)
  if (n === cur.value) return
  emit('update:page', n)
  emit('change')
}

function setSize(s) {
  emit('update:size', s)
  if (cur.value !== 1) emit('update:page', 1)
  emit('change')
}

function doJump() {
  const n = parseInt(jump.value, 10)
  if (!Number.isNaN(n) && n >= 1) go(n)
  jump.value = ''
}
</script>

<template>
  <div class="pagination-bar">
    <div class="pagination-left">
      <span class="pagination-stats">
        {{ total > 0 ? `显示第 ${fmt(start)} 到 ${fmt(end)} 条，共 ${fmt(total)} 条` : '共 0 条' }}
      </span>
      <select
        class="page-size-select"
        :value="size"
        @change="setSize(Number($event.target.value))"
      >
        <option v-for="s in SIZES" :key="s" :value="s">{{ s }}</option>
      </select>
      <span class="text-xs text-gh-muted whitespace-nowrap">条 / 页</span>
    </div>

    <div v-if="totalPages > 1" class="pagination-right">
      <button type="button" class="page-btn" title="首页" :disabled="cur <= 1" @click="go(1)">
        <i class="fa-solid fa-angles-left text-[10px]"></i>
      </button>
      <button type="button" class="page-btn" title="上一页" :disabled="cur <= 1" @click="go(cur - 1)">
        <i class="fa-solid fa-chevron-left"></i>
      </button>
      <template v-for="it in pageItems" :key="typeof it === 'string' ? it : 'p' + it">
        <span v-if="typeof it === 'string'" class="page-ellipsis">…</span>
        <button
          v-else
          type="button"
          class="page-btn"
          :class="{ active: it === cur }"
          :disabled="it === cur"
          @click="go(it)"
        >{{ it }}</button>
      </template>
      <button type="button" class="page-btn" title="下一页" :disabled="cur >= totalPages" @click="go(cur + 1)">
        <i class="fa-solid fa-chevron-right"></i>
      </button>
      <button type="button" class="page-btn" title="末页" :disabled="cur >= totalPages" @click="go(totalPages)">
        <i class="fa-solid fa-angles-right text-[10px]"></i>
      </button>
    </div>

    <div v-if="totalPages > 1" class="pagination-jump">
      <input
        v-model="jump"
        type="number"
        class="jump-input"
        min="1"
        :max="totalPages"
        @keyup.enter="doJump"
      />
      <span class="text-xs text-gh-muted font-mono whitespace-nowrap">{{ cur }} / {{ totalPages }}</span>
      <button type="button" class="jump-btn" @click="doJump">跳转</button>
    </div>
  </div>
</template>

<style scoped>
.pagination-bar {
  margin-top: 12px;
  min-height: 48px;
  padding: 8px 2px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.pagination-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  white-space: nowrap;
}

.pagination-stats {
  min-width: 230px;
  font-size: 12px;
  color: var(--color-gh-muted);
  font-variant-numeric: tabular-nums;
}

.page-size-select {
  width: 60px;
  height: 32px;
  padding: 5px 26px 5px 10px;
  border: 1px solid var(--color-gh-border);
  border-radius: 8px;
  background-color: #ffffff;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='12' viewBox='0 0 12 12' fill='none'%3E%3Cpath d='M2.5 4.5L6 8L9.5 4.5' stroke='%2357606a' stroke-width='1.5' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E");
  background-repeat: no-repeat;
  background-position: right 9px center;
  background-size: 12px;
  color: var(--color-gh-text);
  font-size: 12px;
  line-height: 20px;
  cursor: pointer;
  appearance: none;
  -webkit-appearance: none;
  transition: border-color 0.15s ease, box-shadow 0.15s ease, background-color 0.15s ease;
}
.page-size-select:hover {
  background-color: #f6f8fa;
}
.page-size-select:focus {
  border-color: var(--color-gh-green);
  outline: none;
  box-shadow: 0 0 0 3px rgba(31, 136, 61, 0.16);
}

.pagination-right {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 0;
}

.page-btn,
.page-ellipsis {
  width: 32px;
  height: 32px;
  margin-left: -1px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--color-gh-border);
  background-color: #ffffff;
  color: var(--color-gh-text);
  font-size: 12px;
  font-weight: 500;
  line-height: 20px;
  flex: 0 0 32px;
  user-select: none;
  transition: background-color 0.12s ease, color 0.12s ease, border-color 0.12s ease,
    transform 0.08s ease, box-shadow 0.12s ease;
}
.page-btn:first-child {
  margin-left: 0;
  border-radius: 8px 0 0 8px;
}
.page-btn:last-child {
  border-radius: 0 8px 8px 0;
}
.page-btn:hover:not(:disabled):not(.active) {
  background-color: #eaeef2;
  z-index: 1;
}
.page-btn:active:not(:disabled):not(.active) {
  transform: scale(0.94);
  background-color: #e1e4e8;
}
.page-btn:disabled:not(.active) {
  color: #8c959f;
  background-color: #f6f8fa;
  cursor: not-allowed;
}
.page-btn.active,
.page-btn.active:disabled {
  z-index: 2;
  border-color: var(--color-gh-green);
  background-color: var(--color-gh-green);
  color: #ffffff;
  font-weight: 600;
  cursor: default;
  box-shadow: 0 1px 3px rgba(31, 136, 61, 0.35);
}
.page-ellipsis {
  color: var(--color-gh-muted);
  background-color: #f6f8fa;
  pointer-events: none;
}

.pagination-jump {
  display: flex;
  align-items: center;
  gap: 6px;
}
.jump-input {
  width: 52px;
  height: 32px;
  padding: 0 6px;
  text-align: center;
  border: 1px solid var(--color-gh-border);
  border-radius: 8px;
  background-color: #ffffff;
  font-size: 12px;
  font-family: var(--font-mono);
  color: var(--color-gh-text);
  outline: none;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}
.jump-input:focus {
  border-color: var(--color-gh-blue);
  box-shadow: 0 0 0 3px rgba(9, 105, 218, 0.16);
}
.jump-input::-webkit-outer-spin-button,
.jump-input::-webkit-inner-spin-button {
  -webkit-appearance: none;
  margin: 0;
}
.jump-btn {
  height: 32px;
  padding: 0 12px;
  border: 1px solid var(--color-gh-border);
  border-radius: 8px;
  background-color: #ffffff;
  color: var(--color-gh-muted);
  font-size: 12px;
  cursor: pointer;
  transition: background-color 0.12s ease, color 0.12s ease, transform 0.08s ease;
}
.jump-btn:hover {
  background-color: #eaeef2;
  color: var(--color-gh-text);
}
.jump-btn:active {
  transform: scale(0.96);
}

@media (max-width: 768px) {
  .pagination-bar {
    flex-direction: column;
    align-items: stretch;
    gap: 10px;
  }
  .pagination-left {
    flex-wrap: wrap;
  }
  .pagination-right {
    justify-content: flex-start;
    overflow-x: auto;
    padding-bottom: 2px;
  }
  .pagination-jump {
    justify-content: flex-end;
  }
}
</style>
