<script setup>
import { ref } from 'vue'
import { api } from '../api'
import AppButton from './AppButton.vue'

const emit = defineEmits(['success'])

const username = ref('admin')
const password = ref('')
const error = ref('')
const locked = ref(false)
const loading = ref(false)

async function submit() {
  error.value = ''
  loading.value = true
  try {
    const r = await api.login(username.value, password.value)
    if (r.ok) {
      emit('success', r.username)
    } else {
      error.value = r.error || '登录失败'
      locked.value = !!r.locked
    }
  } catch (e) {
    error.value = e.message
  }
  loading.value = false
}
</script>

<template>
  <div class="login-scene">
    <div class="aurora aurora-a"></div>
    <div class="aurora aurora-b"></div>
    <div class="aurora aurora-c"></div>
    <div class="login-grid"></div>
    <div class="login-grain"></div>

    <div class="login-wrap">
      <div class="login-card fade-up">
        <div class="logo-badge">
          <i class="fa-solid fa-bolt"></i>
        </div>
        <h1 class="login-title font-xh">LLM 网关服务</h1>
        <p class="login-sub">OpenAI 兼容 API 网关 · 管理控制台</p>

        <form @submit.prevent="submit">
          <label class="field">
            <span class="field-label"><i class="fa-solid fa-user"></i>用户名</span>
            <input
              v-model="username"
              type="text"
              autocomplete="username"
              placeholder="请输入用户名"
              class="field-input"
            />
          </label>
          <label class="field">
            <span class="field-label"><i class="fa-solid fa-lock"></i>密码</span>
            <input
              v-model="password"
              type="password"
              autocomplete="current-password"
              placeholder="请输入密码"
              class="field-input"
            />
          </label>
          <p v-if="error" class="login-error"><i class="fa-solid fa-circle-exclamation mr-1"></i>{{ error }}</p>
          <p v-if="locked" class="login-locked"><i class="fa-solid fa-lock mr-1"></i>账号已锁定，请 5 分钟后重试</p>
          <AppButton
            type="submit"
            variant="primary-green"
            block
            class="login-btn"
            :loading="loading"
            :disabled="loading"
          >{{ loading ? '登录中…' : '登 录' }}
          </AppButton>
        </form>
      </div>
      <p class="login-foot fade-up d3">
        <i class="fa-solid fa-shield-halved mr-1"></i>
        所有管理操作均记录审计日志
      </p>
    </div>
  </div>
</template>

<style scoped>
.login-scene {
  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  overflow: hidden;
  background:
    radial-gradient(900px 500px at 12% -10%, rgba(31, 136, 61, 0.14), transparent 60%),
    radial-gradient(800px 520px at 108% 18%, rgba(9, 105, 218, 0.12), transparent 60%),
    radial-gradient(700px 600px at 50% 118%, rgba(210, 153, 34, 0.1), transparent 60%),
    #f7f9fb;
}

.aurora {
  position: absolute;
  border-radius: 9999px;
  filter: blur(72px);
  opacity: 0.55;
  pointer-events: none;
  animation: aurora-drift 16s ease-in-out infinite alternate;
}
.aurora-a {
  width: 480px;
  height: 480px;
  left: -120px;
  top: -140px;
  background: radial-gradient(circle, rgba(45, 164, 78, 0.38), transparent 65%);
}
.aurora-b {
  width: 560px;
  height: 560px;
  right: -160px;
  top: 8%;
  background: radial-gradient(circle, rgba(9, 105, 218, 0.32), transparent 65%);
  animation-delay: -6s;
}
.aurora-c {
  width: 420px;
  height: 420px;
  left: 32%;
  bottom: -190px;
  background: radial-gradient(circle, rgba(210, 153, 34, 0.3), transparent 65%);
  animation-delay: -11s;
}
@keyframes aurora-drift {
  from {
    transform: translate3d(0, 0, 0) scale(1);
  }
  to {
    transform: translate3d(42px, 30px, 0) scale(1.12);
  }
}

