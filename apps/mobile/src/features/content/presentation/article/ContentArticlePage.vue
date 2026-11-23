<script setup lang="ts">
import { computed, ref } from 'vue'

import { FAVORITE_PREVIEW_ENABLED } from '@/features/favorite/application/favorite-preview'
import FavoriteButton from '@/features/favorite/presentation/FavoriteButton.vue'
import type { SupportTarget } from '@/features/support/navigation/support-routes'
import { useCurrentCityStore } from '@/shared/city/state/current-city'
import { ApiRequestError } from '@/shared/http/public-api-request'
import ShareSheet from '@/shared/share/ShareSheet.vue'
import type { ShareTarget } from '@/shared/share/share-link'
import LineIcon from '@/shared/ui/LineIcon.vue'

import { resolveContentRefs, type ContentRefView } from '../../application/content-refs'
import {
  CONTENT_KIND_LABELS,
  articleCoverTone,
  articleFigures,
  articleMetaLine,
} from '../../application/content-view'
import { contentApi } from '../../data/content-api-client'
import { contentLinkedApis } from '../../data/content-linked-apis'
import { resolveArticlePreviewCover } from '../../data/preview/content-preview-covers'
import type { ContentArticle } from '../../domain/content'
import { buildContentArticleUrl, contentAuthorHome } from '../../navigation/content-routes'

// One article (boards ContentArticle, ContentCase2–3, ContentKnow1–3 share this template): the
// author with 主页, the body blocks, 内容中提到 read from each module, 咨询 and the first mention's
// page at the bottom, with 收藏 first (board ContentArticle, plan 08 S5-6).
const props = defineProps<{
  articleId: string
  cityCode: string
}>()

const emit = defineEmits<{
  back: []
  // Back to the discover page when the article cannot be shown here.
  browse: []
  open: [url: string]
  consult: [target: SupportTarget]
}>()

type ArticleState =
  | { kind: 'loading' }
  | { kind: 'ready'; article: ContentArticle }
  | { kind: 'missing' }
  | { kind: 'error' }

const currentCityStore = useCurrentCityStore()
const state = ref<ArticleState>({ kind: 'loading' })
// null while the mentions are being read.
const refs = ref<ContentRefView[] | null>(null)
let requestVersion = 0

const article = computed(() => (state.value.kind === 'ready' ? state.value.article : null))
const tone = computed(() => (article.value ? articleCoverTone(article.value) : null))
const metaLine = computed(() => {
  if (!article.value) return ''
  const city = currentCityStore.currentCity
  return articleMetaLine(article.value, city && city.code === props.cityCode ? city : null)
})
const home = computed(() => (article.value ? contentAuthorHome(article.value.author, props.cityCode) : { kind: 'NONE' as const }))
const consultLabel = computed(() => {
  const author = article.value?.author
  if (!author?.organizationId) return ''
  return author.kind === 'PROVIDER' ? '咨询服务商' : author.kind === 'MERCHANT' ? '咨询商家' : ''
})
const primaryRef = computed(() => refs.value?.[0] ?? null)
// (1.3.0) The pictures of the IMAGE blocks, one entry per block; null where there is none to draw.
const figures = computed(() => (article.value ? articleFigures(article.value) : []))
const hasBar = computed(() => Boolean(FAVORITE_PREVIEW_ENABLED || consultLabel.value || primaryRef.value))

// The head picture: the server's, otherwise (preview builds) the board's.
const cover = computed(() => {
  const current = article.value
  if (!current) return null
  if (current.coverImage) return { ...current.coverImage, cutout: false }
  return resolveArticlePreviewCover(current.articleId)
})

// Icons copied from the board ContentArticle.
const ICONS = {
  back: 'M15 5l-7 7 7 7',
  share: 'M12 15V4M8 8l4-4 4 4M5 13v7h14v-7',
  author: 'M12 3l7 3v6c0 4-3 7-7 9-4-2-7-5-7-9V6zM9 12l2 2 4-4',
  check: 'M5 12l5 5 9-10',
  info: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 11v5M12 7.5h.01',
}

async function load(): Promise<void> {
  if (!props.articleId || !props.cityCode) {
    state.value = { kind: 'missing' }
    return
  }
  const requested = ++requestVersion
  state.value = { kind: 'loading' }
  refs.value = null
  try {
    const loaded = await contentApi.loadArticle(props.articleId, props.cityCode)
    if (requested !== requestVersion) return
    state.value = { kind: 'ready', article: loaded }
    const views = await resolveContentRefs(loaded.refs, props.cityCode, contentLinkedApis)
    if (requested !== requestVersion) return
    refs.value = views
  } catch (error) {
    if (requested !== requestVersion) return
    // 404: not published, or not shown in this city; 400: a broken link. Retrying cannot help.
    const missing = error instanceof ApiRequestError && (error.status === 404 || error.status === 400)
    state.value = { kind: missing ? 'missing' : 'error' }
  }
}

