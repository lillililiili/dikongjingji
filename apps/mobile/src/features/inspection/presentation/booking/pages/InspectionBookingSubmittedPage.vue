<script setup lang="ts">
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { inspectionBookingApi } from '../../../data/service-flow/uni-booking-http-client'
import type { BookingReservation } from '../../../data/service-flow/inspection-booking-api'
import BookingProgress from '../components/BookingProgress.vue'

interface SubmittedRouteQuery { reservationId?: string }
const reservation = ref<BookingReservation | null>(null)
const isLoading = ref(true)
const loadError = ref('')

onLoad((query: SubmittedRouteQuery | undefined) => {
  const reservationId = query?.reservationId?.trim() ?? ''
  if (!reservationId) { isLoading.value = false; loadError.value = '未找到预约记录'; return }
  void loadReservation(reservationId)
})

async function loadReservation(reservationId: string): Promise<void> {
  isLoading.value = true; loadError.value = ''
  try { reservation.value = await inspectionBookingApi.getReservation(reservationId) }
  catch (error) { loadError.value = error instanceof Error ? error.message : '预约记录加载失败' }
  finally { isLoading.value = false }
}

function returnHome(): void { uni.reLaunch({ url: '/pages/home/index' }) }
function copyReservationNo(): void {
  if (!reservation.value) return
  uni.setClipboardData({ data: reservation.value.reservationNo })
}
function showCustomerService(): void { uni.showToast({ title: '客服能力由平台统一接入', icon: 'none' }) }
function formatLength(meters: number | null): string { return meters == null ? '待服务商评估' : `${(meters / 1000).toFixed(1).replace(/\.0$/, '')}公里` }
function formatSubmittedAt(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const part = (number: number): string => String(number).padStart(2, '0')
  return `${date.getFullYear()}-${part(date.getMonth() + 1)}-${part(date.getDate())} ${part(date.getHours())}:${part(date.getMinutes())}`
}
function maskMobile(value: string): string {
  return /^1\d{10}$/.test(value) ? `${value.slice(0, 3)}****${value.slice(-4)}` : value
}
</script>

<template>
  <view class="booking-submitted">
    <view class="booking-submitted__header"><button aria-label="返回" @click="returnHome"><UniIcons type="back" size="22" color="#25282d" /></button><text>预约结果</text><button aria-label="联系客服" @click="showCustomerService"><UniIcons type="headphones" size="20" color="#25282d" /><text>客服</text></button></view>
    <BookingProgress :active="2" />
    <view v-if="isLoading" class="booking-submitted__state">正在恢复预约记录…</view>
    <view v-else-if="loadError" class="booking-submitted__state"><UniIcons type="info-filled" size="40" color="#b5bac1" /><text>{{ loadError }}</text></view>
    <main v-else-if="reservation" class="booking-submitted__content">
      <view class="booking-submitted__success">
        <view class="booking-submitted__success-main">
          <view class="booking-submitted__success-icon"><UniIcons type="checkmarkempty" size="42" color="#08a942" /></view>
          <view class="booking-submitted__success-copy"><text class="booking-submitted__title">预约申请已提交</text><text class="booking-submitted__copy">服务商将尽快联系您确认方案和最终报价</text><text class="booking-submitted__status">待方案确认</text></view>
        </view>
        <button class="booking-submitted__number" @click="copyReservationNo"><text>预约编号</text><text>{{ reservation.reservationNo }}</text><UniIcons type="copy" size="16" color="#50565f" /></button>
        <text class="booking-submitted__time">提交时间：{{ formatSubmittedAt(reservation.submittedAt) }}</text>
      </view>
      <view class="booking-submitted__card">
        <text class="booking-submitted__card-title">预约信息</text>
        <view class="booking-submitted__service"><image :src="reservation.serviceCoverUrl || '/static/images/inspection/inspection-hero-v1.png'" mode="aspectFill" /><text>{{ reservation.serviceTitle }}</text><UniIcons type="right" size="18" color="#626973" /></view>
        <view class="booking-submitted__detail-row"><text>巡检地点</text><text>{{ reservation.location.address }}</text></view>
        <view class="booking-submitted__detail-row"><text>线路长度</text><text>{{ formatLength(reservation.lineLengthMeters) }}</text></view>
        <view class="booking-submitted__detail-row"><text>期望时间</text><text>{{ reservation.preferredServiceDate }}</text></view>
        <view class="booking-submitted__detail-row"><text>巡检项目</text><text>{{ reservation.inspectionItems.map((item) => item.label).join('、') }}</text></view>
        <view class="booking-submitted__detail-row"><text>联系人</text><text>{{ reservation.contact.name }}　{{ maskMobile(reservation.contact.mobile) }}</text></view>
      </view>
      <view class="booking-submitted__timeline">
        <text class="booking-submitted__timeline-title">后续流程</text>
        <view class="booking-submitted__timeline-row booking-submitted__timeline-row--done"><UniIcons type="checkbox-filled" size="18" color="#16af55" /><view><text>需求已提交</text><text>{{ formatSubmittedAt(reservation.submittedAt) }}</text></view></view>
        <view class="booking-submitted__timeline-row booking-submitted__timeline-row--current"><UniIcons type="circle-filled" size="18" color="#ff6a00" /><view><text>服务商确认需求</text><text>等待联系</text></view></view>
        <view class="booking-submitted__timeline-row"><UniIcons type="circle" size="18" color="#b5bac1" /><text>确认方案与报价</text></view>
        <view class="booking-submitted__timeline-row"><UniIcons type="circle" size="18" color="#b5bac1" /><text>安排现场服务</text></view>
      </view>
    </main>
    <view class="booking-submitted__footer"><view><UniIcons type="info-filled" size="18" color="#ff6a00" /><text>请保持手机畅通，方案确认前不会收取任何费用。</text></view><button @click="returnHome">返回首页</button></view>
  </view>
