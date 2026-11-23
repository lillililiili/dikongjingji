<script setup lang="ts">
import { ElMessage } from 'element-plus'

import type { OpsImportCreatedAccount } from './import-api'

/** 导入新建的操作员账号和一次性初始口令：只在这一次显示，关掉就看不到了。 */
const props = defineProps<{
  accounts: OpsImportCreatedAccount[]
}>()

const emit = defineEmits<{
  close: []
}>()

async function copy(): Promise<void> {
  const text = props.accounts.map((account) =>
    `${account.organizationCode}\t${account.loginName}\t${account.initialPassword}`).join('\n')
  try {
    await navigator.clipboard.writeText(text)
    ElMessage({ type: 'success', message: '已复制，请经安全渠道发给各机构，不要发在群里' })
  } catch {
    ElMessage({ type: 'warning', message: '浏览器不让复制，请手动选中表格里的内容' })
  }
}
</script>

<template>
  <el-dialog :model-value="accounts.length > 0" title="新建的操作员账号" width="640px" :close-on-click-modal="false"
             :close-on-press-escape="false" @close="emit('close')">
    <el-alert type="warning" :closable="false" show-icon
              title="初始口令只显示这一次，关掉后再也看不到。请现在复制，经安全渠道发给各机构，首次登录后让他们改掉。" />
    <el-table :data="accounts" border size="small" class="table" data-testid="created-accounts">
      <el-table-column prop="organizationCode" label="经营主体" min-width="160" />
      <el-table-column prop="loginName" label="登录名" min-width="140" />
      <el-table-column prop="initialPassword" label="初始口令" min-width="160" />
    </el-table>
    <template #footer>
      <el-button @click="copy">复制全部</el-button>
      <el-button type="primary" @click="emit('close')">我已保存好</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.table {
  margin-top: 12px;
}
</style>
