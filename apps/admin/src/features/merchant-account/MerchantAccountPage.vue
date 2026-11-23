<script setup lang="ts">
import { onMounted, ref, shallowRef } from 'vue'

import { http } from '@/core/http'
import { errorMessage } from '@/core/http/api-error'
import PageSection from '@/core/ui/PageSection.vue'

import { createMerchantAccountApi, maskSubMchid, type MerchantAccount, type MerchantAccountApi } from './merchant-account-api'

const props = defineProps<{
  api?: MerchantAccountApi
}>()

const api = props.api ?? createMerchantAccountApi(http)
const account = shallowRef<MerchantAccount | null>(null)
const loaded = ref(false)
const loading = ref(false)
const error = ref('')

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    account.value = await api.read()
    loaded.value = true
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <PageSection
    title="收款账户"
    description="买家付的钱直接进本机构自己的微信支付商户号，平台不经手资金、不分账、不抽佣。这里只能查看，登记和修改请联系平台运营。"
  >
    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon>
      <el-button size="small" @click="load()">重新加载</el-button>
    </el-alert>
    <div v-else-if="loading && !loaded" v-loading="true" class="placeholder" />
    <template v-else-if="loaded">
      <el-descriptions v-if="account" :column="1" border class="account">
        <el-descriptions-item label="收款渠道">微信支付（平台收付通 · 二级商户）</el-descriptions-item>
        <el-descriptions-item label="子商户号">
          <span class="account__number">{{ maskSubMchid(account.subMchid) }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag v-if="account.active" type="success">可以收款</el-tag>
          <el-tag v-else type="info">已停用</el-tag>
          <span v-if="!account.active" class="account__hint">停用期间买家不能下单付款，请联系平台运营。</span>
        </el-descriptions-item>
      </el-descriptions>
      <el-empty
        v-else
        class="account__empty"
        description="还没有登记收款账户，请联系平台运营（二级商户进件在微信服务商后台完成）。登记前买家不能在线付款下单。"
      />
    </template>
  </PageSection>
</template>

<style scoped>
.placeholder {
  min-height: 160px;
}

.account {
  max-width: 640px;
}

.account__number {
  font-family: ui-monospace, Menlo, monospace;
  letter-spacing: 1px;
}

.account__hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
