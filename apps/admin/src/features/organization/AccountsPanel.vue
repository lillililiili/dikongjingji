<script setup lang="ts">
import type { FormInstance } from 'element-plus'
import { reactive, ref } from 'vue'

import { roleLabel } from '@/core/auth/roles'
import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'
import { formatDateTime } from '@/core/ui/format'

import {
  GRANTABLE_ROLE_LABELS,
  type GrantableRole,
  type OpsAccount,
  type OpsAccountCreated,
  type OpsOrganizationDetail,
  type OrganizationApi,
} from './organization-api'
import { accountRules, LIMITS } from './organization-rules'

const props = defineProps<{
  api: OrganizationApi
  detail: OpsOrganizationDetail
}>()

const emit = defineEmits<{
  changed: [detail: OpsOrganizationDetail]
  reload: []
}>()

const createRef = ref<FormInstance>()
const create = reactive<{ open: boolean, loginName: string, displayName: string, roles: GrantableRole[] }>({
  open: false,
  loginName: '',
  displayName: '',
  roles: [],
})
const editing = reactive<{ account: OpsAccount | null, roles: GrantableRole[] }>({ account: null, roles: [] })
const created = ref<OpsAccountCreated | null>(null)
const busy = ref(false)

function grantable(roles: readonly string[]): GrantableRole[] {
  return props.detail.grantableRoles.filter((role) => roles.includes(role))
}

function openCreate(): void {
  create.loginName = ''
  create.displayName = ''
  create.roles = props.detail.grantableRoles.slice(0, 1)
  create.open = true
  createRef.value?.clearValidate()
}

async function submitCreate(): Promise<void> {
  if (!createRef.value || busy.value) return
  if (!(await createRef.value.validate().catch(() => false))) return
  const confirmed = await confirmAction({
    title: '确认新建账号',
    message: `给“${props.detail.organization.name}”建账号 ${create.loginName.trim()}，角色：`
      + `${create.roles.map((role) => GRANTABLE_ROLE_LABELS[role].label).join('、')}？`
      + '初始口令只显示一次，请当场转告机构。',
    confirmText: '新建',
  })
  if (!confirmed) return
  busy.value = true
  try {
    created.value = await props.api.createAccount(props.detail.organization.organizationId, create.loginName,
      create.displayName, create.roles)
    create.open = false
    notifyRecorded(`新建账号 ${created.value.account.loginName}`, '操作记录')
    emit('reload')
  } catch (failure) {
    showError(failure)
  } finally {
    busy.value = false
  }
}

function openRoles(account: OpsAccount): void {
  editing.account = account
  editing.roles = grantable(account.roles)
}

async function submitRoles(): Promise<void> {
  const account = editing.account
  if (!account || busy.value) return
  const confirmed = await confirmAction({
    title: '确认修改角色',
    message: `把账号 ${account.loginName} 的角色改成：`
      + `${editing.roles.length ? editing.roles.map((role) => GRANTABLE_ROLE_LABELS[role].label).join('、') : '无'}？`
      + '保存后这个账号要重新登录才生效。',
    confirmText: '保存',
    danger: editing.roles.length < grantable(account.roles).length,
  })
  if (!confirmed) return
  busy.value = true
  try {
    emit('changed', await props.api.replaceRoles(props.detail.organization.organizationId, account.accountId,
      editing.roles))
    editing.account = null
    notifyRecorded(`修改 ${account.loginName} 的角色`, '操作记录')
  } catch (failure) {
    showError(failure)
  } finally {
    busy.value = false
  }
}

async function copyPassword(): Promise<void> {
  if (!created.value) return
  try {
    await navigator.clipboard.writeText(created.value.initialPassword)
  } catch {
    showError(new Error('复制失败，请手动选中复制'))
  }
}
</script>

<template>
  <div>
    <div class="accounts__head">
      <span class="hint">账号的默认机构是这家；机构角色只在这家机构下生效。角色改动后账号要重新登录。</span>
      <el-button type="primary" :disabled="detail.grantableRoles.length === 0" @click="openCreate">新建账号</el-button>
    </div>
    <el-table :data="detail.accounts" row-key="accountId" empty-text="还没有账号" border>
      <el-table-column prop="loginName" label="登录名" min-width="140" />
      <el-table-column prop="displayName" label="显示名" min-width="140" />
      <el-table-column label="角色" min-width="200">
        <template #default="{ row }">
          <el-tag v-for="role in (row as OpsAccount).roles" :key="role" class="accounts__role" effect="plain">
            {{ roleLabel(role) }}
          </el-tag>
          <span v-if="(row as OpsAccount).roles.length === 0" class="hint">无</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">{{ (row as OpsAccount).status === 'ACTIVE' ? '在用' : '停用' }}</template>
      </el-table-column>
      <el-table-column label="新建时间" width="160">
        <template #default="{ row }">{{ formatDateTime((row as OpsAccount).createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button link type="primary" @click="openRoles(row as OpsAccount)">改角色</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="create.open" title="新建账号" width="520px" :close-on-click-modal="false">
      <el-form ref="createRef" :model="create" :rules="accountRules" label-width="80px">
        <el-form-item label="登录名" prop="loginName">
          <el-input v-model="create.loginName" maxlength="32" placeholder="小写字母开头，比如 hhk-ops" />
        </el-form-item>
        <el-form-item label="显示名" prop="displayName">
          <el-input v-model="create.displayName" :maxlength="LIMITS.displayName" placeholder="比如 黄河口签到员" />
        </el-form-item>
        <el-form-item label="角色" prop="roles">
          <el-checkbox-group v-model="create.roles">
            <el-checkbox v-for="role in detail.grantableRoles" :key="role" :value="role">
              {{ GRANTABLE_ROLE_LABELS[role].label }}
              <span class="hint">{{ GRANTABLE_ROLE_LABELS[role].hint }}</span>
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="create.open = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submitCreate">新建</el-button>
      </template>
    </el-dialog>

    <el-dialog
      :model-value="editing.account !== null"
      :title="editing.account ? `修改角色：${editing.account.loginName}` : ''"
      width="480px"
      :close-on-click-modal="false"
      @close="editing.account = null"
    >
      <el-checkbox-group v-model="editing.roles">
        <el-checkbox v-for="role in detail.grantableRoles" :key="role" :value="role">
          {{ GRANTABLE_ROLE_LABELS[role].label }}
          <span class="hint">{{ GRANTABLE_ROLE_LABELS[role].hint }}</span>
        </el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="editing.account = null">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submitRoles">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog
      :model-value="created !== null"
      title="账号已建好"
      width="460px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      @close="created = null"
    >
      <template v-if="created">
        <el-alert type="warning" :closable="false" show-icon
                  title="初始口令只显示这一次，关掉后查不到。请当场转告机构，并提醒登录后妥善保管。" />
        <el-descriptions :column="1" border class="created">
          <el-descriptions-item label="登录名">{{ created.account.loginName }}</el-descriptions-item>
          <el-descriptions-item label="初始口令">
            <code class="created__password">{{ created.initialPassword }}</code>
            <el-button link type="primary" @click="copyPassword">复制</el-button>
          </el-descriptions-item>
        </el-descriptions>
      </template>
      <template #footer>
        <el-button type="primary" @click="created = null">已转告，关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.accounts__head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.accounts__head .hint {
  flex: 1;
}

.accounts__role {
  margin-right: 6px;
}

.hint {
  margin-left: 6px;
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.created {
  margin-top: 12px;
}

.created__password {
  margin-right: 8px;
  font-size: 15px;
  letter-spacing: 1px;
}
</style>
