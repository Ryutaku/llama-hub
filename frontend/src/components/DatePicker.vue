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
  padding: 4px 8px;
  font-size: 12px;
  line-height: 20px;
  background: #ffffff;
  border: 1px solid #d0d7de;
  border-radius: 6px;
  color: #24292f;
  outline: none;
}
.dp-input:focus {
  border-color: #0969da;
  box-shadow: 0 0 0 3px rgba(9, 105, 218, 0.3);
}

.dp-popover {
  position: absolute;
  z-index: 2000;
  left: 0;
  top: 100%;
  margin-top: 4px;
  width: 268px;
  padding: 10px;
  border: 1px solid #8c959f;
  border-radius: 6px;
  background-color: #ffffff;
  box-shadow: 0 8px 24px rgba(140, 149, 159, 0.28);
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
  color: #24292f;
}

.dp-nav {
  width: 28px;
  height: 28px;
  border: 1px solid #d0d7de;
  border-radius: 6px;
  background-color: #ffffff;
  color: #24292f;
  cursor: pointer;
  transition: transform 0.12s ease, background-color 0.15s ease;
}
.dp-nav:hover {
  background-color: #f6f8fa;
}
.dp-nav:active {
  transform: scale(0.9);
  background-color: #eaeef2;
}

.dp-weekdays,
.dp-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 4px;
}

.dp-weekdays {
  margin-bottom: 4px;
  color: #59636e;
  font-size: 12px;
  text-align: center;
}

.dp-day {
  height: 30px;
  border: 1px solid transparent;
  border-radius: 6px;
  background-color: #ffffff;
  color: #24292f;
  font-size: 13px;
  cursor: pointer;
  transition: transform 0.12s ease, background-color 0.15s ease, border-color 0.15s ease;
}
.dp-day:hover {
  border-color: #d0d7de;
  background-color: #f6f8fa;
}
.dp-day:active {
  transform: scale(0.88);
  background-color: #eaeef2;
}
.dp-day.muted {
  color: #8c959f;
}
.dp-day.today {
  border-color: #0969da;
}
.dp-day.selected {
  color: #ffffff;
  border-color: #0969da;
  background-color: #0969da;
}
</style>
