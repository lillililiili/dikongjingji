<script setup lang="ts">
import LineIcon from '@/shared/ui/LineIcon.vue'
import IconTile from '@/shared/ui/IconTile.vue'

// 更多操作 (board MoreSheet), opened from the 更多 button on the product picture: back to the home
// page, 反馈 (帮助与反馈) and 举报 (a conversation with platform support). Sharing has its own button.
defineProps<{
  title: string
  subtitle: string
  coverUrl: string | null
}>()

const emit = defineEmits<{
  close: []
  home: []
  feedback: []
  report: []
}>()

// Paths and colours copied from the board.
const ACTIONS = [
  {
    key: 'home',
    label: '回首页',
    sub: '回到首页推荐频道',
    bg: '#fff0e3',
    fg: '#ff6a00',
    d: 'M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6h-6v6H4a1 1 0 0 1-1-1z',
  },
  {
    key: 'feedback',
    label: '反馈',
    sub: '页面有问题或信息不准确，告诉我们',
    bg: '#e6f0ff',
    fg: '#2f80ed',
    d: 'M4 5h16v11H9l-5 4zM8 9h8M8 12.5h5',
  },
  {
    key: 'report',
    label: '举报',
    sub: '向平台客服举报该商品，由平台核实处理',
    bg: '#fdecec',
    fg: '#d64f67',
    d: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 8v5M12 16h.01',
  },
] as const

function pick(key: (typeof ACTIONS)[number]['key']): void {
  if (key === 'home') emit('home')
  else if (key === 'feedback') emit('feedback')
  else emit('report')
}
</script>

<template>
  <view class="more-sheet" data-testid="mall-product-more-sheet" @click.self="emit('close')">
    <view class="more-sheet__panel" role="dialog" aria-label="更多操作">
      <view class="more-sheet__head">
        <text class="more-sheet__title">更多操作</text>
        <button class="more-sheet__close" aria-label="关闭" data-testid="mall-product-more-close" @click="emit('close')">
          <LineIcon name="close" :size="20" color="#536178" :stroke-width="2" />
        </button>
      </view>
      <view class="more-sheet__product">
        <image v-if="coverUrl" class="more-sheet__cover" :src="coverUrl" mode="aspectFill" />
        <view v-else class="more-sheet__cover" />
        <view class="more-sheet__product-copy">
          <text class="more-sheet__product-name">{{ title }}</text>
          <text class="more-sheet__product-sub">{{ subtitle }}</text>
        </view>
      </view>
      <view class="more-sheet__list">
        <button
          v-for="(action, index) in ACTIONS"
          :key="action.key"
          :class="['more-sheet__row', { 'more-sheet__row--line': index > 0 }]"
          :data-testid="`mall-product-more-${action.key}`"
          @click="pick(action.key)"
        >
          <IconTile :size="40" :icon-size="22" :bg="action.bg" :ink="action.fg" :d="action.d" />
          <view class="more-sheet__row-copy">
            <text class="more-sheet__row-label">{{ action.label }}</text>
            <text class="more-sheet__row-sub">{{ action.sub }}</text>
          </view>
          <LineIcon name="forward" :size="14" color="#91a0b4" :stroke-width="2" />
        </button>
      </view>
      <button class="more-sheet__cancel" data-testid="mall-product-more-cancel" @click="emit('close')">取消</button>
    </view>
  </view>
</template>

<style scoped>
.more-sheet {
  position: fixed;
  z-index: 900;
  inset: 0;
  display: flex;
  align-items: flex-end;
  background: rgba(0, 0, 0, 0.45);
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

.more-sheet__panel {
  display: flex;
  width: 100%;
  flex-direction: column;
  gap: 10px;
  padding: 8px 16px calc(24px + env(safe-area-inset-bottom));
  border-radius: 20px 20px 0 0;
  color: #202c42;
  background: #ffffff;
  box-sizing: border-box;
}

.more-sheet__head {
  display: flex;
  align-items: center;
}

.more-sheet__title {
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
}

.more-sheet__close {
  display: flex;
  width: 44px;
  height: 44px;
  margin-left: auto;
  align-items: center;
  justify-content: center;
}

.more-sheet__product {
  display: flex;
  padding: 10px;
  align-items: center;
  gap: 10px;
  border-radius: 12px;
  background: #f5f7fc;
}

.more-sheet__cover {
  width: 44px;
  height: 44px;
  flex: none;
  border-radius: 8px;
  background: #eef2f8;
}

.more-sheet__product-copy,
.more-sheet__row-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.more-sheet__product-name {
  overflow: hidden;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.more-sheet__product-sub,
.more-sheet__row-sub {
  overflow: hidden;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.more-sheet__list {
  display: flex;
  flex-direction: column;
}

.more-sheet__row {
  display: flex;
  width: 100%;
  min-height: 56px;
  align-items: center;
  gap: 12px;
  border-top: 1px solid transparent;
}

.more-sheet__row--line {
  border-top-color: #f0f1f3;
}

.more-sheet__row-label {
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
  white-space: nowrap;
}

.more-sheet__cancel {
  height: 46px;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  color: #202c42;
  background: #ffffff;
  font-size: 16px;
  line-height: 44px;
  text-align: center;
  box-sizing: border-box;
}
</style>
