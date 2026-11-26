<script setup lang="ts">
import type { FormInstance } from 'element-plus'
import { computed, reactive, ref, watch } from 'vue'

import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'
import { displayMediaUrl } from '@/core/ui/media-url'

import {
  BADGE_OPTIONS,
  CATALOG_LIMITS as L,
  CATEGORY_OPTIONS,
  saleModeLabel,
  type CatalogApi,
  type OpsProductDetail,
  type ProductDraft,
} from './catalog-api'
import { imageUrlProblem, productRules, sellingPointProblem } from './catalog-rules'
import MediaUploadButton from './MediaUploadButton.vue'

const props = defineProps<{
  api: CatalogApi
  detail: OpsProductDetail
}>()

const emit = defineEmits<{
  changed: [detail: OpsProductDetail]
  dirty: [value: boolean]
}>()

const formRef = ref<FormInstance>()
const form = reactive<ProductDraft>({
  category: 'ROBOT',
  name: '',
  summary: '',
  coverUrl: '',
  experienceBadge: null,
  sortOrder: 0,
  sellingPoints: [],
  specs: [],
  packItems: [],
  gallery: [],
  introBlocks: [],
})
const newPoint = ref('')
const galleryUrl = ref('')
const saving = ref(false)

const baseline = ref('')
const dirty = computed(() => JSON.stringify(form) !== baseline.value || !!newPoint.value.trim() || !!galleryUrl.value.trim())

function fill(detail: OpsProductDetail): void {
  const product = detail.product
  form.category = product.category
  form.name = product.name
  form.summary = product.summary ?? ''
  form.coverUrl = product.coverUrl ?? ''
  form.experienceBadge = product.experienceBadge
  form.sortOrder = product.sortOrder
  form.sellingPoints = [...detail.sellingPoints]
  form.specs = detail.specs.map((spec) => ({ ...spec }))
  form.packItems = detail.packItems.map((item) => ({ ...item }))
  form.gallery = [...detail.gallery]
  form.introBlocks = detail.introBlocks.map((block) => ({ ...block }))
  newPoint.value = ''
  galleryUrl.value = ''
  baseline.value = JSON.stringify(form)
  formRef.value?.clearValidate()
}

watch(() => props.detail, fill, { immediate: true })
watch(dirty, value => emit('dirty', value), { flush: 'sync' })

async function discard(): Promise<void> {
  if (await confirmAction({ title: '放弃商品资料的修改', message: '恢复为当前已保存的商品资料。', confirmText: '放弃修改', danger: true })) {
    fill(props.detail)
  }
}

function addPoint(): void {
  const problem = sellingPointProblem(form.sellingPoints, newPoint.value)
  if (problem) {
    showError(new Error(problem))
    return
  }
  form.sellingPoints.push(newPoint.value.trim())
  newPoint.value = ''
}

function move<T>(list: T[], index: number, delta: number): void {
  const target = index + delta
  if (target < 0 || target >= list.length) return
  const [item] = list.splice(index, 1)
  if (item !== undefined) list.splice(target, 0, item)
}

function addGalleryUrl(url: string): void {
  const problem = form.gallery.length >= L.gallery ? `图集最多 ${L.gallery} 张` : imageUrlProblem(url)
  if (problem || url.trim() === '') {
    if (problem) showError(new Error(problem))
    return
  }
  form.gallery.push(url.trim())
  galleryUrl.value = ''
}

function addIntro(type: 'TEXT' | 'IMAGE'): void {
  if (form.introBlocks.length >= L.introBlocks) {
    showError(new Error(`图文介绍最多 ${L.introBlocks} 段`))
    return
  }
  form.introBlocks.push({ type, text: '', imageUrl: type === 'IMAGE' ? '' : null })
}

/** 表格里的行在提交前自己检查一遍（el-form 只管单个字段）。 */
function rowsProblem(): string | null {
  for (const [index, spec] of form.specs.entries()) {
    const label = spec.label.trim()
    const value = spec.value.trim()
    if (label === '' && value === '') continue
    if (label === '' || value === '') return `参数第 ${index + 1} 行要同时填参数名和参数值`
    if (Array.from(label).length > L.specLabel) return `参数名最多 ${L.specLabel} 个字`
    if (Array.from(value).length > L.specValue) return `参数值最多 ${L.specValue} 个字`
  }
  for (const [index, item] of form.packItems.entries()) {
    if (item.name.trim() === '') continue
    if (Array.from(item.name.trim()).length > L.packName) return `包装清单第 ${index + 1} 行名称最多 ${L.packName} 个字`
    if (!Number.isInteger(item.quantity) || item.quantity < 1 || item.quantity > L.packQuantity) {
      return `包装清单第 ${index + 1} 行数量是 1–${L.packQuantity}`
    }
  }
  for (const [index, block] of form.introBlocks.entries()) {
    if (block.type === 'TEXT' && Array.from((block.text ?? '').trim()).length > L.introText) {
      return `图文第 ${index + 1} 段最多 ${L.introText} 个字`
    }
    if (block.type === 'IMAGE' && block.imageUrl) {
      const problem = imageUrlProblem(block.imageUrl, props.detail.introBlocks[index]?.imageUrl)
      if (problem) return `图文第 ${index + 1} 段：${problem}`
    }
  }
  const cover = imageUrlProblem(form.coverUrl, props.detail.product.coverUrl)
  if (cover) return `封面：${cover}`
  return null
}

