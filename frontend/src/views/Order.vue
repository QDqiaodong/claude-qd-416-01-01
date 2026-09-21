<template>
  <div>
    <div class="head">
      <h2>工单</h2>
      <el-button type="primary" @click="dialog = true">新建工单</el-button>
    </div>

    <div class="board">
      <div
        v-for="col in columns"
        :key="col"
        class="column"
        @dragover.prevent
        @drop="onDrop($event, col)"
      >
        <div class="col-title">{{ col }}（{{ grouped[col].length }}）</div>
        <div
          v-for="o in grouped[col]"
          :key="o.id"
          class="card"
          draggable="true"
          @dragstart="onDrag($event, o)"
        >
          <div class="job">{{ o.jobName }}</div>
          <div class="meta">台号：{{ machineLabel(o.machineId) }} · 数量：{{ o.qty }}</div>
          <el-tag
            v-if="signoffMap[o.id]"
            size="small"
            :type="signoffTagType(signoffMap[o.id].status)"
            class="so"
          >签样 {{ signoffMap[o.id].status }}</el-tag>
          <el-tag v-else size="small" type="info" class="so">未试装</el-tag>
        </div>
        <div v-if="!grouped[col].length" class="empty">拖拽工单到此</div>
      </div>
    </div>

    <el-dialog v-model="dialog" title="新建工单" width="420px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="机器">
          <el-select v-model="form.machineId" placeholder="选择机器">
            <el-option v-for="m in machines" :key="m.id" :label="m.code + ' ' + m.name" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="工单名称">
          <el-input v-model="form.jobName" />
        </el-form-item>
        <el-form-item label="数量">
          <el-input-number v-model="form.qty" :min="1" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status">
            <el-option label="待排" value="待排" />
            <el-option label="进行中" value="进行中" />
            <el-option label="已完成" value="已完成" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import http from '../api'

const columns = ['待排', '进行中', '已完成']
const orders = ref([])
const machines = ref([])
const signoffs = ref([])
const dialog = ref(false)
const form = ref({ machineId: null, jobName: '', qty: 100, status: '待排' })

const grouped = computed(() => {
  const g = { 待排: [], 进行中: [], 已完成: [] }
  for (const o of orders.value) (g[o.status] || (g[o.status] = [])).push(o)
  return g
})

const machineMap = computed(() => Object.fromEntries(machines.value.map((m) => [m.id, m.code])))
const machineLabel = (id) => machineMap.value[id] || ('#' + id)

// 每张工单取最有效的一条签样（已过 > 未过 > 退回）展示在卡片上
const signoffMap = computed(() => {
  const pri = { 已过: 3, 未过: 2, 退回: 1 }
  const map = {}
  for (const s of signoffs.value) {
    if (!map[s.orderId] || (pri[s.status] || 0) > (pri[map[s.orderId].status] || 0)) map[s.orderId] = s
  }
  return map
})
const signoffTagType = (st) => (st === '已过' ? 'success' : st === '退回' ? 'danger' : 'info')

const load = async () => {
  orders.value = await http.get('/orders')
  machines.value = await http.get('/machines')
  signoffs.value = await http.get('/signoffs')
}

const onDrag = (e, o) => e.dataTransfer.setData('text/plain', String(o.id))
const onDrop = async (e, col) => {
  const id = e.dataTransfer.getData('text/plain')
  const o = orders.value.find((x) => String(x.id) === id)
  if (!o || o.status === col) return
  try {
    await http.put(`/orders/${id}`, { status: col })
  } catch {
    // 后端拦截（如：没有已过签样，不能开印），拦截器已弹出原因
  } finally {
    await load()
  }
}

const submit = async () => {
  await http.post('/orders', { ...form.value })
  dialog.value = false
  await load()
}

onMounted(load)
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.board { display: flex; gap: 16px; }
.column {
  flex: 1; background: #fff; border-radius: 8px; padding: 12px; min-height: 320px;
  border: 1px solid #ece5e1;
}
.col-title { font-weight: 700; margin-bottom: 10px; color: var(--el-color-primary); }
.card {
  background: #faf7f5; border: 1px solid #e3d9d4; border-radius: 6px;
  padding: 10px; margin-bottom: 10px; cursor: grab;
}
.card .job { font-weight: 600; }
.card .meta { font-size: 12px; color: #8a7f7a; margin-top: 4px; }
.card .so { margin-top: 6px; }
.empty { color: #c0b6b0; font-size: 13px; text-align: center; padding: 20px 0; }
</style>
