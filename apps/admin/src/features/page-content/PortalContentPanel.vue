<script setup lang="ts">
import { ref, shallowRef, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { errorMessage } from '@/core/http/api-error'
import MediaField from '@/core/ops/MediaField.vue'
import CityPicker from '@/features/content/components/CityPicker.vue'
import type { OpsInspectionCategory } from '@/features/inspection-catalog/inspection-catalog-api'
import PageContentHistory from './PageContentHistory.vue'
import { pageContent, typeLabels, stateLabels, type Portal, type Change } from './page-content'

defineProps<{categories: OpsInspectionCategory[]}>()
const city = ref('')
const rows = ref<Portal[]>([])
const form = ref<Portal>()
const error = ref('')
const problem = ref('')
const loading = ref(false)
const saving = ref(false)
const history = shallowRef<() => Promise<Change[]>>()
let loadId = 0
async function load() {
  const seq = ++loadId; loading.value=true; error.value=''; rows.value=[]
  try {
    const response = await pageContent.portal(city.value)
    if (seq === loadId) rows.value=response
  } catch(e) { if(seq===loadId) error.value=errorMessage(e) }
  finally { if(seq===loadId) loading.value=false }
}
function edit(p?: Portal) {
  problem.value=''
  history.value=p ? () => pageContent.portalChanges(p) : undefined
  form.value=p ? {...p} : {id:'',cityCode:city.value||null,contentType:'HERO',itemCode:'DEFAULT',title:'',
    subtitle:'',highlightedText:'',imageUrl:'',sortOrder:10,status:'ACTIVE',version:0}
}
function selectType(type: string) {
  if (!form.value) return
  form.value.itemCode=type==='HERO'?'DEFAULT':''
  form.value.highlightedText=''; form.value.imageUrl=''
}
async function save() {
  const p=form.value
  if(!p || saving.value) return
  if(!p.title.trim() || !p.itemCode.trim()) {problem.value='请填写标题和条目编码'; return}
  saving.value=true; problem.value=''
  try { await pageContent.savePortal(p); form.value=undefined; ElMessage.success('入口配置已保存'); await load() }
  catch(e) {problem.value=errorMessage(e)} finally {saving.value=false}
}
watch(city,load,{immediate:true})
</script>
<template>
  <div class="toolbar"><CityPicker v-model="city" placeholder="全国默认" /><el-button type="primary" @click="edit()">新增入口内容</el-button></div>
  <p class="hint">{{ city ? '当前编辑该城市的覆盖内容。未配置的栏目沿用全国默认，场景说明按类目继承。' : '全国默认内容用于没有单独配置的城市。' }}</p>
  <el-alert v-if="error" type="error" :title="error" :closable="false"><el-button @click="load">重试</el-button></el-alert>
  <el-table v-else v-loading="loading" :data="rows" row-key="id" empty-text="当前范围尚未配置" border stripe>
    <el-table-column label="栏目" width="110"><template #default="{row}">{{ typeLabels[row.contentType] }}</template></el-table-column>
    <el-table-column prop="title" label="标题" /><el-table-column prop="subtitle" label="说明" show-overflow-tooltip />
    <el-table-column prop="sortOrder" label="顺序" width="80" />
    <el-table-column label="状态" width="90"><template #default="{row}">{{ stateLabels[row.status] }}</template></el-table-column>
    <el-table-column label="操作" width="90"><template #default="{row}"><el-button link type="primary" @click="edit(row as Portal)">编辑</el-button></template></el-table-column>
  </el-table>
  <el-dialog :model-value="!!form" :title="form?.id?'编辑入口内容':'新增入口内容'" width="620px" destroy-on-close
    :close-on-click-modal="false" @close="form=undefined">
    <el-form v-if="form" label-width="100px">
      <el-form-item label="适用范围">{{ form.cityCode || '全国默认' }}</el-form-item>
      <el-form-item label="栏目"><el-select v-model="form.contentType" :disabled="!!form.id" @change="selectType">
        <el-option v-for="(name,key) in typeLabels" :key="key" :value="key" :label="name" /></el-select></el-form-item>
      <el-form-item v-if="form.contentType==='SCENE'" label="场景类目" required>
        <el-select v-model="form.itemCode" :disabled="!!form.id"><el-option v-for="c in categories.filter(c=>c.status==='ACTIVE' && !c.parentCode)"
          :key="c.code" :value="c.code" :label="c.name" /></el-select></el-form-item>
      <el-form-item v-else-if="form.contentType!=='HERO'" label="条目编码" required>
        <el-input v-model="form.itemCode" :disabled="!!form.id" placeholder="字母、数字、下划线或连字符" maxlength="64" /></el-form-item>
      <el-form-item label="标题" required><el-input v-model="form.title" maxlength="128" /></el-form-item>
      <el-form-item label="说明"><el-input :model-value="form.subtitle||''" maxlength="256" @update:model-value="v=>form && (form.subtitle=v)" /></el-form-item>
      <template v-if="form.contentType==='HERO'">
        <el-form-item label="强调文字"><el-input :model-value="form.highlightedText||''" maxlength="64" @update:model-value="v=>form && (form.highlightedText=v)" /></el-form-item>
        <el-form-item label="横幅图片"><MediaField :model-value="form.imageUrl||''" @update:model-value="v=>form && (form.imageUrl=v)" /></el-form-item>
      </template>
      <el-form-item v-if="form.contentType!=='HERO' && form.contentType!=='SCENE'" label="顺序"><el-input-number v-model="form.sortOrder" :min="0" :precision="0" /></el-form-item>
      <el-form-item label="状态"><el-select v-model="form.status" :disabled="form.contentType==='HERO' && !form.cityCode">
        <el-option value="ACTIVE" label="启用" /><el-option value="INACTIVE" label="停用" /></el-select></el-form-item>
    </el-form>
    <PageContentHistory v-if="history" :load="history" />
    <el-alert v-if="problem" type="error" :title="problem" :closable="false" />
    <template #footer><el-button :disabled="saving" @click="form=undefined">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
  </el-dialog>
</template>
<style scoped>
.toolbar {display:flex; align-items:center; gap:12px}
.hint {color:var(--admin-text-secondary);font-size:13px;line-height:1.6}
</style>