async function save(): Promise<void> {
  if (!formRef.value || saving.value) return
  if (!(await formRef.value.validate().catch(() => false))) return
  if (newPoint.value.trim() || galleryUrl.value.trim()) {
    showError(new Error('还有未添加的卖点或图集地址，请先添加或清空输入。'))
    return
  }
  const problem = rowsProblem()
  if (problem) {
    showError(new Error(problem))
    return
  }
  const published = props.detail.product.status === 'PUBLISHED'
  const confirmed = await confirmAction({
    title: '确认保存商品资料',
    message: published
      ? '这个商品已上架，保存后 App 商品详情马上按新资料显示。'
      : '保存商品资料（卖点、参数、包装清单、图集和图文整份替换）？',
    confirmText: '保存',
  })
  if (!confirmed) return
  saving.value = true
  try {
    emit('changed', await props.api.update(props.detail.product.productId, form, props.detail.product.version))
    notifyRecorded('保存商品资料', '操作记录')
  } catch (failure) {
    showError(failure)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-form ref="formRef" :model="form" :rules="productRules" label-width="110px" class="product-form">
    <el-form-item label="交易方式">
      <span>{{ saleModeLabel(detail.product.saleMode) }}</span>
      <span class="field-hint">（建好后不能改）</span>
    </el-form-item>
    <el-form-item label="类目" prop="category">
      <el-select v-model="form.category" style="width: 200px">
        <el-option v-for="option in CATEGORY_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
    </el-form-item>
    <el-form-item label="商品名称" prop="name">
      <el-input v-model="form.name" :maxlength="L.name" show-word-limit />
    </el-form-item>
    <el-form-item label="一句话介绍" prop="summary">
      <el-input v-model="form.summary" :maxlength="L.summary" show-word-limit placeholder="上架前必填" />
    </el-form-item>
    <el-form-item label="体验标识">
      <el-select v-model="form.experienceBadge" clearable placeholder="没有" style="width: 200px"
                 @clear="form.experienceBadge = null">
        <el-option v-for="option in BADGE_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
    </el-form-item>
    <el-form-item label="排序">
      <el-input-number v-model="form.sortOrder" :min="0" :max="L.sortOrder" :step="10" />
      <span class="field-hint">小的在前</span>
    </el-form-item>

    <el-form-item label="卖点标签">
      <div class="points" data-testid="selling-points">
        <el-tag v-for="(point, index) in form.sellingPoints" :key="point" closable
                @close="form.sellingPoints.splice(index, 1)">
          {{ point }}
        </el-tag>
        <el-input v-if="form.sellingPoints.length < L.sellingPoints" v-model="newPoint" size="small"
                  :maxlength="L.sellingPoint" placeholder="新卖点，回车添加" style="width: 180px"
                  @keyup.enter="addPoint" />
        <el-button v-if="form.sellingPoints.length < L.sellingPoints" size="small" @click="addPoint">添加</el-button>
      </div>
      <div class="field-hint">App 商品名称下面的标签，最多 {{ L.sellingPoints }} 个，每个不超过 {{ L.sellingPoint }} 字。</div>
    </el-form-item>

    <el-form-item label="参数">
      <div class="rows">
        <div v-for="(spec, index) in form.specs" :key="index" class="row">
          <el-input v-model="spec.label" :maxlength="L.specLabel" placeholder="参数名，如：续航" style="width: 180px" />
          <el-input v-model="spec.value" :maxlength="L.specValue" placeholder="参数值" />
          <el-button link :disabled="index === 0" @click="move(form.specs, index, -1)">上移</el-button>
          <el-button link :disabled="index === form.specs.length - 1" @click="move(form.specs, index, 1)">下移</el-button>
          <el-button link type="danger" @click="form.specs.splice(index, 1)">删除</el-button>
        </div>
        <el-button size="small" :disabled="form.specs.length >= L.specs"
                   @click="form.specs.push({ label: '', value: '' })">加一行参数</el-button>
      </div>
    </el-form-item>

    <el-form-item label="包装清单">
      <div class="rows">
        <div v-for="(item, index) in form.packItems" :key="index" class="row">
          <el-input v-model="item.name" :maxlength="L.packName" placeholder="名称，如：充电线" />
          <el-input-number v-model="item.quantity" :min="1" :max="L.packQuantity" />
          <el-button link :disabled="index === 0" @click="move(form.packItems, index, -1)">上移</el-button>
          <el-button link :disabled="index === form.packItems.length - 1"
                     @click="move(form.packItems, index, 1)">下移</el-button>
          <el-button link type="danger" @click="form.packItems.splice(index, 1)">删除</el-button>
        </div>
        <el-button size="small" :disabled="form.packItems.length >= L.packItems"
                   @click="form.packItems.push({ name: '', quantity: 1 })">加一行</el-button>
      </div>
    </el-form-item>

    <el-form-item label="封面">
      <div class="media">
        <img v-if="form.coverUrl" class="thumb" :src="displayMediaUrl(form.coverUrl)" alt="封面" />
        <div class="media__actions">
          <el-input v-model="form.coverUrl" :maxlength="L.url" placeholder="上传，或填受控的 https 地址" clearable />
          <MediaUploadButton :api="api" label="上传封面" @uploaded="(url) => (form.coverUrl = url)" />
        </div>
      </div>
    </el-form-item>

    <el-form-item label="图集">
      <div class="rows">
        <div class="gallery">
          <div v-for="(url, index) in form.gallery" :key="url + index" class="gallery__item">
            <img class="thumb" :src="displayMediaUrl(url)" :alt="`图集第 ${index + 1} 张`" />
            <div>
              <el-button link :disabled="index === 0" @click="move(form.gallery, index, -1)">前移</el-button>
              <el-button link :disabled="index === form.gallery.length - 1"
                         @click="move(form.gallery, index, 1)">后移</el-button>
              <el-button link type="danger" @click="form.gallery.splice(index, 1)">删除</el-button>
            </div>
          </div>
        </div>
        <div v-if="form.gallery.length < L.gallery" class="row">
          <el-input v-model="galleryUrl" :maxlength="L.url" placeholder="或填受控的 https 地址" />
          <el-button @click="addGalleryUrl(galleryUrl)">添加地址</el-button>
          <MediaUploadButton :api="api" label="上传图片" @uploaded="addGalleryUrl" />
        </div>
        <div class="field-hint">最多 {{ L.gallery }} 张，按顺序显示在封面后面。</div>
      </div>
    </el-form-item>

    <el-form-item label="图文介绍">
      <div class="rows">
        <div v-for="(block, index) in form.introBlocks" :key="index" class="intro">
          <div class="intro__head">
            <strong>第 {{ index + 1 }} 段 · {{ block.type === 'TEXT' ? '文字' : '图片' }}</strong>
            <span>
              <el-button link :disabled="index === 0" @click="move(form.introBlocks, index, -1)">上移</el-button>
              <el-button link :disabled="index === form.introBlocks.length - 1"
                         @click="move(form.introBlocks, index, 1)">下移</el-button>
              <el-button link type="danger" @click="form.introBlocks.splice(index, 1)">删除</el-button>
            </span>
          </div>
          <el-input v-if="block.type === 'TEXT'" v-model="block.text" type="textarea" :rows="3"
                    :maxlength="L.introText" show-word-limit />
          <template v-else>
            <div class="media">
              <img v-if="block.imageUrl" class="thumb" :src="displayMediaUrl(block.imageUrl)" alt="" />
              <div class="media__actions">
                <el-input v-model="block.imageUrl" :maxlength="L.url" placeholder="上传，或填受控的 https 地址" />
                <MediaUploadButton :api="api" @uploaded="(url) => (block.imageUrl = url)" />
              </div>
            </div>
            <el-input v-model="block.text" :maxlength="L.introText" placeholder="图片说明（可空）" />
          </template>
        </div>
        <div>
          <el-button size="small" @click="addIntro('TEXT')">加一段文字</el-button>
          <el-button size="small" @click="addIntro('IMAGE')">加一张图</el-button>
        </div>
      </div>
    </el-form-item>

    <el-form-item>
      <el-button type="primary" :loading="saving" @click="save">保存资料</el-button>
      <el-button v-if="dirty" :disabled="saving" @click="discard">放弃修改</el-button>
    </el-form-item>
  </el-form>
</template>

<style scoped>
.product-form {
  max-width: 900px;
}

.field-hint {
  margin-left: 8px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--admin-text-secondary);
}

.rows {
  display: flex;
  width: 100%;
  flex-direction: column;
  gap: 8px;
}

.row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.points {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.media {
  display: flex;
  width: 100%;
  align-items: flex-start;
  gap: 12px;
}

.media__actions {
  display: flex;
  flex: 1;
  align-items: center;
  gap: 8px;
}

.thumb {
  width: 80px;
  height: 80px;
  flex: none;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  object-fit: cover;
  background: #eef2f8;
}

.gallery {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.gallery__item {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.intro {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
}

.intro__head {
  display: flex;
  justify-content: space-between;
}

@media (max-width: 640px) {
  .product-form :deep(.el-form-item) {
    display: block;
  }

  .product-form :deep(.el-form-item__label) {
    justify-content: flex-start;
    width: auto !important;
    height: auto;
    line-height: 24px;
  }

  .product-form :deep(.el-form-item__content) {
    min-width: 0;
    margin-left: 0 !important;
  }

  .product-form :deep(.el-input:has(.el-input__count)) {
    margin-bottom: 16px;
  }

  .product-form :deep(.el-input .el-input__count) {
    position: absolute;
    top: 100%;
    right: 0;
    line-height: 16px;
  }

  .row,
  .media,
  .media__actions {
    flex-wrap: wrap;
  }
}
</style>
