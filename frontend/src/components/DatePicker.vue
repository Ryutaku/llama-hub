<template>
  <div class="relative inline-block">
    <input
      :value="modelValue"
      readonly
      class="dp-input cursor-pointer"
      :placeholder="placeholder"
      @click="show"
      @focus="show"
      @keydown.esc="open = false"
    />
    <div v-if="open" class="dp-popover">
      <div class="dp-header">
        <button type="button" class="dp-nav" @click="shiftMonth(-1)">‹</button>
        <div class="dp-title">{{ viewYear }} 年 {{ viewMonth + 1 }} 月</div>
        <button type="button" class="dp-nav" @click="shiftMonth(1)">›</button>
      </div>
      <div class="dp-weekdays">
        <span v-for="w in weekdayNames" :key="w">{{ w }}</span>
      </div>
      <div class="dp-grid">
        <button
          v-for="cell in cells"
          :key="cell.key"
          type="button"
          class="dp-day"
          :class="cellClass(cell)"
          @click="pick(cell)"
        >{{ cell.day }}</button>
      </div>
    </div>
  </div>
</template>

<script>
import { computed, onMounted, onUnmounted, ref } from 'vue'

function fmt(d) {
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return d.getFullYear() + '-' + m + '-' + day
}

function parseDate(s) {
  if (!s) return null
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(s))
  if (!m) return null
  const d = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]))
  return isNaN(d.getTime()) ? null : d
}

function sameDate(a, b) {
  return a && b &&
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate()
}

let activePicker = null

export default {
  name: 'DatePicker',
  props: {
    modelValue: { type: String, default: '' },
    placeholder: { type: String, default: '选择日期' }
  },
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    const root = ref(null)
    const open = ref(false)
    const viewYear = ref(2026)
    const viewMonth = ref(0)

    function closeIfActive() {
      if (activePicker === doClose) {
        activePicker = null
      }
    }

    function doClose() {
      open.value = false
      closeIfActive()
    }

    const weekdayNames = ['日', '一', '二', '三', '四', '五', '六']

    const cells = computed(() => {
      const first = new Date(viewYear.value, viewMonth.value, 1)
      const start = new Date(viewYear.value, viewMonth.value, 1 - first.getDay())
      const out = []
      for (let i = 0; i < 42; i++) {
        const d = new Date(start.getFullYear(), start.getMonth(), start.getDate() + i)
        out.push({
          date: d,
          day: d.getDate(),
          key: d.getTime(),
          inMonth: d.getMonth() === viewMonth.value
        })
      }
      return out
    })

    function cellClass(cell) {
      const date = cell.date
      const cls = []
      if (!cell.inMonth) {
        cls.push('muted')
      }
      if (sameDate(date, parseDate(props.modelValue))) {
        cls.push('selected')
      } else if (sameDate(date, new Date())) {
        cls.push('today')
      }
      return cls
    }

    function pick(cell) {
      emit('update:modelValue', fmt(cell.date))
      doClose()
    }

    function shiftMonth(n) {
      const d = new Date(viewYear.value, viewMonth.value + n, 1)
      viewYear.value = d.getFullYear()
      viewMonth.value = d.getMonth()
    }

    function show() {
      if (activePicker && activePicker !== doClose) {
        activePicker()
      }
      open.value = true
      activePicker = doClose
      const d = parseDate(props.modelValue) || new Date()
      viewYear.value = d.getFullYear()
      viewMonth.value = d.getMonth()
    }

    function onDocMousedown(e) {
      if (root.value && !root.value.contains(e.target)) {
        doClose()
      }
    }
    function onHide() {
      doClose()
    }

    onMounted(() => {
      document.addEventListener('mousedown', onDocMousedown)
      window.addEventListener('resize', onHide)
      window.addEventListener('scroll', onHide, true)
    })
    onUnmounted(() => {
      document.removeEventListener('mousedown', onDocMousedown)
      window.removeEventListener('resize', onHide)
      window.removeEventListener('scroll', onHide, true)
    })

    return { root, open, viewYear, viewMonth, cells, weekdayNames, cellClass, pick, shiftMonth, show }
  }
}
</script>

<style scoped>
.dp-input {
  width: 150px;
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
.dp-input:focus {
  border-color: #22d3ee;
  box-shadow: 0 0 0 3px rgba(34, 211, 238, 0.18);
}

.dp-popover {
  position: absolute;
  z-index: 2000;
  left: 0;
  top: 100%;
  margin-top: 4px;
  width: 268px;
  padding: 10px;
  border: 1px solid rgba(34, 211, 238, 0.25);
  border-radius: 10px;
  background-color: #0e1624;
  box-shadow:
    0 16px 40px -12px rgba(0, 0, 0, 0.7),
    0 0 24px -10px rgba(34, 211, 238, 0.3);
}

.dp-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.dp-title {
  font-weight: 600;
  font-size: 14px;
  color: var(--color-gh-text);
}

.dp-nav {
  width: 28px;
  height: 28px;
  border: 1px solid var(--color-gh-border);
  border-radius: 6px;
  background-color: #0a111d;
  color: var(--color-gh-text);
  cursor: pointer;
  transition: transform 0.12s ease, background-color 0.15s ease, border-color 0.15s ease;
}
.dp-nav:hover {
  border-color: rgba(34, 211, 238, 0.45);
  background-color: #101c2e;
}
.dp-nav:active {
  transform: scale(0.9);
  background-color: #16233a;
}

.dp-weekdays,
.dp-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 4px;
}

.dp-weekdays {
  margin-bottom: 4px;
  color: var(--color-gh-muted);
  font-size: 12px;
  text-align: center;
}

.dp-day {
  height: 30px;
  border: 1px solid transparent;
  border-radius: 6px;
  background-color: transparent;
  color: var(--color-gh-text);
  font-size: 13px;
  cursor: pointer;
  transition: transform 0.12s ease, background-color 0.15s ease, border-color 0.15s ease, box-shadow 0.15s ease;
}
.dp-day:hover {
  border-color: rgba(34, 211, 238, 0.3);
  background-color: #101c2e;
}
.dp-day:active {
  transform: scale(0.88);
  background-color: #16233a;
}
.dp-day.muted {
  color: #4d5f78;
}
.dp-day.today {
  border-color: rgba(34, 211, 238, 0.55);
  color: #7deffc;
}
.dp-day.selected {
  color: #04121a;
  border-color: transparent;
  background: var(--color-gh-green);
  font-weight: 600;
  box-shadow: 0 0 12px -2px rgba(34, 211, 238, 0.65);
}
</style>