function openHome(): void {
  const target = home.value
  if (target.kind === 'PAGE') emit('open', target.url)
  else if (target.kind === 'NOTICE') uni.showToast({ title: target.message, icon: 'none' })
}

function consult(): void {
  const author = article.value?.author
  if (!author?.organizationId || author.kind === 'PLATFORM') return
  emit('consult', { kind: author.kind, organizationId: author.organizationId })
}

// 分享: only 复制链接 of this article in this city.
const shareTarget = ref<ShareTarget | null>(null)
function share(): void {
  const current = article.value
  if (!current) return
  shareTarget.value = {
    subject: '内容',
    title: current.title,
    subtitle: CONTENT_KIND_LABELS[current.kind],
    owner: current.author.name,
    imageUrl: current.coverImage?.url ?? null,
    path: buildContentArticleUrl(current.articleId, props.cityCode),
  }
}

void load()
</script>

<template>
  <view class="article" data-testid="content-article" :data-state="state.kind">
    <view class="article__nav">
      <button class="article__nav-button" data-testid="content-article-back" aria-label="返回" @click="emit('back')">
        <view class="article__nav-circle"><LineIcon :d="ICONS.back" :size="20" color="#ffffff" :stroke-width="2" /></view>
      </button>
      <button
        v-if="article"
        class="article__nav-button"
        data-testid="content-article-share"
        aria-label="分享"
        aria-haspopup="dialog"
        @click="share"
      >
        <view class="article__nav-circle"><LineIcon :d="ICONS.share" :size="20" color="#ffffff" /></view>
      </button>
    </view>

    <view v-if="state.kind === 'loading'" class="article__loading" aria-label="正在加载">
      <view class="article__skeleton article__skeleton--hero" />
      <view class="article__skeleton" />
      <view class="article__skeleton" />
    </view>

    <view v-else-if="state.kind === 'missing' || state.kind === 'error'" class="article__state" data-testid="content-article-unavailable">
      <LineIcon :d="ICONS.info" :size="42" color="#b5bac1" />
      <text class="article__state-title">内容暂不可用</text>
      <text class="article__state-copy">
        {{ state.kind === 'missing' ? '内容已下线，或不在当前城市展示' : '内容加载失败，请检查网络后重试' }}
      </text>
      <button
        class="article__state-action"
        data-testid="content-article-recover"
        @click="state.kind === 'missing' ? emit('browse') : load()"
      >
        {{ state.kind === 'missing' ? '看看其他内容' : '重新加载' }}
      </button>
    </view>

    <template v-else-if="article && tone">
      <!-- The head picture is always cropped to fill, a product shot too (board ContentKnow2). -->
      <view v-if="cover" class="article__hero" data-testid="content-article-hero">
        <image class="article__hero-image" :src="cover.url" mode="aspectFill" :aria-label="cover.alt" />
        <view class="article__hero-tags">
          <text class="article__hero-tag">{{ CONTENT_KIND_LABELS[article.kind] }}</text>
        </view>
      </view>
      <view v-else class="article__block" :style="{ background: tone.background }" data-testid="content-article-block">
        <view class="article__block-tags">
          <text class="article__hero-tag">{{ CONTENT_KIND_LABELS[article.kind] }}</text>
        </view>
        <text class="article__block-title" :style="{ color: tone.color }" role="heading" aria-level="1" data-testid="content-article-title">{{ article.title }}</text>
      </view>

      <view class="article__main">
        <text v-if="cover" class="article__title" role="heading" aria-level="1" data-testid="content-article-title">{{ article.title }}</text>

        <view class="article__author" data-testid="content-article-author">
          <view class="article__author-badge">
            <LineIcon :d="ICONS.author" :size="20" color="#159a70" />
          </view>
          <view class="article__author-copy">
            <view class="article__author-line">
              <text class="article__author-name">{{ article.author.name }}</text>
              <text v-if="article.sponsored" class="article__ad" data-testid="content-sponsored">广告</text>
            </view>
            <view class="article__author-meta-line">
              <text class="article__author-meta" data-testid="content-article-meta">{{ metaLine }}</text>
              <!-- Sample data says so quietly (R5 #14). -->
              <text v-if="article.sample" class="article__sample" data-testid="content-sample">示例</text>
            </view>
          </view>
          <button
            v-if="home.kind !== 'NONE'"
            class="article__home"
            data-testid="content-article-home"
            @click="openHome"
          >
            主页
          </button>
        </view>

        <template v-for="(block, index) in article.blocks" :key="index">
          <view v-if="block.type === 'FACTS'" class="article__facts">
            <template v-for="item in block.items" :key="item.title">
              <text class="article__fact-key">{{ item.title }}</text>
              <text class="article__fact-value">{{ item.text }}</text>
            </template>
          </view>
          <text v-else-if="block.type === 'PARAGRAPH'" class="article__paragraph">{{ block.text }}</text>
          <text v-else-if="block.type === 'HEADING'" class="article__heading" role="heading" aria-level="2">{{ block.text }}</text>
          <view v-else-if="block.type === 'STEPS'" class="article__steps">
            <view v-for="(item, step) in block.items" :key="item.title" class="article__step">
              <text class="article__step-number">{{ step + 1 }}</text>
              <view class="article__step-copy">
                <text class="article__step-title">{{ item.title }}</text>
                <text v-if="item.text" class="article__step-text">{{ item.text }}</text>
              </view>
            </view>
          </view>
          <view v-else-if="block.type === 'LIST'" class="article__list">
            <view v-for="item in block.items" :key="item.title" class="article__list-item">
              <LineIcon class="article__list-check" :d="ICONS.check" :size="16" color="#159a70" :stroke-width="3" />
              <text class="article__list-text"><text class="article__list-title">{{ item.title }}</text> {{ item.text ?? '' }}</text>
            </view>
          </view>
          <view v-else-if="block.type === 'TIP'" class="article__tip">
            <LineIcon :d="ICONS.info" :size="16" color="#738096" :stroke-width="2" />
            <text>{{ block.text }}</text>
          </view>
          <text v-else-if="block.type === 'NOTICE'" class="article__notice">{{ block.text }}</text>
          <!-- 1.3.0 正文配图（画板 ContentArticle“怎么巡”后面那张）：358×190 圆角图，下面居中的图注。 -->
          <view v-else-if="block.type === 'IMAGE' && figures[index]" class="article__figure" data-testid="content-article-figure">
            <image class="article__figure-image" :src="figures[index]?.url" mode="aspectFill" :aria-label="figures[index]?.alt" />
            <text v-if="figures[index]?.caption" class="article__figure-caption" data-testid="content-article-figure-caption">{{ figures[index]?.caption }}</text>
          </view>
        </template>
      </view>

      <view v-if="refs === null && article.refs.length" class="article__refs" aria-label="正在读取内容中提到的服务和商品">
        <text class="article__refs-title">内容中提到</text>
        <view v-for="mention in article.refs" :key="`${mention.type}:${mention.refId}`" class="article__ref article__ref--loading" />
      </view>
      <view v-else-if="refs && refs.length" class="article__refs" data-testid="content-article-refs">
        <text class="article__refs-title" role="heading" aria-level="2">内容中提到</text>
        <button
          v-for="item in refs"
          :key="item.key"
          class="article__ref"
          :data-testid="`content-ref-${item.refId}`"
          :data-type="item.type"
          @click="emit('open', item.url)"
        >
          <view
            :class="['article__ref-thumb', { 'article__ref-thumb--contain': item.imagePadded }]"
            :style="{ background: item.imagePadded ? '#fff0e3' : item.imageContain ? '#f0edff' : item.imageUrl ? '#eef2f8' : item.tagTone.background }"
          >
            <image
              v-if="item.imageUrl"
              class="article__ref-image"
              :src="item.imageUrl"
              :mode="item.imageContain ? 'aspectFit' : 'aspectFill'"
              :aria-label="item.title"
            />
            <text v-else class="article__ref-initial" :style="{ color: item.tagTone.color }">{{ item.tag }}</text>
          </view>
          <view class="article__ref-copy">
            <view class="article__ref-line">
              <text class="article__ref-tag" :style="{ background: item.tagTone.background, color: item.tagTone.color }">{{ item.tag }}</text>
              <text class="article__ref-title">{{ item.title }}</text>
            </view>
            <view v-if="item.price" class="article__ref-price">
              <text class="article__ref-amount">{{ item.price.amount }}</text>
              <text v-if="item.price.unit">{{ item.price.unit }}</text>
            </view>
          </view>
          <text :class="['article__ref-action', { 'article__ref-action--primary': item.primary }]">{{ item.actionLabel }}</text>
        </button>
      </view>

      <view v-if="hasBar" class="article__bar">
        <FavoriteButton
          v-if="FAVORITE_PREVIEW_ENABLED"
          type="CONTENT"
          :target-id="article.articleId"
          :city-code="cityCode"
          :return-url="buildContentArticleUrl(article.articleId, cityCode)"
        />
        <button
          v-if="consultLabel"
          class="article__consult"
          data-testid="content-article-consult"
          @click="consult"
        >
          {{ consultLabel }}
        </button>
        <button
          v-if="primaryRef"
          class="article__primary"
          data-testid="content-article-primary"
          @click="emit('open', primaryRef.url)"
        >
          {{ primaryRef.actionLabel }}
        </button>
      </view>
    </template>

    <ShareSheet :target="shareTarget" @close="shareTarget = null" />
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board ContentArticle (390 wide). */
.article {
  min-height: 100vh;
  padding-bottom: calc(80px + env(safe-area-inset-bottom));
  color: #202c42;
  background: #f5f7fc;
  box-sizing: border-box;
  font-family:
    "PingFang SC", "Noto Sans SC", system-ui, -apple-system, BlinkMacSystemFont,
    "Microsoft YaHei", sans-serif;
}

button {
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
  color: inherit;
  font-family: inherit;
  line-height: normal;
  text-align: left;
}

button::after {
  border: 0;
}

.article__nav {
  position: fixed;
  z-index: 20;
  top: calc(var(--status-bar-height) + 4px);
  right: 8px;
  left: 8px;
  display: flex;
  justify-content: space-between;
  pointer-events: none;
}

.article__nav-button {
  display: flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
  pointer-events: auto;
}

.article__nav-circle {
  display: flex;
  width: 36px;
  height: 36px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.35);
}

