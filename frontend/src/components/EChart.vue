<script setup>
import { ref, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  option: { type: Object, required: true },
  height: { type: String, default: '96px' }
})

const el = ref(null)
let chart = null
let ro = null

function render() {
  if (!el.value) return
  if (!chart) {
    chart = echarts.init(el.value)
  }
  chart.setOption(props.option, true)
}

onMounted(async () => {
  await nextTick()
  render()
  if (!el.value) return
  let lastW = el.value.clientWidth
  let lastH = el.value.clientHeight
  // ResizeObserver 首次 observe 会立即 report 一次，若直接 resize()
  // 会以 animation.duration=0 打断 ECharts 的入场动画；只在尺寸真的变了才 resize
  ro = new ResizeObserver(() => {
    if (!chart || !el.value) return
    const w = el.value.clientWidth
    const h = el.value.clientHeight
    if (w === lastW && h === lastH) return
    lastW = w
    lastH = h
    chart.resize()
  })
  ro.observe(el.value)
})

onBeforeUnmount(() => {
  if (ro) ro.disconnect()
  if (chart) chart.dispose()
  chart = null
})

watch(() => props.option, render, { deep: true })
</script>

<template>
  <div ref="el" class="w-full" :style="{ height }"></div>
</template>
