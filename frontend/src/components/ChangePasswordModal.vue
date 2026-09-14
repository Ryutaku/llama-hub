<script setup>
import { ref } from 'vue'
import { api } from '../api'
import { toast } from '../ui'
import AppButton from './AppButton.vue'

const emit = defineEmits(['close', 'changed'])

const oldPwd = ref('')
const newPwd = ref('')
const confirmPwd = ref('')
const error = ref('')
const busy = ref(false)

async function submit() {
  error.value = ''
  if (!oldPwd.value || !newPwd.value) {
    error.value = '请填写完整'
    return
  }
  if (newPwd.value.length < 8) {
    error.value = '新密码长度至少 8 位'
    return
  }
  if (newPwd.value !== confirmPwd.value) {
    error.value = '两次输入的新密码不一致'
    return
  }
  busy.value = true
  try {
    await api.changePassword(oldPwd.value, newPwd.value)
    toast('密码已修改，请重新登录', 'success')
    emit('changed')
  } catch (e) {
    error.value = e.message
  }
  busy.value = false
}
</script>

<template>
  <div class="fixed inset-0 bg-black/40 flex items-center justify-center z-50 px-4" @click.self="emit('close')">
    <div class="modal-pop bg-gh-bg border border-gh-border rounded-lg w-full max-w-sm p-4 shadow-xl">
      <h3 class="font-semibold text-base mb-3">修改密码</h3>
      <label class="block text-xs text-gh-muted mb-1">旧密码</label>
      <input v-model="oldPwd" type="password" autocomplete="current-password" class="input mb-3" />
      <label class="block text-xs text-gh-muted mb-1">新密码（至少 8 位）</label>
      <input v-model="newPwd" type="password" autocomplete="new-password" class="input mb-3" />
      <label class="block text-xs text-gh-muted mb-1">确认新密码</label>
      <input v-model="confirmPwd" type="password" autocomplete="new-password" class="input mb-3" />
      <p v-if="error" class="text-sm text-gh-red mb-3">{{ error }}</p>
      <div class="flex justify-end gap-1.5">
        <AppButton variant="secondary" @click="emit('close')">取消</AppButton>
        <AppButton
          variant="primary-blue"
          :loading="busy" :disabled="busy" @click="submit">确认修改</AppButton>
      </div>
    </div>
  </div>
</template>

<style scoped>
.input {
  width: 100%;
  padding: 4px 8px;
  font-size: 12px;
  line-height: 20px;
  background: #ffffff;
  border: 1px solid #d0d7de;
  border-radius: 6px;
  color: #24292f;
  outline: none;
}
.input:focus {
  border-color: #0969da;
}
</style>
