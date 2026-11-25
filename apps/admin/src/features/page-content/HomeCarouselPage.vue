<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from 'vue'
import { ElMessage } from 'element-plus'
import { errorMessage } from '@/core/http/api-error'
import MediaField from '@/core/ops/MediaField.vue'
import MediaPreview from '@/core/ops/MediaPreview.vue'
import PageSection from '@/core/ui/PageSection.vue'
import PagedTable from '@/core/ui/PagedTable.vue'
import { usePagedList } from '@/core/ui/use-paged-list'
import CityPicker from '@/features/content/components/CityPicker.vue'
import PageContentHistory from './PageContentHistory.vue'
import { pageContent, stateLabels, toneLabels, targetLabels, type Slide, type Change } from './page-content'

const list = usePagedList((f: {cityCode: string}, page, size) => pageContent.slides(f.cityCode,page,size), {cityCode:''})
const form = ref<Slide>()
const busy = ref(false)
const problem = ref('')
const history = shallowRef<() => Promise<Change[]>>()
const fields = [
  ['tag','标签',60], ['titleFirst','标题第一行',60], ['titleSecond','标题第二行',60],
  ['descriptionFirst','说明第一行',120], ['descriptionSecond','说明第二行',120],
  ['actionLabel','按钮文字',60], ['imageAlt','图片说明',120],
] as const
const ready = computed(() => form.value && form.value.cityCode && form.value.imageUrl
  && fields.every(([key]) => form.value![key].trim()))
const backgrounds: Record<string,string> = {SKY:'#dceefa',SUN:'#ffe9cb',VIOLET:'#ede3fc',MINT:'#d9f2eb'}
function edit(row?: Slide) {
  problem.value = ''
  history.value = row ? () => pageContent.slideChanges(row) : undefined
  form.value = row ? {...row} : {id:'',version:0,cityCode:list.filters.cityCode,sortOrder:0,tag:'',titleFirst:'',
    titleSecond:'',descriptionFirst:'',descriptionSecond:'',actionLabel:'',imageUrl:'',imageAlt:'',
    tone:'SKY',target:'ACTIVITY',status:'DRAFT'}
}
async function save() {
  if (!form.value || busy.value || !ready.value) return
  busy.value = true; problem.value = ''
  try {
    await pageContent.saveSlide(form.value)
    form.value = undefined
    ElMessage.success('轮播已保存')
    await list.load()
  } catch (e) { problem.value = errorMessage(e) }
  finally { busy.value = false }
}
onMounted(() => list.load())
</script>
<template>
  <PageSection title="首页轮播" description="按城市管理推荐频道的轮播，按顺序从小到大显示。">
    <template #actions><el-button type="primary" @click="edit()">新增轮播</el-button></template>
    <div class="filter"><CityPicker v-model="list.filters.cityCode" placeholder="全部城市" @update:model-value="list.search()" /></div>
    <PagedTable :items="list.items.value.map(s => ({...s,rowKey:s.cityCode+'/'+s.id}))" :total="list.total.value" :page="list.page.value" :size="list.size.value"
      :loading="list.loading.value" :error="list.error.value" row-key="rowKey"
      @retry="list.load" @change-page="list.changePage" @change-size="list.changeSize">
      <el-table-column prop="cityCode" label="城市编码" width="100" />
      <el-table-column label="标题"><template #default="{row}">{{ row.titleFirst }} · {{ row.titleSecond }}</template></el-table-column>
      <el-table-column label="跳转"><template #default="{row}">{{ targetLabels[row.target] }}</template></el-table-column>
      <el-table-column prop="sortOrder" label="顺序" width="80" />
      <el-table-column label="状态" width="100"><template #default="{row}">{{ stateLabels[row.status] }}</template></el-table-column>
      <el-table-column label="操作" width="90"><template #default="{row}"><el-button link type="primary" @click="edit(row as Slide)">编辑</el-button></template></el-table-column>
    </PagedTable>
    <el-dialog :model-value="!!form" :title="form?.id ? '编辑轮播' : '新增轮播'" width="700px" destroy-on-close
      :close-on-click-modal="false" @close="form=undefined">
      <el-form v-if="form" label-width="102px">
        <el-form-item label="城市" required><CityPicker v-model="form.cityCode" :disabled="!!form.id" /></el-form-item>
        <el-form-item v-for="[key,label,max] in fields" :key="key" :label="label" required>
          <el-input v-model="form[key]" :maxlength="max" /></el-form-item>
        <el-form-item label="轮播图片" required><MediaField v-model="form.imageUrl" upload-only /></el-form-item>
        <el-form-item label="配色"><el-select v-model="form.tone">
          <el-option v-for="(name,key) in toneLabels" :key="key" :value="key" :label="name" /></el-select></el-form-item>
        <el-form-item label="点击后前往"><el-select v-model="form.target">
          <el-option v-for="(name,key) in targetLabels" :key="key" :value="key" :label="name" /></el-select></el-form-item>
        <el-form-item label="顺序"><el-input-number v-model="form.sortOrder" :min="0" :precision="0" /></el-form-item>
        <el-form-item label="状态"><el-select v-model="form.status" :disabled="!form.id">
          <el-option v-for="key in ['DRAFT','PUBLISHED','OFFLINE']" :key="key" :value="key" :label="stateLabels[key]" /></el-select></el-form-item>
        <el-form-item label="预览">
          <div class="preview" :style="{background:backgrounds[form.tone]}">
            <div><small>{{ form.tag }}</small><strong>{{ form.titleFirst }}<br>{{ form.titleSecond }}</strong>
              <div>{{ form.descriptionFirst }}<br>{{ form.descriptionSecond }}</div><b>{{ form.actionLabel }}</b></div>
            <MediaPreview :url="form.imageUrl" :width="125" :height="100" />
          </div>
        </el-form-item>
      </el-form>
      <PageContentHistory v-if="history" :load="history" />
      <el-alert v-if="problem" type="error" :title="problem" :closable="false" />
      <template #footer>
        <el-button :disabled="busy" @click="form=undefined">取消</el-button>
        <el-button type="primary" :disabled="!ready" :loading="busy" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </PageSection>
</template>
<style scoped>
.filter { margin-bottom: 16px }
.preview { display:flex; justify-content:space-between; align-items:center; gap:20px; width:100%; padding:16px; border-radius:8px; }
.preview strong,.preview b {display:block; margin:4px 0}
</style>
