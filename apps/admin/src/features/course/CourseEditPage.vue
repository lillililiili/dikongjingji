<script setup lang="ts">
/**
 * 课程详情：基本资料（线上课、线下班的字段不同）、目录（只有线上课）、上架 / 下架、改动记录。
 * 资料和目录各自整份保存，都带页面读到的 version；别人刚改过（OPS_VERSION_CONFLICT）时提示刷新。
 * 上架前按详情的 listMissing 列出还差什么并禁用按钮；后端回 OPS_PUBLISH_INCOMPLETE 时把 details.missing 翻成中文。
 */
import type { FormInstance } from 'element-plus'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { http } from '@/core/http'
import { errorMessage, isApiError } from '@/core/http/api-error'
import ChangeLogTable from '@/core/ops/ChangeLogTable.vue'
import MediaField from '@/core/ops/MediaField.vue'
import MediaListField from '@/core/ops/MediaListField.vue'
import type { OpsChange } from '@/core/ops/ops-common'
import RowListEditor from '@/core/ops/RowListEditor.vue'
import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'
import { formatDateTime } from '@/core/ui/format'
import PageSection from '@/core/ui/PageSection.vue'

import ActivitySelect from './components/ActivitySelect.vue'
import OrganizationSelect from './components/OrganizationSelect.vue'
import ParagraphListEditor from './components/ParagraphListEditor.vue'
import ProductMultiSelect from './components/ProductMultiSelect.vue'
import CourseOutlineEditor from './CourseOutlineEditor.vue'
import {
  CHANGE_ACTION_LABELS,
  CHANGE_SUBJECT_LABELS,
  courseFormOf,
  createCourseApi,
  describeListMissing,
  labelOf,
  LIMITS,
  listMissingText,
  MODE_OPTIONS,
  outlineFormOf,
  STATUS_OPTIONS,
  statusTag,
  TOPIC_OPTIONS,
  type ChapterForm,
  type CourseForm,
  type OpsCourse,
} from './course-api'
import { courseProblems, courseRules, outlineProblems } from './course-rules'

const route = useRoute()
const router = useRouter()
const api = createCourseApi(http)

const courseId = computed(() => (typeof route.params.courseId === 'string' ? route.params.courseId : ''))
const course = ref<OpsCourse | null>(null)
const changes = ref<OpsChange[]>([])
const changesLoading = ref(false)
const loading = ref(false)
const loadError = ref('')
const busy = ref(false)
const tab = ref<'info' | 'outline' | 'changes'>('info')
const formRef = ref<FormInstance>()
const form = reactive<CourseForm>(courseFormOf(placeholderCourse()))
const outline = ref<ChapterForm[]>([])

const online = computed(() => course.value?.mode === 'ONLINE')
const rules = computed(() => courseRules(course.value?.mode ?? 'ONLINE'))
const missingText = computed(() => listMissingText(course.value?.listMissing ?? []))
const canPublish = computed(() => (course.value?.listMissing.length ?? 0) === 0)

const INCLUDE_FIELDS = [
  { key: 'name', label: '名称', max: LIMITS.includeName, placeholder: '名称，如“课程视频”', grow: 3 },
  { key: 'quantity', label: '数量', placeholder: '数量', grow: 1 },
] as const
const SPEC_FIELDS = [
  { key: 'label', label: '名称', max: LIMITS.specLabel, placeholder: '名称，如“上课方式”', grow: 1 },
  { key: 'value', label: '内容', max: LIMITS.specValue, placeholder: '内容', grow: 3 },
] as const

/** 还没读到课程时表单的占位（不会提交）。 */
function placeholderCourse(): OpsCourse {
  return {
    courseId: '', mode: 'ONLINE', topic: 'DRONE', status: 'DRAFT', title: '', subtitle: null,
    organization: { organizationId: '', name: '' }, coverUrl: null, galleryUrls: [], priceAmountMinor: null,
    validityDays: null, ageNote: null, instructorName: null, instructorTitle: null, includes: [], extraSpecs: [],
    intro: [], experienceItemId: null, bundledProductIds: [], activityId: null, scheduleLabel: null, sortOrder: 0,
    chapters: [], lessonCount: 0, enrollmentCount: 0, listMissing: [], firstListedAt: null, createdAt: '', updatedAt: '',
    version: 0,
  }
}