.login-grid {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background-image:
    linear-gradient(rgba(31, 35, 40, 0.045) 1px, transparent 1px),
    linear-gradient(90deg, rgba(31, 35, 40, 0.045) 1px, transparent 1px);
  background-size: 36px 36px;
  mask-image: radial-gradient(ellipse 80% 70% at 50% 45%, black 30%, transparent 78%);
  -webkit-mask-image: radial-gradient(ellipse 80% 70% at 50% 45%, black 30%, transparent 78%);
}

.login-grain {
  position: absolute;
  inset: 0;
  pointer-events: none;
  opacity: 0.05;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='160' height='160'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='2'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E");
}

.login-wrap {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 400px;
}

.login-card {
  background: rgba(255, 255, 255, 0.72);
  backdrop-filter: blur(20px) saturate(1.4);
  -webkit-backdrop-filter: blur(20px) saturate(1.4);
  border: 1px solid rgba(255, 255, 255, 0.85);
  border-radius: 20px;
  padding: 36px 32px 32px;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.9),
    0 24px 64px -16px rgba(31, 35, 40, 0.22),
    0 4px 16px -8px rgba(31, 35, 40, 0.1);
}

.logo-badge {
  width: 56px;
  height: 56px;
  margin: 0 auto 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 16px;
  background: linear-gradient(135deg, #2da44e 0%, #1f883d 55%, #116329 100%);
  color: #ffffff;
  font-size: 24px;
  position: relative;
  box-shadow:
    0 10px 24px -8px rgba(31, 136, 61, 0.55),
    inset 0 1px 0 rgba(255, 255, 255, 0.28);
}
.logo-badge::after {
  content: '';
  position: absolute;
  inset: -6px;
  border-radius: 20px;
  border: 1.5px solid rgba(45, 164, 78, 0.5);
  animation: badge-ring 2.4s ease-out infinite;
}
@keyframes badge-ring {
  0% {
    transform: scale(0.92);
    opacity: 0.9;
  }
  70%,
  100% {
    transform: scale(1.22);
    opacity: 0;
  }
}

.login-title {
  text-align: center;
  font-size: 26px;
  font-weight: 700;
  letter-spacing: 1px;
  color: #1f2328;
}
.login-sub {
  text-align: center;
  margin-top: 6px;
  font-size: 13px;
  letter-spacing: 1.5px;
  color: #59636e;
}

form {
  margin-top: 26px;
}

.field {
  display: block;
  margin-bottom: 16px;
}
.field-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  letter-spacing: 0.5px;
  color: #59636e;
  margin-bottom: 6px;
}
.field-label i {
  font-size: 11px;
  color: #1f883d;
}
.field-input {
  width: 100%;
  height: 42px;
  padding: 0 14px;
  font-size: 14px;
  color: #24292f;
  background: rgba(255, 255, 255, 0.85);
  border: 1px solid #d0d7de;
  border-radius: 10px;
  outline: none;
  transition: border-color 0.15s ease, box-shadow 0.15s ease, background-color 0.15s ease;
}
.field-input::placeholder {
  color: #8c959f;
}
.field-input:hover {
  border-color: #b6bec9;
}
.field-input:focus {
  border-color: #1f883d;
  background: #ffffff;
  box-shadow: 0 0 0 3px rgba(31, 136, 61, 0.16);
}

.login-error {
  font-size: 13px;
  color: #cf222e;
  margin-bottom: 12px;
}
.login-locked {
  font-size: 12px;
  color: #9a6700;
  margin-bottom: 12px;
}

.login-btn {
  height: 42px;
  font-size: 14px;
  letter-spacing: 2px;
  border-radius: 10px;
}

.login-foot {
  margin-top: 18px;
  text-align: center;
  font-size: 12px;
  letter-spacing: 0.5px;
  color: #8c959f;
}

.fade-up {
  animation: login-fade-up 0.55s cubic-bezier(0.22, 1, 0.36, 1) both;
}
.d3 {
  animation-delay: 0.28s;
}
@keyframes login-fade-up {
  from {
    opacity: 0;
    transform: translateY(14px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (prefers-reduced-motion: reduce) {
  .aurora,
  .logo-badge::after,
  .fade-up {
    animation: none;
  }
}
</style>
