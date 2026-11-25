<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { http } from '@/core/http'
import { ApiRequestError, errorMessageWithTrace } from '@/core/http/api-error'
import { newIdempotencyKey } from '@/core/http/api-client'
import MediaField from '@/core/ops/MediaField.vue'
import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'
import { formatDateTime } from '@/core/ui/format'
import { actions, formFor, onlineExperienceApi, types, type Change, type Choice, type ItemStatus, type OnlineItem } from './online-experience-api'

const props = defineProps<{ item: OnlineItem|null }>()
const emit = defineEmits<{ close: [], saved: [] }>()
const api = onlineExperienceApi(http)
const id = props.item?.id ?? newIdempotencyKey()
const saved = ref(props.item)
const form = ref(formFor(props.item))
const error = ref('')
const busy = ref(false)
const stale = ref(false)
const history = ref<Change[]>([])
const historyError = ref('')
const products = ref<Choice[]>([])
const courses = ref<Choice[]>([])
const videos = ref<Choice[]>([])
const trial = computed(() => ['SOFTWARE_TRIAL','COURSE_TRIAL'].includes(form.value.type))
const versions = { PRODUCT: 0, COURSE: 0, VIDEO: 0 }
const missingLabels: Record<string,string> = { coverUrl:'封面',summary:'介绍',simulationTemplate:'仿真模板',videoUrl:'录播视频',trialMinutes:'试用时长',courseId:'含可播放试听课节的已上架课程' }