/** 目录去掉页面本地 key 后比较，看有没有没保存的改动。 */
function outlineShape(chapters: readonly ChapterForm[]): string {
  return JSON.stringify(chapters.map((chapter) => ({
    chapterId: chapter.chapterId,
    title: chapter.title,
    lessons: chapter.lessons.map(({ key: _key, ...lesson }) => lesson),
  })))
}

const infoDirty = computed(() => course.value !== null
  && JSON.stringify(form) !== JSON.stringify(courseFormOf(course.value)))
const outlineDirty = computed(() => course.value !== null && course.value.mode === 'ONLINE'
  && outlineShape(outline.value) !== outlineShape(outlineFormOf(course.value.chapters)))

/** 显示新读到（或刚保存回来）的课程；只重置指定的表单，另一份没保存的改动留着。 */
function show(next: OpsCourse, reset: { info: boolean, outline: boolean }): void {
  course.value = next
  if (reset.info) {
    Object.assign(form, courseFormOf(next))
    formRef.value?.clearValidate()
  }
  if (reset.outline) outline.value = outlineFormOf(next.chapters)
}

async function loadChanges(): Promise<void> {
  if (!courseId.value) return
  changesLoading.value = true
  try {
    changes.value = await api.changes(courseId.value)
  } catch {
    changes.value = []
  } finally {
    changesLoading.value = false
  }
}