.article__hero {
  position: relative;
  height: 220px;
  background: #eef2f8;
}

.article__hero-image {
  display: block;
  width: 100%;
  height: 100%;
}

.article__hero-tags {
  position: absolute;
  bottom: 12px;
  left: 16px;
  display: flex;
  gap: 6px;
}

.article__hero-tag {
  padding: 2px 8px;
  border-radius: 6px;
  color: #ffffff;
  background: rgba(0, 0, 0, 0.45);
  font-size: 12px;
  font-weight: 500;
  line-height: 18px;
  white-space: nowrap;
}

.article__block {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: calc(var(--status-bar-height) + 60px) 16px 18px;
}

.article__block-tags {
  display: flex;
  gap: 6px;
}

.article__block-title {
  font-size: 22px;
  font-weight: 700;
  line-height: 31px;
}

.article__main {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px 16px 18px;
  background: #ffffff;
}

.article__title {
  font-size: 20px;
  font-weight: 700;
  line-height: 28px;
}

.article__author {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 10px;
}

.article__author-badge {
  display: flex;
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #e2f6ed;
}

.article__author-copy {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.article__author-line {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 6px;
}

.article__author-name {
  min-width: 0;
  overflow: hidden;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.article__ad {
  flex-shrink: 0;
  padding: 0 4px;
  border: 1px solid #c9d3e1;
  border-radius: 4px;
  color: #627187;
  font-size: 11px;
  line-height: 15px;
  white-space: nowrap;
}

.article__author-meta {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.article__home {
  flex-shrink: 0;
  height: 30px;
  padding: 0 14px;
  border: 1px solid #ff6a00;
  border-radius: 999px;
  box-sizing: border-box;
  color: #ba470b;
  font-size: 13px;
  font-weight: 500;
  line-height: 28px;
  white-space: nowrap;
}

.article__facts {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr);
  gap: 8px 10px;
  padding: 12px 14px;
  border-radius: 12px;
  background: #fff0e3;
  font-size: 13px;
  line-height: 19px;
}

.article__fact-key {
  color: #ba470b;
  font-weight: 500;
  white-space: nowrap;
}

.article__paragraph {
  font-size: 15px;
  line-height: 26px;
}

.article__heading {
  margin-top: 6px;
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
}

.article__steps {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.article__step {
  display: flex;
  gap: 10px;
}

.article__step-number {
  flex-shrink: 0;
  width: 24px;
  height: 24px;
  margin-top: 1px;
  border-radius: 50%;
  color: #ba470b;
  background: #fff0e3;
  font-size: 12px;
  font-weight: 700;
  line-height: 24px;
  text-align: center;
}

.article__step-copy {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.article__step-title {
  font-size: 15px;
  font-weight: 500;
  line-height: 24px;
}

.article__step-text {
  color: #536178;
  font-size: 14px;
  line-height: 23px;
}

.article__list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.article__list-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  color: #536178;
  font-size: 14px;
  line-height: 22px;
}

.article__list-text {
  min-width: 0;
}

.article__list-title {
  color: #202c42;
  font-weight: 500;
}

.article__tip {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding: 10px 12px;
  border-radius: 10px;
  color: #536178;
  background: #f5f7fc;
  font-size: 13px;
  line-height: 19px;
}

.article__notice {
  margin-top: 2px;
  color: #738096;
  font-size: 12px;
  line-height: 18px;
}

.article__figure {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 2px;
}

.article__figure-image {
  display: block;
  width: 100%;
  height: 190px;
  overflow: hidden;
  border-radius: 12px;
  background: #eef2f8;
}

.article__figure-caption {
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  text-align: center;
}

.article__refs {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 16px 16px;
}

.article__refs-title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.article__ref {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.article__ref--loading {
  height: 84px;
  box-sizing: border-box;
  background: linear-gradient(90deg, #eef1f6 25%, #f6f8fb 50%, #eef1f6 75%);
}

.article__ref-thumb {
  display: flex;
  flex-shrink: 0;
  width: 60px;
  height: 60px;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border-radius: 10px;
}

.article__ref-image {
  width: 100%;
  height: 100%;
}

/* An illustration or a cut-out sits whole on the tint with some air (board ContentArticle). */
.article__ref-thumb--contain {
  padding: 10px;
  box-sizing: border-box;
}

.article__ref-initial {
  font-size: 13px;
  font-weight: 700;
}

.article__author-meta-line {
  display: flex;
  min-width: 0;
  align-items: baseline;
  gap: 6px;
}

.article__sample {
  flex-shrink: 0;
  color: #738096;
  font-size: 11px;
  line-height: 17px;
}

.article__list-check {
  margin-top: 3px;
}

.article__ref-copy {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 6px;
}

.article__ref-line {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 6px;
}

.article__ref-tag {
  flex-shrink: 0;
  padding: 0 5px;
  border-radius: 6px;
  font-size: 12px;
  line-height: 18px;
  white-space: nowrap;
}

.article__ref-title {
  min-width: 0;
  overflow: hidden;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.article__ref-price {
  display: flex;
  align-items: baseline;
  gap: 3px;
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.article__ref-amount {
  color: #e85400;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.article__ref-action {
  flex-shrink: 0;
  height: 36px;
  padding: 0 14px;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  box-sizing: border-box;
  color: #202c42;
  font-size: 14px;
  line-height: 34px;
  white-space: nowrap;
}

.article__ref-action--primary {
  border-color: #ff6a00;
  color: #ffffff;
  background: #ff6a00;
  font-weight: 500;
}

.article__bar {
  box-shadow: var(--app-bar-shadow);
  position: fixed;
  z-index: 15;
  right: 0;
  bottom: 0;
  left: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
  border-top: 1px solid #e5ebf3;
  background: #ffffff;
}

.article__consult,
.article__primary {
  display: flex;
  flex: 1 1 0;
  height: 46px;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  box-sizing: border-box;
  font-size: 16px;
  font-weight: 500;
  white-space: nowrap;
}

.article__consult {
  border: 1.5px solid #ff6a00;
  color: #ba470b;
  background: #ffffff;
}

.article__primary {
  color: #ffffff;
  background: #ff6a00;
  box-shadow: var(--app-primary-shadow);
}

.article__loading {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: calc(var(--status-bar-height) + 60px) 16px 16px;
}

.article__skeleton {
  height: 96px;
  border-radius: 12px;
  background: linear-gradient(90deg, #eef1f6 25%, #f6f8fb 50%, #eef1f6 75%);
}

.article__skeleton--hero {
  height: 160px;
}

.article__state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: calc(var(--status-bar-height) + 120px) 24px 24px;
  text-align: center;
}

.article__state-title {
  font-size: 16px;
  font-weight: 600;
}

.article__state-copy {
  color: #738096;
  font-size: 13px;
}

.article__state-action {
  min-height: 40px;
  margin-top: 6px;
  padding: 0 20px;
  border: 1px solid #ff6a00;
  border-radius: 999px;
  color: #ba470b;
  font-size: 14px;
  line-height: 38px;
  text-align: center;
}
</style>
