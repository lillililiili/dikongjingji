<script setup lang="ts">
import type { UploadRawFile } from 'element-plus'
import { ElMessage } from 'element-plus'
import { computed, ref } from 'vue'

import { confirmAction, showError } from '@/core/ui/feedback'
import { formatBytes, formatDateTime } from '@/core/ui/format'

import type { ServiceOrder, ServiceOrderApi, ServiceOrderFile } from './service-order-api'
import { canEditFiles, DELIVERY_STATUS, FILE_ACCEPT, fileProblem, LIMITS } from './service-order-rules'

/**
 * 交付：还没交付的文件（服务中、整改中可以上传、删除；multipart，不带 If-Match）和历次交付（说明、文件、客户的验收或
 * 退回原因）。文件都带登录从工作台接口下载。上传、删除之后让详情页重新读订单（拿新的版本号）。
 */
const props = defineProps<{
  order: ServiceOrder
  api: Pick<ServiceOrderApi, 'uploadFile' | 'removeFile' | 'downloadFile'>
}>()

const emit = defineEmits<{
  reload: []
}>()

const uploading = ref(false)
const editable = computed(() => canEditFiles(props.order))
const deliveries = computed(() => [...props.order.deliveries].sort((a, b) => b.round - a.round))

async function upload(raw: UploadRawFile): Promise<boolean> {
  const problem = fileProblem(raw, props.order.stagedFiles.length)
  if (problem) {
    showError(new Error(problem))
    return false
  }
  uploading.value = true
  try {
    await props.api.uploadFile(props.order.orderId, raw)
    ElMessage.success(`已上传“${raw.name}”，提交交付时勾选它`)
    emit('reload')
  } catch (failure) {
    showError(failure)
  } finally {
    uploading.value = false
  }
  // 已经自己上传了，不让 el-upload 再传一次。
  return false
}

async function remove(file: ServiceOrderFile): Promise<void> {
  const confirmed = await confirmAction({
    title: '确认删除文件',
    message: `删掉还没交付的“${file.name}”？删掉后要重新上传。`,
    confirmText: '删除',
    danger: true,
  })
  if (!confirmed) return
  try {
    await props.api.removeFile(props.order.orderId, file.fileId)
    ElMessage.success('已删除')
    emit('reload')
  } catch (failure) {
    showError(failure)
  }
}

async function download(file: ServiceOrderFile): Promise<void> {
  try {
    const result = await props.api.downloadFile(props.order.orderId, file.fileId)
    const url = URL.createObjectURL(result.blob)
    const link = document.createElement('a')
    link.href = url
    link.download = result.fileName ?? file.name
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 10_000)
  } catch (failure) {
    showError(failure)
  }
}
</script>

<template>
  <div class="delivery">
    <div v-if="editable || order.stagedFiles.length > 0" class="staged">
      <div class="staged__head">
        <h4>还没交付的文件</h4>
        <span class="hint">
          PDF、JPEG、PNG、ZIP、Office 文档（docx、xlsx、pptx）、MP4，单个 ≤20 MB，最多 {{ LIMITS.stagedFiles }} 个。
        </span>
        <el-upload v-if="editable" :show-file-list="false" :accept="FILE_ACCEPT" :before-upload="upload"
                   :disabled="uploading">
          <el-button :loading="uploading">上传文件</el-button>
        </el-upload>
      </div>
      <el-table :data="order.stagedFiles" row-key="fileId" size="small" border empty-text="还没有上传文件">
        <el-table-column prop="name" label="文件" min-width="220" />
        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ formatBytes((row as ServiceOrderFile).sizeBytes) }}</template>
        </el-table-column>
        <el-table-column label="上传时间" width="150">
          <template #default="{ row }">{{ formatDateTime((row as ServiceOrderFile).uploadedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <el-button link type="primary" @click="download(row as ServiceOrderFile)">下载</el-button>
            <el-button v-if="editable" link type="danger" @click="remove(row as ServiceOrderFile)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-empty v-if="deliveries.length === 0" description="还没有交付过" :image-size="60" />
    <el-card v-for="delivery in deliveries" :key="delivery.round" shadow="never" class="round">
      <div class="round__head">
        <strong>第 {{ delivery.round }} 次交付</strong>
        <el-tag :type="DELIVERY_STATUS[delivery.status].tag" effect="plain">{{ DELIVERY_STATUS[delivery.status].label }}</el-tag>
        <span class="hint">{{ formatDateTime(delivery.deliveredAt) }}</span>
      </div>
      <p class="pre">{{ delivery.note }}</p>
      <div v-if="delivery.files.length > 0" class="round__files">
        <el-button v-for="file in delivery.files" :key="file.fileId" size="small" @click="download(file)">
          {{ file.name }}（{{ formatBytes(file.sizeBytes) }}）
        </el-button>
      </div>
      <el-alert
        v-if="delivery.status === 'RETURNED'"
        type="error"
        :closable="false"
        :title="`客户退回整改：${delivery.reviewNote ?? '（没写原因）'}`"
        :description="`${delivery.reviewedBy ?? '客户'} · ${formatDateTime(delivery.reviewedAt)}`"
      />
      <p v-else-if="delivery.status === 'ACCEPTED'" class="hint">
        {{ delivery.reviewedBy ?? '客户' }} 于 {{ formatDateTime(delivery.reviewedAt) }} 确认验收
      </p>
    </el-card>
  </div>
</template>

<style scoped>
.delivery {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.staged__head,
.round__head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}

.staged__head h4 {
  margin: 0;
  font-size: 14px;
}

.hint {
  flex: 1;
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.round__head .hint {
  flex: none;
}

.round__files {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}

.pre {
  margin: 0 0 8px;
  white-space: pre-wrap;
}
</style>
