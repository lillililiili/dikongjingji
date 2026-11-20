<script setup lang="ts">
import LineIcon from '@/shared/ui/LineIcon.vue'
import type { DeliveryFileRow } from '../delivery-view'

// Board Delivery: the same blue file icons as 交付成果 on the detail page.
const FILE_ICONS: Record<DeliveryFileRow['type'], string> = {
  INSPECTION_REPORT: 'M6 3h8l4 4v14H6zM14 3v4h4M9 12h6M9 16h6',
  DEFECT_LIST: 'M4 5h16v14H4zM4 10h16M4 15h16M10 5v14',
  POINT_KML: 'M12 21c4.6-4.7 7-8.1 7-11a7 7 0 0 0-14 0c0 2.9 2.4 6.3 7 11zM12 12.5a2.5 2.5 0 1 0 0-5a2.5 2.5 0 0 0 0 5z',
  IMAGE_ARCHIVE: 'M4 5h16v14H4zM4 16l5-5 4 4 3-3 4 4M15.5 9.5h.01',
}

defineProps<{
  rows: DeliveryFileRow[]
  allLinkText: string
  activeAction: string
  retentionText: string
}>()

const emit = defineEmits<{
  preview: [fileId: string]
  save: [fileId: string]
  saveAll: []
}>()
</script>

<template>
  <view class="delivery-files">
    <view class="delivery-files__head">
      <view class="delivery-files__head-copy">
        <text class="delivery-files__title">交付文件</text>
        <view v-if="allLinkText" class="delivery-files__link">
          <LineIcon name="check" :size="12" color="#159a70" :stroke-width="3" />
          <text>{{ allLinkText }}</text>
        </view>
        <text v-else class="delivery-files__count">共 {{ rows.length }} 个文件</text>
      </view>
      <button
        class="delivery-files__action"
        :disabled="Boolean(activeAction)"
        @click="emit('saveAll')"
      >
        {{ activeAction === 'all:SAVE' ? '下载中' : '全部下载' }}
      </button>
    </view>

    <view v-for="row in rows" :key="row.fileId" class="delivery-files__row">
      <view class="delivery-files__icon">
        <LineIcon :d="FILE_ICONS[row.type]" :size="20" color="#2f80ed" />
      </view>
      <view class="delivery-files__copy">
        <text class="delivery-files__name" :data-testid="`delivery-file-title-${row.fileId}`">{{ row.title }}</text>
        <view v-if="row.linkText" class="delivery-files__link">
          <LineIcon name="check" :size="12" color="#159a70" :stroke-width="3" />
          <text>{{ row.linkText }}</text>
        </view>
      </view>
      <button
        class="delivery-files__action"
        :aria-label="`下载${row.name}`"
        :disabled="Boolean(activeAction)"
        @click="emit('save', row.fileId)"
      >
        {{ activeAction === `${row.fileId}:SAVE` ? '下载中' : '下载' }}
      </button>
    </view>

    <view class="delivery-files__note">
      <LineIcon class="delivery-files__note-icon" name="lock" :size="14" color="#738096" :stroke-width="2" />
      <view class="delivery-files__note-copy">
        <text>文件经受控链接下载，不显示存储地址</text>
        <text v-if="retentionText">{{ retentionText }}</text>
      </view>
    </view>
  </view>
</template>

<style scoped>
.delivery-files {
  display: flex;
  padding: 14px 14px 12px;
  flex-direction: column;
  border-radius: 14px;
  background: #fff;
  /* The card shadow and hairline of the 2026-11-14 prototype's light theme. */
  box-shadow: var(--app-card-shadow);
}

.delivery-files__head {
  display: flex;
  min-height: 44px;
  margin-bottom: 4px;
  align-items: center;
  gap: 10px;
}

.delivery-files__head-copy,
.delivery-files__copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.delivery-files__title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.delivery-files__count,
.delivery-files__detail {
  color: #666b73;
  font-size: 12px;
  line-height: 17px;
}

.delivery-files__link {
  display: flex;
  align-items: center;
  gap: 4px;
  color: #11804a;
  font-size: 12px;
  line-height: 17px;
}

.delivery-files__row {
  display: flex;
  min-height: 61px;
  padding: 6px 0;
  align-items: center;
  gap: 10px;
  box-sizing: border-box;
  border-top: 1px solid #f0f1f3;
}

.delivery-files__icon {
  display: flex;
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: #e6f0ff;
}

.delivery-files__name {
  font-size: 14px;
  line-height: 20px;
}

.delivery-files__action {
  height: 36px;
  flex-shrink: 0;
  margin: 0;
  padding: 0 16px;
  box-sizing: border-box;
  border: 1px solid #ff6a00;
  border-radius: 999px;
  color: #ba470b;
  background: #fff;
  font-size: 13px;
  line-height: 34px;
  white-space: nowrap;
}

.delivery-files__action--quiet {
  padding: 0 12px;
  border-color: #c4c8ce;
  color: #191919;
}

.delivery-files__action::after {
  border: 0;
}

.delivery-files__action[disabled] {
  color: #a3a8b0;
  border-color: #ebedf0;
  background: #fff;
  opacity: 1;
}

.delivery-files__note {
  display: flex;
  padding-top: 10px;
  gap: 6px;
  border-top: 1px solid #f0f1f3;
}

.delivery-files__note-icon {
  flex-shrink: 0;
  margin-top: 1.5px;
}

.delivery-files__note-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}
</style>
