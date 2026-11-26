<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { http } from '@/core/http'
import { ApiRequestError, errorMessage } from '@/core/http/api-error'
import { confirmAction } from '@/core/ui/feedback'
import { formatDateTime } from '@/core/ui/format'

interface Game {
  id: string
  title: string
  subtitle: string
  skillLabel: string
  tone: 'VIOLET' | 'SKY'
  avatar: 'ROBOT' | 'DRONE'
  board: string
  sortOrder: number
  version: number
  publishedVersion: number | null
  published: boolean
}
interface Change { version: number; action: string; operatorName: string; changedAt: string }
const api = '/api/v1/ops/experience/play-games'
const actions: Record<string,string> = {CREATE:'创建', EDIT:'保存草稿', PUBLISH:'发布', OFFLINE:'下架'}
const palette = [
  { value:'.', name:'空地', symbol:'·' },
  { value:'W', name:'障碍', symbol:'■' },
  { value:'S', name:'星星', symbol:'★' },
  { value:'G', name:'终点', symbol:'旗' },
]
const rows = ref<Game[]>([])
const form = ref<Game>()
const history = ref<Change[]>([])
const historyError = ref('')
const brush = ref('S')
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const formError = ref('')
let revision = 0

async function load() {
  const request = ++revision
  loading.value = true
  error.value = ''
  try {
    const result = await http.get<Game[]>(api)
    if (request === revision) rows.value = result
  } catch (failure) {
    if (request === revision) error.value = errorMessage(failure)
  } finally {
    if (request === revision) loading.value = false
  }
}
async function edit(game?: Game) {
  formError.value = ''
  historyError.value = ''
  history.value = []
  brush.value = 'S'
  form.value = game ? {...game} : {
    id:'', title:'', subtitle:'', skillLabel:'', tone:'VIOLET', avatar:'ROBOT', board:'.'.repeat(25),
    sortOrder:0, version:0, publishedVersion:null, published:false,
  }
  if (game) {
    try {
      const changes = await http.get<Change[]>(api+'/'+game.id+'/changes')
      if (form.value?.id === game.id) history.value = changes
    } catch (failure) {
      if (form.value?.id === game.id) historyError.value = errorMessage(failure)
    }
  }
}
function paint(index: number) {
  const game = form.value
  if (!game || index === 0) return
  const cells = game.board.split('')
  if (brush.value === 'G') {
    const previous = cells.indexOf('G')
    if (previous >= 0) cells[previous]='.'
  }
  cells[index] = brush.value
  game.board = cells.join('')
}
async function save() {
  const game = form.value
  if (!game || busy.value) return
  if (![game.title, game.subtitle, game.skillLabel].every(v=>v.trim())) {
    formError.value = '请填写标题、介绍和技能标签'
    return
  }
  busy.value = true
  formError.value = ''
  try {
    if (game.id) await http.put<Game>(api+'/'+game.id, game)
    else await http.post<Game>(api, game)
    form.value = undefined
    ElMessage.success('已保存关卡草稿')
    await load()
  } catch (failure) { formError.value = errorMessage(failure) }
  finally { busy.value = false }
}
async function changeStatus(game: Game, action: 'publish'|'offline') {
  if (busy.value) return
  const publishing = action === 'publish'
  const label = publishing ? '发布' : '下架'
  if (!await confirmAction({
    title:label+'关卡', message:publishing ? '发布后，玩家将看到当前草稿的介绍、排序和棋盘。' : '下架后，玩家将无法从列表或原链接进入此关卡。',
    confirmText:label, danger:!publishing,
  })) return
  busy.value = true
  error.value = ''
  try {
    await http.post<Game>(api+'/'+game.id+'/'+action, {version:game.version})
    ElMessage.success('关卡已'+label)
    await load()
  } catch (failure) {
    const board = failure instanceof ApiRequestError ? failure.details?.board : undefined
    error.value = typeof board === 'string' ? '无法发布「'+game.title+'」：'+board : errorMessage(failure)
  } finally { busy.value = false }
}
function cellName(value: string) { return palette.find(cell=>cell.value === value)?.name || '空地' }
function cellSymbol(value: string) { return palette.find(cell=>cell.value === value)?.symbol || '·' }
onMounted(load)
</script>

