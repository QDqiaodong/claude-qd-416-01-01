<template>
  <div>
    <div class="head">
      <h2>装订机</h2>
      <div class="tools">
        <el-select v-model="filter" style="width: 150px" @change="load">
          <el-option label="全部状态" value="" />
          <el-option label="空闲" value="空闲" />
          <el-option label="运行" value="运行" />
          <el-option label="维修" value="维修" />
          <el-option label="异常（非法状态）" value="异常" />
        </el-select>
        <el-button type="primary" @click="openCreate">新增机器</el-button>
      </div>
    </div>

    <el-alert
      v-if="filter === '异常'"
      type="error"
      :closable="false"
      show-icon
      title="以下机台的状态是历史上自由输入写进库的非法值（如检修/停机），系统不认它是维修中。请对该机台执行一次报修，再点修好，状态即可收敛回三个固定取值。"
      style="margin-bottom: 12px"
    />

    <el-row :gutter="16">
      <el-col v-for="m in machines" :key="m.id" :span="8" style="margin-bottom:16px">
        <el-card shadow="hover">
          <div class="machine-card">
            <svg :width="120" :height="90" viewBox="0 0 120 90">
              <!-- 胶装机：书脊矩形 + 胶层 -->
              <g v-if="m.machineType === 'glue'" :stroke="statusColor(m.status)" stroke-width="3" fill="none">
                <rect x="34" y="14" width="52" height="62" rx="4" :fill="statusColor(m.status) + '22'" />
                <line x1="44" y1="14" x2="44" y2="76" stroke-width="5" />
                <path d="M52 76 q6 8 12 0 q6 8 12 0" />
              </g>
              <!-- 骑马钉机：两道钉弧 -->
              <g v-else :stroke="statusColor(m.status)" stroke-width="3" fill="none">
                <path d="M40 70 L40 24 Q60 10 80 24 L80 70" :fill="statusColor(m.status) + '22'" />
                <path d="M52 22 l-6 14 M52 22 l6 14" />
                <path d="M68 22 l-6 14 M68 22 l6 14" />
              </g>
            </svg>
            <div class="info">
              <div class="code">{{ m.code }} · {{ m.name }}</div>
              <el-tag :type="tagType(m.status)" size="small">{{ m.status }}</el-tag>
              <el-tag v-if="!validStatus(m.status)" type="danger" size="small" style="margin-left:4px">非法状态</el-tag>
              <div class="type">{{ m.machineType === 'glue' ? '胶装机' : '骑马钉机' }}</div>
              <div class="param" v-if="m.machineType === 'glue'">最大厚度：{{ m.maxThickness }} mm</div>
              <div class="param" v-else>最大针数：{{ m.maxStitches }}</div>
              <div class="actions">
                <el-button
                  v-if="m.status !== '维修'"
                  size="small"
                  type="danger"
                  plain
                  @click="repair(m)"
                >报修</el-button>
                <el-button
                  v-else
                  size="small"
                  type="success"
                  plain
                  @click="restore(m)"
                >修好（回空闲）</el-button>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog v-model="dialog" title="新增机器" width="420px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="机器类型">
          <el-select v-model="form.machineType" placeholder="选择类型">
            <el-option label="胶装机" value="glue" />
            <el-option label="骑马钉机" value="saddle" />
          </el-select>
        </el-form-item>
        <el-form-item label="编号">
          <el-input v-model="form.code" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status">
            <el-option label="空闲" value="空闲" />
            <el-option label="运行" value="运行" />
            <el-option label="维修" value="维修" />
          </el-select>
          <div class="hint">维修状态请通过机台卡片上的“报修”进入，会连带处理在印工单和未过签样。</div>
        </el-form-item>
        <el-form-item label="最大厚度(mm)" v-if="form.machineType === 'glue'">
          <el-input-number v-model="form.maxThickness" :min="1" />
        </el-form-item>
        <el-form-item label="最大针数" v-else-if="form.machineType === 'saddle'">
          <el-input-number v-model="form.maxStitches" :min="1" />
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
import { ref, onMounted } from 'vue'
import http from '../api'
import { ElMessageBox, ElMessage } from 'element-plus'

const machines = ref([])
const dialog = ref(false)
const filter = ref('')
const form = ref({ machineType: 'glue', code: '', name: '', status: '空闲', maxThickness: 50, maxStitches: 24 })

const VALID = ['空闲', '运行', '维修']
const validStatus = (s) => VALID.includes(s)
const statusColor = (s) => (s === '空闲' ? '#67c23a' : s === '运行' ? '#e6a23c' : s === '维修' ? '#f56c6c' : '#909399')
const tagType = (s) => (s === '空闲' ? 'success' : s === '运行' ? 'warning' : s === '维修' ? 'danger' : 'info')

const load = async () => {
  machines.value = await http.get('/machines', { params: filter.value ? { status: filter.value } : {} })
}

const openCreate = () => {
  form.value = { machineType: 'glue', code: '', name: '', status: '空闲', maxThickness: 50, maxStitches: 24 }
  dialog.value = true
}

const submit = async () => {
  const payload = {
    machineType: form.value.machineType,
    code: form.value.code,
    name: form.value.name,
    status: form.value.status
  }
  if (form.value.machineType === 'glue') payload.maxThickness = form.value.maxThickness
  else payload.maxStitches = form.value.maxStitches
  await http.post('/machines', payload)
  dialog.value = false
  await load()
}

const repair = async (m) => {
  try {
    await ElMessageBox.confirm(
      `确认将 ${m.code} ${m.name} 报修？\n· 机名下若有正在开印的工单，报修会被拦下，需先收尾或退回该工单；\n· 报修生效时，本机台所有未过签样一并作废成退回，已扣的试装纸不退回库存；\n· 修好后这些签样不会自动恢复，需重新开试装。`,
      '机台报修',
      { type: 'warning', confirmButtonText: '确认报修' }
    )
  } catch {
    return
  }
  await http.post(`/machines/${m.id}/repair`)
  ElMessage.success('已报修，机台进入维修')
  await load()
}

const restore = async (m) => {
  try {
    await ElMessageBox.confirm(
      `确认 ${m.code} ${m.name} 已修好？机台将回到空闲。报修时作废的签样不会自动恢复，需重新开试装。`,
      '机台修好',
      { type: 'success', confirmButtonText: '确认修好' }
    )
  } catch {
    return
  }
  await http.post(`/machines/${m.id}/restore`)
  ElMessage.success('机台已恢复空闲')
  await load()
}

onMounted(load)
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.tools { display: flex; gap: 10px; }
.machine-card { display: flex; align-items: center; gap: 14px; }
.info .code { font-weight: 700; }
.info .type { color: #8a7f7a; font-size: 12px; margin: 4px 0; }
.info .param { font-size: 13px; color: var(--el-color-primary); }
.info .actions { margin-top: 8px; }
.hint { font-size: 12px; color: #a89d97; line-height: 1.4; margin-top: 2px; }
</style>
