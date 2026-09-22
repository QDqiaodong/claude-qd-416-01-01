<template>
  <div>
    <div class="head">
      <h2>成品</h2>
      <el-button type="primary" @click="openCreate">登记成品</el-button>
    </div>

    <el-card shadow="never" class="ledger">
      <div class="ledger-title">工单入库台账（换单后原单与新单的数量在此可核对）</div>
      <el-table :data="ledger" size="small">
        <el-table-column label="工单" min-width="160">
          <template #default="{ row }">#{{ row.id }} {{ row.jobName }}</template>
        </el-table-column>
        <el-table-column label="计划数量" width="90" align="right">
          <template #default="{ row }">{{ row.qty }}</template>
        </el-table-column>
        <el-table-column label="待入库" width="110" align="right">
          <template #default="{ row }">{{ row.pendingCount }} 笔 / {{ row.pendingQty }} 册</template>
        </el-table-column>
        <el-table-column label="已入库" width="110" align="right">
          <template #default="{ row }">{{ row.stockedCount }} 笔 / {{ row.stockedQty }} 册</template>
        </el-table-column>
        <el-table-column label="可再登记余量" width="110" align="right">
          <template #default="{ row }">
            <span :class="{ neg: row.remain < 0 }">{{ row.remain }}</span>
          </template>
        </el-table-column>
        <el-table-column label="已过签样" width="90" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.passedCount > 0" type="success" size="small">{{ row.passedCount }} 条</el-tag>
            <el-tag v-else type="danger" size="small">无</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-row :gutter="16">
      <el-col v-for="p in products" :key="p.id" :span="8" style="margin-bottom:16px">
        <el-card shadow="hover" class="pcard">
          <div class="name">
            {{ p.name }}
            <el-tag :type="p.status === '已入库' ? 'success' : 'info'" size="small">{{ p.status }}</el-tag>
            <el-tag v-if="p.status === '已入库'" type="warning" size="small">已锁定</el-tag>
          </div>
          <div class="meta">数量：{{ p.qty }}</div>
          <div class="meta">归属工单：#{{ p.orderId }} {{ orderLabel(p.orderId) }}</div>
          <div v-if="p.prevOrderId" class="meta trace">
            自 #{{ p.prevOrderId }} {{ orderLabel(p.prevOrderId) }} 转入
          </div>
          <div v-if="p.status === '待入库'" class="actions">
            <el-button size="small" type="success" plain @click="stockIn(p)">登记入库</el-button>
            <el-button size="small" @click="openEdit(p)">编辑 / 换单</el-button>
          </div>
          <div v-else class="meta locked">归属工单与数量已锁定，不可再改</div>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog v-model="dialog" title="登记成品" width="420px">
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
        <el-form-item label="状态">
          <el-tag type="info" size="small">待入库</el-tag>
          <div class="hint">登记后只能是待入库；入库请走卡片上的“登记入库”，不能一登记就已入库。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editDialog" title="编辑 / 换单（仅待入库可改）" width="420px">
      <el-form :model="editForm" label-width="80px">
        <el-form-item label="归属工单">
          <el-select v-model="editForm.orderId" placeholder="目标工单须已完成且签样已过">
            <el-option
              v-for="o in doneOrders"
              :key="o.id"
              :label="'#' + o.id + ' ' + o.jobName + (passedOrderIds.has(o.id) ? '' : '（无已过签样）')"
              :value="o.id"
              :disabled="!passedOrderIds.has(o.id)"
            />
          </el-select>
          <div v-if="editForm.orderId !== editForm.origOrderId" class="hint">
            换单：#{{ editForm.origOrderId }} → #{{ editForm.orderId }}，保存后原工单留痕可查。
          </div>
        </el-form-item>
        <el-form-item label="名称"><el-input v-model="editForm.name" /></el-form-item>
        <el-form-item label="数量"><el-input-number v-model="editForm.qty" :min="1" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessageBox } from 'element-plus'
import http from '../api'

const products = ref([])
const orders = ref([])
const signoffs = ref([])
const dialog = ref(false)
const editDialog = ref(false)
const form = ref({ orderId: null, name: '', qty: 100 })
const editForm = ref({ id: null, orderId: null, origOrderId: null, name: '', qty: 1 })

const doneOrders = computed(() => orders.value.filter((o) => o.status === '已完成'))

const passedOrderIds = computed(
  () => new Set(signoffs.value.filter((s) => s.status === '已过').map((s) => s.orderId))
)

const orderMap = computed(() => Object.fromEntries(orders.value.map((o) => [o.id, o])))
const orderLabel = (id) => orderMap.value[id]?.jobName || ''

// 按工单汇总 计划/待入库/已入库/可再登记余量：换单后原单减、新单加，两边关系在此可核对
const ledger = computed(() =>
  doneOrders.value.map((o) => {
    const ps = products.value.filter((p) => p.orderId === o.id)
    const pending = ps.filter((p) => p.status === '待入库')
    const stocked = ps.filter((p) => p.status === '已入库')
    const sum = (arr) => arr.reduce((t, p) => t + (p.qty || 0), 0)
    return {
      ...o,
      pendingCount: pending.length,
      pendingQty: sum(pending),
      stockedCount: stocked.length,
      stockedQty: sum(stocked),
      remain: (o.qty || 0) - sum(stocked) - sum(pending),
      passedCount: signoffs.value.filter((s) => s.orderId === o.id && s.status === '已过').length,
    }
  })
)

const load = async () => {
  products.value = await http.get('/products')
  orders.value = await http.get('/orders')
  signoffs.value = await http.get('/signoffs')
}

const openCreate = () => {
  form.value = { orderId: null, name: '', qty: 100 }
  dialog.value = true
}
const submit = async () => {
  await http.post('/products', { ...form.value })
  dialog.value = false
  await load()
}

const openEdit = (p) => {
  editForm.value = { id: p.id, orderId: p.orderId, origOrderId: p.orderId, name: p.name, qty: p.qty }
  editDialog.value = true
}
const submitEdit = async () => {
  const f = editForm.value
  await http.put(`/products/${f.id}`, { name: f.name, qty: f.qty, orderId: f.orderId })
  editDialog.value = false
  await load()
}

const stockIn = async (p) => {
  try {
    await ElMessageBox.confirm(
      `确认把「${p.name}」（${p.qty} 册）登记为已入库？入库后归属工单和数量将锁定，不能再改。`,
      '登记入库',
      { type: 'warning', confirmButtonText: '确认入库', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await http.put(`/products/${p.id}`, { status: '已入库' })
  } catch {
    // 后端拦截（如：工单签样已被退回、他人已抢先入库），拦截器已弹出原因
  } finally {
    await load()
  }
}

onMounted(load)
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.ledger { margin-bottom: 16px; }
.ledger-title { font-weight: 700; margin-bottom: 8px; }
.neg { color: var(--el-color-danger); font-weight: 700; }
.pcard .name { font-weight: 700; font-size: 15px; display: flex; gap: 6px; align-items: center; }
.pcard .meta { font-size: 13px; color: #8a7f7a; margin: 4px 0; }
.pcard .trace { color: #b88230; }
.pcard .locked { color: #a8a29e; font-size: 12px; margin-top: 6px; }
.pcard .actions { margin-top: 8px; }
.hint { font-size: 12px; color: #a8a29e; line-height: 1.4; }
</style>