async function load(): Promise<void> {
  if (!courseId.value) return
  loading.value = true
  loadError.value = ''
  try {
    show(await api.detail(courseId.value), { info: true, outline: true })
    if (course.value?.mode !== 'ONLINE' && tab.value === 'outline') tab.value = 'info'
    await loadChanges()
  } catch (failure) {
    loadError.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

/** 版本冲突：别人刚改过。问一次要不要刷新（刷新会丢掉这一页没保存的修改）。 */
async function offerReload(): Promise<void> {
  const confirmed = await confirmAction({
    title: '课程刚被别人修改过',
    message: '这一页读到的是旧版本，不能直接保存。刷新后看到最新内容，这一页没保存的修改会丢掉（可以先自己记一下）。',
    confirmText: '刷新',
  })
  if (confirmed) await load()
}

/** 写操作：成功后提示已留痕、刷新改动记录；失败时按错误码说清楚。 */
async function run(action: () => Promise<OpsCourse>, done: string, reset: { info: boolean, outline: boolean },
  missingPrefix: string): Promise<boolean> {
  busy.value = true
  try {
    show(await action(), reset)
    notifyRecorded(done, '改动记录')
    await loadChanges()
    return true
  } catch (failure) {
    if (isApiError(failure, 'OPS_VERSION_CONFLICT')) {
      void offerReload()
      return false
    }
    const missing = describeListMissing(failure, missingPrefix)
    showError(missing ? new Error(missing) : failure)
    return false
  } finally {
    busy.value = false
  }
}

async function saveInfo(): Promise<void> {
  const current = course.value
  if (!current || !formRef.value || busy.value) return
  if (!(await formRef.value.validate().catch(() => false))) return
  const problems = courseProblems(current.mode, form)
  if (problems.length > 0) {
    showError(new Error(problems[0]))
    return
  }
  const confirmed = await confirmAction({
    title: '确认保存基本资料',
    message: current.status === 'LISTED'
      ? '课程已上架，保存后 App 上立刻显示新的内容；改完也不能缺上架必填项。'
      : '保存后仍是草稿或已下架，App 上看不到。',
    confirmText: '保存',
  })
  if (!confirmed) return
  await run(() => api.update(current, form), '保存基本资料', { info: true, outline: false },
    '已上架的课程改完不能缺必填项，还差')
}

async function saveOutline(): Promise<void> {
  const current = course.value
  if (!current || busy.value) return
  const problems = outlineProblems(outline.value)
  if (problems.length > 0) {
    showError(new Error(problems.length > 1 ? `${problems[0]}（还有 ${problems.length - 1} 处）` : problems[0]))
    return
  }
  const removed = removedLessonCount(current, outline.value)
  const confirmed = await confirmAction({
    title: '确认保存目录',
    message: `整份保存 ${outline.value.length} 章、${outline.value.reduce((sum, chapter) => sum + chapter.lessons.length, 0)} 节课时`
      + `${removed > 0 ? `，删掉原来的 ${removed} 节` : ''}；课序按现在的顺序跨章连续编号。`
      + (current.status === 'LISTED' ? '课程已上架，保存后 App 上立刻显示新的目录。' : ''),
    confirmText: '保存目录',
    danger: removed > 0,
  })
  if (!confirmed) return
  await run(() => api.saveOutline(current.courseId, outline.value, current.version), '保存目录',
    { info: false, outline: true }, '已上架的课程改完不能缺必填项，还差')
}

function removedLessonCount(current: OpsCourse, chapters: readonly ChapterForm[]): number {
  const kept = new Set(chapters.flatMap((chapter) => chapter.lessons.map((lesson) => lesson.lessonId)))
  return current.chapters.flatMap((chapter) => chapter.lessons).filter((lesson) => !kept.has(lesson.lessonId)).length
}

async function resetOutline(): Promise<void> {
  if (!course.value) return
  const confirmed = await confirmAction({ title: '放弃目录的修改', message: '恢复成已保存的目录，这一页对目录的修改会丢掉。', confirmText: '放弃修改', danger: true })
  if (confirmed) outline.value = outlineFormOf(course.value.chapters)
}

async function publish(): Promise<void> {
  const current = course.value
  if (!current || busy.value || !canPublish.value) return
  const unsaved = infoDirty.value || outlineDirty.value
  const confirmed = await confirmAction({
    title: '确认上架',
    message: `上架后 App 的课程列表和详情能看到“${current.title}”${current.mode === 'ONLINE' ? '，可以购买或随商品开通' : ''}。`
      + (unsaved ? '页面上还有没保存的修改：上架按已保存的内容校验和展示。' : ''),
    confirmText: '上架',
  })
  if (confirmed) await run(() => api.publish(current.courseId, current.version), '上架', { info: false, outline: false }, '上架前还差')
}

async function unlist(): Promise<void> {
  const current = course.value
  if (!current || busy.value) return
  const confirmed = await confirmAction({
    title: '确认下架',
    message: '下架后 App 列表和详情都看不到这门课，不能再买；已经开通的照常能学、能按规则退款。',
    confirmText: '下架',
    danger: true,
  })
  if (confirmed) await run(() => api.unlist(current.courseId, current.version), '下架', { info: false, outline: false }, '还差')
}

function updateIncludes(rows: Record<string, string>[]): void {
  form.includes = rows.map((row) => ({ name: row.name ?? '', quantity: row.quantity ?? '' }))
}

function updateSpecs(rows: Record<string, string>[]): void {
  form.extraSpecs = rows.map((row) => ({ label: row.label ?? '', value: row.value ?? '' }))
}

watch(courseId, () => {
  void load()
})

onMounted(() => {
  void load()
})
</script>

<template>
  <PageSection
    :title="course?.title ?? '课程'"
    :description="course
      ? `${labelOf(MODE_OPTIONS, course.mode)} · ${labelOf(TOPIC_OPTIONS, course.topic)} · ${course.organization.name}`
      : '读取课程…'"
    traced
  >
    <template #actions>
      <el-button @click="router.push({ name: 'courses' })">返回列表</el-button>
      <template v-if="course">
        <el-tooltip v-if="course.status !== 'LISTED'" :disabled="canPublish" :content="`上架前还差：${missingText}`">
          <span>
            <el-button type="success" :loading="busy" :disabled="!canPublish" data-test="publish" @click="publish">上架</el-button>
          </span>
        </el-tooltip>
        <el-button v-else type="warning" :loading="busy" data-test="unlist" @click="unlist">下架</el-button>
      </template>
    </template>

    <el-alert v-if="loadError" type="error" :title="loadError" :closable="false" show-icon>
      <el-button size="small" @click="load">重新加载</el-button>
    </el-alert>

    <div v-loading="loading" class="course-edit">
      <div v-if="course" class="course-edit__status">
        <el-tag :type="statusTag(course.status)">{{ labelOf(STATUS_OPTIONS, course.status) }}</el-tag>
        <span>第一次上架：{{ formatDateTime(course.firstListedAt) }}</span>
        <span>最后修改：{{ formatDateTime(course.updatedAt) }}</span>
        <span v-if="course.mode === 'ONLINE'">{{ course.lessonCount }} 节课时</span>
        <span>开通过 {{ course.enrollmentCount }} 人（含已退款、已过期）</span>
        <el-alert v-if="course.status !== 'LISTED' && missingText" type="warning" :closable="false" show-icon
                  data-test="missing" :title="`上架前还差：${missingText}`" />
        <el-alert v-if="course.mode === 'OFFLINE'" type="info" :closable="false" show-icon
                  title="线下班：App 上标题、封面、价格、城市取自挂靠的活动，报名走活动；这里只管课程频道的卡片。" />
      </div>

      <el-tabs v-if="course" v-model="tab">
        <el-tab-pane name="info">
          <template #label>基本资料<span v-if="infoDirty" class="course-edit__dirty">（未保存）</span></template>
          <el-form ref="formRef" :model="form" :rules="rules" label-width="130px" class="course-edit__form">
            <el-form-item label="课程名称" prop="title">
              <el-input v-model="form.title" :maxlength="LIMITS.title" show-word-limit />
            </el-form-item>
            <el-form-item v-if="online" label="副标题" prop="subtitle">
              <el-input v-model="form.subtitle" :maxlength="LIMITS.subtitle" show-word-limit placeholder="可不填" />
            </el-form-item>
            <el-form-item label="子类目" prop="topic">
              <el-radio-group v-model="form.topic">
                <el-radio v-for="option in TOPIC_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="开课机构" prop="organizationId">
              <OrganizationSelect v-model="form.organizationId" :label="course.organization.name" />
            </el-form-item>
            <el-form-item label="封面" prop="coverUrl">
              <MediaField v-model="form.coverUrl" />
            </el-form-item>

            <template v-if="online">
              <el-form-item label="图集" prop="galleryUrls">
                <MediaListField v-model="form.galleryUrls" :max="LIMITS.gallery" />
              </el-form-item>
              <el-form-item label="价格（元）" prop="priceYuan">
                <el-input v-model="form.priceYuan" style="width: 160px" placeholder="比如 199" />
                <div class="course-edit__hint">
                  留空 = 不单独出售，只随下面的商品附带；单独出售时开课机构要登记在线收款账户（“收款账户”菜单）。
                </div>
              </el-form-item>
              <el-form-item label="有效期（天）" prop="validityDays">
                <el-input-number v-model="form.validityDays" :min="1" :max="LIMITS.validityDays" :value-on-clear="null"
                                 placeholder="长期有效" />
                <span class="course-edit__inline-hint">留空 = 长期有效</span>
              </el-form-item>
              <el-form-item label="适合年龄" prop="ageNote">
                <el-input v-model="form.ageNote" :maxlength="LIMITS.ageNote" show-word-limit placeholder="比如“8 岁以上”" />
              </el-form-item>
              <el-form-item label="讲师姓名" prop="instructorName">
                <el-input v-model="form.instructorName" :maxlength="LIMITS.instructorName" show-word-limit />
              </el-form-item>
              <el-form-item label="讲师头衔" prop="instructorTitle">
                <el-input v-model="form.instructorTitle" :maxlength="LIMITS.instructorTitle" show-word-limit />
              </el-form-item>
              <el-form-item label="课程包含" prop="includes">
                <RowListEditor
                  :model-value="form.includes"
                  :fields="INCLUDE_FIELDS"
                  :max="LIMITS.includes"
                  :new-row="() => ({ name: '', quantity: '1' })"
                  @update:model-value="updateIncludes"
                />
              </el-form-item>
              <el-form-item label="参数补充" prop="extraSpecs">
                <RowListEditor
                  :model-value="form.extraSpecs"
                  :fields="SPEC_FIELDS"
                  :max="LIMITS.specs"
                  :new-row="() => ({ label: '', value: '' })"
                  add-label="加一行"
                  @update:model-value="updateSpecs"
                />
              </el-form-item>
              <el-form-item label="图文介绍" prop="intro">
                <ParagraphListEditor v-model="form.intro" :max="LIMITS.intro" :max-chars="LIMITS.introParagraph" />
              </el-form-item>
              <el-form-item label="体验中心试听入口" prop="experienceItemId">
                <el-input v-model="form.experienceItemId" clearable style="width: 360px"
                          placeholder="体验项目 ID（UUID），可不填" />
                <div class="course-edit__hint">一门课一个；已经给了别的课程的不能再用。</div>
              </el-form-item>
              <el-form-item label="附带于商品" prop="bundledProductIds">
                <ProductMultiSelect v-model="form.bundledProductIds" :max="LIMITS.bundledProducts" />
              </el-form-item>
            </template>

            <template v-else>
              <el-form-item label="挂靠的活动" prop="activityId">
                <ActivitySelect v-model="form.activityId" />
              </el-form-item>
              <el-form-item label="卡片文字" prop="scheduleLabel">
                <el-input v-model="form.scheduleLabel" :maxlength="LIMITS.scheduleLabel" show-word-limit style="width: 240px"
                          placeholder="比如“8 次课”" />
              </el-form-item>
            </template>

            <el-form-item label="排序" prop="sortOrder">
              <el-input-number v-model="form.sortOrder" :min="0" :max="LIMITS.sortOrder" />
              <span class="course-edit__inline-hint">大的在前</span>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="busy" data-test="save-info" @click="saveInfo">保存基本资料</el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <el-tab-pane v-if="online" name="outline">
          <template #label>目录<span v-if="outlineDirty" class="course-edit__dirty">（未保存）</span></template>
          <p class="course-edit__hint">
            整份保存：最多 {{ LIMITS.chapters }} 章、每章 {{ LIMITS.lessonsPerChapter }} 节、全课 {{ LIMITS.lessonsTotal }} 节。
            时长按“分:秒”或秒数填；视频上传 MP4 或粘贴 https 地址。已经有人学过的课时不能删，可以改标题、时长和视频。
          </p>
          <CourseOutlineEditor :chapters="outline" :disabled="busy" />
          <div class="course-edit__outline-actions">
            <el-button type="primary" :loading="busy" data-test="save-outline" @click="saveOutline">保存目录</el-button>
            <el-button :disabled="busy || !outlineDirty" @click="resetOutline">放弃修改</el-button>
          </div>
        </el-tab-pane>

        <el-tab-pane label="改动记录" name="changes">
          <ChangeLogTable :changes="changes" :loading="changesLoading" :subject-labels="CHANGE_SUBJECT_LABELS"
                          :action-labels="CHANGE_ACTION_LABELS" />
        </el-tab-pane>
      </el-tabs>
    </div>
  </PageSection>
</template>

<style scoped>
.course-edit {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.course-edit__status {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  font-size: 13px;
  color: var(--admin-text-secondary);
}

.course-edit__form {
  max-width: 860px;
}

.course-edit__hint {
  width: 100%;
  margin: 0;
  font-size: 12px;
  line-height: 18px;
  color: var(--admin-text-secondary);
}

.course-edit__inline-hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.course-edit__dirty {
  margin-left: 2px;
  font-size: 12px;
  color: var(--el-color-warning);
}

.course-edit__outline-actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}
</style>
