<template>
  <div class="app">
    <header class="topbar">
      <div class="brand">📚 装订车间</div>
      <div class="stepper">
        <template v-for="(m, i) in modules" :key="m.path">
          <div class="step" :class="{ active: active === m.path, done: idx(m.path) < idx(active) }" @click="go(m.path)">
            <div class="dot">{{ i + 1 }}</div>
            <div class="label">{{ m.label }}</div>
          </div>
          <div v-if="i < modules.length - 1" class="line" :class="{ filled: idx(m.path) < idx(active) }"></div>
        </template>
      </div>
    </header>
    <main class="content"><router-view /></main>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const modules = [
  { path: '/machines', label: '装订机' },
  { path: '/orders', label: '工单' },
  { path: '/signoffs', label: '签样' },
  { path: '/papers', label: '纸张' },
  { path: '/products', label: '成品' }
]
const route = useRoute()
const router = useRouter()
const active = computed(() => route.path)
const idx = (p) => modules.findIndex((m) => m.path === p)
const go = (p) => router.push(p)
</script>

<style>
html, body, #app { margin: 0; height: 100%; }
.app { min-height: 100vh; background: #faf7f5; }
.topbar {
  display: flex; align-items: center; gap: 24px;
  background: var(--el-color-primary); color: #fff; padding: 10px 20px;
}
.brand { font-weight: 700; font-size: 18px; white-space: nowrap; }
.stepper { display: flex; align-items: center; flex: 1; }
.step { display: flex; flex-direction: column; align-items: center; cursor: pointer; color: #fff; }
.dot {
  width: 28px; height: 28px; border-radius: 50%;
  background: rgba(255, 255, 255, 0.35);
  display: flex; align-items: center; justify-content: center; font-weight: 700;
}
.step.active .dot { background: #fff; color: var(--el-color-primary); }
.step.done .dot { background: var(--el-color-primary-light-7); color: #fff; }
.label { margin-top: 4px; font-size: 13px; }
.line { flex: 1; height: 2px; background: rgba(255, 255, 255, 0.35); margin: 0 12px; }
.line.filled { background: #fff; }
.content { padding: 18px; }
</style>
