<template>
  <div>
    <div class="head">
      <h2>成品</h2>
      <el-button type="primary" @click="openCreate">登记成品</el-button>
    </div>

    <el-alert
      class="rule"
      type="info"
      :closable="false"
      title="入库链：成品只能「待入库 → 已入库」单向推进；已入库后归属工单与数量锁死，不能换单、改数量或回退。换单只允许换到仍「已完成且有已过签样」的工单，原工单号会留在卡片上。"
    />

    <el-row :gutter="16">
      <el-col v-for="p in products" :key="p.id" :span="8" style="margin-bottom:16px">
        <el-card shadow="hover" class="pcard">
          <div class="name">{{ p.name }}</div>
          <div class="meta">数量：{{ p.qty }}</div>
          <div class="meta">
            归属工单：{{ orderLabel(p.orderId) }}
            <el-tag v-if="p.status === '已入库'" size="small" type="info" class="lock">已锁定</el-tag>
          </div>
          <div v-if="p.previousOrderId" class="meta prev">
            原工单：{{ orderLabel(p.previousOrderId) }}
            <el-tag size="small" type="warning">换单留痕</el-tag>
          </div>
          <el-tag :type="p.status === '已入库' ? 'success' : 'info'" size="small" class="st">{{ p.status }}</el-tag>
          <div class="ops">
            <template v-if="p.status === '待入库'">
              <el-button size="small" type="success" @click="stockIn(p)">登记入库</el-button>
              <el-button size="small" @click="openEdit(p)">改数量</el-button>
              <el-button size="small" type="warning" plain @click="openMove(p)">换归属工单</el-button>
            </template>
            <span v-else class="muted">已入库：工单/数量已锁，仅名称可改</span>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog v-model="createDialog" title="登记成品" width="420px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="归属工单">
          <el-select v-model="form.orderId" placeholder="仅已完成且签样已过的工单">
            <el-option
              v-for="o in doneOrders"
              :key="o.id"
              :label="'#' + o.id + ' ' + o.jobName + (passedOrderIds.has(o.id) ? '' : '（无已过签样）')"
              :value="o.id"
              :disabled="!passedOrderIds.has(o.id)"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="数量"><el-input-number v-model="form.qty" :min="1" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editDialog" :title="'改数量 · 成品 #' + editing?.id" width="420px">
      <el-form label-width="80px">
        <el-form-item label="名称"><el-input v-model="editForm.name" /></el-form-item>
        <el-form-item label="数量"><el-input-number v-model="editForm.qty" :min="1" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="moveDialog" :title="'换归属工单 · 成品 #' + editing?.id" width="460px">
      <el-form label-width="110px">
        <el-form-item label="当前归属工单">
          <span>{{ orderLabel(editing?.orderId) }}</span>
        </el-form-item>
        <el-form-item label="新归属工单">
          <el-select v-model="moveForm.orderId" placeholder="仅已完成且有已过签样的工单" style="width:100%">
            <el-option
              v-for="o in movableOrders"
              :key="o.id"
              :label="'#' + o.id + ' ' + o.jobName + (passedOrderIds.has(o.id) ? '' : '（无已过签样，不可选）')"
              :value="o.id"
              :disabled="!passedOrderIds.has(o.id) || o.id === editing?.orderId"
            />
          </el-select>
        </el-form-item>
        <el-alert
          type="warning"
          :closable="false"
          title="换单后原工单号会保留在卡片上；已完成且签样有效的工单才收得下这笔成品。"
        />
      </el-form>
      <template #footer>
        <el-button @click="moveDialog = false">取消</el-button>
        <el-button type="primary" @click="submitMove">确认换单</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import http from '../api'
import { ElMessageBox, ElMessage } from 'element-plus'

const products = ref([])
const doneOrders = ref([])
const signoffs = ref([])

const createDialog = ref(false)
const editDialog = ref(false)
const moveDialog = ref(false)
const form = ref({ orderId: null, name: '', qty: 100 })
const editing = ref(null)
const editForm = ref({ name: '', qty: 1 })
const moveForm = ref({ orderId: null })

const orderMap = computed(() => Object.fromEntries(doneOrders.value.map((o) => [o.id, o])))
const orderLabel = (id) => {
  if (id == null) return '—'
  const o = orderMap.value[id]
  return o ? '#' + o.id + ' ' + o.jobName : '#' + id + '（工单不存在）'
}
// 仍保留至少一条“已过”签样的工单（多个已过/被退回都算，只要还有一条已过）
const passedOrderIds = computed(
  () => new Set(signoffs.value.filter((s) => s.status === '已过').map((s) => s.orderId))
)
const movableOrders = computed(() => doneOrders.value)

const load = async () => {
  products.value = await http.get('/products')
  const orders = await http.get('/orders')
  doneOrders.value = orders.filter((o) => o.status === '已完成')
  signoffs.value = await http.get('/signoffs')
}

const openCreate = () => {
  form.value = { orderId: null, name: '', qty: 100 }
  createDialog.value = true
}
const submitCreate = async () => {
  // 新登记成品一律按待入库入库；入库只能稍后走“登记入库”动作
  await http.post('/products', { ...form.value, status: '待入库' })
  createDialog.value = false
  ElMessage.success('已登记为待入库成品')
  await load()
}

const openEdit = (p) => {
  editing.value = p
  editForm.value = { name: p.name, qty: p.qty }
  editDialog.value = true
}
const submitEdit = async () => {
  await http.put(`/products/${editing.value.id}`, { ...editForm.value })
  editDialog.value = false
  ElMessage.success('已保存')
  await load()
}

const openMove = (p) => {
  editing.value = p
  moveForm.value = { orderId: null }
  moveDialog.value = true
}
const submitMove = async () => {
  if (moveForm.value.orderId == null) {
    ElMessage.error('请选择新归属工单')
    return
  }
  await http.put(`/products/${editing.value.id}`, { orderId: moveForm.value.orderId })
  moveDialog.value = false
  ElMessage.success('已换单，原工单号留在卡片上可核对')
  await load()
}

const stockIn = async (p) => {
  try {
    await ElMessageBox.confirm(
      `确认把「${p.name}」${p.qty} 件登记为已入库？入库后归属工单 ${orderLabel(p.orderId)} 与数量都会锁住，不能再改。`,
      '登记入库',
      { type: 'warning' }
    )
  } catch {
    return
  }
  await http.put(`/products/${p.id}`, { status: '已入库' })
  ElMessage.success('已入库')
  await load()
}

onMounted(load)
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.rule { margin-bottom: 12px; }
.pcard .name { font-weight: 700; font-size: 15px; }
.pcard .meta { font-size: 13px; color: #8a7f7a; margin: 4px 0; }
.pcard .prev { color: #b8862f; }
.pcard .st { margin-top: 4px; }
.pcard .lock { margin-left: 6px; }
.ops { margin-top: 10px; border-top: 1px dashed #e3d9d4; padding-top: 8px; }
.muted { color: #b0a59f; font-size: 12px; }
</style>
