<script setup lang="ts">
import { computed } from 'vue'

import { roleLabel } from '@/core/auth/roles'
import { useSessionStore } from '@/core/auth/session'
import { buildMenu } from '@/core/navigation/module'
import PageSection from '@/core/ui/PageSection.vue'
import { ADMIN_MODULES } from '@/modules'

const session = useSessionStore()
const groups = computed(() => buildMenu(ADMIN_MODULES, session.roles))
</script>

<template>
  <PageSection :title="`你好，${session.account?.displayName ?? ''}`"
               :description="session.account?.organizationName ?? undefined">
    <p class="roles">
      当前角色：
      <el-tag v-for="role in session.roles" :key="role" effect="plain" class="roles__tag">{{ roleLabel(role) }}</el-tag>
    </p>
    <el-empty
      v-if="groups.length === 0"
      description="你的账号暂时没有可用的功能。需要的话请联系平台运营给本机构开通角色。"
    />
    <div v-for="group in groups" :key="group.key" class="group">
      <h2 class="group__title">{{ group.title }}</h2>
      <div class="group__items">
        <router-link v-for="item in group.items" :key="item.path" :to="item.path" class="card">
          <el-icon :size="22"><component :is="item.icon" /></el-icon>
          <span>{{ item.title }}</span>
        </router-link>
      </div>
    </div>
  </PageSection>
</template>

<style scoped>
.roles {
  margin: 0 0 16px;
  color: var(--admin-text-secondary);
}

.roles__tag {
  margin-right: 6px;
}

.group + .group {
  margin-top: 20px;
}

.group__title {
  margin: 0 0 10px;
  font-size: 15px;
}

.group__items {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 12px;
}

.card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px;
  border: 1px solid var(--admin-border);
  border-radius: 12px;
  color: var(--admin-text);
  text-decoration: none;
}

.card:hover {
  border-color: var(--el-color-primary);
  background: var(--admin-brand-surface);
  color: var(--admin-brand-text);
}
</style>
