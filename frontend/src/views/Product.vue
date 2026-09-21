<template>
  <div>
    <div class="head">
      <h2>成品</h2>
      <el-button type="primary" @click="openCreate">登记成品</el-button>
    </div>

    <el-row :gutter="16">
      <el-col v-for="p in products" :key="p.id" :span="8" style="margin-bottom:16px">
        <el-card shadow="hover" class="pcard">
          <div class="name">{{ p.name }}</div>
          <div class="meta">数量：{{ p.qty }}</div>
          <div class="meta">归属工单：#{{ p.orderId }}</div>
          <el-tag :type="p.status === '已入库' ? 'success' : 'info'" size="small">{{ p.status }}</el-tag>
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
          <el-select v-model="form.status">
            <el-option label="待入库" value="待入库" />
            <el-option label="已入库" value="已入库" />
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
import { ref, computed, onMounted } from 'vue'
import http from '../api'

const products = ref([])
const doneOrders = ref([])
const signoffs = ref([])
const dialog = ref(false)
const form = ref({ orderId: null, name: '', qty: 100, status: '待入库' })

const passedOrderIds = computed(
  () => new Set(signoffs.value.filter((s) => s.status === '已过').map((s) => s.orderId))
)

const load = async () => {
  products.value = await http.get('/products')
  const orders = await http.get('/orders')
  doneOrders.value = orders.filter((o) => o.status === '已完成')
  signoffs.value = await http.get('/signoffs')
}

const openCreate = () => {
  form.value = { orderId: null, name: '', qty: 100, status: '待入库' }
  dialog.value = true
}
const submit = async () => {
  await http.post('/products', { ...form.value })
  dialog.value = false
  await load()
}

onMounted(load)
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.pcard .name { font-weight: 700; font-size: 15px; }
.pcard .meta { font-size: 13px; color: #8a7f7a; margin: 4px 0; }
</style>
