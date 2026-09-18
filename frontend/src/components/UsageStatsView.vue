<script setup>
import { ref } from 'vue'
import DailyUsageView from './DailyUsageView.vue'
import MemberUsageView from './MemberUsageView.vue'

const subTabs = [
  { key: 'member', label: '成员用量', icon: 'fa-solid fa-users' },
  { key: 'daily', label: '每日用量', icon: 'fa-solid fa-chart-line' }
]

const sub = ref('member')
</script>

<template>
  <div>
    <div class="flex items-center gap-1 border-b border-gh-border mb-4">
      <button
        v-for="t in subTabs"
        :key="t.key"
        class="px-3.5 py-2 text-sm rounded-t-md transition-all duration-150 active:scale-95"
        :class="sub === t.key
          ? 'subtab-active'
          : 'text-gh-muted hover:text-gh-text hover:bg-gh-cyan/5'"
        @click="sub = t.key"
      ><i :class="t.icon" class="mr-1.5"></i>{{ t.label }}</button>
    </div>

    <MemberUsageView v-if="sub === 'member'" />
    <DailyUsageView v-else />
  </div>
</template>

<style scoped>
.subtab-active {
  color: #7deffc;
  font-weight: 600;
  background: rgba(34, 211, 238, 0.08);
  box-shadow: inset 0 -2px 0 #22d3ee;
}
</style>