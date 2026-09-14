<script setup>
import { computed, ref, watch, onMounted, onBeforeUnmount } from 'vue'

const props = defineProps({
  total: { type: Number, default: 0 },
  page: { type: Number, default: 1 },
  size: { type: Number, default: 15 }
})

const emit = defineEmits(['update:page', 'update:size', 'change'])

const SIZES = [10, 15, 20, 50, 100]

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
  if (t <= 7) return Array.from({ length: t }, (_, i) => i + 1)
  const left = Math.max(1, Math.min(c - 2, t - 5))
  const right = Math.min(t, left + 4)
  const items = []
  if (left > 1) {
    items.push(1)
    if (left > 2) items.push('gap')
  }
  for (let p = left; p <= right; p++) items.push(p)
  if (right < t) {
    if (right < t - 1) items.push('gap')
    items.push(t)
  }
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

const sizeOpen = ref(false)
const sizeUp = ref(false)
const sizeBox = ref(null)
const MENU_H = 132

function toggleSize() {
  if (sizeOpen.value) {
    sizeOpen.value = false
    return
  }
  const btn = sizeBox.value && sizeBox.value.querySelector('.page-size-btn')
  if (btn) {
    const rect = btn.getBoundingClientRect()
    sizeUp.value =
      window.innerHeight - rect.bottom < MENU_H && rect.top > MENU_H
  }
  sizeOpen.value = true
}

function pickSize(s) {
  sizeOpen.value = false
  setSize(s)
}

function onDocMouseDown(e) {
  if (sizeBox.value && !sizeBox.value.contains(e.target)) {
    sizeOpen.value = false
  }
}

function onKeydown(e) {
  if (e.key === 'Escape') sizeOpen.value = false
}

function onResize() {
  sizeOpen.value = false
}

onMounted(() => {
  document.addEventListener('mousedown', onDocMouseDown)
  document.addEventListener('keydown', onKeydown)
  window.addEventListener('resize', onResize)
})
onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onDocMouseDown)
  document.removeEventListener('keydown', onKeydown)
  window.removeEventListener('resize', onResize)
})
</script>

<template>
  <div v-if="total === 0" class="pagination-stats">共 0 条</div>
  <div v-else class="pagination-bar">
    <div class="pagination-left">
      <span class="pagination-stats">显示第 {{ fmt(start) }} 到 {{ fmt(end) }} 条，共 {{ fmt(total) }} 条</span>
      <div class="size-dropdown" ref="sizeBox">
        <div class="size-anchor">
          <button type="button" class="page-size-btn" :class="{ open: sizeOpen }" @click="toggleSize">
            {{ size }}
            <i class="fa-solid fa-chevron-down size-caret"></i>
          </button>
        <ul v-if="sizeOpen" class="size-menu" :class="{ up: sizeUp }" role="listbox" aria-label="每页条数">
          <li
            v-for="s in SIZES"
            :key="s"
            class="size-option"
            :class="{ active: s === size }"
            role="option"
            :aria-selected="s === size"
            @click="pickSize(s)"
          >
            <span>{{ s }}</span>
            <i v-if="s === size" class="fa-solid fa-check text-[10px]"></i>
          </li>
        </ul>
        </div>
        <span class="text-xs text-gh-muted whitespace-nowrap">条 / 页</span>
      </div>
    </div>

    <nav class="page-nav" aria-label="分页导航">
      <button type="button" class="page-item" title="上一页" aria-label="上一页" :disabled="cur <= 1" @click="go(cur - 1)">
        <i class="fa-solid fa-chevron-left"></i>
      </button>
      <template v-for="(it, i) in pageItems" :key="`${i}-${it}`">
        <span v-if="it === 'gap'" class="page-item page-ellipsis">…</span>
        <button
          v-else
          type="button"
          class="page-item"
          :class="{ 'page-active': it === cur }"
          :aria-current="it === cur ? 'page' : undefined"
          @click="go(it)"
        >{{ it }}</button>
      </template>
      <button type="button" class="page-item" title="下一页" aria-label="下一页" :disabled="cur >= totalPages" @click="go(cur + 1)">
        <i class="fa-solid fa-chevron-right"></i>
      </button>
    </nav>
  </div>
