<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { http } from '@/core/http'
import PageSection from '@/core/ui/PageSection.vue'
import PagedTable from '@/core/ui/PagedTable.vue'
import { usePagedList } from '@/core/ui/use-paged-list'
import { onlineExperienceApi, types, type ItemType, type OnlineItem } from './online-experience-api'
import { VENUE_STATUS_OPTIONS, venueStatus } from './venue-api'
import OnlineExperienceEditor from './OnlineExperienceEditor.vue'

const api = onlineExperienceApi(http)
const list = usePagedList(api.list, { keyword: '', type: '', status: '' })
const edit = ref<{ item: OnlineItem|null } | null>(null)
onMounted(list.load)
</script>

<template>
  <PageSection title="线上体验项目" description="维护 App 体验频道中的仿真、录播、软件试用和课程试听。" traced>
    <template #actions><el-button type="primary" @click="edit={item:null}">新建项目</el-button></template>
    <el-form inline @submit.prevent="list.search()">
      <el-form-item label="标题"><el-input v-model="list.filters.keyword" clearable maxlength="60" @clear="list.search()" /></el-form-item>
      <el-form-item label="类型"><el-select v-model="list.filters.type" clearable placeholder="全部" style="width:140px" @change="list.search()">
        <el-option v-for="(name,key) in types" :key="key" :label="name" :value="key" />
      </el-select></el-form-item>
      <el-form-item label="状态"><el-select v-model="list.filters.status" clearable placeholder="全部" style="width:120px" @change="list.search()">
        <el-option v-for="s in VENUE_STATUS_OPTIONS" :key="s.value" :label="s.label" :value="s.value" />
      </el-select></el-form-item>
      <el-button type="primary" native-type="submit">查询</el-button><el-button @click="list.reset()">重置</el-button>
    </el-form>
    <PagedTable :items="list.items.value" :total="list.total.value" :page="list.page.value" :size="list.size.value" :loading="list.loading.value"
      :error="list.error.value" row-key="id" empty-text="没有符合条件的体验项目" @change-page="list.changePage" @change-size="list.changeSize" @retry="list.load()">
      <el-table-column label="项目名称" min-width="210"><template #default="{row}"><el-button link type="primary" @click="edit={item:row as OnlineItem}">{{ row.title }}</el-button></template></el-table-column>
      <el-table-column label="类型" width="110"><template #default="{row}">{{ types[row.type as ItemType] }}</template></el-table-column>
      <el-table-column label="状态" width="95"><template #default="{row}"><el-tag :type="venueStatus(row.status).tag">{{ venueStatus(row.status).label }}</el-tag></template></el-table-column>
      <el-table-column prop="productName" label="关联商品" min-width="140" />
      <el-table-column prop="courseName" label="试听课程" min-width="140" />
      <el-table-column prop="sortOrder" label="排序" width="76" />
    </PagedTable>
    <OnlineExperienceEditor v-if="edit" :item="edit.item" @close="edit=null" @saved="list.load()" />
  </PageSection>
</template>
