<script setup lang="ts">
import { relatedContentArticleUrl, type RelatedContentRow } from '../../application/related-content'

// 相关内容 on a product page (R6-3). Rows follow the boards PdFull and PdRental: an 88 × 64 picture,
// the kind in small blue type, the title in 14/500.
const props = defineProps<{
  rows: RelatedContentRow[]
  cityCode: string
}>()

function openArticle(row: RelatedContentRow): void {
  uni.navigateTo({ url: relatedContentArticleUrl(row, props.cityCode) })
}
</script>

<template>
  <view class="related" data-testid="content-related">
    <text class="related__title">相关内容</text>
    <button
      v-for="row in rows"
      :key="row.articleId"
      class="related__row"
      :data-testid="`content-related-${row.articleId}`"
      @click="openArticle(row)"
    >
      <view
        class="related__thumb"
        :style="{ background: row.image ? (row.image.cutout ? '#f0edff' : '#eef0f3') : row.tone.background }"
      >
        <image
          v-if="row.image"
          class="related__image"
          :src="row.image.url"
          :mode="row.image.cutout ? 'aspectFit' : 'aspectFill'"
          lazy-load
          :aria-label="row.image.alt"
        />
      </view>
      <view class="related__copy">
        <text class="related__kind">{{ row.kindLine }}</text>
        <text class="related__name">{{ row.title }}</text>
      </view>
    </button>
  </view>
</template>

<style scoped>
.related {
  display: flex;
  margin: 0 16px;
  flex-direction: column;
  gap: 10px;
}

.related__title {
  color: #202c42;
  font-size: 16px;
  font-weight: 700;
}

.related__row {
  display: flex;
  width: 100%;
  margin: 0;
  padding: 10px;
  gap: 10px;
  border: 0;
  border-radius: 12px;
  color: #202c42;
  background: #ffffff;
  font-family: inherit;
  line-height: 1.2;
  text-align: left;
  box-shadow: var(--app-card-shadow);
  box-sizing: border-box;
}

.related__row::after {
  border: 0;
}

.related__thumb {
  width: 88px;
  height: 64px;
  flex: none;
  overflow: hidden;
  border-radius: 8px;
}

.related__image {
  display: block;
  width: 100%;
  height: 100%;
}

.related__copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.related__kind {
  color: #1d63c4;
  font-size: 11px;
  line-height: 15px;
}

.related__name {
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  word-break: break-all;
}
</style>