<template>
  <div class="game-actions"><el-button type="primary" @click="edit()">新增关卡</el-button></div>
  <el-alert v-if="error" type="error" :closable="false" :title="error"><el-button link @click="load">重新加载</el-button></el-alert>
  <el-table v-loading="loading" :data="rows" row-key="id" empty-text="尚无趣玩关卡" border stripe>
    <el-table-column prop="title" label="标题" min-width="160" />
    <el-table-column prop="subtitle" label="介绍" min-width="160" show-overflow-tooltip />
    <el-table-column prop="sortOrder" label="顺序" width="75" />
    <el-table-column label="状态" min-width="140"><template #default="{row}">
      {{ row.published ? (row.version===row.publishedVersion?'已发布':'已发布 · 有新草稿') : (row.publishedVersion===null?'草稿':'已下架') }}
    </template></el-table-column>
    <el-table-column label="操作" min-width="185"><template #default="{row}">
      <el-button link type="primary" @click="edit(row as Game)">编辑</el-button>
      <el-button link type="primary" :disabled="busy || (row.published && row.version===row.publishedVersion)" @click="changeStatus(row as Game,'publish')">发布</el-button>
      <el-button v-if="row.published" link type="danger" :disabled="busy" @click="changeStatus(row as Game,'offline')">下架</el-button>
    </template></el-table-column>
  </el-table>
  <el-dialog :model-value="!!form" title="关卡草稿" width="660px" top="4vh" class="game-dialog"
    destroy-on-close :close-on-click-modal="false" @close="form=undefined">
    <el-form v-if="form" label-width="86px">
      <el-form-item label="标题" required><el-input v-model="form.title" maxlength="100" /></el-form-item>
      <el-form-item label="介绍" required><el-input v-model="form.subtitle" maxlength="180" /></el-form-item>
      <el-form-item label="技能标签" required><el-input v-model="form.skillLabel" maxlength="100" /></el-form-item>
      <el-form-item label="顺序"><el-input-number v-model="form.sortOrder" :min="0" :precision="0" /></el-form-item>
      <el-form-item label="配色"><el-radio-group v-model="form.tone"><el-radio-button value="VIOLET">浅紫</el-radio-button><el-radio-button value="SKY">天蓝</el-radio-button></el-radio-group></el-form-item>
      <el-form-item label="角色"><el-radio-group v-model="form.avatar"><el-radio-button value="ROBOT">机器人</el-radio-button><el-radio-button value="DRONE">无人机</el-radio-button></el-radio-group></el-form-item>
      <el-form-item label="棋盘">
        <div>
          <el-radio-group v-model="brush" aria-label="画笔"><el-radio-button v-for="b in palette" :key="b.value" :value="b.value">{{ b.name }}</el-radio-button></el-radio-group>
          <div class="board" aria-label="五行五列棋盘">
            <button v-for="(cell,index) in form.board.split('')" :key="index" type="button" :disabled="index===0"
              :class="['cell','cell-'+cell]" :aria-label="'第'+(Math.floor(index/5)+1)+'行第'+(index%5+1)+'列，'+(index===0?'起点':cellName(cell))"
              :data-testid="'ops-game-cell-'+index" @click="paint(index)">{{ index===0?'起':cellSymbol(cell) }}</button>
          </div>
          <p class="board-help">选择画笔，再点格子。玩家从左上角出发，收齐星星后到达终点。</p>
        </div>
      </el-form-item>
    </el-form>
    <el-alert v-if="formError || historyError" type="error" :closable="false" :title="formError || historyError" />
    <el-table v-if="form?.id" :data="history" size="small" empty-text="尚无运营改动">
      <el-table-column prop="version" label="版本" width="65" />
      <el-table-column label="操作"><template #default="{row}">{{ actions[row.action] }}</template></el-table-column>
      <el-table-column prop="operatorName" label="操作人" />
      <el-table-column label="时间"><template #default="{row}">{{ formatDateTime(row.changedAt) }}</template></el-table-column>
    </el-table>
    <template #footer><el-button :disabled="busy" @click="form=undefined">取消</el-button>
      <el-button type="primary" :loading="busy" @click="save">保存草稿</el-button></template>
  </el-dialog>
</template>
<style scoped>
.game-actions { margin-bottom: 14px; }
:global(.game-dialog .el-dialog__body) { max-height: calc(92vh - 106px); overflow-y: auto; }
.board { display:grid; grid-template-columns:repeat(5,42px); gap:4px; margin-top:10px; }
.cell { width:42px; height:42px; border:1px solid #cad5e0; border-radius:5px; background:#f6f8fa; color:#526071; font:inherit; cursor:pointer; }
.cell:disabled { background:#d7eef9; color:#1c456b; cursor:default; }
.cell-W { background:#526071; color:#fff; }
.cell-S { background:#fff2cc; color:#805400; }
.cell-G { background:#def3e7; color:#236545; }
.cell:focus-visible { outline:2px solid #276bbe; }
.board-help { margin:6px 0 0; color:var(--admin-text-secondary); font-size:12px; }
</style>
