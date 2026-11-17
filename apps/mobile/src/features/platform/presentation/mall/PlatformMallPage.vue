<script setup lang="ts">
import AppHeader from '@/app-shell/components/AppHeader.vue'
import PlatformHeroBanner from '@/shared/presentation/PlatformHeroBanner.vue'
import type { PlatformWeatherPresentation } from '@/shared/weather/platform-weather'
import type { PlatformFeatureIntent } from '../../domain/platform-feature-intent'
import type {
  PlatformMallContent,
  PlatformMallItem,
} from '../../domain/platform-mall-content'

defineProps<{
  cityName: string
  weather: PlatformWeatherPresentation
  content: PlatformMallContent
}>()

const emit = defineEmits<{
  intent: [intent: PlatformFeatureIntent]
}>()

function openItem(item: PlatformMallItem, kind: 'PRODUCT' | 'SOLUTION'): void {
  emit('intent', { type: 'OPEN_ITEM', itemId: item.id, itemKind: kind })
}
</script>

<template>
  <view class="mall-page">
    <view class="mall-page__surface">
      <AppHeader
        :city-name="cityName"
        :weather="weather"
        @location="emit('intent', { type: 'OPEN_LOCATION' })"
        search-placeholder="搜索无人机或机器人"
        @search="emit('intent', { type: 'SEARCH', channel: 'AERIAL' })"
        @message="emit('intent', { type: 'OPEN_MESSAGE_CENTER' })"
      />

      <PlatformHeroBanner
        :image-url="content.hero.imageUrl"
        :image-alt="`${content.hero.title}${content.hero.highlight}`"
        :title="content.hero.title"
        :highlight="content.hero.highlight"
        :description="content.hero.description"
        :highlights="content.hero.highlights"
        :action-label="content.hero.actionLabel"
        @action="emit('intent', { type: 'OPEN_ITEM', itemId: 'mall-hero', itemKind: 'PRODUCT' })"
      />

      <view class="mall-category-grid">
        <button
          v-for="category in content.categories"
          :key="category.code"
          class="mall-category"
          @click="emit('intent', { type: 'OPEN_ITEM', itemId: category.code, itemKind: 'PRODUCT' })"
        >
          <image :src="category.imageUrl" mode="aspectFill" />
          <view>
            <text class="mall-category__title">{{ category.title }}</text>
            <text class="mall-category__subtitle">{{ category.subtitle }}</text>
          </view>
        </button>
      </view>

      <section class="mall-section">
        <view class="mall-section__heading">
          <text class="mall-section__title">精选推荐</text>
          <text class="mall-section__copy">为你精选 · 高性价比好物</text>
        </view>
        <scroll-view scroll-x class="mall-section__scroll">
          <view class="mall-product-row">
            <button
              v-for="product in content.products"
              :key="product.id"
              class="mall-product"
              @click="openItem(product, 'PRODUCT')"
            >
              <image :src="product.imageUrl" mode="aspectFill" />
              <text class="mall-product__title">{{ product.title }}</text>
              <text class="mall-product__subtitle">{{ product.subtitle }}</text>
              <text class="mall-product__price">{{ product.priceLabel }}</text>
              <text v-if="product.salesLabel" class="mall-product__sales">
                {{ product.salesLabel }}
              </text>
              <view class="mall-product__badges">
                <text v-for="badge in product.badges" :key="badge">{{ badge }}</text>
              </view>
            </button>
          </view>
        </scroll-view>
      </section>

      <section class="mall-section">
        <view class="mall-section__heading">
          <text class="mall-section__title">设备租赁</text>
          <text class="mall-section__copy">租好装备 · 更省成本</text>
        </view>
        <scroll-view scroll-x class="mall-section__scroll">
          <view class="mall-rental-grid">
            <button
              v-for="rental in content.rentals"
              :key="rental.id"
              class="mall-rental"
              @click="openItem(rental, 'PRODUCT')"
            >
              <image :src="rental.imageUrl" mode="aspectFit" />
              <text>{{ rental.title }}</text>
              <text>{{ rental.priceLabel }}</text>
            </button>
          </view>
        </scroll-view>
      </section>

      <section class="mall-section">
        <view class="mall-section__heading">
          <text class="mall-section__title">行业解决方案商城</text>
          <text class="mall-section__copy">场景化方案 · 一站式采购</text>
        </view>
        <view class="mall-solution-grid">
          <button
            v-for="solution in content.solutions"
            :key="solution.id"
            class="mall-solution"
            @click="emit('intent', { type: 'OPEN_ITEM', itemId: solution.id, itemKind: 'SOLUTION' })"
          >
            <image :src="solution.imageUrl" mode="aspectFill" />
            <view>
              <text>{{ solution.title }}</text>
              <text>{{ solution.subtitle }}</text>
              <text>立即选购 →</text>
            </view>
          </button>
        </view>
      </section>

      <section class="mall-section mall-section--compact">
        <view class="mall-section__heading">
          <text class="mall-section__title">品牌推荐</text>
        </view>
        <view class="mall-brand-row">
          <button
            v-for="brand in content.brands"
            :key="brand"
            @click="emit('intent', { type: 'OPEN_ITEM', itemId: brand, itemKind: 'PRODUCT' })"
          >
            {{ brand }}
          </button>
        </view>
      </section>

      <section class="mall-section mall-section--compact">
        <view class="mall-section__heading">
          <text class="mall-section__title">优质店铺</text>
          <text class="mall-section__copy">精选店铺 · 放心选购</text>
        </view>
        <view class="mall-store-grid">
          <button
            v-for="store in content.stores"
            :key="store.id"
            @click="openItem(store, 'PRODUCT')"
          >
            <image :src="store.imageUrl" mode="aspectFill" />
            <view>
              <text>{{ store.title }}</text>
              <text>{{ store.subtitle }}</text>
            </view>
            <text>{{ store.priceLabel }} ›</text>
          </button>
        </view>
      </section>
    </view>
  </view>
