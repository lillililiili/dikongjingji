<script setup lang="ts">
import type { FormInstance } from 'element-plus'
import { onMounted, reactive, ref, shallowRef } from 'vue'

import { http } from '@/core/http'
import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'
import { formatDateTime } from '@/core/ui/format'
import PagedTable from '@/core/ui/PagedTable.vue'
import PageSection from '@/core/ui/PageSection.vue'
import { usePagedList } from '@/core/ui/use-paged-list'

import {
  createMerchantAccountApi,
  MERCHANT_ACCOUNT_ACTION_LABELS,
  merchantAccountRules,
  PLACEHOLDER_SUB_MCHID,
  type MerchantAccountFilters,
  type OpsMerchantAccount,
  type OpsMerchantAccountOperation,
} from './merchant-account-api'

const api = createMerchantAccountApi(http)
const list = usePagedList<OpsMerchantAccount, MerchantAccountFilters>(
  (filters, page, size) => api.list(filters, page, size), { keyword: '', registered: '' })

const formRef = ref<FormInstance>()
const editing = shallowRef<OpsMerchantAccount | null>(null)
const form = reactive({ subMchid: '', active: true })
const saving = ref(false)

const history = reactive<{ open: boolean, account: OpsMerchantAccount | null, loading: boolean }>({
  open: false,
  account: null,
  loading: false,
})
const operations = shallowRef<OpsMerchantAccountOperation[]>([])

function openEdit(account: OpsMerchantAccount): void {
  editing.value = account
  form.subMchid = account.subMchid ?? ''
  form.active = account.registered ? account.active : true
  formRef.value?.clearValidate()
}

async function save(): Promise<void> {
  const account = editing.value
  if (!account || !formRef.value || saving.value) return
  if (!(await formRef.value.validate().catch(() => false))) return
  const subMchid = form.subMchid.trim()
  const turningOff = account.registered && account.active && !form.active
  const confirmed = await confirmAction({
    title: account.registered ? '确认修改收款账户' : '确认登记收款账户',
    message: `${account.organizationName}：二级商户号 ${subMchid}，${form.active ? '收款' : '暂停收款'}。`
      + (form.active ? '' : '暂停后这家的标价商品、收费活动和场馆项目不能在线下单。')
      + (PLACEHOLDER_SUB_MCHID.test(subMchid) ? '这是开发用的占位号段，只有开发环境能登记。' : ''),
    confirmText: '保存',
    danger: turningOff,
  })
  if (!confirmed) return
  saving.value = true
  try {
    await api.save(account.organizationId, subMchid, form.active, account.version)
    notifyRecorded(`${account.registered ? '修改' : '登记'}${account.organizationName}的收款账户`, '记录')
    editing.value = null
    await list.load()
  } catch (failure) {
    showError(failure)
  } finally {
    saving.value = false
  }
}

async function openHistory(account: OpsMerchantAccount): Promise<void> {
  history.account = account
  history.open = true
  history.loading = true
  operations.value = []
  try {
    operations.value = await api.operations(account.organizationId)
  } catch (failure) {
    showError(failure)
  } finally {
    history.loading = false
  }
}

onMounted(() => {
  void list.load()
})
</script>

<template>
  <PageSection
    title="商家收款账户"
    description="经营机构的微信支付二级商户号。只在这里登记，进件在微信服务商后台完成；没有有效账户的商家，标价商品不能在线下单。"
    traced
  >
    <el-form inline @submit.prevent="list.search()">
      <el-form-item label="机构">
        <el-input v-model="list.filters.keyword" clearable maxlength="40" placeholder="名称或机构编码"
                  @clear="list.search()" />
      </el-form-item>
      <el-form-item label="登记">
        <el-select v-model="list.filters.registered" clearable placeholder="全部" style="width: 120px"
                   @change="list.search()">
          <el-option label="已登记" value="true" />
          <el-option label="未登记" value="false" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" native-type="submit">查询</el-button>
        <el-button @click="list.reset()">重置</el-button>
      </el-form-item>
    </el-form>

    <PagedTable
      :items="list.items.value"
      :total="list.total.value"
      :page="list.page.value"
      :size="list.size.value"
      :loading="list.loading.value"
      :error="list.error.value"
      row-key="organizationId"
      empty-text="没有符合条件的经营机构"
      @change-page="list.changePage"
      @change-size="list.changeSize"
      @retry="list.load()"
    >
      <el-table-column label="机构" min-width="220">
        <template #default="{ row }">
          <div>{{ (row as OpsMerchantAccount).organizationName }}</div>
          <div class="sub">{{ (row as OpsMerchantAccount).organizationCode }}</div>
        </template>
      </el-table-column>
      <el-table-column label="机构状态" width="90">
        <template #default="{ row }">{{ (row as OpsMerchantAccount).organizationStatus === 'ACTIVE' ? '在用' : '停用' }}</template>
      </el-table-column>
      <el-table-column label="二级商户号" width="180">
        <template #default="{ row }">
          <span v-if="(row as OpsMerchantAccount).registered">{{ (row as OpsMerchantAccount).subMchid }}</span>
          <span v-else class="sub">未登记</span>
        </template>
      </el-table-column>
      <el-table-column label="收款" width="90">
        <template #default="{ row }">
          <el-switch :model-value="(row as OpsMerchantAccount).registered && (row as OpsMerchantAccount).active"
                     disabled />
        </template>
      </el-table-column>
      <el-table-column label="更新时间" width="160">
        <template #default="{ row }">{{ formatDateTime((row as OpsMerchantAccount).updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="140">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row as OpsMerchantAccount)">
            {{ (row as OpsMerchantAccount).registered ? '修改' : '登记' }}
          </el-button>
          <el-button link @click="openHistory(row as OpsMerchantAccount)">记录</el-button>
        </template>
      </el-table-column>
    </PagedTable>
  </PageSection>

  <el-dialog :model-value="editing !== null"
             :title="editing ? `${editing.registered ? '修改' : '登记'}收款账户：${editing.organizationName}` : ''"
             width="520px" :close-on-click-modal="false" @close="editing = null">
    <el-alert type="info" :closable="false" class="notice"
              title="只登记二级商户号，进件在微信服务商后台完成；开发环境可以用 1900000xxx 占位号段，其他环境会被拒绝。" />
    <el-form ref="formRef" :model="form" :rules="merchantAccountRules" label-width="100px">
      <el-form-item label="二级商户号" prop="subMchid">
        <el-input v-model="form.subMchid" maxlength="32" placeholder="8–32 位数字" />
      </el-form-item>
      <el-form-item label="收款">
        <el-switch v-model="form.active" active-text="收款" inactive-text="暂停" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="editing = null">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>

  <el-drawer v-model="history.open" :title="history.account ? `登记记录：${history.account.organizationName}` : ''"
             size="420px">
    <div v-loading="history.loading">
      <el-empty v-if="!history.loading && operations.length === 0" description="还没有登记记录" />
      <el-timeline v-else>
        <el-timeline-item
          v-for="operation in operations"
          :key="operation.operationId"
          :timestamp="`${formatDateTime(operation.occurredAt)} · ${operation.operatorName}`"
          placement="top"
        >
          <strong>{{ MERCHANT_ACCOUNT_ACTION_LABELS[operation.action] }}</strong>
          <p class="sub">{{ operation.detail }}</p>
        </el-timeline-item>
      </el-timeline>
      <p class="sub">显示最近 50 条。</p>
    </div>
  </el-drawer>
</template>

<style scoped>
.sub {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.notice {
  margin-bottom: 16px;
}
</style>
