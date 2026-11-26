<script setup lang="ts">
import { useRouter } from 'vue-router'

/** 无权限（403）、找不到页面（404）。 */
defineProps<{
  kind: 'forbidden' | 'not-found'
}>()

const router = useRouter()
</script>

<template>
  <div class="status-card">
    <el-result
      :icon="kind === 'forbidden' ? 'warning' : 'info'"
      :title="kind === 'forbidden' ? '没有权限' : '页面不存在'"
      :sub-title="
        kind === 'forbidden'
          ? '你的账号没有这个页面的权限。需要的话请联系平台运营开通。'
          : '地址可能写错了，或者这个功能还没开放。'
      "
    >
      <template #extra>
        <el-button type="primary" @click="router.replace('/')">回首页</el-button>
      </template>
    </el-result>
  </div>
</template>

<style scoped>
.status-card {
  border: 1px solid var(--admin-border);
  border-radius: 4px;
  background: #ffffff;
  padding: 32px 20px;
}
</style>
