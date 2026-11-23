<script setup lang="ts">
import LineIcon from '@/shared/ui/LineIcon.vue'
import QualificationBadge from '@/shared/ui/QualificationBadge.vue'

import { describeServiceOrderCustomer } from '../../application/service-order-format'
import { resolveServiceOrderPreviewCover } from '../../data/preview/serviceorder-preview-covers'
import type { ServiceOrder } from '../../domain/service-order'

// The white card at the top of OrderService and PlanConfirm: the demand's scene photo, the title,
// the provider with 资质已审核, and who ordered (企业 / 个人 with the operator).
const props = defineProps<{
  order: ServiceOrder
}>()

const emit = defineEmits<{
  provider: [providerId: string]
}>()

const cover = resolveServiceOrderPreviewCover(props.order.type)
const customer = describeServiceOrderCustomer(props.order.customer)
</script>

<template>
  <view class="so-head" data-testid="service-order-head-card">
    <image v-if="cover" class="so-head__image" :src="cover" mode="aspectFill" />
    <view v-else class="so-head__image so-head__image--icon">
      <LineIcon name="doc" :size="28" color="#91a0b4" :stroke-width="1.6" />
    </view>
    <view class="so-head__copy">
      <text class="so-head__title" data-testid="service-order-title">{{ order.title }}</text>
      <view class="so-head__line">
        <button
          class="so-head__provider"
          data-testid="service-order-provider"
          @click="order.provider.providerId && emit('provider', order.provider.providerId)"
        >
          服务商 {{ order.provider.name }}
        </button>
        <QualificationBadge v-if="order.provider.qualificationReviewed" :size="11" />
      </view>
      <view class="so-head__line">
        <text class="so-head__tag">{{ customer.tag }}</text>
        <text class="so-head__customer" data-testid="service-order-customer">{{ customer.text }}</text>
      </view>
    </view>
  </view>
</template>

<style scoped>
/* OrderService board: 12px padding, 72px photo with 10px corners, 16/700 title, 13px grey lines. */
.so-head {
  display: flex;
  gap: 12px;
  padding: 12px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: var(--app-card-shadow);
}

.so-head__image {
  width: 72px;
  height: 72px;
  flex: none;
  border-radius: 10px;
  background: #eef2f8;
}

.so-head__image--icon {
  display: flex;
  align-items: center;
  justify-content: center;
}

.so-head__copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}

.so-head__title {
  color: #202c42;
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.so-head__line {
  display: flex;
  height: 20px;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

button {
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  font-family: inherit;
  line-height: 1.2;
  text-align: left;
}

button::after {
  border: 0;
}

.so-head__provider {
  margin: -12px 0;
  padding: 12px 0;
  color: #536178;
  font-size: 13px;
  line-height: 20px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.so-head__tag {
  flex: none;
  padding: 1px 6px;
  border-radius: 6px;
  color: #1d63c4;
  background: #e6f0ff;
  font-size: 11px;
  line-height: 16px;
  white-space: nowrap;
}

.so-head__customer {
  min-width: 0;
  color: #536178;
  font-size: 13px;
  line-height: 20px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