async function choices(kind: 'PRODUCT'|'COURSE'|'VIDEO', keyword: string) {
  const seq = ++versions[kind]
  try {
    const options = await api.choices(kind, keyword)
    if (seq !== versions[kind]) return
    if (kind === 'VIDEO') { videos.value=options; return }
    const linked = kind === 'PRODUCT' ? saved.value?.productId : saved.value?.courseId
    const name = kind === 'PRODUCT' ? saved.value?.productName : saved.value?.courseName
    if (linked && !options.some(o => o.id === linked)) options.unshift({id:linked,name:name || linked})
    if (kind === 'PRODUCT') products.value = options
    else courses.value = options
  } catch (e) { if (seq === versions[kind]) showError(e) }
}
async function readChanges() {
  if (!saved.value) return
  historyError.value=''
  try { history.value=await api.changes(id) } catch (e) { historyError.value=errorMessageWithTrace(e) }
}
async function reload() {
  busy.value=true
  try { saved.value=await api.get(id); form.value=formFor(saved.value); stale.value=false; error.value=''; await readChanges() }
  catch(e) { error.value=errorMessageWithTrace(e) } finally { busy.value=false }
}
function setType() {
  form.value.simulationTemplate=null
  form.value.trialMinutes=null
  form.value.courseId=null
  form.value.videoUrl=''
  form.value.loginRequired=form.value.type==='SOFTWARE_TRIAL'
}
async function submit(status: ItemStatus) {
  if (busy.value || stale.value) return
  if (!form.value.title.trim()) { error.value='请填写项目标题'; return }
  const withdrawing=status==='OFFLINE' && saved.value?.status==='PUBLISHED'
  const message=withdrawing ? '用户将无法新开此项目，已有体验记录仍可查看。'
    : status==='PUBLISHED' ? '保存后，用户将看到本次发布的项目内容。' : '保存本次修改？'
  if (!await confirmAction({title:withdrawing?'下架线上体验':'保存线上体验',message,danger:withdrawing})) return
  busy.value=true
  error.value=''
  try {
    saved.value=await api.save(id,{...form.value,status},saved.value?.courseId ?? null)
    form.value=formFor(saved.value)
    notifyRecorded('保存','项目修改记录')
    emit('saved')
    await readChanges()
  } catch(e) {
    const missing = e instanceof ApiRequestError && Array.isArray(e.details?.missing) ? e.details.missing as string[] : []
    error.value=missing.length ? '发布前请补齐'+missing.map(k=>missingLabels[k]??k).join('、') : errorMessageWithTrace(e)
    if (e instanceof ApiRequestError && e.code.includes('VERSION_CONFLICT')) stale.value=true
  } finally { busy.value=false }
}
onMounted(() => { void choices('PRODUCT',''); void choices('COURSE',''); void readChanges() })
</script>
<template>
  <el-dialog :model-value="true" :title="saved ? '编辑线上体验' : '新建线上体验'" width="min(720px, 95vw)" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" @close="emit('close')">
    <el-form label-position="top" :disabled="busy" @submit.prevent>
      <el-form-item label="项目标题" required><el-input v-model="form.title" maxlength="120" show-word-limit /></el-form-item>
      <el-form-item label="项目类型"><el-select v-model="form.type" :disabled="!!saved && saved.status!=='DRAFT'" @change="setType"><el-option v-for="(name,k) in types" :key="k" :value="k" :label="name" /></el-select></el-form-item>
      <el-form-item label="介绍"><el-input v-model="form.summary" type="textarea" maxlength="200" show-word-limit /></el-form-item>
      <el-form-item label="封面"><MediaField v-model="form.coverUrl" :disabled="busy" /></el-form-item>
      <el-form-item v-if="form.type==='SIMULATION'" label="仿真模板"><el-select v-model="form.simulationTemplate" clearable placeholder="选择已有模板">
        <el-option label="无人机定点悬停" value="DRONE_HOVER" /><el-option label="机器人避障" value="ROBOT_OBSTACLE" />
      </el-select></el-form-item>
      <el-form-item v-if="form.type==='RECORDED_DEMO'" label="录播视频">
        <MediaField v-model="form.videoUrl" kind="VIDEO" :disabled="busy" />
        <el-select :model-value="null" filterable remote placeholder="从已发布视频选用素材" :remote-method="(q:string)=>choices('VIDEO',q)"
          @visible-change="(open:boolean)=>{if(open) choices('VIDEO','')}" @change="(value:string)=>form.videoUrl=value">
          <el-option v-for="o in videos" :key="o.id" :value="o.mediaUrl!" :label="o.name" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="trial" label="试用时长（分钟）"><el-input-number v-model="form.trialMinutes" :min="1" :max="43200" :precision="0" /></el-form-item>
      <el-form-item v-if="form.type==='COURSE_TRIAL'" label="试听课程"><el-select v-model="form.courseId" filterable remote clearable :empty-values="[null,undefined,'']" :value-on-clear="null"
        :remote-method="(q:string)=>choices('COURSE',q)" placeholder="搜索已上架的线上课程"><el-option v-for="o in courses" :key="o.id" :value="o.id" :label="o.name" /></el-select></el-form-item>
      <el-form-item label="关联商品"><el-select v-model="form.productId" filterable remote clearable :empty-values="[null,undefined,'']" :value-on-clear="null" :remote-method="(q:string)=>choices('PRODUCT',q)" placeholder="搜索已发布商品">
        <el-option v-for="o in products" :key="o.id" :value="o.id" :label="o.name" /></el-select></el-form-item>
      <el-form-item label="展示顺序"><el-input-number v-model="form.sortOrder" :min="0" :max="100000" :precision="0" /></el-form-item>
    </el-form>
    <section v-if="saved"><h3>项目修改记录</h3><el-alert v-if="historyError" type="error" :title="historyError" :closable="false"><el-button @click="readChanges">重试</el-button></el-alert>
      <el-table v-else :data="history" empty-text="暂无运营修改记录" max-height="220">
        <el-table-column prop="version" label="版本" width="70" /><el-table-column label="操作"><template #default="{row}">{{actions[row.action]??row.action}}</template></el-table-column>
        <el-table-column prop="operatorName" label="操作人" /><el-table-column label="操作时间" min-width="155"><template #default="{row}">{{formatDateTime(row.changedAt)}}</template></el-table-column>
      </el-table></section>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon><el-button v-if="stale" @click="reload">读取最新版本</el-button></el-alert>
    <template #footer><el-button :disabled="busy" @click="emit('close')">关闭</el-button>
      <el-button v-if="saved?.status==='PUBLISHED'" type="danger" plain :disabled="busy||stale" @click="submit('OFFLINE')">下架</el-button>
      <el-button v-if="saved && saved.status!=='PUBLISHED'" type="success" :disabled="busy||stale" @click="submit('PUBLISHED')">发布</el-button>
      <el-button type="primary" :loading="busy" :disabled="stale" @click="submit(saved?.status??'DRAFT')">{{saved?'保存修改':'保存草稿'}}</el-button>
    </template>
  </el-dialog>
</template>
