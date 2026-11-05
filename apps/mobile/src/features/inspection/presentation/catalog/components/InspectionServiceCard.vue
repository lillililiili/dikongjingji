<script setup lang="ts">
import { computed } from 'vue'
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

import {
  formatCatalogPrice,
  formatCompletedCount,
  type InspectionCatalogService,
} from '../model/inspection-catalog'

const props = defineProps<{
  service: InspectionCatalogService
}>()

defineEmits<{
  detail: [serviceId: string]
}>()

const displayPrice = computed(() =>
  formatCatalogPrice(props.service.price),
)
const completedCountLabel = computed(() =>
  props.service.completedCount === undefined
    ? ''
    : formatCompletedCount(props.service.completedCount),
)
</script>

<template>
  <article
    class="inspection-card"
    :data-testid="`catalog-service-${service.id}`"
    :aria-label="`${service.title}，查看详情`"
    @click="$emit('detail', service.id)"
  >
    <view class="inspection-card__media">
      <image
        class="inspection-card__image"
        :src="service.imageUrl"
        mode="aspectFill"
        :style="{ objectPosition: service.imagePosition ?? '50% 50%' }"
        lazy-load
      />
      <text class="inspection-card__category">
        {{ service.categoryLabel }}
      </text>
      <text
        v-if="service.badgeLabel"
        class="inspection-card__badge"
      >
        {{ service.badgeLabel }}
      </text>
    </view>

    <view class="inspection-card__body">
      <view class="inspection-card__copy">
        <text class="inspection-card__title">{{ service.title }}</text>
        <text class="inspection-card__description">
          {{ service.description }}
        </text>
        <view class="inspection-card__tags">
          <text
            v-for="tag in service.tags.slice(0, 3)"
            :key="tag"
            class="inspection-card__tag"
          >
            {{ tag }}
          </text>
        </view>
      </view>

      <view class="inspection-card__footer">
        <view class="inspection-card__commercial">
          <view class="inspection-card__price">
            <text class="inspection-card__price-value">
              {{ displayPrice.amountLabel }}
            </text>
            <text
              v-if="displayPrice.unitLabel"
              class="inspection-card__price-unit"
            >
              /{{ displayPrice.unitLabel }}
            </text>
          </view>
          <view
            v-if="
              service.rating !== undefined ||
              service.completedCount !== undefined
            "
            class="inspection-card__stats"
          >
            <template v-if="service.rating !== undefined">
              <UniIcons
                type="star-filled"
                size="11"
                color="#ffad00"
              />
              <text>{{ service.rating.toFixed(1) }}</text>
            </template>
            <text v-if="completedCountLabel">
              {{ completedCountLabel }}
            </text>
          </view>
        </view>
        <button
          class="inspection-card__action"
          @click.stop="$emit('detail', service.id)"
        >
          <text class="inspection-card__action-label">详情</text>
        </button>
      </view>
    </view>
  </article>
</template>

<style scoped>
.inspection-card {
  display: grid;
  overflow: hidden;
  min-height: 88px;
  grid-template-columns:
    clamp(112px, 34vw, 142px)
    minmax(0, 1fr);
  border: 1px solid #eceef1;
  border-radius: 12px;
  background: #ffffff;
  box-shadow: 0 3px 12px rgba(31, 35, 40, 0.045);
}

.inspection-card__media {
  position: relative;
  overflow: hidden;
  min-height: 88px;
  background: #eef1f3;
}

.inspection-card__image {
  display: block;
  width: 100%;
  height: 100%;
}

.inspection-card__category,
.inspection-card__badge {
  position: absolute;
  z-index: 1;
  top: 5px;
  min-height: 20px;
  padding: 0 7px;
  border-radius: 0 10px 10px 0;
  color: #ffffff;
  font-size: 10px;
  font-weight: 700;
  line-height: 20px;
  box-sizing: border-box;
}

.inspection-card__category {
  left: 0;
  background: #1fc277;
}

.inspection-card__badge {
  right: 0;
  border-radius: 10px 0 0 10px;
  background: #ff6a00;
}

.inspection-card__body {
  display: flex;
  min-width: 0;
  min-height: 88px;
  padding: 6px 7px 5px 8px;
  flex-direction: column;
  justify-content: space-between;
  box-sizing: border-box;
}

.inspection-card__copy {
  min-width: 0;
}

.inspection-card__title {
  display: block;
  overflow: hidden;
  color: #1d2025;
  font-size: 14px;
  font-weight: 750;
  line-height: 1.32;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.inspection-card__description {
  display: block;
  overflow: hidden;
  margin-top: 1px;
  color: #727881;
  font-size: 9px;
  line-height: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.inspection-card__tags {
  display: flex;
  overflow: hidden;
  margin-top: 3px;
  gap: 3px;
}

.inspection-card__tag {
  overflow: hidden;
  min-width: 0;
  padding: 2px 4px;
  border: 1px solid #cbeedc;
  border-radius: 5px;
  color: #27aa69;
  background: #f4fcf8;
  font-size: 8px;
  line-height: 1;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.inspection-card__footer {
  display: grid;
  min-width: 0;
  margin-top: 3px;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: end;
  gap: 5px;
}

.inspection-card__commercial {
  display: flex;
  overflow: hidden;
  min-width: 0;
  align-items: center;
  gap: 7px;
}

.inspection-card__price {
  display: flex;
  min-width: 0;
  align-items: baseline;
}

.inspection-card__price-value {
  color: #ff5a00;
  font-size: 16px;
  font-weight: 750;
  line-height: 1;
  white-space: nowrap;
}

.inspection-card__price-unit {
  overflow: hidden;
  color: #898f97;
  font-size: 8px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.inspection-card__stats {
  display: flex;
  overflow: hidden;
  min-width: 0;
  min-height: 12px;
  margin-top: 1px;
  align-items: center;
  gap: 3px;
  color: #8e949c;
  font-size: 8px;
  line-height: 1;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.inspection-card__action {
  display: flex;
  min-width: 52px;
  height: 44px;
  padding: 0;
  align-items: center;
  justify-content: center;
  border: 0;
  background: transparent;
}

.inspection-card__action::after {
  border: 0;
}

.inspection-card__action-label {
  min-width: 50px;
  height: 30px;
  padding: 0 10px;
  border-radius: 16px;
  color: #ffffff;
  background: #ff5a00;
  font-size: 12px;
  font-weight: 650;
  line-height: 30px;
  box-sizing: border-box;
}

@media (min-width: 410px) {
  .inspection-card__body {
    min-height: 96px;
    padding: 7px 9px 6px 9px;
  }

  .inspection-card__title {
    font-size: 15px;
  }

  .inspection-card__description {
    font-size: 10px;
  }

  .inspection-card__tag {
    padding: 2px 5px;
    font-size: 9px;
  }
}
</style>