</template>

<style scoped>
.booking-submitted{min-height:100vh;background:#f7f8fa;color:#25282d}.booking-submitted__header{display:grid;min-height:calc(48px + env(safe-area-inset-top));padding-top:env(safe-area-inset-top);grid-template-columns:44px 1fr 64px;align-items:center;background:#fff;font-size:17px;font-weight:700;text-align:center;box-sizing:border-box}.booking-submitted__header button{height:44px;margin:0;padding:0;border:0;background:transparent}.booking-submitted__header button::after{border:0}.booking-submitted__header button:last-child{display:flex;width:64px;align-items:center;justify-content:center;gap:3px;font-size:12px}.booking-submitted__content{max-width:600px;margin:0 auto;padding:8px 8px 92px}.booking-submitted__success{padding:14px 14px 12px;border:1px solid #eceef1;border-radius:13px;background:#fff}.booking-submitted__success-main{display:grid;grid-template-columns:86px minmax(0,1fr);align-items:center}.booking-submitted__success-icon{display:grid;width:72px;height:72px;place-items:center;border-radius:50%;background:#e7f8e9}.booking-submitted__success-copy{display:flex;align-items:flex-start;flex-direction:column}.booking-submitted__title{font-size:19px;font-weight:700;letter-spacing:1px}.booking-submitted__copy{margin-top:4px;color:#737b85;font-size:12px}.booking-submitted__status{margin-top:6px;padding:2px 8px;border:1px solid #ff6a00;border-radius:10px;color:#ff5a00;font-size:11px}.booking-submitted__number{display:grid;width:100%;min-height:37px;margin-top:10px;padding:0 12px;grid-template-columns:78px 1fr 22px;align-items:center;border:1px solid #e2e5e8;border-radius:7px;background:#fff;color:#25282d;font-size:13px;text-align:left}.booking-submitted__number::after,.booking-submitted__footer button::after{border:0}.booking-submitted__number text:nth-child(2){font-weight:600;letter-spacing:.5px}.booking-submitted__time{display:block;margin-top:7px;color:#737a83;font-size:11px}.booking-submitted__card{margin-top:8px;padding:11px 12px 8px;border:1px solid #eceef1;border-radius:12px;background:#fff;color:#25282d;font-size:12px}.booking-submitted__card-title{display:block;margin-bottom:7px;font-size:15px;font-weight:700}.booking-submitted__service{display:grid;min-height:60px;padding-bottom:7px;grid-template-columns:88px minmax(0,1fr) 18px;gap:10px;align-items:center}.booking-submitted__service image{width:88px;height:60px;border-radius:7px;background:#edf0f2}.booking-submitted__service>text{overflow:hidden;font-size:15px;font-weight:700;text-overflow:ellipsis;white-space:nowrap}.booking-submitted__detail-row{display:grid;min-height:28px;grid-template-columns:92px minmax(0,1fr);align-items:center;border-top:1px solid #eceef1}.booking-submitted__detail-row>text:last-child{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.booking-submitted__timeline{margin-top:8px;padding:11px 12px 9px;border:1px solid #eceef1;border-radius:12px;background:#fff}.booking-submitted__timeline-title{display:block;margin-bottom:6px;font-size:15px;font-weight:700}.booking-submitted__timeline-row{position:relative;display:grid;min-height:33px;grid-template-columns:24px minmax(0,1fr);align-items:start;color:#41464e;font-size:12px}.booking-submitted__timeline-row:not(:last-child)::after{position:absolute;top:17px;bottom:-4px;left:8px;border-left:1px dashed #b6bbc1;content:''}.booking-submitted__timeline-row--done:not(:last-child)::after{border-color:#16af55}.booking-submitted__timeline-row>view{display:flex;flex-direction:column}.booking-submitted__timeline-row>view text:last-child{margin-top:1px;color:#8b929a;font-size:10px}.booking-submitted__timeline-row--current>view text{color:#ff5a00}.booking-submitted__state{display:flex;min-height:360px;align-items:center;justify-content:center;flex-direction:column;gap:12px;color:#7e858e;font-size:14px}.booking-submitted__footer{position:fixed;right:0;bottom:0;left:0;padding:0 8px calc(7px + env(safe-area-inset-bottom));background:#fff}.booking-submitted__footer>view{display:flex;margin:0 -8px;padding:6px 12px;align-items:center;gap:6px;color:#a65c2e;background:#fff5ea;font-size:10px}.booking-submitted__footer button{display:block;width:min(100%,584px);height:40px;margin:5px auto 0;border:0;border-radius:22px;color:#fff;background:linear-gradient(90deg,#ff8a00,#ff4d00);font-size:16px;font-weight:700}
.booking-submitted__content{padding:7px 8px 84px}
.booking-submitted__success{padding:11px 12px 9px}
.booking-submitted__success-main{grid-template-columns:76px minmax(0,1fr)}
.booking-submitted__success-icon{width:64px;height:64px}
.booking-submitted__title{font-size:18px;letter-spacing:.5px}
.booking-submitted__copy{margin-top:3px;font-size:11px}
.booking-submitted__status{margin-top:5px;padding:1px 7px;font-size:10px}
.booking-submitted__number{min-height:34px;margin-top:8px;padding:0 10px;grid-template-columns:74px 1fr 20px;font-size:12px}
.booking-submitted__time{margin-top:5px;font-size:10px}
.booking-submitted__card{margin-top:7px;padding:9px 11px 6px;font-size:11px}
.booking-submitted__card-title{margin-bottom:5px;font-size:14px}
.booking-submitted__service{min-height:54px;padding-bottom:5px;grid-template-columns:80px minmax(0,1fr) 18px;gap:9px}
.booking-submitted__service image{width:80px;height:54px}
.booking-submitted__service>text{font-size:14px}
.booking-submitted__detail-row{min-height:25px;grid-template-columns:86px minmax(0,1fr)}
.booking-submitted__timeline{margin-top:7px;padding:9px 11px 7px}
.booking-submitted__timeline-title{margin-bottom:4px;font-size:14px}
.booking-submitted__timeline-row{min-height:29px;grid-template-columns:23px minmax(0,1fr);font-size:11px}
.booking-submitted__timeline-row:not(:last-child)::after{top:16px;bottom:-3px}
.booking-submitted__timeline-row>view text:last-child{font-size:9px}
.booking-submitted__footer{padding-bottom:calc(6px + env(safe-area-inset-bottom))}
.booking-submitted__footer>view{padding:5px 11px;gap:5px;font-size:9px}
.booking-submitted__footer button{height:38px;margin-top:4px;border-radius:21px;font-size:15px}
</style>