</template>

<style scoped>
.mall-page {
  min-height: 100vh;
  padding-bottom: var(--app-root-tabbar-content-offset);
  color: #17191d;
  background: #f7f8fa;
  box-sizing: border-box;
  font-family:
    system-ui, -apple-system, BlinkMacSystemFont, "PingFang SC",
    "Microsoft YaHei", sans-serif;
}

.mall-page__surface {
  width: 100%;
  max-width: 600px;
  margin: 0 auto;
  padding:
    calc(var(--status-bar-height) + 10px)
    var(--app-page-horizontal-gutter)
    14px;
  box-sizing: border-box;
}

button {
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  font-family: inherit;
  line-height: 1.2;
}

button::after {
  border: 0;
}

.mall-category-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-top: 10px;
  padding: 10px;
  border: 1px solid #edf0f3;
  border-radius: 16px;
  background: #ffffff;
}

.mall-category {
  display: flex;
  min-height: 86px;
  align-items: center;
  gap: 10px;
  padding: 8px;
  border-radius: 12px;
  background: #f2f7fa;
  text-align: left;
}

.mall-category image {
  width: 64px;
  height: 72px;
  flex: 0 0 auto;
  border-radius: 9px;
}

.mall-category__title,
.mall-category__subtitle {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
}

.mall-category__title {
  font-size: 14px;
  font-weight: 850;
  line-height: 1.2;
  white-space: nowrap;
}

.mall-category__subtitle {
  margin-top: 4px;
  color: var(--app-secondary-text);
  font-size: 12px;
  line-height: 1.3;
}

.mall-section {
  margin-top: 10px;
  padding: 12px 10px;
  border: 1px solid #edf0f3;
  border-radius: 16px;
  background: #ffffff;
}

.mall-section--compact {
  padding-top: 10px;
  padding-bottom: 10px;
}

.mall-section__heading {
  display: flex;
  min-width: 0;
  align-items: baseline;
  gap: 9px;
  margin-bottom: 10px;
}

