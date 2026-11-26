<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { errorMessage } from '@/core/http/api-error'
import { confirmAction, showError } from '@/core/ui/feedback'
import PageSection from '@/core/ui/PageSection.vue'
import PagedTable from '@/core/ui/PagedTable.vue'
import { usePagedList } from '@/core/ui/use-paged-list'
import { actionLabels, agreements, agreementTypes, statusLabels, type Agreement, type AgreementHeader, type Draft } from './agreements'

const list = usePagedList((f: {doc: string}, page, size) => agreements.list(f.doc, page, size), {doc:''})
const saved = ref<Agreement>()
const draft = ref<Draft>()
const mode = ref<'edit' | 'preview'>('edit')
const busy = ref(false)
const opening = ref(false)
const problem = ref('')
const valid = computed(() => draft.value && draft.value.title.trim() && draft.value.version.trim()
  && draft.value.sections.every(s => s.title.trim() && s.body.trim()))

function create() {
  saved.value = undefined
  mode.value = 'edit'
  problem.value = ''
  draft.value = {doc:'USER_AGREEMENT',title:'',version:'',effectiveOn:null,revision:0,sections:[]}
}
async function open(row: AgreementHeader, view: 'edit' | 'preview') {
  if (opening.value) return
  opening.value = true
  try {
    const item = await agreements.detail(row.id)
    saved.value = item
    const {doc, title, version, effectiveOn, revision} = item.document
    draft.value = {doc, title, version, effectiveOn, revision, sections:item.sections.map(s => ({...s}))}
    mode.value = view
    problem.value = ''
  } catch (e) { showError(e) }
  finally { opening.value = false }
}
async function save() {
  if (!valid.value || !draft.value || busy.value) return
  busy.value = true
  problem.value = ''
  try {
    await agreements.save(saved.value?.document.id, draft.value)
    draft.value = undefined
    ElMessage.success('草稿已保存')
    await list.load()
  } catch (e) { problem.value = errorMessage(e) }
  finally { busy.value = false }
}
async function publish() {
  const header = saved.value?.document
  if (!header || header.status !== 'DRAFT' || !header.effectiveOn || busy.value) return
  busy.value = true
  problem.value = ''
  try {
    if (!await confirmAction({title:'发布协议版本',message:'《' + header.title + '》将按北京时间 ' + header.effectiveOn
      + ' 生效。发布后保留此版本的正文。',confirmText:'确认发布'})) return
    await agreements.publish(header)
    draft.value = undefined
    ElMessage.success('协议已发布')
    await list.load()
  } catch (e) { problem.value = errorMessage(e) }
  finally { busy.value = false }
}
onMounted(list.load)
</script>
<template>
  <PageSection title="平台协议" description="维护 App 展示的协议正文、版本和生效日期。" traced>
    <template #actions><el-button type="primary" @click="create">新增版本</el-button></template>
    <el-select v-model="list.filters.doc" class="type-filter" placeholder="全部协议类型" clearable aria-label="筛选协议类型" @change="list.search">
      <el-option v-for="(label,key) in agreementTypes" :key="key" :label="label" :value="key" />
    </el-select>
    <PagedTable :items="list.items.value" :total="list.total.value" :page="list.page.value" :size="list.size.value"
      :loading="list.loading.value || opening" :error="list.error.value" row-key="id" empty-text="暂无协议版本"
      @retry="list.load" @change-page="list.changePage" @change-size="list.changeSize">
      <el-table-column label="类型"><template #default="{row}">{{ agreementTypes[(row as AgreementHeader).doc] }}</template></el-table-column>
      <el-table-column prop="title" label="协议标题" min-width="180" />
      <el-table-column prop="version" label="版本号" />
      <el-table-column label="生效日期"><template #default="{row}">{{ row.effectiveOn || '待填写' }}</template></el-table-column>
      <el-table-column label="状态"><template #default="{row}">{{ statusLabels[(row as AgreementHeader).status] }}</template></el-table-column>
      <el-table-column label="操作" width="150"><template #default="{row}">
        <el-button link type="primary" @click="open(row as AgreementHeader,'preview')">预览</el-button>
        <el-button v-if="row.status==='DRAFT'" link type="primary" @click="open(row as AgreementHeader,'edit')">编辑</el-button>
      </template></el-table-column>
    </PagedTable>
    <el-dialog :model-value="!!draft" :title="mode==='preview'?'协议预览':'编辑协议草稿'" width="690px"
      destroy-on-close :close-on-click-modal="false" @close="draft=undefined">
      <template v-if="draft">
        <el-form v-if="mode==='edit'" label-position="top">
          <div class="agreement-fields">
            <el-form-item label="协议类型" required><el-select v-model="draft.doc" :disabled="!!saved">
              <el-option v-for="(label,key) in agreementTypes" :key="key" :label="label" :value="key" />
            </el-select></el-form-item>
            <el-form-item label="版本号" required><el-input v-model="draft.version" maxlength="40" /></el-form-item>
          </div>
          <el-form-item label="协议标题" required><el-input v-model="draft.title" maxlength="160" /></el-form-item>
          <el-form-item label="生效日期"><el-date-picker v-model="draft.effectiveOn" type="date" value-format="YYYY-MM-DD" placeholder="选择北京时间日期" /></el-form-item>
          <fieldset v-for="(section,index) in draft.sections" :key="index">
            <legend>第 {{ index+1 }} 节</legend>
            <el-form-item label="章节标题" required><el-input v-model="section.title" maxlength="200" /></el-form-item>
            <el-form-item label="章节正文" required><el-input v-model="section.body" type="textarea" :rows="4" maxlength="20000" /></el-form-item>
            <el-button v-if="index>0" size="small" @click="draft.sections.splice(index-1,0,...draft.sections.splice(index,1))">上移</el-button>
            <el-button size="small" @click="draft.sections.splice(index,1)">移除本节</el-button>
          </fieldset>
          <el-button :disabled="draft.sections.length>=80" @click="draft.sections.push({title:'',body:''})">添加章节</el-button>
        </el-form>
        <article v-else class="agreement-preview">
          <h2>{{ draft.title }}</h2>
          <p>{{ draft.version }} · {{ draft.effectiveOn || '尚未设置生效日期' }}</p>
          <p v-if="!draft.sections.length">尚未录入正文</p>
          <section v-for="(section,index) in draft.sections" :key="index"><h3>{{ section.title }}</h3><p>{{ section.body }}</p></section>
        </article>
        <details v-if="saved?.history.length"><summary>操作记录</summary>
          <p v-for="entry in saved.history" :key="entry.revision">{{ new Date(entry.changedAt).toLocaleString('zh-CN') }} · {{ entry.operatorName }} · {{ actionLabels[entry.action] }}</p>
        </details>
        <el-alert v-if="problem" :title="problem" type="error" :closable="false" />
      </template>
      <template #footer><el-button :disabled="busy" @click="draft=undefined">关闭</el-button>
        <el-button v-if="mode==='edit'" type="primary" :loading="busy" :disabled="!valid" @click="save">保存草稿</el-button>
        <el-button v-else-if="saved?.document.status==='DRAFT'" type="primary" :loading="busy"
          :disabled="!draft?.effectiveOn || !draft.sections.length" @click="publish">发布版本</el-button>
      </template>
    </el-dialog>
  </PageSection>
</template>
<style scoped>
.type-filter { width: 200px; margin-bottom: 16px; }
.agreement-fields { display:grid; grid-template-columns:1fr 1fr; gap:20px; }
fieldset { border:1px solid var(--el-border-color); border-radius:6px; margin:0 0 16px; padding:12px; }
.agreement-preview { overflow-wrap:anywhere; }
.agreement-preview p { white-space:pre-wrap; line-height:1.7; }
details { margin:16px 0; }
</style>
