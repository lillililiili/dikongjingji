<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

defineProps<{
  visible: boolean
}>()

const emit = defineEmits<{
  close: []
}>()
</script>

<template>
  <view
    v-if="visible"
    class="platform-unavailable-dialog"
    role="dialog"
    aria-modal="true"
    aria-label="暂未开放"
    @click.self="emit('close')"
  >
    <view class="platform-unavailable-dialog__card" @click.stop>
      <button
        class="platform-unavailable-dialog__close"
        aria-label="关闭"
        @click="emit('close')"
      >
        <UniIcons type="closeempty" size="25" color="#7c838c" />
      </button>
      <image
        class="platform-unavailable-dialog__image"
        src="/static/images/inspection/platform/unavailable-robot.png"
        mode="aspectFit"
      />
      <text class="platform-unavailable-dialog__title">暂未开放</text>
      <text class="platform-unavailable-dialog__copy">该服务正在准备中，敬请期待</text>
      <button class="platform-unavailable-dialog__action" @click="emit('close')">
        我知道了
      </button>
    </view>
  </view>
</template>

<style scoped>
.platform-unavailable-dialog{position:fixed;inset:0;z-index:80;display:flex;align-items:center;justify-content:center;padding:36rpx;background:rgba(26,30,36,.46)}
.platform-unavailable-dialog__card{position:relative;display:flex;width:min(596rpx,calc(100vw - 48px));flex-direction:column;align-items:center;padding:44rpx 42rpx 36rpx;border-radius:32rpx;background:#fff;box-shadow:0 26rpx 70rpx rgba(15,23,42,.2)}
.platform-unavailable-dialog__close{position:absolute;top:18rpx;right:18rpx;display:flex;width:56rpx;height:56rpx;align-items:center;justify-content:center;margin:0;padding:0;border:0;background:transparent;line-height:1}
.platform-unavailable-dialog__close::after,.platform-unavailable-dialog__action::after{border:0}
.platform-unavailable-dialog__image{width:194rpx;height:194rpx;margin:6rpx 0 15rpx;transform:scale(1.38);transform-origin:center}
.platform-unavailable-dialog__title{margin-top:6rpx;color:#17191d;font-family:"Microsoft YaHei UI","PingFang SC","Noto Sans SC",system-ui,sans-serif;font-size:36rpx;font-weight:800;line-height:1.35}
.platform-unavailable-dialog__copy{margin-top:12rpx;color:#747b85;font-family:"Microsoft YaHei UI","PingFang SC","Noto Sans SC",system-ui,sans-serif;font-size:23rpx;line-height:1.5;text-align:center}
.platform-unavailable-dialog__action{width:100%;height:78rpx;margin:34rpx 0 0;padding:0;border:0;border-radius:40rpx;color:#fff;background:#ff6508;font-family:"Microsoft YaHei UI","PingFang SC","Noto Sans SC",system-ui,sans-serif;font-size:26rpx;font-weight:700;line-height:78rpx}
</style>
