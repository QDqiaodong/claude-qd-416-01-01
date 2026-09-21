<template>
  <div>
    <div class="head">
      <h2>纸张</h2>
      <el-button type="primary" @click="openCreate">新增纸张</el-button>
    </div>

    <el-card v-for="p in papers" :key="p.id" class="row" shadow="hover">
      <div class="row-head">
        <span class="code">{{ p.code }}</span>
        <span class="gsm">{{ p.gsm }}g</span>
        <span class="stock" :class="{ low: p.stock <= p.warnLine }">库存 {{ p.stock }} 张</span>
        <span class="warn">预警线 {{ p.warnLine }}</span>
        <el-button size="small" type="warning" plain @click="consume(p)">领纸</el-button>
      </div>
      <el-progress
        :percentage="pct(p)"
        :color="p.stock <= p.warnLine ? '#f56c6c' : '#795548'"
        :stroke-width="14"
        :show-text="false"
      />
    </el-card>

    <el-dialog v-model="dialog" title="新增纸张" width="420px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="编号"><el-input v-model="form.code" /></el-form-item>
        <el-form-item label="克重"><el-input-number v-model="form.gsm" :min="1" /></el-form-item>
        <el-form-item label="库存"><el-input-number v-model="form.stock" :min="0" /></el-form-item>
        <el-form-item label="预警线"><el-input-number v-model="form.warnLine" :min="0" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import http from '../api'
import { ElMessageBox } from 'element-plus'

const papers = ref([])
const dialog = ref(false)
const form = ref({ code: '', gsm: 80, stock: 1000, warnLine: 200 })

const pct = (p) => {
  const max = Math.max(p.stock, p.warnLine, 1)
  return Math.min(100, Math.round((p.stock / max) * 100))
}

const load = async () => { papers.value = await http.get('/papers') }

const openCreate = () => {
  form.value = { code: '', gsm: 80, stock: 1000, warnLine: 200 }
  dialog.value = true
}
const submit = async () => {
  await http.post('/papers', { ...form.value })
  dialog.value = false
  await load()
}

const consume = async (p) => {
  const { value } = await ElMessageBox.prompt('领纸数量（张）', '工单领纸', { inputValue: 50 })
  const qty = Number(value)
  if (!qty || qty <= 0) return
  await http.post(`/papers/${p.id}/consume`, { qty })
  await load()
}

onMounted(load)
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.row { margin-bottom: 12px; }
.row-head { display: flex; align-items: center; gap: 14px; margin-bottom: 8px; }
.code { font-weight: 700; }
.gsm { color: #8a7f7a; }
.stock { font-weight: 600; }
.stock.low { color: #f56c6c; }
.warn { color: #b0a59f; font-size: 13px; }
.row-head .el-button { margin-left: auto; }
</style>