.mall-section__title {
  font-size: 17px;
  font-weight: 850;
}

.mall-section__copy {
  overflow: hidden;
  color: var(--app-secondary-text);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mall-section__scroll {
  width: 100%;
  white-space: nowrap;
}

.mall-product-row {
  display: grid;
  width: max-content;
  min-width: 100%;
  grid-auto-columns: calc((100vw - 2 * var(--app-page-horizontal-gutter) - 31px) / 2);
  grid-auto-flow: column;
  gap: 10px;
  padding-right: 2px;
  box-sizing: border-box;
}

.mall-product {
  min-width: 0;
  text-align: left;
}

.mall-product > image {
  width: 100%;
  height: 118px;
  border-radius: 10px;
  background: #eff4f7;
}

.mall-product__title,
.mall-product__subtitle,
.mall-product__price,
.mall-product__sales {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mall-product__title {
  margin-top: 6px;
  font-size: 14px;
  font-weight: 800;
}

.mall-product__subtitle,
.mall-product__sales {
  margin-top: 3px;
  color: var(--app-secondary-text);
  font-size: 11px;
}

.mall-product__price {
  margin-top: 5px;
  color: #d84b00;
  font-size: 15px;
  font-weight: 850;
}

.mall-product__badges {
  display: flex;
  min-height: 20px;
  margin-top: 5px;
  flex-wrap: wrap;
  gap: 3px;
}

.mall-product__badges text {
  padding: 2px 4px;
  border: 1px solid #ffb485;
  border-radius: 5px;
  color: #c94700;
  font-size: 10px;
}

.mall-rental-grid {
  display: grid;
  width: max-content;
  min-width: 100%;
  grid-auto-columns: calc((100vw - 2 * var(--app-page-horizontal-gutter) - 31px) / 2);
  grid-auto-flow: column;
  gap: 10px;
  padding-right: 2px;
  box-sizing: border-box;
}

.mall-rental {
  padding: 6px;
  border-radius: 11px;
  background: #f2f7f5;
  text-align: center;
}

.mall-rental image {
  width: 100%;
  height: 82px;
}

.mall-rental text {
  display: block;
  margin-top: 4px;
  overflow: hidden;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mall-rental text:last-child {
  color: #ff6508;
  font-size: 13px;
  font-weight: 800;
}

.mall-solution-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.mall-solution {
  position: relative;
  height: 116px;
  overflow: hidden;
  border-radius: 10px;
  text-align: left;
}

.mall-solution image {
  width: 100%;
  height: 100%;
}

.mall-solution > view {
  position: absolute;
  inset: 0;
  padding: 10px;
  color: #ffffff;
  background: linear-gradient(90deg, rgba(19, 42, 57, 0.72), transparent);
}

.mall-solution text {
  display: block;
  margin-top: 4px;
  font-size: 11px;
}

.mall-solution text:first-child {
  margin-top: 0;
  font-size: 15px;
  font-weight: 850;
}

.mall-brand-row {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 7px;
}

.mall-brand-row button {
  min-height: 34px;
  padding: 0 5px;
  border: 1px solid #edf0f3;
  border-radius: 17px;
  font-size: 11px;
  font-weight: 750;
}

.mall-store-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.mall-store-grid button {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr);
  gap: 6px;
  align-items: center;
  padding: 7px;
  border: 1px solid #edf0f3;
  border-radius: 11px;
  text-align: left;
}

.mall-store-grid image {
  width: 34px;
  height: 34px;
  border-radius: 8px;
}

.mall-store-grid text {
  display: block;
  overflow: hidden;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mall-store-grid view text:first-child {
  font-size: 12px;
  font-weight: 800;
}

.mall-store-grid view text:last-child {
  margin-top: 3px;
  color: #858e98;
}

.mall-store-grid > button > text:last-child {
  grid-column: 1 / -1;
  color: #ff6508;
  text-align: right;
}
</style>