</template>

<style scoped>
.pagination-bar {
  margin-top: 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.pagination-left {
  display: flex;
  align-items: center;
  gap: 24px;
  flex-wrap: wrap;
}

.pagination-stats {
  font-size: 13px;
  color: var(--color-gh-text);
  font-variant-numeric: tabular-nums;
}

.size-dropdown {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.size-anchor {
  position: relative;
  display: inline-flex;
}

.page-size-btn {
  height: 30px;
  min-width: 64px;
  padding: 0 10px;
  display: inline-flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  border: 1px solid var(--color-gh-border);
  border-radius: 8px;
  background-color: #0a111d;
  color: var(--color-gh-text);
  font-size: 12px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
  cursor: pointer;
  transition: border-color 0.15s ease, box-shadow 0.15s ease, background-color 0.15s ease;
}
.page-size-btn:hover,
.page-size-btn.open {
  border-color: rgba(34, 211, 238, 0.45);
}
.page-size-btn:focus {
  border-color: var(--color-gh-cyan);
  outline: none;
  box-shadow: 0 0 0 3px rgba(34, 211, 238, 0.16);
}
.size-caret {
  font-size: 10px;
  color: var(--color-gh-muted);
  transition: transform 0.15s ease, color 0.15s ease;
}
.page-size-btn.open .size-caret {
  transform: rotate(180deg);
  color: var(--color-gh-cyan);
}

.size-menu {
  position: absolute;
  top: calc(100% + 6px);
  left: 0;
  z-index: 30;
  min-width: 100%;
  width: max-content;
  margin: 0;
  padding: 4px;
  list-style: none;
  display: flex;
  flex-direction: column;
  background: var(--color-gh-panel);
  border: 1px solid var(--color-gh-border);
  border-radius: 8px;
  box-shadow: 0 12px 28px -12px rgba(0, 0, 0, 0.85), inset 0 1px 0 rgba(140, 180, 240, 0.07);
}
.size-menu.up {
  top: auto;
  bottom: calc(100% + 6px);
}

.size-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 6px 10px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
  color: var(--color-gh-text);
  white-space: nowrap;
  cursor: pointer;
  transition: background-color 0.12s ease, color 0.12s ease;
}
.size-option:hover {
  background: rgba(34, 211, 238, 0.12);
  color: #7ee7f8;
}
.size-option.active {
  color: #7ee7f8;
  font-weight: 700;
}
.size-option.active .fa-check {
  color: var(--color-gh-cyan);
}

.page-nav {
  display: inline-flex;
  align-items: center;
  border: 1px solid var(--color-gh-border);
  border-radius: 8px;
  overflow: hidden;
  background-color: #0a111d;
}

.page-item {
  height: 30px;
  min-width: 30px;
  padding: 0 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-right: 1px solid var(--color-gh-border);
  background: transparent;
  color: var(--color-gh-text);
  font-size: 12px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
  cursor: pointer;
  user-select: none;
  flex: 0 0 auto;
  transition: background-color 0.12s ease, color 0.12s ease;
}
.page-item:last-child {
  border-right: none;
}
.page-item i {
  font-size: 11px;
}
.page-item:hover:not(:disabled):not(.page-active) {
  background-color: #101c2e;
}
.page-item:active:not(:disabled):not(.page-active) {
  background-color: #16233a;
}
.page-item:disabled {
  color: #4d5f78;
  cursor: not-allowed;
}
.page-item.page-active {
  background: rgba(34, 211, 238, 0.14);
  color: #7ee7f8;
  font-weight: 700;
}

.page-ellipsis {
  min-width: 26px;
  color: var(--color-gh-muted);
  cursor: default;
}
</style>
