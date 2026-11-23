<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { http } from '@/core/http'
import { formatDateTime } from '@/core/ui/format'
import { formatYuan } from '@/core/ui/money'
import PagedTable from '@/core/ui/PagedTable.vue'
import PageSection from '@/core/ui/PageSection.vue'
import { usePagedList } from '@/core/ui/use-paged-list'

import CourseCreateDialog from './CourseCreateDialog.vue'
import {
  createCourseApi,
  labelOf,
  MODE_OPTIONS,
  STATUS_OPTIONS,
  statusTag,
  TOPIC_OPTIONS,
  type CourseFilters,
  type OpsCourse,
  type OpsCourseSummary,
} from './course-api'

const router = useRouter()
const api = createCourseApi(http)
const creating = ref(false)
const list = usePagedList<OpsCourseSummary, CourseFilters>((filters, page, size) => api.list(filters, page, size),
  { keyword: '', mode: '', topic: '', status: '' })

function open(courseId: string): void {
  void router.push({ name: 'course-detail', params: { courseId } })
}

function created(course: OpsCourse): void {
  creating.value = false
  open(course.courseId)
}

function price(row: OpsCourseSummary): string {
  if (row.mode === 'OFFLINE') return '取自活动'
  return row.priceAmountMinor === null ? '不单独出售' : formatYuan(row.priceAmountMinor)
}

onMounted(() => {
  void list.load()
})
</script>

<template>
  <PageSection
    title="课程"
    description="线上录播课（章节、课时视频，单独出售或随商品附带）和线下班（挂靠赛事研学活动）：新建草稿、补齐资料后上架。"
    traced
  >
    <template #actions>
      <el-button type="primary" @click="creating = true">新建课程</el-button>
    </template>

    <el-form inline @submit.prevent="list.search()">
      <el-form-item label="课程">
        <el-input v-model="list.filters.keyword" clearable maxlength="30" placeholder="课程名称"
                  @clear="list.search()" />
      </el-form-item>
      <el-form-item label="上课方式">
        <el-select v-model="list.filters.mode" clearable placeholder="全部" style="width: 110px" @change="list.search()">
          <el-option v-for="option in MODE_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="子类目">
        <el-select v-model="list.filters.topic" clearable placeholder="全部" style="width: 110px" @change="list.search()">
          <el-option v-for="option in TOPIC_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="list.filters.status" clearable placeholder="全部" style="width: 110px" @change="list.search()">
          <el-option v-for="option in STATUS_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" native-type="submit">查询</el-button>
        <el-button @click="list.reset()">重置</el-button>
      </el-form-item>
    </el-form>

    <PagedTable
      :items="list.items.value"
      :total="list.total.value"
      :page="list.page.value"
      :size="list.size.value"
      :loading="list.loading.value"
      :error="list.error.value"
      row-key="courseId"
      empty-text="没有符合条件的课程"
      @change-page="list.changePage"
      @change-size="list.changeSize"
      @retry="list.load()"
    >
      <el-table-column label="课程" min-width="220">
        <template #default="{ row }">
          <el-button link type="primary" @click="open((row as OpsCourseSummary).courseId)">
            {{ (row as OpsCourseSummary).title }}
          </el-button>
          <div class="sub">{{ (row as OpsCourseSummary).organizationName }}</div>
        </template>
      </el-table-column>
      <el-table-column label="上课方式" width="90">
        <template #default="{ row }">{{ labelOf(MODE_OPTIONS, (row as OpsCourseSummary).mode) }}</template>
      </el-table-column>
      <el-table-column label="子类目" width="80">
        <template #default="{ row }">{{ labelOf(TOPIC_OPTIONS, (row as OpsCourseSummary).topic) }}</template>
      </el-table-column>
      <el-table-column label="价格" width="110">
        <template #default="{ row }">{{ price(row as OpsCourseSummary) }}</template>
      </el-table-column>
      <el-table-column label="课时" width="70">
        <template #default="{ row }">
          {{ (row as OpsCourseSummary).mode === 'ONLINE' ? `${(row as OpsCourseSummary).lessonCount} 节` : '—' }}
        </template>
      </el-table-column>
      <el-table-column prop="sortOrder" label="排序" width="70" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusTag((row as OpsCourseSummary).status)">
            {{ labelOf(STATUS_OPTIONS, (row as OpsCourseSummary).status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最后修改" width="150">
        <template #default="{ row }">{{ formatDateTime((row as OpsCourseSummary).updatedAt) }}</template>
      </el-table-column>
    </PagedTable>
  </PageSection>

  <CourseCreateDialog :api="api" :open="creating" @close="creating = false" @created="created" />
</template>

<style scoped>
.sub {
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
