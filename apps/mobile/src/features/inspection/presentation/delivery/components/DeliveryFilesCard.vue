<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import type { DeliveryFileRow } from '../delivery-view'

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
          <UniIcons type="checkmarkempty" size="12" color="#19b866" />
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
        <image class="delivery-files__icon-image" :src="row.iconUrl" mode="aspectFit" />
      </view>
      <view class="delivery-files__copy">
        <text class="delivery-files__name">{{ row.name }}</text>
        <view v-if="row.linkText" class="delivery-files__link">
          <UniIcons type="checkmarkempty" size="12" color="#19b866" />
          <text>{{ row.linkText }}</text>
        </view>
        <text v-else class="delivery-files__detail">{{ row.detailText }}</text>
      </view>
      <button
        v-if="row.canPreview"
        class="delivery-files__action delivery-files__action--quiet"
        :disabled="Boolean(activeAction)"
        @click="emit('preview', row.fileId)"
      >
        查看
      </button>
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
      <UniIcons type="locked" size="14" color="#8a8f97" />
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
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
  outline: 1px solid rgba(232, 237, 244, 0.5);
  outline-offset: -1px;
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
  background: #eaf2fd;
}

.delivery-files__icon-image {
  width: 20px;
  height: 20px;
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
  color: #d94f00;
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

.delivery-files__note :deep(.uni-icons) {
  flex-shrink: 0;
  margin-top: 1.5px;
}

.delivery-files__note-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  color: #666b73;
  font-size: 12px;
  line-height: 17px;
}
</style>
