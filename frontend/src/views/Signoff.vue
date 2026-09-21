<template>
  <div>
    <div class="head">
      <h2>试装签样</h2>
      <el-button type="primary" @click="openCreate">新建签样</el-button>
    </div>

    <el-table :data="signoffs" border class="tbl">
      <el-table-column prop="id" label="#" width="56" />
      <el-table-column label="待排工单" min-width="150">
        <template #default="{ row }">
          <span v-if="orderMap[row.orderId]">#{{ row.orderId }} {{ orderMap[row.orderId].jobName }}</span>
          <span v-else>#{{ row.orderId }}</span>
        </template>
      </el-table-column>
      <el-table-column label="装订机" min-width="140">
        <template #default="{ row }">{{ machineLabel(row.machineId) }}</template>
      </el-table-column>
      <el-table-column label="纸张" width="90">
        <template #default="{ row }">{{ paperLabel(row.paperId) }}</template>
      </el-table-column>
      <el-table-column prop="trialQty" label="试装册数" width="90" />
      <el-table-column label="试装参数" min-width="130">
        <template #default="{ row }">
          <span v-if="machineTypeOf(row.machineId) === 'glue'">
            书脊 {{ row.spineThickness == null ? '—' : row.spineThickness + ' mm' }}
          </span>
          <span v-else>订针 {{ row.stitchCount == null ? '—' : row.stitchCount + ' 针' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="试装纸" width="90">
        <template #default="{ row }">
          <el-tag :type="row.paperDeducted ? 'warning' : 'info'" size="small">
            {{ row.paperDeducted ? '已扣' : '未扣' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="230">
        <template #default="{ row }">
          <template v-if="row.status === '未过'">
            <el-button size="small" @click="openEdit(row)">编辑</el-button>
            <el-button size="small" type="success" @click="pass(row)">通过</el-button>
            <el-button size="small" type="danger" plain @click="reject(row)">退回</el-button>
          </template>
          <template v-else-if="row.status === '已过'">
            <span class="muted">机台纸种已锁定</span>
            <el-button size="small" type="danger" plain @click="reject(row)">退回</el-button>
          </template>
          <span v-else class="muted">已退回</span>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog" :title="form.id ? '编辑签样 #' + form.id : '新建签样'" width="440px">
      <el-form label-width="90px">
        <el-form-item v-if="!form.id" label="待排工单">
          <el-select v-model="form.orderId" placeholder="选择待排工单">
            <el-option v-for="o in pendingOrders" :key="o.id" :label="'#' + o.id + ' ' + o.jobName" :value="o.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="装订机">
          <el-select v-model="form.machineId" placeholder="选择装订机">
            <el-option
              v-for="m in machines"
              :key="m.id"
              :label="machineOptionLabel(m)"
              :value="m.id"
              :disabled="!machineUsable(m)"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="纸张">
          <el-select v-model="form.paperId" placeholder="选择纸张" :disabled="lockedPaper">
            <el-option
              v-for="p in papers"
              :key="p.id"
              :label="p.code + '（库存 ' + p.stock + ' 张）'"
              :value="p.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="试装册数">
          <el-input-number v-model="form.trialQty" :min="1" :disabled="lockedPaper" />
        </el-form-item>
        <el-form-item v-if="selectedMachineType === 'glue'" label="书脊厚度mm">
          <el-input-number v-model="form.spineThickness" :min="1" placeholder="胶装机必填" />
        </el-form-item>
        <el-form-item v-else-if="selectedMachineType === 'saddle'" label="订针数">
          <el-input-number v-model="form.stitchCount" :min="1" />
        </el-form-item>
        <el-alert
          v-if="lockedPaper"
          type="warning"
          :closable="false"
          title="试装纸已扣，不能再换纸或改册数；如需更换请退回本条后新开签样"
        />
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import http from '../api'
import { ElMessageBox, ElMessage } from 'element-plus'

const signoffs = ref([])
const orders = ref([])
const machines = ref([])
const papers = ref([])
const dialog = ref(false)
const form = ref({})

const load = async () => {
  const [s, o, m, p] = await Promise.all([
    http.get('/signoffs'),
    http.get('/orders'),
    http.get('/machines'),
    http.get('/papers')
  ])
  signoffs.value = s
  orders.value = o
  machines.value = m
  papers.value = p
}

const orderMap = computed(() => Object.fromEntries(orders.value.map((o) => [o.id, o])))
const machineMap = computed(() => Object.fromEntries(machines.value.map((m) => [m.id, m])))
const paperMap = computed(() => Object.fromEntries(papers.value.map((p) => [p.id, p])))
const machineLabel = (id) => {
  const m = machineMap.value[id]
  return m ? m.code + ' ' + m.name : '#' + id
}
const paperLabel = (id) => (paperMap.value[id] ? paperMap.value[id].code : '#' + id)
const machineTypeOf = (id) => machineMap.value[id] && machineMap.value[id].machineType
// 只有空闲/运行机台能开试装；维修中及历史非法状态（检修/停机等）一律不能选
const machineUsable = (m) => m.status === '空闲' || m.status === '运行'
const machineOptionLabel = (m) =>
  m.code + ' ' + m.name + (machineUsable(m) ? '' : `（${m.status}，不可试装）`)
const pendingOrders = computed(() => orders.value.filter((o) => o.status === '待排'))

const selectedMachineType = computed(() => machineTypeOf(form.value.machineId))
const lockedPaper = computed(() => Boolean(form.value.id && form.value.paperDeducted))

const statusType = (s) => (s === '已过' ? 'success' : s === '退回' ? 'danger' : 'info')

const openCreate = () => {
  form.value = {
    id: null,
    orderId: null,
    machineId: null,
    paperId: null,
    trialQty: 3,
    spineThickness: null,
    stitchCount: null,
    paperDeducted: false
  }
  dialog.value = true
}

const openEdit = (row) => {
  form.value = {
    id: row.id,
    orderId: row.orderId,
    machineId: row.machineId,
    paperId: row.paperId,
    trialQty: row.trialQty,
    spineThickness: row.spineThickness,
    stitchCount: row.stitchCount,
    paperDeducted: row.paperDeducted
  }
  dialog.value = true
}

const submit = async () => {
  const f = form.value
  const payload = {
    machineId: f.machineId,
    paperId: f.paperId,
    trialQty: f.trialQty,
    spineThickness: f.spineThickness,
    stitchCount: f.stitchCount
  }
  if (f.id) await http.put(`/signoffs/${f.id}`, payload)
  else await http.post('/signoffs', { ...payload, orderId: f.orderId })
  dialog.value = false
  await load()
}

const pass = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确认签样通过？试装 ${row.trialQty} 册，通过后机台和纸种即锁定。`,
      '签样通过',
      { type: 'warning' }
    )
  } catch {
    return
  }
  await http.put(`/signoffs/${row.id}`, { status: '已过' })
  ElMessage.success('签样已过，工单可以开印')
  await load()
}

const reject = async (row) => {
  try {
    await ElMessageBox.confirm(
      '退回后已扣的试装纸不退回库存，该工单成品入口继续挡着。确认退回？',
      '签样退回',
      { type: 'warning' }
    )
  } catch {
    return
  }
  await http.put(`/signoffs/${row.id}`, { status: '退回' })
  await load()
}

onMounted(load)
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.tbl { width: 100%; }
.muted { color: #b0a59f; font-size: 13px; margin-right: 8px; }
</style>
